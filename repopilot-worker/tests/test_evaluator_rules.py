"""规则算子语义：通过、失败、不适用、不确定与配置错误。

这里断言的是**结论与理由**，不是"跑通了"：
每个用例都明确写出期望的 verdict、score 与关键 reason 片段。
算子把"缺输入"和"配置写错"分成两条路径，测试也分两条：
前者产出 NOT_APPLICABLE / INDETERMINATE，后者抛 EvaluatorConfigError。
"""

from __future__ import annotations

from typing import Any

import pytest

from app.client import WorkerApiError
from app.evaluator.base import (
    EvaluationInput,
    EvaluatorConfigError,
    TraceEvents,
    TraceRef,
    as_text,
    is_empty,
    resolve_path,
)
from app.evaluator.rules import AnswerExactMatch, CostBudget, OutputNonEmpty, TraceToolError
from app.evaluator.trace import TraceReader, manifest_sha256

SEALED = TraceRef(sealed=True, events_url="/api/v1/runs/r1/events?limit=200",
                  event_count=0, manifest_sha256=None)


def make_input(**overrides: Any) -> EvaluationInput:
    base: dict[str, Any] = {
        "job_id": "job-1",
        "batch_id": "batch-1",
        "execution_order": 1,
        "required": True,
        "run_id": "run-1",
        "mode": "ONLINE",
        "task_outcome": "SUCCESS",
        "accepted_attempt_id": "attempt-1",
        "output": {"answer": "42"},
        "output_artifact_id": None,
        "usage": {"costUsd": 0.02, "currency": "USD"},
        "case": {"expected": {"answer": "42"}},
        "trace": SEALED,
        "evaluator": {"key": "output.non_empty", "version": 1, "type": "RULE"},
        "config": {"outputPath": "answer"},
    }
    base.update(overrides)
    return EvaluationInput(**base)


def tool_events(*specs: tuple[str, str, str, dict[str, Any] | None]) -> TraceEvents:
    """specs: (eventType, toolName, toolCallId, payload 覆盖)。"""
    events: list[dict[str, Any]] = []
    for index, (event_type, tool_name, call_id, extra) in enumerate(specs):
        payload: dict[str, Any] = {"toolName": tool_name, "toolCallId": call_id}
        payload.update(extra or {})
        events.append({"eventId": f"evt-{index}", "eventType": event_type, "payload": payload})
    return TraceEvents(events, len(events))


# --------------------------------------------------------------------- 输出非空


class TestOutputNonEmpty:
    evaluator = OutputNonEmpty()

    def test_non_empty_output_passes(self) -> None:
        outcome = self.evaluator.evaluate(make_input(), TraceEvents([], 0))
        assert outcome.verdict == "PASS"
        assert outcome.score == 1.0
        assert outcome.result["empty"] is False
        assert outcome.generated_by == "RULE"

    def test_blank_output_fails_but_is_a_normal_completion(self) -> None:
        outcome = self.evaluator.evaluate(make_input(output={"answer": "   \n "}), TraceEvents([], 0))
        assert outcome.verdict == "FAIL"
        assert outcome.score == 0.0
        assert outcome.result["empty"] is True

    def test_empty_list_counts_as_empty(self) -> None:
        outcome = self.evaluator.evaluate(make_input(output={"answer": []}), TraceEvents([], 0))
        assert outcome.verdict == "FAIL"

    def test_missing_source_output_is_not_applicable(self) -> None:
        outcome = self.evaluator.evaluate(
            make_input(output=None, output_artifact_id=None), TraceEvents([], 0))
        assert outcome.verdict == "NOT_APPLICABLE"
        assert outcome.score is None
        assert "缺失输出" in outcome.reason

    def test_artifact_only_output_is_indeterminate(self) -> None:
        outcome = self.evaluator.evaluate(
            make_input(output=None, output_artifact_id="01JARTIFACT"), TraceEvents([], 0))
        assert outcome.verdict == "INDETERMINATE"
        assert outcome.evidence == [{"type": "artifact", "artifactId": "01JARTIFACT"}]

    def test_missing_field_is_indeterminate(self) -> None:
        outcome = self.evaluator.evaluate(make_input(output={"other": "x"}), TraceEvents([], 0))
        assert outcome.verdict == "INDETERMINATE"
        assert "answer" in outcome.reason

    def test_config_without_output_path_is_technical_error(self) -> None:
        with pytest.raises(EvaluatorConfigError) as excinfo:
            self.evaluator.evaluate(make_input(config={}), TraceEvents([], 0))
        assert excinfo.value.error_code == "EVALUATOR_CONFIG_INVALID"
        assert "outputPath" in excinfo.value.message

    def test_config_with_wrong_type_is_technical_error(self) -> None:
        with pytest.raises(EvaluatorConfigError):
            self.evaluator.evaluate(make_input(config=["answer"]), TraceEvents([], 0))

    def test_long_value_is_truncated_in_result(self) -> None:
        outcome = self.evaluator.evaluate(make_input(output={"answer": "x" * 5000}), TraceEvents([], 0))
        assert len(outcome.result["value"]) < 5000
        assert "truncated" in outcome.result["value"]


