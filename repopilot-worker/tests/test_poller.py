"""轮询器行为：完成、失败、重试提示与租约失效。

用假客户端替代 HTTP，验证的是**协议决策**（什么时候 complete、什么时候 fail、
租约失效后是否还敢提交），而不是网络细节。
"""

from __future__ import annotations

import time
from typing import Any

import pytest

from app.client import WorkerApiError, manifest_sha256
from app.config import Settings
from app.poller import WorkerPoller
from app.runner.mock import MockRunner


class FakeClient:
    def __init__(self, task: dict[str, Any]) -> None:
        self.task = task
        self.claimed = False
        self.events: list[dict[str, Any]] = []
        self.completes: list[dict[str, Any]] = []
        self.failures: list[dict[str, Any]] = []
        self.complete_error: Exception | None = None
        self.heartbeat_error: Exception | None = None
        self.heartbeat_calls = 0

    def claim(self, worker_id: str, capabilities: list[str], max_tasks: int):
        if self.claimed:
            return []
        self.claimed = True
        return [self.task]

    def heartbeat(self, attempt_id: str, lease_token: str):
        self.heartbeat_calls += 1
        if self.heartbeat_error:
            raise self.heartbeat_error
        return {"leaseUntil": "2030-01-01T00:00:00.000Z", "cancelled": False}

    def append_events(self, attempt_id: str, lease_token: str, events: list[dict[str, Any]]):
        self.events.extend(events)
        return [{"eventId": e["eventId"], "status": "ACCEPTED"} for e in events]

    def complete(self, attempt_id: str, lease_token: str, **kwargs: Any):
        if self.complete_error:
            raise self.complete_error
        self.completes.append({"attemptId": attempt_id, "leaseToken": lease_token, **kwargs})
        return {"attemptId": attempt_id, "status": "COMPLETED"}

    def fail(self, attempt_id: str, lease_token: str, **kwargs: Any):
        self.failures.append({"attemptId": attempt_id, "leaseToken": lease_token, **kwargs})
        return {"attemptId": attempt_id, "status": "FAILED"}

    def upload_artifact(self, *args: Any, **kwargs: Any):
        return {"id": "01J00000000000000000000099"}


def make_task() -> dict[str, Any]:
    return {
        "kind": "RUN",
        "runId": "01J00000000000000000000001",
        "attemptId": "01J00000000000000000000002",
        "attemptNo": 1,
        "leaseToken": "token-abc",
        "leaseUntil": "2030-01-01T00:00:00.000Z",
        "deadlineAt": "2030-01-01T00:00:00.000Z",
        "input": {
            "runnerType": "MOCK",
            "agentConfig": {"model": "mock-model"},
            "budget": {},
            "datasetCase": {"caseKey": "case-1", "taskType": "AGENT_TASK", "input": {}},
        },
    }


@pytest.fixture()
def settings(monkeypatch: pytest.MonkeyPatch) -> Settings:
    monkeypatch.setenv("WORKER_ID", "test-worker")
    monkeypatch.setenv("WORKER_CAPABILITIES", "RUN:MOCK")
    monkeypatch.setenv("WORKER_HEARTBEAT_INTERVAL", "0.05")
    monkeypatch.setenv("REPOPILOT_WORKER_TOKEN", "x")
    return Settings()


def test_success_completes_with_manifest(settings: Settings) -> None:
    client = FakeClient(make_task())
    poller = WorkerPoller(client, MockRunner("SUCCESS"), settings)

    summary = poller.run_once()

    assert summary.claimed == 1
    assert summary.completed == 1
    assert summary.failed == 0
    assert len(client.completes) == 1
    completion = client.completes[0]
    event_ids = [e["eventId"] for e in client.events]
    assert completion["task_outcome"] == "SUCCESS"
    assert completion["expected_event_count"] == len(event_ids)
    assert completion["manifest_sha256"] == manifest_sha256(event_ids)


def test_business_failure_completes_with_failure_outcome(settings: Settings) -> None:
    client = FakeClient(make_task())
    poller = WorkerPoller(client, MockRunner("BUSINESS_FAILURE"), settings)

    summary = poller.run_once()

    assert summary.completed == 1
    assert client.completes[0]["task_outcome"] == "FAILURE"
    assert client.failures == []


def test_technical_error_calls_fail(settings: Settings) -> None:
    client = FakeClient(make_task())
    poller = WorkerPoller(client, MockRunner("TECHNICAL_ERROR"), settings)

    summary = poller.run_once()

    assert summary.failed == 1
    assert client.completes == []
    assert client.failures[0]["error_code"] == "MOCK_TECHNICAL_ERROR"
    assert client.failures[0]["retryable_hint"] is False
    # 失败前的轨迹仍要上报，否则排障时看不到"跑到哪一步崩的"
    assert len(client.events) == 2


def test_retryable_error_requests_retry(settings: Settings) -> None:
    client = FakeClient(make_task())
    poller = WorkerPoller(client, MockRunner("RETRYABLE_ERROR"), settings)

    poller.run_once()

    assert client.failures[0]["retryable_hint"] is True


def test_stale_complete_is_abandoned(settings: Settings) -> None:
    client = FakeClient(make_task())
    client.complete_error = WorkerApiError(409, "STALE_ATTEMPT", "expired")
    poller = WorkerPoller(client, MockRunner("SUCCESS"), settings)

    summary = poller.run_once()

    assert summary.abandoned == 1
    assert summary.completed == 0
    assert summary.errors == []


def test_heartbeat_stale_prevents_completion(settings: Settings) -> None:
    client = FakeClient(make_task())
    client.heartbeat_error = WorkerApiError(409, "STALE_ATTEMPT", "lease revoked")
    poller = WorkerPoller(client, SlowRunner(), settings)

    summary = poller.run_once()

    assert summary.abandoned == 1
    assert client.completes == []
    assert client.heartbeat_calls >= 1


class SlowRunner(MockRunner):
    """执行时间超过一个心跳周期，让心跳有机会发现租约失效。"""

    def __init__(self) -> None:
        super().__init__("SUCCESS")

    def run(self, request, emit):  # type: ignore[no-untyped-def]
        time.sleep(0.3)
        return super().run(request, emit)


def test_deadline_is_parsed(settings: Settings) -> None:
    # 协议时间是可选的；缺失时不应该让整个任务失败
    task = make_task()
    task.pop("deadlineAt")
    client = FakeClient(task)
    poller = WorkerPoller(client, MockRunner("SUCCESS"), settings)

    summary = poller.run_once()

    assert summary.completed == 1
