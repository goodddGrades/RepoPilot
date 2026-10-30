"""评测任务分派：完成、技术失败、依赖重试与租约失效。

与 ``test_poller.py`` 的区别：那里测的是 Agent 执行任务的协议，这里测的是
``kind=EVALUATION`` 的分支。二者共用租约，但终态语义不同，所以单独断言：

* ``verdict=FAIL`` 是**正常完成**（业务失败不是技术失败）；
* 配置错误 / 算子不支持 → ``job_fail`` 且不可重试；
* 轨迹读不到 → ``job_fail``，可重试与否取决于 HTTP 状态；
* 心跳收到 409 → 放弃提交，哪怕本地已经算出结果。
"""

from __future__ import annotations

import threading
import time
from typing import Any

import pytest

from app.client import WorkerApiError
from app.config import Settings
from app.evaluator import registry
from app.evaluator.base import EvaluationOutcome
from app.evaluator.rules import OutputNonEmpty
from app.evaluator.trace import manifest_sha256
from app.poller import WorkerPoller


class FakeEvalClient:
    """只实现评测路径用到的方法；RUN 路径的方法一旦被调用就说明分派错了。"""

    def __init__(self, task: dict[str, Any], pages: list[dict[str, Any]] | None = None) -> None:
        self.task = task
        self.pages = pages if pages is not None else []
        self.claimed = False
        self.requests: list[str] = []
        self.completes: list[dict[str, Any]] = []
        self.failures: list[dict[str, Any]] = []
        self.job_heartbeats = 0
        self.heartbeat_error: Exception | None = None
        self.complete_error: Exception | None = None

    def claim(self, worker_id: str, capabilities: list[str], max_tasks: int):
        if self.claimed:
            return []
        self.claimed = True
        return [self.task]

    def get(self, path: str):
        self.requests.append(path)
        if not self.pages:
            return {"items": [], "hasMore": False}
        return self.pages.pop(0)

    def job_heartbeat(self, attempt_id: str, lease_token: str):
        self.job_heartbeats += 1
        if self.heartbeat_error:
            raise self.heartbeat_error
        return {"leaseUntil": "2030-01-01T00:00:00Z", "cancelled": False}

    def job_complete(self, attempt_id: str, lease_token: str, **kwargs: Any):
        if self.complete_error:
            raise self.complete_error
        self.completes.append({"attemptId": attempt_id, "leaseToken": lease_token, **kwargs})
        return {"attemptId": attempt_id, "status": "COMPLETED", "jobId": self.task["jobId"]}

    def job_fail(self, attempt_id: str, lease_token: str, **kwargs: Any):
        self.failures.append({"attemptId": attempt_id, "leaseToken": lease_token, **kwargs})
        return {"attemptId": attempt_id, "status": "FAILED", "jobId": self.task["jobId"],
                "retryScheduled": False, "nextRetryAt": None}

    def fail(self, attempt_id: str, lease_token: str, **kwargs: Any):
        self.failures.append({"attemptId": attempt_id, "leaseToken": lease_token, **kwargs})
        return {"attemptId": attempt_id, "status": "FAILED"}

    def heartbeat(self, *args: Any, **kwargs: Any):  # pragma: no cover - 分派错误才会命中
        raise AssertionError("评测任务不应走 Agent 心跳")

    def append_events(self, *args: Any, **kwargs: Any):  # pragma: no cover
        raise AssertionError("评测任务不应上报轨迹事件")

    def complete(self, *args: Any, **kwargs: Any):  # pragma: no cover
        raise AssertionError("评测任务不应走 Agent 完成回调")


_UNSET = object()


def make_task(evaluator_key: str = "output.non_empty", config: dict[str, Any] | None = None,
              output: Any = _UNSET, output_artifact_id: str | None = None,
              event_count: int = 0, manifest: str | None = None,
              sealed: bool = True) -> dict[str, Any]:
    return {
        "kind": "EVALUATION",
        "runId": "01J00000000000000000000001",
        "attemptId": "01J00000000000000000000002",
        "attemptNo": 1,
        "leaseToken": "token-eval",
        "leaseUntil": "2030-01-01T00:00:00.000Z",
        "deadlineAt": "2030-01-01T00:00:00.000Z",
        "jobId": "01J00000000000000000000003",
        "input": {
            "schemaVersion": "1",
            "jobId": "01J00000000000000000000003",
            "batchId": "01J00000000000000000000004",
            "executionOrder": 1,
            "required": True,
            "evaluator": {"key": evaluator_key, "version": 1, "type": "RULE",
                          "implementationRef": f"builtin/{evaluator_key}"},
            "config": config if config is not None else {"outputPath": "answer"},
            "acceptedRunSnapshot": {
                "runId": "01J00000000000000000000001",
                "mode": "ONLINE",
                "taskOutcome": "SUCCESS",
                "acceptedAttemptId": "01J00000000000000000000005",
                "output": {"answer": "42"} if output is _UNSET else output,
                "outputArtifactId": output_artifact_id,
                "usage": {"costUsd": 0.01, "currency": "USD"},
                "datasetCase": {"caseKey": "case-1", "taskType": "AGENT_TASK",
                                "input": {"q": "?"}, "expected": {"answer": "42"},
                                "metadata": {}},
                "trace": {"sealed": sealed,
                          "eventsUrl": "/api/v1/runs/01J00000000000000000000001/events?limit=200",
                          "eventCount": event_count,
                          "manifestSha256": manifest},
            },
        },
    }