# --------------------------------------------------------------------- 精确匹配


class TestAnswerExactMatch:
    evaluator = AnswerExactMatch()

    def config(self, **overrides: Any) -> dict[str, Any]:
        base = {"outputPath": "answer", "expectedPath": "answer"}
        base.update(overrides)
        return base

    def test_exact_match_passes(self) -> None:
        outcome = self.evaluator.evaluate(make_input(config=self.config()), TraceEvents([], 0))
        assert outcome.verdict == "PASS"
        assert outcome.result["normalization"]["trim"] is True

    def test_default_normalization_absorbs_whitespace_and_crlf(self) -> None:
        data = make_input(output={"answer": "line1\r\nline2  "},
                          case={"expected": {"answer": "line1\nline2"}},
                          config=self.config())
        assert self.evaluator.evaluate(data, TraceEvents([], 0)).verdict == "PASS"

    def test_case_sensitive_by_default(self) -> None:
        data = make_input(output={"answer": "ABC"}, case={"expected": {"answer": "abc"}},
                          config=self.config())
        assert self.evaluator.evaluate(data, TraceEvents([], 0)).verdict == "FAIL"

    def test_ignore_case_from_config(self) -> None:
        data = make_input(output={"answer": "ABC"}, case={"expected": {"answer": "abc"}},
                          config=self.config(ignoreCase=True))
        assert self.evaluator.evaluate(data, TraceEvents([], 0)).verdict == "PASS"

    def test_trim_disabled_reveals_difference(self) -> None:
        data = make_input(output={"answer": "42 "}, case={"expected": {"answer": "42"}},
                          config=self.config(trim=False))
        assert self.evaluator.evaluate(data, TraceEvents([], 0)).verdict == "FAIL"

    def test_missing_ground_truth_is_not_applicable(self) -> None:
        data = make_input(case={"expected": {}}, config=self.config())
        outcome = self.evaluator.evaluate(data, TraceEvents([], 0))
        assert outcome.verdict == "NOT_APPLICABLE"
        assert "ground truth" in outcome.reason

    def test_null_ground_truth_is_not_applicable(self) -> None:
        data = make_input(case={"expected": {"answer": None}}, config=self.config())
        assert self.evaluator.evaluate(data, TraceEvents([], 0)).verdict == "NOT_APPLICABLE"

    def test_missing_actual_is_indeterminate(self) -> None:
        data = make_input(output={}, config=self.config())
        outcome = self.evaluator.evaluate(data, TraceEvents([], 0))
        assert outcome.verdict == "INDETERMINATE"
        assert outcome.result["actualPresent"] is False

    def test_non_string_values_compare_as_canonical_json(self) -> None:
        data = make_input(output={"answer": {"b": 1, "a": 2}},
                          case={"expected": {"answer": {"a": 2, "b": 1}}},
                          config=self.config())
        assert self.evaluator.evaluate(data, TraceEvents([], 0)).verdict == "PASS"

    def test_boolean_switch_must_be_boolean(self) -> None:
        with pytest.raises(EvaluatorConfigError) as excinfo:
            self.evaluator.evaluate(make_input(config=self.config(trim="yes")), TraceEvents([], 0))
        assert "trim" in excinfo.value.message


