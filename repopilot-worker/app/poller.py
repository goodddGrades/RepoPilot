"""领取—执行—回传循环。

职责边界：
* 只使用 ``/internal/v1`` 协议，不直连数据库；
* 每次尝试独立：一个任务失败不阻塞同批其它任务；
* 心跳在独立线程里跑，主线程执行 Runner/算子。心跳返回 409（租约失效）时置取消位，
  主线程**不再提交完成**——即使本地已经算出结果，也不能写进已被别人接管的 Attempt。

一次领取可能同时包含两类任务：``RUN``（Agent 执行）与 ``EVALUATION``（算子）。
两者共用租约协议但终态语义不同：
* RUN 的业务失败通过 ``complete(task_outcome=FAILURE)`` 交回，仍然进入评测；
* 评测的 ``verdict=FAIL`` 是一次正常完成；只有配置错误/依赖不可用才走 ``job_fail``。

不在这里做"完成之后的重试"：完成失败如果是 409，说明这次执行已经作废；
如果是 5xx/超时，用同一正文重放一次（幂等），仍失败就留给后端回收。
"""

from __future__ import annotations

import threading
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from app.client import WorkerApiError, WorkerClient, event_payload, manifest_sha256
from app.common.logging import get_logger
from app.config import Settings
from app.evaluator import registry
from app.evaluator.base import (
    EvaluationDependencyError,
    EvaluationInput,
    Evaluator,
    EvaluatorConfigError,
    TraceRef,
)
from app.evaluator.trace import TraceReader
from app.runner.base import Runner, RunnerFailure, RunnerRequest
from app.runner.mock import build_event_collector

log = get_logger("app.poller")


@dataclass
class PollSummary:
    claimed: int = 0
    completed: int = 0
    failed: int = 0
    abandoned: int = 0
    runs: int = 0
    evaluations: int = 0
    errors: list[str] = field(default_factory=list)