@pytest.fixture()
def settings(monkeypatch: pytest.MonkeyPatch) -> Settings:
    monkeypatch.setenv("WORKER_ID", "eval-worker")
    monkeypatch.setenv("WORKER_CAPABILITIES", "EVAL:output.non_empty")
    monkeypatch.setenv("WORKER_HEARTBEAT_INTERVAL", "0.05")
    monkeypatch.setenv("REPOPILOT_WORKER_TOKEN", "x")
    return Settings()


def build(settings: Settings, client: FakeEvalClient,
          evaluators: dict[str, Any] | None = None) -> WorkerPoller:
    return WorkerPoller(client, None, settings,
                        evaluators=evaluators if evaluators is not None
                        else {"output.non_empty": OutputNonEmpty()})


def test_successful_evaluation_persists_verdict(settings: Settings) -> None:
    client = FakeEvalClient(make_task())
    summary = build(settings, client).run_once()

    assert summary.claimed == 1
    assert summary.completed == 1
    assert summary.evaluations == 1
    assert summary.runs == 0
    assert client.failures == []
    completion = client.completes[0]
    assert completion["verdict"] == "PASS"
    assert completion["score"] == 1.0
    assert completion["generated_by"] == "RULE"
    assert completion["result"]["empty"] is False
    assert completion["evidence"] == [{"type": "outputPath", "path": "answer"}]


def test_quality_failure_is_reported_as_completion(settings: Settings) -> None:
    client = FakeEvalClient(make_task(output={"answer": "   "}))
    summary = build(settings, client).run_once()

    # 质量不过关是"评测跑完了"，不是"评测没跑成"
    assert summary.failed == 0
    assert summary.completed == 1
    assert client.failures == []
    assert client.completes[0]["verdict"] == "FAIL"
    assert client.completes[0]["score"] == 0.0


def test_missing_output_is_not_applicable(settings: Settings) -> None:
    client = FakeEvalClient(make_task(output=None, output_artifact_id=None))
    build(settings, client).run_once()

    assert client.completes[0]["verdict"] == "NOT_APPLICABLE"
    assert client.completes[0]["score"] is None


def test_config_error_fails_without_retry(settings: Settings) -> None:
    client = FakeEvalClient(make_task(config={}))
    summary = build(settings, client).run_once()

    assert summary.completed == 0
    assert summary.failed == 1
    assert client.completes == []
    failure = client.failures[0]
    assert failure["error_code"] == "EVALUATOR_CONFIG_INVALID"
    # 同一个配置重试多少次都会得到同样的错误
    assert failure["retryable_hint"] is False


def test_unsupported_evaluator_fails_without_running(settings: Settings) -> None:
    # judge.rubric 属于 TASK-008 的 LLM Judge，V1 未实现：必须显式失败而不是空跑成功
    client = FakeEvalClient(make_task(evaluator_key="judge.rubric",
                                      config={"rubricPath": "quality"}))
    summary = build(settings, client).run_once()

    assert summary.failed == 1
    assert client.failures[0]["error_code"] == "UNSUPPORTED_EVALUATOR"
    assert client.failures[0]["retryable_hint"] is False
    assert "judge.rubric" in client.failures[0]["error_message"]
    # 未实现的算子不能退化成"什么都没做但成功"
    assert client.completes == []


def test_trace_manifest_mismatch_fails_without_retry(settings: Settings) -> None:
    client = FakeEvalClient(make_task(event_count=5, manifest="deadbeef"),
                            pages=[{"items": [], "hasMore": False}])
    build(settings, client).run_once()

    failure = client.failures[0]
    assert failure["error_code"] == "TRACE_MANIFEST_MISMATCH"
    assert "不一致" in failure["error_message"]
    assert failure["retryable_hint"] is False