# --------------------------------------------------------------------- 工具错误


class TestTraceToolError:
    evaluator = TraceToolError()

    def test_no_tool_observation_is_not_applicable(self) -> None:
        outcome = self.evaluator.evaluate(make_input(), TraceEvents([], 0))
        assert outcome.verdict == "NOT_APPLICABLE"
        assert "不能据此判定通过" in outcome.reason

    def test_all_tools_succeeded_passes(self) -> None:
        trace = tool_events(("TOOL_STARTED", "search", "c1", None),
                            ("TOOL_COMPLETED", "search", "c1", {"outcome": "SUCCESS"}),
                            ("TOOL_STARTED", "fetch", "c2", None),
                            ("TOOL_COMPLETED", "fetch", "c2", {"success": True}))
        outcome = self.evaluator.evaluate(make_input(), trace)
        assert outcome.verdict == "PASS"
        assert outcome.result["toolCalls"] == 2
        assert len(outcome.evidence) == 2

    def test_failed_tool_fails(self) -> None:
        trace = tool_events(("TOOL_COMPLETED", "search", "c1", {"outcome": "ERROR"}))
        outcome = self.evaluator.evaluate(make_input(), trace)
        assert outcome.verdict == "FAIL"
        assert outcome.score == 0.0
        assert outcome.result["failures"][0]["toolName"] == "search"

    def test_error_field_presence_means_failure(self) -> None:
        trace = tool_events(("TOOL_COMPLETED", "search", "c1", {"error": "boom"}))
        assert self.evaluator.evaluate(make_input(), trace).verdict == "FAIL"

    def test_explicit_tool_failed_event_fails(self) -> None:
        trace = tool_events(("TOOL_STARTED", "search", "c1", None),
                            ("TOOL_FAILED", "search", "c1", {"reason": "timeout"}))
        assert self.evaluator.evaluate(make_input(), trace).verdict == "FAIL"

    def test_unclosed_call_is_indeterminate_not_pass(self) -> None:
        trace = tool_events(("TOOL_STARTED", "search", "c1", None))
        outcome = self.evaluator.evaluate(make_input(), trace)
        assert outcome.verdict == "INDETERMINATE"
        assert outcome.result["unclosed"] == ["c1"]

    def test_ignored_tools_are_excluded(self) -> None:
        trace = tool_events(("TOOL_COMPLETED", "flaky", "c1", {"outcome": "ERROR"}),
                            ("TOOL_COMPLETED", "search", "c2", {"outcome": "SUCCESS"}))
        outcome = self.evaluator.evaluate(make_input(config={"ignoreTools": ["flaky"]}), trace)
        assert outcome.verdict == "PASS"
        assert outcome.result["toolCalls"] == 1

    def test_ignoring_everything_leaves_nothing_to_judge(self) -> None:
        trace = tool_events(("TOOL_COMPLETED", "flaky", "c1", {"outcome": "ERROR"}))
        outcome = self.evaluator.evaluate(make_input(config={"ignoreTools": ["flaky"]}), trace)
        assert outcome.verdict == "NOT_APPLICABLE"

    def test_ignore_tools_must_be_string_array(self) -> None:
        with pytest.raises(EvaluatorConfigError):
            self.evaluator.evaluate(make_input(config={"ignoreTools": "search"}),
                                    TraceEvents([], 0))
        with pytest.raises(EvaluatorConfigError):
            self.evaluator.evaluate(make_input(config={"ignoreTools": [1, 2]}),
                                    TraceEvents([], 0))

    def test_evidence_is_capped_at_fifty(self) -> None:
        specs = [("TOOL_COMPLETED", f"t{i}", f"c{i}", {"outcome": "SUCCESS"}) for i in range(80)]
        outcome = self.evaluator.evaluate(make_input(), tool_events(*specs))
        assert outcome.verdict == "PASS"
        assert len(outcome.evidence) == 50