class WorkerPoller:
    def __init__(self, client: WorkerClient, runner: Runner | None, settings: Settings,
                 evaluators: dict[str, Evaluator] | None = None,
                 trace_reader: TraceReader | None = None) -> None:
        self.client = client
        self.runner = runner
        self.settings = settings
        self.evaluators = evaluators if evaluators is not None else {}
        self.trace_reader = trace_reader or TraceReader(client)

    # ------------------------------------------------------------------ 单轮

    def run_once(self) -> PollSummary:
        summary = PollSummary()
        tasks = self.client.claim(self.settings.worker_id, self.settings.capabilities,
                                  self.settings.max_tasks)
        summary.claimed = len(tasks)
        for task in tasks:
            try:
                if task.get("kind") == "EVALUATION":
                    self.execute_evaluation(task, summary)
                else:
                    self.execute(task, summary)
            except WorkerApiError as exc:
                if exc.is_stale:
                    summary.abandoned += 1
                    log.warning("任务已失效，跳过回传: %s", exc.message)
                else:
                    summary.errors.append(str(exc))
                    log.error("任务回传失败: %s", exc)
            except Exception as exc:  # noqa: BLE001 - 单个任务失败不能拖垮循环
                summary.errors.append(str(exc))
                log.exception("执行任务异常")
                if task.get("kind") == "EVALUATION":
                    self._try_job_fail(task, "EVALUATOR_CRASHED", str(exc), retryable=False)
                else:
                    self._try_fail(task, "WORKER_INTERNAL_ERROR", str(exc), retryable=False)
        return summary

    # ------------------------------------------------------------------ 评测任务

    def execute_evaluation(self, task: dict[str, Any], summary: PollSummary) -> None:
        attempt_id = task["attemptId"]
        lease_token = task["leaseToken"]
        payload = task.get("input") or {}
        evaluator_info = payload.get("evaluator") or {}
        key = str(evaluator_info.get("key") or "")
        evaluator = self.evaluators.get(key) or registry.get(key)
        if evaluator is None:
            self._try_job_fail(task, "UNSUPPORTED_EVALUATOR",
                               f"Worker 未声明或未实现算子 {key}", retryable=False)
            summary.failed += 1
            return

        data = _to_evaluation_input(task, payload)
        cancelled = threading.Event()
        stop_heartbeat = threading.Event()
        heartbeat = threading.Thread(target=self._job_heartbeat_loop,
                                     args=(attempt_id, lease_token, stop_heartbeat, cancelled),
                                     name=f"job-heartbeat-{attempt_id[:8]}", daemon=True)
        heartbeat.start()
        try:
            trace = self.trace_reader.load(data.trace)
            outcome = evaluator.evaluate(data, trace)
        except EvaluatorConfigError as exc:
            stop_heartbeat.set()
            if cancelled.is_set():
                summary.abandoned += 1
                return
            self._try_job_fail(task, exc.error_code, exc.message, retryable=False)
            summary.failed += 1
            return
        except EvaluationDependencyError as exc:
            stop_heartbeat.set()
            if cancelled.is_set():
                summary.abandoned += 1
                return
            self._try_job_fail(task, exc.error_code, exc.message, retryable=exc.retryable)
            summary.failed += 1
            return
        except Exception as exc:  # noqa: BLE001
            stop_heartbeat.set()
            if cancelled.is_set():
                summary.abandoned += 1
                return
            self._try_job_fail(task, "EVALUATOR_CRASHED", str(exc), retryable=False)
            summary.failed += 1
            return
        finally:
            stop_heartbeat.set()

        if cancelled.is_set():
            summary.abandoned += 1
            log.warning("租约失效，放弃提交评测结果: attempt=%s", attempt_id)
            return

        try:
            self._complete_job(attempt_id, lease_token, outcome.verdict, outcome.score,
                               outcome.reason, outcome.evidence, outcome.result,
                               outcome.generated_by, outcome.usage)
        except WorkerApiError as exc:
            if exc.is_stale:
                summary.abandoned += 1
                return
            raise
        summary.completed += 1
        summary.evaluations += 1

    def _complete_job(self, attempt_id: str, lease_token: str, verdict: str,
                      score: float | None, reason: str, evidence: list[dict[str, Any]],
                      result: dict[str, Any], generated_by: str,
                      usage: dict[str, Any] | None = None) -> None:
        try:
            self.client.job_complete(attempt_id, lease_token, verdict=verdict, score=score,
                                     reason=reason, evidence=evidence, result=result,
                                     generated_by=generated_by, usage=usage)
        except WorkerApiError as exc:
            if exc.is_stale or not exc.retryable:
                raise
            log.warning("评测完成回执丢失，使用同一正文重放: %s", exc)
            self.client.job_complete(attempt_id, lease_token, verdict=verdict, score=score,
                                     reason=reason, evidence=evidence, result=result,
                                     generated_by=generated_by, usage=usage)

    def _job_heartbeat_loop(self, attempt_id: str, lease_token: str,
                            stop: threading.Event, cancelled: threading.Event) -> None:
        interval = max(0.05, self.settings.heartbeat_interval)
        while not stop.wait(interval):
            try:
                self.client.job_heartbeat(attempt_id, lease_token)
            except WorkerApiError as exc:
                if exc.status == 409 or exc.is_stale:
                    cancelled.set()
                    return
                log.warning("评测心跳失败（可重试）: %s", exc)
            except Exception as exc:  # noqa: BLE001 - 网络抖动不终止执行
                log.warning("评测心跳异常: %s", exc)

    def _try_job_fail(self, task: dict[str, Any], code: str, message: str, *,
                      retryable: bool) -> None:
        try:
            self.client.job_fail(task["attemptId"], task["leaseToken"], error_code=code,
                                 error_message=message[:1024], retryable_hint=retryable)
        except WorkerApiError as exc:
            if exc.is_stale:
                log.warning("评测失败回执已过期（Attempt 被回收）: %s", task["attemptId"])
                return
            raise

    # ------------------------------------------------------------------ 执行任务

    def execute(self, task: dict[str, Any], summary: PollSummary) -> None:
        if self.runner is None:
            self._try_fail(task, "NO_RUNNER", "Worker 没有可用的 Runner 实现", retryable=False)
            summary.failed += 1
            return
        attempt_id = task["attemptId"]
        lease_token = task["leaseToken"]
        payload = task.get("input") or {}
        case = payload.get("datasetCase") or {}

        runner_request = RunnerRequest(
            run_id=task["runId"],
            attempt_id=attempt_id,
            attempt_no=task["attemptNo"],
            runner_type=payload.get("runnerType", "MOCK"),
            agent_config=payload.get("agentConfig") or {},
            budget=payload.get("budget") or {},
            case=case,
            deadline_at=_parse_instant(task.get("deadlineAt")),
        )

        cancelled = threading.Event()
        stop_heartbeat = threading.Event()
        heartbeat = threading.Thread(target=self._heartbeat_loop,
                                     args=(attempt_id, lease_token, stop_heartbeat, cancelled),
                                     name=f"heartbeat-{attempt_id[:8]}", daemon=True)
        heartbeat.start()

        emit, events = build_event_collector()
        try:
            result = self.runner.run(runner_request, emit)
        except RunnerFailure as failure:
            stop_heartbeat.set()
            if cancelled.is_set():
                summary.abandoned += 1
                return
            # 先把已产生的事件送上去，再报告失败：失败 Attempt 的轨迹同样是证据
            self._upload_events(attempt_id, lease_token, events)
            self._try_fail(task, failure.error_code, failure.message, retryable=failure.retryable)
            summary.failed += 1
            return
        except Exception as exc:  # noqa: BLE001
            stop_heartbeat.set()
            if cancelled.is_set():
                summary.abandoned += 1
                return
            self._upload_events(attempt_id, lease_token, events)
            self._try_fail(task, "RUNNER_CRASHED", str(exc), retryable=False)
            summary.failed += 1
            return
        finally:
            stop_heartbeat.set()

        if cancelled.is_set():
            summary.abandoned += 1
            log.warning("租约失效，放弃提交结果: attempt=%s", attempt_id)
            return

        accepted_ids = self._upload_events(attempt_id, lease_token, events)

        output_artifact_id = None
        inline_output = result.output
        if self.settings.upload_artifact and result.output is not None:
            import json

            blob = json.dumps(result.output, ensure_ascii=False, sort_keys=True).encode("utf-8")
            artifact = self.client.upload_artifact(
                task["runId"], attempt_id, lease_token, blob,
                filename="output.json", artifact_type="OUTPUT")
            output_artifact_id = artifact["id"]
            inline_output = None

        completion = {
            "attempt_id": attempt_id,
            "lease_token": lease_token,
            "task_outcome": result.task_outcome,
            "output": inline_output,
            "usage": result.usage,
            "expected_event_count": len(accepted_ids),
            "manifest_sha256": manifest_sha256(accepted_ids),
        }
        if output_artifact_id:
            completion["output_artifact_id"] = output_artifact_id

        self._complete(completion)
        summary.completed += 1
        summary.runs += 1

    # ------------------------------------------------------------------ 协议细节

    def _heartbeat_loop(self, attempt_id: str, lease_token: str,
                        stop: threading.Event, cancelled: threading.Event) -> None:
        interval = max(0.05, self.settings.heartbeat_interval)
        while not stop.wait(interval):
            try:
                self.client.heartbeat(attempt_id, lease_token)
            except WorkerApiError as exc:
                if exc.status == 409 or exc.is_stale:
                    cancelled.set()
                    return
                log.warning("心跳失败（可重试）: %s", exc)
            except Exception as exc:  # noqa: BLE001 - 网络抖动不终止执行
                log.warning("心跳异常: %s", exc)

    def _upload_events(self, attempt_id: str, lease_token: str, events: list[Any]) -> list[str]:
        accepted: list[str] = []
        for start in range(0, len(events), 500):
            batch = events[start:start + 500]
            acks = self.client.append_events(attempt_id, lease_token,
                                             [event_payload(e) for e in batch])
            accepted.extend(ack["eventId"] for ack in acks)
        return accepted

    def _complete(self, completion: dict[str, Any]) -> None:
        attempt_id = completion["attempt_id"]
        lease_token = completion["lease_token"]
        body = {k: v for k, v in completion.items() if k not in ("attempt_id", "lease_token")}
        if body.get("output_artifact_id") is None:
            body.pop("output_artifact_id", None)
        try:
            self.client.complete(attempt_id, lease_token,
                                 task_outcome=body["task_outcome"],
                                 output=body.get("output"),
                                 usage=body.get("usage"),
                                 expected_event_count=body["expected_event_count"],
                                 manifest_sha256=body["manifest_sha256"],
                                 output_artifact_id=body.get("output_artifact_id"))
        except WorkerApiError as exc:
            if exc.is_stale or not exc.retryable:
                raise
            log.warning("完成回执丢失，使用同一正文重放: %s", exc)
            self.client.complete(attempt_id, lease_token,
                                 task_outcome=body["task_outcome"],
                                 output=body.get("output"),
                                 usage=body.get("usage"),
                                 expected_event_count=body["expected_event_count"],
                                 manifest_sha256=body["manifest_sha256"],
                                 output_artifact_id=body.get("output_artifact_id"))

    def _try_fail(self, task: dict[str, Any], code: str, message: str, *, retryable: bool) -> None:
        try:
            self.client.fail(task["attemptId"], task["leaseToken"], error_code=code,
                             error_message=message[:1024], retryable_hint=retryable)
        except WorkerApiError as exc:
            if exc.is_stale:
                log.warning("失败回执已过期（Attempt 被回收）: %s", task["attemptId"])
                return
            raise