def test_trace_fetch_server_error_is_retryable(settings: Settings) -> None:
    task = make_task()
    client = FakeEvalClient(task)

    def boom(path: str):
        raise WorkerApiError(503, "UNAVAILABLE", "backend down")

    client.get = boom  # type: ignore[method-assign]
    summary = build(settings, client).run_once()

    assert summary.failed == 1
    assert client.failures[0]["error_code"] == "TRACE_FETCH_FAILED"
    assert client.failures[0]["retryable_hint"] is True


def test_unsealed_trace_is_rejected(settings: Settings) -> None:
    client = FakeEvalClient(make_task(sealed=False))
    build(settings, client).run_once()

    assert client.failures[0]["error_code"] == "TRACE_NOT_SEALED"
    assert client.failures[0]["retryable_hint"] is False


def test_lost_lease_discards_the_result(settings: Settings) -> None:
    class SlowEvaluator(OutputNonEmpty):
        def evaluate(self, data, trace):
            time.sleep(0.4)
            return super().evaluate(data, trace)

    client = FakeEvalClient(make_task())
    client.heartbeat_error = WorkerApiError(409, "STALE_ATTEMPT", "lease taken over")
    summary = build(settings, client, {"output.non_empty": SlowEvaluator()}).run_once()

    assert summary.abandoned == 1
    assert summary.completed == 0
    # 已经被别人接管的 Attempt 不能再写完成：否则会出现两份结果
    assert client.completes == []


def test_heartbeat_runs_during_evaluation(settings: Settings) -> None:
    class SlowEvaluator(OutputNonEmpty):
        def evaluate(self, data, trace):
            time.sleep(0.3)
            return super().evaluate(data, trace)

    client = FakeEvalClient(make_task())
    build(settings, client, {"output.non_empty": SlowEvaluator()}).run_once()

    assert client.job_heartbeats >= 1
    assert len(client.completes) == 1


def test_complete_is_replayed_when_receipt_is_lost(settings: Settings) -> None:
    client = FakeEvalClient(make_task())
    attempts = {"n": 0}

    def flaky(attempt_id: str, lease_token: str, **kwargs: Any):
        attempts["n"] += 1
        if attempts["n"] == 1:
            raise WorkerApiError(502, "BAD_GATEWAY", "gateway hiccup")
        return {"attemptId": attempt_id, "status": "COMPLETED", "jobId": client.task["jobId"]}

    client.job_complete = flaky  # type: ignore[method-assign]
    summary = build(settings, client).run_once()

    assert attempts["n"] == 2
    assert summary.completed == 1


def test_stale_receipt_is_not_retried(settings: Settings) -> None:
    client = FakeEvalClient(make_task())
    client.complete_error = WorkerApiError(409, "STALE_ATTEMPT", "gone")
    summary = build(settings, client).run_once()

    assert summary.abandoned == 1
    assert summary.completed == 0


def test_runner_absent_does_not_handle_run_tasks(settings: Settings) -> None:
    task = {"kind": "RUN", "attemptId": "a1", "leaseToken": "t1", "runId": "r1", "input": {}}
    client = FakeEvalClient(task)
    summary = build(settings, client).run_once()

    assert summary.failed == 1
    assert client.failures[0]["error_code"] == "NO_RUNNER"


def test_evaluation_reads_trace_from_the_snapshot_url(settings: Settings) -> None:
    events = [{"eventId": "evt-1", "eventType": "TOOL_COMPLETED", "payload": {}}]
    task = make_task(event_count=1, manifest=manifest_sha256(["evt-1"]))
    client = FakeEvalClient(task, pages=[{"items": events, "hasMore": False}])
    build(settings, client).run_once()

    assert client.requests == ["/api/v1/runs/01J00000000000000000000001/events?limit=200"]
    assert client.completes[0]["verdict"] == "PASS"


def test_registry_exposes_all_six_v1_evaluators() -> None:
    """六个最小算子全部可构造（TASK-008 补齐 judge.rubric 与 test.command）。

    `available_keys` 覆盖需要运行期资源的算子（Judge 走 CLI）；
    `build` 才是"真的造一个实例"的入口。
    """
    assert sorted(registry.available_keys()) == [
        "answer.exact_match", "cost.budget", "judge.rubric",
        "output.non_empty", "test.command", "trace.tool_error"]

    with pytest.raises(RuntimeError):
        registry.resolve(["not.implemented"])


def test_registry_build_requires_real_cli_for_runner_only_operators() -> None:
    """test.command 不依赖外部进程，可直接构造。"""
    evaluator = registry.build("test.command")

    assert evaluator.key == "test.command"
    assert evaluator.generated_by == "TEST"


def test_registry_get_does_not_fabricate_resource_heavy_evaluators() -> None:
    """`get` 只返回无外部依赖的算子：不能给出一个"连不上供应商"的假实例。"""
    assert registry.get("output.non_empty") is not None
    assert registry.get("judge.rubric") is None
    assert registry.get("test.command") is None