# --------------------------------------------------------------------- 成本预算


class TestCostBudget:
    evaluator = CostBudget()

    def test_within_budget_passes(self) -> None:
        outcome = self.evaluator.evaluate(
            make_input(config={"maxCost": 0.05, "currency": "USD"}), TraceEvents([], 0))
        assert outcome.verdict == "PASS"
        assert outcome.result["actualCost"] == 0.02

    def test_over_budget_fails(self) -> None:
        outcome = self.evaluator.evaluate(
            make_input(config={"maxCost": 0.01, "currency": "USD"}), TraceEvents([], 0))
        assert outcome.verdict == "FAIL"
        assert outcome.score == 0.0

    def test_exactly_at_budget_passes(self) -> None:
        outcome = self.evaluator.evaluate(
            make_input(config={"maxCost": 0.02, "currency": "USD"}), TraceEvents([], 0))
        assert outcome.verdict == "PASS"

    def test_missing_cost_is_not_applicable_not_zero(self) -> None:
        outcome = self.evaluator.evaluate(
            make_input(usage={}, config={"maxCost": 0.05, "currency": "USD"}), TraceEvents([], 0))
        assert outcome.verdict == "NOT_APPLICABLE"
        assert outcome.score is None
        assert outcome.result["costPresent"] is False

    def test_missing_currency_is_not_applicable(self) -> None:
        outcome = self.evaluator.evaluate(
            make_input(usage={"costUsd": 0.02}, config={"maxCost": 0.05, "currency": "USD"}),
            TraceEvents([], 0))
        assert outcome.verdict == "NOT_APPLICABLE"
        assert outcome.result["currencyPresent"] is False

    def test_currency_mismatch_is_not_applicable(self) -> None:
        outcome = self.evaluator.evaluate(
            make_input(usage={"costUsd": 0.02, "currency": "CNY"},
                       config={"maxCost": 0.05, "currency": "USD"}), TraceEvents([], 0))
        assert outcome.verdict == "NOT_APPLICABLE"
        assert "不可比较" in outcome.reason

    def test_currency_compared_case_insensitively(self) -> None:
        outcome = self.evaluator.evaluate(
            make_input(usage={"costUsd": 0.02, "currency": "usd"},
                       config={"maxCost": 0.05, "currency": "usd"}), TraceEvents([], 0))
        assert outcome.verdict == "PASS"

    def test_non_numeric_cost_is_technical_error(self) -> None:
        with pytest.raises(EvaluatorConfigError):
            self.evaluator.evaluate(
                make_input(usage={"costUsd": "cheap"}, config={"maxCost": 0.05, "currency": "USD"}),
                TraceEvents([], 0))

    def test_max_cost_must_be_number(self) -> None:
        with pytest.raises(EvaluatorConfigError):
            self.evaluator.evaluate(make_input(config={"maxCost": "0.05", "currency": "USD"}),
                                    TraceEvents([], 0))

    def test_currency_must_be_three_letters(self) -> None:
        with pytest.raises(EvaluatorConfigError):
            self.evaluator.evaluate(make_input(config={"maxCost": 0.05, "currency": "US"}),
                                    TraceEvents([], 0))


# --------------------------------------------------------------------- 轨迹读取


class FakeTraceClient:
    def __init__(self, pages: list[dict[str, Any]], error: Exception | None = None) -> None:
        self.pages = pages
        self.error = error
        self.calls: list[str] = []

    def get(self, path: str) -> dict[str, Any]:
        self.calls.append(path)
        if self.error:
            raise self.error
        return self.pages.pop(0)


def ref_for(events: list[dict[str, Any]], **overrides: Any) -> TraceRef:
    values = {
        "sealed": True,
        "events_url": "/api/v1/runs/r1/events?limit=200",
        "event_count": len(events),
        "manifest_sha256": manifest_sha256([event["eventId"] for event in events]),
    }
    values.update(overrides)
    return TraceRef(**values)