def _to_evaluation_input(task: dict[str, Any], payload: dict[str, Any]) -> EvaluationInput:
    snapshot = payload.get("acceptedRunSnapshot") or {}
    trace = snapshot.get("trace") or {}
    return EvaluationInput(
        job_id=str(payload.get("jobId") or task.get("jobId") or ""),
        batch_id=str(payload.get("batchId") or ""),
        execution_order=int(payload.get("executionOrder") or 0),
        required=bool(payload.get("required")),
        run_id=str(snapshot.get("runId") or task.get("runId") or ""),
        mode=str(snapshot.get("mode") or ""),
        task_outcome=str(snapshot.get("taskOutcome") or "UNKNOWN"),
        accepted_attempt_id=snapshot.get("acceptedAttemptId"),
        output=snapshot.get("output"),
        output_artifact_id=snapshot.get("outputArtifactId"),
        usage=snapshot.get("usage") or {},
        case=snapshot.get("datasetCase") or {},
        trace=TraceRef(sealed=bool(trace.get("sealed")),
                       events_url=trace.get("eventsUrl"),
                       event_count=int(trace.get("eventCount") or 0),
                       manifest_sha256=trace.get("manifestSha256")),
        evaluator=payload.get("evaluator") or {},
        config=payload.get("config") or {},
    )


def _parse_instant(value: str | None) -> datetime:
    if not value:
        from datetime import timezone

        return datetime.now(tz=timezone.utc)
    return datetime.fromisoformat(value.replace("Z", "+00:00"))
