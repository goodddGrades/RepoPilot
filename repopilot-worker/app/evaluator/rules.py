"""V1 内置算子：规则、轨迹与成本。

每个算子只回答一个可复现的问题，并把"为什么"写进 reason 与 result：
* 缺输入 → NOT_APPLICABLE（不适用），不是 PASS，也不是 0 分；
* 证据不足以判断 → INDETERMINATE；
* 判定为失败 → FAIL + score=0，**Job 仍然正常完成**（质量失败不是技术失败）；
* 配置写错 → :class:`EvaluatorConfigError`，由 Worker 报技术失败，不伪装成 FAIL。

所有阈值、路径与规范化开关都来自已发布的 plan item config，算子内部没有隐藏默认值。
"""

from __future__ import annotations

from typing import Any

from app.evaluator.base import (
    MISSING,
    EvaluationInput,
    EvaluationOutcome,
    EvaluatorConfigError,
    TraceEvents,
    as_text,
    is_empty,
    resolve_path,
)

_VERDICT_SCORE = {"PASS": 1.0, "FAIL": 0.0}

COST_KEYS = ("costUsd", "cost_usd", "cost", "totalCost", "total_cost")
CURRENCY_KEYS = ("currency", "costCurrency", "cost_currency")
FAILED_OUTCOMES = {"FAILED", "FAILURE", "ERROR", "TIMEOUT", "TIMED_OUT"}


def _config(data: EvaluationInput) -> dict[str, Any]:
    config = data.config
    if config is None:
        return {}
    if not isinstance(config, dict):
        raise EvaluatorConfigError("config 必须是 JSON 对象")
    return config


def _required_str(config: dict[str, Any], key: str) -> str:
    value = config.get(key)
    if not isinstance(value, str) or not value.strip():
        raise EvaluatorConfigError(f"config.{key} 必须是非空字符串")
    return value.strip()


def _bool(config: dict[str, Any], key: str, default: bool) -> bool:
    value = config.get(key, default)
    if not isinstance(value, bool):
        raise EvaluatorConfigError(f"config.{key} 必须是布尔值")
    return value


def _number(config: dict[str, Any], key: str) -> float:
    value = config.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise EvaluatorConfigError(f"config.{key} 必须是数字")
    return float(value)


def _evidence_path(kind: str, path: str) -> dict[str, Any]:
    return {"type": kind, "path": path}


def _truncate(value: Any, limit: int = 2000) -> Any:
    """结果里保留可追溯的值，但不无限膨胀：超长文本截断并标注。"""
    if isinstance(value, str) and len(value) > limit:
        return value[:limit] + f"…(truncated, {len(value)} chars)"
    return value


class OutputNonEmpty:
    """output.non_empty：有输出但为空 → FAIL；来源缺失输出 → NOT_APPLICABLE。"""

    key = "output.non_empty"
    generated_by = "RULE"

    def evaluate(self, data: EvaluationInput, trace: TraceEvents) -> EvaluationOutcome:
        config = _config(data)
        output_path = _required_str(config, "outputPath")
        if not data.output_present:
            return EvaluationOutcome(
                verdict="NOT_APPLICABLE", score=None,
                reason="来源缺失输出，无法判断是否为空",
                evidence=[], result={"outputPath": output_path, "outputPresent": False})
        if data.output is None:
            return EvaluationOutcome(
                verdict="INDETERMINATE", score=None,
                reason="只登记了输出附件，正文未内联，无法判断内容是否为空",
                evidence=[{"type": "artifact", "artifactId": data.output_artifact_id}],
                result={"outputPath": output_path, "outputArtifactId": data.output_artifact_id})
        value = resolve_path(data.output, output_path)
        if value is MISSING:
            return EvaluationOutcome(
                verdict="INDETERMINATE", score=None,
                reason=f"输出中不存在字段 {output_path}",
                evidence=[_evidence_path("outputPath", output_path)],
                result={"outputPath": output_path, "fieldPresent": False})
        empty = is_empty(value)
        return EvaluationOutcome(
            verdict="FAIL" if empty else "PASS",
            score=_VERDICT_SCORE["FAIL" if empty else "PASS"],
            reason="输出为空" if empty else "输出非空",
            evidence=[_evidence_path("outputPath", output_path)],
            result={"outputPath": output_path, "empty": empty,
                    "value": _truncate(value)})


