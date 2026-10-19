"""Mock Runner 的五种预设。

这些用例只验证"预设是否按契约产出结果"，不验证任何 Agent 能力——
Mock 的价值是把协议跑通，而不是模拟智能。
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from app.runner.base import RunnerFailure, RunnerRequest, RunnerTimeout
from app.runner.mock import MockRunner, build_event_collector


def make_request(attempt_no: int = 1) -> RunnerRequest:
    return RunnerRequest(
        run_id="01J00000000000000000000001",
        attempt_id="01J00000000000000000000002",
        attempt_no=attempt_no,
        runner_type="MOCK",
        agent_config={"model": "mock-model"},
        budget={},
        case={"caseKey": "case-1", "taskType": "AGENT_TASK", "input": {"prompt": "hi"}},
        deadline_at=datetime.now(tz=timezone.utc) + timedelta(minutes=5),
    )


def run(scenario: str, *, attempt_no: int = 1, fail_first: bool = False):
    runner = MockRunner(scenario, fail_first_attempt=fail_first)
    emit, events = build_event_collector()
    result = runner.run(make_request(attempt_no), emit)
    return result, events


def test_success_produces_result_and_events() -> None:
    result, events = run("SUCCESS")

    assert result.task_outcome == "SUCCESS"
    assert result.output is not None
    assert result.output["mock"] is True
    assert result.output["runner"] == "MOCK"
    assert result.output["caseKey"] == "case-1"
    assert [e.event_type for e in events] == [
        "RUN_STARTED", "MODEL_REQUEST", "MODEL_RESPONSE", "OUTPUT_PRODUCED", "RUN_COMPLETED",
    ]
    assert all(len(e.event_id) == 26 for e in events)
    assert len({e.event_id for e in events}) == len(events)
    assert [e.sequence_no for e in events] == [1, 2, 3, 4, 5]


def test_business_failure_is_a_completed_result() -> None:
    result, events = run("BUSINESS_FAILURE")

    # 业务失败不是技术异常：有可信输出，交由质量算子判 FAIL
    assert result.task_outcome == "FAILURE"
    assert result.output is not None
    assert result.output["scenario"] == "BUSINESS_FAILURE"
    assert events[-1].event_type == "RUN_COMPLETED"


def test_technical_error_raises_non_retryable_failure() -> None:
    with pytest.raises(RunnerFailure) as caught:
        run("TECHNICAL_ERROR")

    assert caught.value.error_code == "MOCK_TECHNICAL_ERROR"
    assert caught.value.retryable is False


def test_timeout_raises_timeout_failure() -> None:
    with pytest.raises(RunnerTimeout) as caught:
        run("TIMEOUT")

    assert caught.value.error_code == "TIMED_OUT"
    assert caught.value.retryable is False


def test_retryable_error_is_marked_retryable() -> None:
    with pytest.raises(RunnerFailure) as caught:
        run("RETRYABLE_ERROR")

    assert caught.value.retryable is True


def test_fail_first_attempt_then_succeed() -> None:
    with pytest.raises(RunnerFailure) as caught:
        run("SUCCESS", attempt_no=1, fail_first=True)
    assert caught.value.retryable is True

    result, _ = run("SUCCESS", attempt_no=2, fail_first=True)
    assert result.task_outcome == "SUCCESS"


def test_unknown_scenario_is_rejected() -> None:
    with pytest.raises(ValueError):
        MockRunner("NOT_A_SCENARIO")
