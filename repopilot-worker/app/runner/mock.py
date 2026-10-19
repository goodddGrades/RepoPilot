"""Mock Runner：五种预设，覆盖成功、业务失败、技术异常、超时与可重试错误。

它**不是**真实 Agent 接入。所有输出都带 ``mock=true`` 与 ``runner=MOCK``，
报告与前端据此可以明确区分"演练数据"和"真实执行结果"。
"""

from __future__ import annotations

import os
import time
from datetime import datetime, timezone
from typing import Any

from app.runner.base import (
    EmitEvent,
    Runner,
    RunnerEvent,
    RunnerFailure,
    RunnerRequest,
    RunnerResult,
    RunnerTimeout,
)

_ULID_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
_ULID_MASK = (1 << 128) - 1


def new_ulid() -> str:
    """生成 26 位 Crockford Base32 ULID（与后端校验一致）。"""
    timestamp = int(time.time() * 1000) & ((1 << 48) - 1)
    randomness = int.from_bytes(os.urandom(10), "big")
    value = ((timestamp << 80) | randomness) & _ULID_MASK
    chars = []
    for _ in range(26):
        chars.append(_ULID_ALPHABET[value & 0x1F])
        value >>= 5
    return "".join(reversed(chars))


SCENARIOS = {
    "SUCCESS",
    "BUSINESS_FAILURE",
    "TECHNICAL_ERROR",
    "TIMEOUT",
    "RETRYABLE_ERROR",
}


class MockRunner(Runner):
    """按配置返回固定模式的执行器，用于打通协议而不是模拟智能。"""

    name = "MOCK"

    def __init__(self, scenario: str = "SUCCESS", *, fail_first_attempt: bool = False) -> None:
        normalized = (scenario or "SUCCESS").upper()
        if normalized not in SCENARIOS:
            raise ValueError(f"未知的 Mock 场景: {scenario}，可选值: {sorted(SCENARIOS)}")
        self.scenario = normalized
        self.fail_first_attempt = fail_first_attempt

    def run(self, request: RunnerRequest, emit: EmitEvent) -> RunnerResult:
        if self.fail_first_attempt and request.attempt_no == 1:
            # 首次尝试注入可重试错误：验证"基础设施重试不增加 Run"
            raise RunnerFailure("MOCK_RETRYABLE_FIRST_ATTEMPT",
                                "Mock 首次尝试注入可重试错误", retryable=True)

        scenario = self.scenario
        emit("RUN_STARTED", {"runnerType": self.name, "scenario": scenario,
                             "attemptNo": request.attempt_no})

        if scenario == "TIMEOUT":
            emit("ERROR", {"errorCode": "TIMED_OUT", "message": "Mock 超时"})
            raise RunnerTimeout("Mock Runner 模拟超时")

        if scenario == "TECHNICAL_ERROR":
            emit("ERROR", {"errorCode": "MOCK_TECHNICAL_ERROR",
                           "message": "Mock 模拟技术异常"})
            raise RunnerFailure("MOCK_TECHNICAL_ERROR", "Mock 模拟技术异常", retryable=False)

        if scenario == "RETRYABLE_ERROR":
            emit("ERROR", {"errorCode": "UPSTREAM_UNAVAILABLE",
                           "message": "Mock 模拟上游暂时不可用"})
            raise RunnerFailure("UPSTREAM_UNAVAILABLE", "Mock 模拟上游暂时不可用", retryable=True)

        emit("MODEL_REQUEST", {"model": "mock-model",
                               "requestSummary": f"case={request.case.get('caseKey')}"})
        emit("MODEL_RESPONSE", {"model": "mock-model",
                                "outputSummary": scenario, "usage": {"totalTokens": 12}})

        if scenario == "BUSINESS_FAILURE":
            # 业务失败仍是"执行完成"：有可信输出，进入评测后由质量算子判 FAIL
            emit("OUTPUT_PRODUCED", {"outputSummary": "business failure"})
            emit("RUN_COMPLETED", {"taskOutcome": "FAILURE"})
            return RunnerResult(
                task_outcome="FAILURE",
                output={"mock": True, "runner": self.name, "scenario": scenario,
                        "reason": "Mock 模拟任务业务失败"},
                usage={"totalTokens": 12, "costUsd": 0.0},
            )

        emit("OUTPUT_PRODUCED", {"outputSummary": "mock answer"})
        emit("RUN_COMPLETED", {"taskOutcome": "SUCCESS"})
        return RunnerResult(
            task_outcome="SUCCESS",
            output={"mock": True, "runner": self.name, "scenario": scenario,
                    "caseKey": request.case.get("caseKey"),
                    "answer": f"mock answer for {request.case.get('caseKey')}"},
            usage={"totalTokens": 12, "costUsd": 0.0},
        )


def now_utc() -> datetime:
    """统一毫秒精度 UTC，和服务端事件哈希口径一致。"""
    now = datetime.now(tz=timezone.utc)
    return now.replace(microsecond=(now.microsecond // 1000) * 1000)


def build_event_collector() -> tuple[EmitEvent, list[RunnerEvent]]:
    """返回 (emit, events)：emit 生成稳定 eventId 并追加到列表。"""
    events: list[RunnerEvent] = []
    counter = {"value": 0}

    def emit(event_type: str, payload: dict[str, Any]) -> None:
        counter["value"] += 1
        events.append(RunnerEvent(event_id=new_ulid(), event_type=event_type,
                                  payload=payload, sequence_no=counter["value"],
                                  occurred_at=now_utc()))

    return emit, events
