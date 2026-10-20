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

    # ------------------------------------------------------------------ Inbox（TASK-013-C）

    def accept_notification(self, consumer_name: str, envelope: dict[str, Any]) -> str:
        """把收到的事件登记进 Inbox，返回 ``ACCEPTED`` / ``DUPLICATE``。

        ``POST /internal/v1/notifications/accept`` 承担三件事，
        缺任何一件消息通道都不成立：

        1. **去重**：``(consumer_name, event_id)`` 唯一键让重复投递没有业务效果；
        2. **契约校验**：后端是事件契约的唯一权威，Worker 自查一遍必然漂移；
        3. **异内容检测**：同一个 eventId 出现不同载荷是数据损坏，
           后端返回 ``PAYLOAD_CONFLICT``（409），必须隔离而不是当重复静默 ACK。

        **不自动重试**：登记失败应当由消费者决定去向（重投或进死信），
        在传输层偷偷重试会让"失败了几次"这个诊断信息失真。
        """
        data = self._request("POST", "/internal/v1/notifications/accept", {
            "consumerName": consumer_name,
            "event": envelope,
        })
        return str(data.get("status") or "UNKNOWN")

    # ------------------------------------------------------------------ 消费统计（TASK-013-E）

    def report_consumer_stats(self, consumer_name: str, stats: dict[str, int]) -> None:
        """把本轮消费统计上报给后端（``POST /internal/v1/messaging/consumer-stats``）。

        **为什么消费结果必须由消费者上报**：后端数据库里看不到
        "一条消息被 reject 进了死信"这件事——在 ``outbox_events`` 里它仍然是
        ``PUBLISHED``（发布确实成功了），在 ``inbox_events`` 里也可能没有记录
        （它压根没走到登记那一步）。消费成功的精确计数只在消费者进程里存在。

        **上报失败必须被调用方吞掉**：统计上报是观测手段，不是业务路径。
        让它把消息处理流程带崩，会用"观测手段坏了"换来"业务也坏了"。

        **上报的是增量**：后端累加。传累计值的话，进程重启会让计数倒退。
        """
        self._request("POST", "/internal/v1/messaging/consumer-stats", {
            "consumerName": consumer_name,
            "stats": stats,
        })

    # ------------------------------------------------------------------ 协议

    def claim(self, worker_id: str, capabilities: list[str], max_tasks: int) -> list[dict[str, Any]]:
        data = self._request("POST", "/internal/v1/work/claim", {
            "workerId": worker_id,
            "capabilities": capabilities,
            "maxTasks": max_tasks,
        })
        return data.get("tasks") or []

    def get(self, path: str) -> dict[str, Any]:
        """读取相对路径（例如评测输入里的 eventsUrl）。"""
        return self._request("GET", path)

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

    # ------------------------------------------------------------------ 评测（TASK-007）

    def job_heartbeat(self, attempt_id: str, lease_token: str) -> dict[str, Any]:
        return self._request("POST", f"/internal/v1/job-attempts/{attempt_id}/heartbeat",
                             {"leaseToken": lease_token})

    def job_complete(self, attempt_id: str, lease_token: str, *, verdict: str,
                     score: float | None, reason: str, evidence: list[dict[str, Any]],
                     result: dict[str, Any], generated_by: str,
                     usage: dict[str, Any] | None = None) -> dict[str, Any]:
        """提交评测结论。``verdict=FAIL`` 是质量失败，仍然是一次正常完成。"""
        return self._request("POST", f"/internal/v1/job-attempts/{attempt_id}/complete", {
            "leaseToken": lease_token,
            "verdict": verdict,
            "score": score,
            "reason": reason,
            "evidence": evidence,
            "result": result,
            "generatedBy": generated_by,
            "usage": usage,
        })

    def job_fail(self, attempt_id: str, lease_token: str, *, error_code: str,
                 error_message: str, retryable_hint: bool,
                 usage: dict[str, Any] | None = None) -> dict[str, Any]:
        return self._request("POST", f"/internal/v1/job-attempts/{attempt_id}/fail", {
            "leaseToken": lease_token,
            "errorCode": error_code,
            "errorMessage": error_message[:1024],
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