class AnswerExactMatch:
    """answer.exact_match：缺 ground truth → NOT_APPLICABLE；有值后按配置规范化比较。"""

    key = "answer.exact_match"
    generated_by = "RULE"

    def evaluate(self, data: EvaluationInput, trace: TraceEvents) -> EvaluationOutcome:
        config = _config(data)
        output_path = _required_str(config, "outputPath")
        expected_path = _required_str(config, "expectedPath")
        trim = _bool(config, "trim", True)
        normalize_newlines = _bool(config, "normalizeNewlines", True)
        ignore_case = _bool(config, "ignoreCase", False)

        expected_raw = resolve_path(data.case.get("expected"), expected_path)
        if expected_raw is MISSING or expected_raw is None:
            return EvaluationOutcome(
                verdict="NOT_APPLICABLE", score=None,
                reason=f"样本缺少 ground truth: {expected_path}",
                evidence=[_evidence_path("casePath", f"expected.{expected_path}")],
                result={"expectedPath": expected_path, "expectedPresent": False})

        actual_raw = resolve_path(data.output, output_path)
        if not data.output_present or actual_raw is MISSING:
            return EvaluationOutcome(
                verdict="INDETERMINATE", score=None,
                reason=f"输出中缺少可比对的字段: {output_path}",
                evidence=[_evidence_path("outputPath", output_path)],
                result={"outputPath": output_path, "actualPresent": False,
                        "expected": _truncate(expected_raw)})

        expected = _normalize(as_text(expected_raw), trim, normalize_newlines, ignore_case)
        actual = _normalize(as_text(actual_raw), trim, normalize_newlines, ignore_case)
        matched = expected == actual
        return EvaluationOutcome(
            verdict="PASS" if matched else "FAIL",
            score=_VERDICT_SCORE["PASS" if matched else "FAIL"],
            reason="与期望值完全一致" if matched else "与期望值不一致",
            evidence=[_evidence_path("casePath", f"expected.{expected_path}"),
                      _evidence_path("outputPath", output_path)],
            result={"expected": _truncate(expected_raw), "actual": _truncate(actual_raw),
                    "normalization": {"trim": trim, "normalizeNewlines": normalize_newlines,
                                      "ignoreCase": ignore_case}})


def _normalize(text: str, trim: bool, normalize_newlines: bool, ignore_case: bool) -> str:
    value = text
    if normalize_newlines:
        value = value.replace("\r\n", "\n").replace("\r", "\n")
    if trim:
        value = value.strip()
    if ignore_case:
        value = value.casefold()
    return value


class TraceToolError:
    """trace.tool_error：完整工具观测中有失败 → FAIL；缺少观测 → NOT_APPLICABLE。"""

    key = "trace.tool_error"
    generated_by = "RULE"

    def evaluate(self, data: EvaluationInput, trace: TraceEvents) -> EvaluationOutcome:
        config = _config(data)
        ignore = config.get("ignoreTools", [])
        if ignore is None:
            ignore = []
        if not isinstance(ignore, list) or any(not isinstance(item, str) for item in ignore):
            raise EvaluatorConfigError("config.ignoreTools 必须是字符串数组")
        ignored = set(ignore)

        started = [event for event in trace.of_type("TOOL_STARTED")
                   if _tool_name(event) not in ignored]
        completed = [event for event in trace.of_type("TOOL_COMPLETED")
                     if _tool_name(event) not in ignored]
        failed = [event for event in trace.of_type("TOOL_FAILED")
                  if _tool_name(event) not in ignored]

        if not started and not completed and not failed:
            return EvaluationOutcome(
                verdict="NOT_APPLICABLE", score=None,
                reason="轨迹中没有工具调用观测，不能据此判定通过",
                evidence=[], result={"toolCalls": 0, "ignoredTools": ignore})

        failures: list[dict[str, Any]] = []
        for event in completed:
            payload = event.get("payload") or {}
            outcome = str(payload.get("outcome") or payload.get("status") or "").upper()
            if (outcome in FAILED_OUTCOMES or payload.get("success") is False
                    or payload.get("error") is not None or payload.get("errorCode")):
                failures.append({"eventId": event.get("eventId"),
                                 "toolName": _tool_name(event),
                                 "toolCallId": payload.get("toolCallId")})
        for event in failed:
            failures.append({"eventId": event.get("eventId"), "toolName": _tool_name(event),
                             "toolCallId": (event.get("payload") or {}).get("toolCallId")})

        completed_ids = {_call_id(event) for event in completed if _call_id(event)}
        started_ids = {_call_id(event) for event in started if _call_id(event)}
        unanswered = sorted(started_ids - completed_ids - {_call_id(e) for e in failed})

        if unanswered:
            # 有开始没有结束：观测不完整。此时"没有失败"只是没看到，不能当 PASS。
            return EvaluationOutcome(
                verdict="INDETERMINATE", score=None,
                reason=f"工具调用未闭合，缺少 {len(unanswered)} 个完成事件: {', '.join(unanswered)}",
                evidence=[{"type": "event", "eventId": event.get("eventId")}
                          for event in started if _call_id(event) in unanswered][:50],
                result={"toolCalls": len(started_ids | completed_ids), "unclosed": unanswered,
                        "failures": failures, "ignoredTools": ignore})

        if failures:
            return EvaluationOutcome(
                verdict="FAIL", score=0.0,
                reason=f"{len(failures)} 个工具调用失败",
                evidence=[{"type": "event", "eventId": item["eventId"]}
                          for item in failures if item.get("eventId")][:50],
                result={"toolCalls": len(started_ids | completed_ids), "failures": failures,
                        "ignoredTools": ignore})

        return EvaluationOutcome(
            verdict="PASS", score=1.0,
            reason=f"{len(started_ids)} 个工具调用全部成功",
            evidence=[{"type": "event", "eventId": event.get("eventId")}
                      for event in completed][:50],
            result={"toolCalls": len(started_ids | completed_ids), "failures": [],
                    "ignoredTools": ignore})