class TestTraceReader:
    def test_multi_page_is_read_until_exhausted(self) -> None:
        events = [{"eventId": f"evt-{i}", "eventType": "TOOL_COMPLETED"} for i in range(3)]
        client = FakeTraceClient([
            {"items": events[:2], "hasMore": True, "nextCursor": "c-2"},
            {"items": events[2:], "hasMore": False},
        ])
        trace = TraceReader(client).load(ref_for(events))
        assert len(trace) == 3
        assert trace.expected_count == 3
        assert "cursor=c-2" in client.calls[1]

    def test_unsealed_trace_is_rejected(self) -> None:
        from app.evaluator.base import EvaluationDependencyError

        with pytest.raises(EvaluationDependencyError) as excinfo:
            TraceReader(FakeTraceClient([])).load(ref_for([], sealed=False))
        assert excinfo.value.error_code == "TRACE_NOT_SEALED"
        assert excinfo.value.retryable is False

    def test_count_mismatch_is_rejected(self) -> None:
        from app.evaluator.base import EvaluationDependencyError

        events = [{"eventId": "evt-0", "eventType": "TOOL_COMPLETED"}]
        client = FakeTraceClient([{"items": [], "hasMore": False}])
        with pytest.raises(EvaluationDependencyError) as excinfo:
            TraceReader(client).load(ref_for(events))
        assert excinfo.value.error_code == "TRACE_MANIFEST_MISMATCH"
        # 缺事件不是"可重试的网络问题"，重试只会重复读到同一份残缺清单
        assert excinfo.value.retryable is False

    def test_digest_mismatch_is_rejected_even_with_same_count(self) -> None:
        from app.evaluator.base import EvaluationDependencyError

        events = [{"eventId": "evt-0", "eventType": "TOOL_COMPLETED"}]
        tampered = [{"eventId": "evt-9", "eventType": "TOOL_COMPLETED"}]
        client = FakeTraceClient([{"items": tampered, "hasMore": False}])
        with pytest.raises(EvaluationDependencyError) as excinfo:
            TraceReader(client).load(ref_for(events))
        assert "摘要" in excinfo.value.message

    def test_server_error_is_retryable(self) -> None:
        from app.evaluator.base import EvaluationDependencyError

        client = FakeTraceClient([], error=WorkerApiError(503, "UNAVAILABLE", "server is busy"))
        with pytest.raises(EvaluationDependencyError) as excinfo:
            TraceReader(client).load(ref_for([]))
        assert excinfo.value.error_code == "TRACE_FETCH_FAILED"
        assert excinfo.value.retryable is True

    def test_missing_cursor_breaks_the_contract(self) -> None:
        from app.evaluator.base import EvaluationDependencyError

        client = FakeTraceClient([{"items": [], "hasMore": True}])
        with pytest.raises(EvaluationDependencyError) as excinfo:
            TraceReader(client).load(ref_for([]))
        assert excinfo.value.error_code == "TRACE_CURSOR_MISSING"


# --------------------------------------------------------------------- 工具函数


class TestHelpers:
    def test_resolve_path_handles_objects_arrays_and_roots(self) -> None:
        from app.evaluator.base import MISSING

        document = {"a": {"b": [10, 20]}, "c": None}
        assert resolve_path(document, "a.b.1") == 20
        assert resolve_path(document, "$") is document
        assert resolve_path(document, "a.b.5") is MISSING
        assert resolve_path(document, "a.missing") is MISSING
        # 值本身是 null 与"字段不存在"必须区分：前者可判定，后者不可
        assert resolve_path(document, "c") is None

    def test_resolve_path_returns_the_missing_sentinel(self) -> None:
        from app.evaluator.base import MISSING

        assert resolve_path({"a": 1}, "b") is MISSING
        assert resolve_path(None, "a") is MISSING

    def test_is_empty_covers_blank_collections_and_none(self) -> None:
        assert is_empty("") and is_empty("  \n") and is_empty([]) and is_empty({}) and is_empty(None)
        assert not is_empty("0") and not is_empty([0]) and not is_empty(0)

    def test_as_text_is_stable_for_structures(self) -> None:
        assert as_text({"b": 1, "a": 2}) == as_text({"a": 2, "b": 1})
        assert as_text("raw") == "raw"
