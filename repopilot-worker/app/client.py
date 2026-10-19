"""Java 后端 Worker 协议客户端。

使用标准库 ``urllib`` 而不是新增 HTTP 依赖：Worker 的运行时依赖被锁定在
``requirements.txt``，为一个同步调用引入第三方库会让部署面变大，收益很小。

错误处理约定：
* 4xx/5xx 抛 :class:`WorkerApiError`，携带后端稳定错误码；
* 网络超时/连接错误由调用方（poller）决定是否重试；
* 在这里**不自动重试**——重试需要复用同一请求身份（leaseToken 与正文），
  那是业务决策，不应该藏在传输层。
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from typing import Any


class WorkerApiError(Exception):
    """后端返回的非 2xx 响应。"""

    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(f"{status} {code}: {message}")
        self.status = status
        self.code = code
        self.message = message

    @property
    def is_stale(self) -> bool:
        return self.code == "STALE_ATTEMPT"

    @property
    def retryable(self) -> bool:
        return self.status in (429, 503) or self.status >= 500


def _iso(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


class WorkerClient:
    """同步 HTTP 客户端（一个 Worker 进程一个实例，线程安全：无共享可变状态）。"""

    def __init__(self, base_url: str, token: str, *, timeout: float = 15.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout

    # ------------------------------------------------------------------ 公共

    def _request(self, method: str, path: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
        data = None
        headers = {"Accept": "application/json", "Authorization": f"Bearer {self.token}"}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers,
                                         method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", errors="replace")
            try:
                envelope = json.loads(raw)
            except json.JSONDecodeError:
                envelope = {}
            raise WorkerApiError(exc.code, envelope.get("code", "HTTP_ERROR"),
                                 envelope.get("message", raw[:200])) from exc
        if payload.get("code") != "OK":
            raise WorkerApiError(200, payload.get("code", "UNKNOWN"),
                                 payload.get("message", "unknown error"))
        return payload.get("data") or {}

    # ------------------------------------------------------------------ 协议

    def claim(self, worker_id: str, capabilities: list[str], max_tasks: int) -> list[dict[str, Any]]:
        data = self._request("POST", "/internal/v1/work/claim", {
            "workerId": worker_id,
            "capabilities": capabilities,
            "maxTasks": max_tasks,
        })
        return data.get("tasks") or []

    def heartbeat(self, attempt_id: str, lease_token: str) -> dict[str, Any]:
        return self._request("POST", f"/internal/v1/run-attempts/{attempt_id}/heartbeat",
                             {"leaseToken": lease_token})

    def append_events(self, attempt_id: str, lease_token: str,
                      events: list[dict[str, Any]]) -> list[dict[str, Any]]:
        data = self._request("POST", f"/internal/v1/run-attempts/{attempt_id}/events", {
            "leaseToken": lease_token,
            "events": events,
        })
        return data.get("items") or []

    def complete(self, attempt_id: str, lease_token: str, *, task_outcome: str,
                 output: dict[str, Any] | None, usage: dict[str, Any] | None,
                 expected_event_count: int, manifest_sha256: str,
                 output_artifact_id: str | None = None) -> dict[str, Any]:
        body: dict[str, Any] = {
            "leaseToken": lease_token,
            "taskOutcome": task_outcome,
            "output": output,
            "usage": usage,
            "expectedEventCount": expected_event_count,
            "eventManifestSha256": manifest_sha256,
        }
        if output_artifact_id:
            body["outputArtifactId"] = output_artifact_id
        return self._request("POST", f"/internal/v1/run-attempts/{attempt_id}/complete", body)

    def fail(self, attempt_id: str, lease_token: str, *, error_code: str, error_message: str,
             retryable_hint: bool, usage: dict[str, Any] | None = None) -> dict[str, Any]:
        return self._request("POST", f"/internal/v1/run-attempts/{attempt_id}/fail", {
            "leaseToken": lease_token,
            "errorCode": error_code,
            "errorMessage": error_message,
            "retryableHint": retryable_hint,
            "usage": usage,
        })

    def upload_artifact(self, run_id: str, attempt_id: str, lease_token: str,
                        content: bytes, *, filename: str, artifact_type: str = "OUTPUT") -> dict[str, Any]:
        boundary = "----repopilot" + uuid.uuid4().hex
        parts: list[bytes] = []
        for name, value in (("runId", run_id), ("attemptId", attempt_id),
                            ("leaseToken", lease_token), ("artifactType", artifact_type)):
            parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n"
                         f"{value}\r\n".encode("utf-8"))
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                     f"filename=\"{filename}\"\r\nContent-Type: application/octet-stream\r\n\r\n"
                     .encode("utf-8"))
        parts.append(content)
        parts.append(f"\r\n--{boundary}--\r\n".encode("utf-8"))
        body = b"".join(parts)

        request = urllib.request.Request(
            self.base_url + "/internal/v1/artifacts", data=body, method="POST",
            headers={
                "Accept": "application/json",
                "Authorization": f"Bearer {self.token}",
                "Content-Type": f"multipart/form-data; boundary={boundary}",
                "Content-Length": str(len(body)),
            })
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", errors="replace")
            try:
                envelope = json.loads(raw)
            except json.JSONDecodeError:
                envelope = {}
            raise WorkerApiError(exc.code, envelope.get("code", "HTTP_ERROR"),
                                 envelope.get("message", raw[:200])) from exc
        return payload.get("data") or {}


def event_payload(event: Any) -> dict[str, Any]:
    """把 RunnerEvent 转成协议字段（不含 eventId 排序逻辑）。"""
    return {
        "eventId": event.event_id,
        "eventType": event.event_type,
        "occurredAt": _iso(event.occurred_at),
        "sequenceNo": event.sequence_no,
        "payload": event.payload,
    }


def manifest_sha256(event_ids: list[str]) -> str:
    """事件清单摘要：eventId 按 ASCII 升序，LF 连接，末尾无 LF。"""
    import hashlib

    joined = "\n".join(sorted(event_ids))
    return hashlib.sha256(joined.encode("utf-8")).hexdigest()


def env_token() -> str:
    return os.environ.get("REPOPILOT_WORKER_TOKEN", "")