def _tool_name(event: dict[str, Any]) -> str:
    payload = event.get("payload") or {}
    return str(payload.get("toolName") or payload.get("tool") or "")


def _call_id(event: dict[str, Any]) -> str:
    payload = event.get("payload") or {}
    return str(payload.get("toolCallId") or "")


class CostBudget:
    """cost.budget：成本/币种齐全且同币种时比较；未知 → NOT_APPLICABLE。"""

    key = "cost.budget"
    generated_by = "RULE"

    def evaluate(self, data: EvaluationInput, trace: TraceEvents) -> EvaluationOutcome:
        config = _config(data)
        max_cost = _number(config, "maxCost")
        currency = _required_str(config, "currency").upper()
        if len(currency) != 3:
            raise EvaluatorConfigError("config.currency 必须是 3 位币种代码")

        usage = data.usage if isinstance(data.usage, dict) else {}
        cost_key = next((key for key in COST_KEYS if usage.get(key) is not None), None)
        currency_key = next((key for key in CURRENCY_KEYS if usage.get(key) is not None), None)
        actual_currency = str(usage.get(currency_key) or "").upper() if currency_key else ""

        if cost_key is None:
            return EvaluationOutcome(
                verdict="NOT_APPLICABLE", score=None,
                reason="缺少成本数据，不自动按 0 分或通过处理",
                evidence=[], result={"maxCost": max_cost, "currency": currency,
                                     "costPresent": False})
        cost_value = usage.get(cost_key)
        if isinstance(cost_value, bool) or not isinstance(cost_value, (int, float)):
            raise EvaluatorConfigError(f"usage.{cost_key} 必须是数字，收到: {cost_value!r}")
        if not actual_currency:
            return EvaluationOutcome(
                verdict="NOT_APPLICABLE", score=None,
                reason="缺少币种，成本不可比较（不做汇率猜测）",
                evidence=[_evidence_path("usagePath", f"usage.{cost_key}")],
                result={"maxCost": max_cost, "currency": currency, "actualCost": cost_value,
                        "currencyPresent": False})
        if actual_currency != currency:
            return EvaluationOutcome(
                verdict="NOT_APPLICABLE", score=None,
                reason=f"成本币种 {actual_currency} 与预算币种 {currency} 不同，不可比较",
                evidence=[_evidence_path("usagePath", f"usage.{cost_key}")],
                result={"maxCost": max_cost, "currency": currency, "actualCost": cost_value,
                        "actualCurrency": actual_currency})

        within = float(cost_value) <= max_cost
        return EvaluationOutcome(
            verdict="PASS" if within else "FAIL",
            score=_VERDICT_SCORE["PASS" if within else "FAIL"],
            reason="成本在预算内" if within else "成本超出预算",
            evidence=[_evidence_path("usagePath", f"usage.{cost_key}")],
            result={"actualCost": cost_value, "maxCost": max_cost, "currency": currency})
