"""算子接口与评测输入。

边界：
* 算子只做"给定已封口的输入，产出一个结论"，不负责领取、租约、重试、写库；
* 输入缺失与配置错误是两种不同的问题——前者产出 NOT_APPLICABLE / INDETERMINATE，
  后者抛 :class:`EvaluatorConfigError`（技术失败，不重试）；
* 仍然读不到的依赖（例如轨迹接口 5xx）抛 :class:`EvaluationDependencyError`，
  由 Worker 标记为可重试。

算子不得自行编造期望值、工具输出或成本：缺失就是缺失。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol

MISSING = object()


@dataclass(frozen=True)
class TraceRef:
    """封口轨迹的读取信息（来自服务端快照）。"""

    sealed: bool
    events_url: str | None
    event_count: int
    manifest_sha256: str | None


@dataclass(frozen=True)
class EvaluationInput:
    job_id: str
    batch_id: str
    execution_order: int
    required: bool
    run_id: str
    mode: str
    task_outcome: str
    accepted_attempt_id: str | None
    output: Any
    output_artifact_id: str | None
    usage: dict[str, Any]
    case: dict[str, Any]
    trace: TraceRef
    evaluator: dict[str, Any]
    config: dict[str, Any]

    @property
    def evaluator_key(self) -> str:
        return str(self.evaluator.get("key") or "")

    @property
    def output_present(self) -> bool:
        """输出是否存在。仅有一个 Artifact id 也算"有输出"，但正文需要另行读取。"""
        return self.output is not None or bool(self.output_artifact_id)


@dataclass
class EvaluationOutcome:
    """一次算子执行的结构化结论。

    ``usage`` 是算子**自身**消耗的资源（Judge 的 token 与成本），与被测 Run 的
    usage 是两笔账：它必须独立上报，否则重试累计的 Judge 开销会丢失，
    成本预算也算不准。缺字段就是未知，不写 0。
    """

    verdict: str
    score: float | None
    reason: str
    evidence: list[dict[str, Any]] = field(default_factory=list)
    result: dict[str, Any] = field(default_factory=dict)
    generated_by: str = "RULE"
    usage: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if self.verdict not in {"PASS", "FAIL", "INDETERMINATE", "NOT_APPLICABLE"}:
            raise ValueError(f"未知 verdict: {self.verdict}")
        if self.score is not None and not 0 <= self.score <= 1:
            raise ValueError(f"score 越界: {self.score}")


class EvaluatorConfigError(Exception):
    """配置不合法（缺字段、类型错误、路径非法）：技术失败，不自动重试。"""

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.error_code = "EVALUATOR_CONFIG_INVALID"
        self.message = message


class EvaluationDependencyError(Exception):
    """外部依赖不可用（轨迹读取失败、超时）：技术失败，可重试。"""

    def __init__(self, error_code: str, message: str, *, retryable: bool = True) -> None:
        super().__init__(message)
        self.error_code = error_code
        self.message = message
        self.retryable = retryable


class Evaluator(Protocol):
    """算子接口：纯函数式，不持有可变状态（Worker 可能并发复用实例）。"""

    key: str
    generated_by: str

    def evaluate(self, data: EvaluationInput, trace: "TraceEvents") -> EvaluationOutcome:
        ...


class TraceEvents(list):
    """已校验的封口事件列表。

    继承 ``list`` 是为了让算子的过滤/统计写法保持简单；额外保留
    ``fetched_count`` 与 ``expected_count``，用于在结果里记录"读到了多少"。
    """

    def __init__(self, events: list[dict[str, Any]], expected_count: int) -> None:
        super().__init__(events)
        self.expected_count = expected_count

    def of_type(self, *event_types: str) -> list[dict[str, Any]]:
        wanted = set(event_types)
        return [event for event in self if event.get("eventType") in wanted]


def resolve_path(document: Any, path: str | None) -> Any:
    """按点号路径取值；``$`` / 空串 / ``.`` 表示整份文档。缺失返回 :data:`MISSING`。"""
    if document is None:
        return MISSING
    if path is None or path in ("", "$", "."):
        return document
    current = document
    for part in path.split("."):
        if part == "":
            continue
        if isinstance(current, dict):
            if part not in current:
                return MISSING
            current = current[part]
        elif isinstance(current, list):
            try:
                index = int(part)
            except ValueError:
                return MISSING
            if index < 0 or index >= len(current):
                return MISSING
            current = current[index]
        else:
            return MISSING
    return current


def is_empty(value: Any) -> bool:
    """空白字符串、空数组、空对象、null 都算"有输出但为空"。"""
    if value is None:
        return True
    if isinstance(value, str):
        return value.strip() == ""
    if isinstance(value, (list, dict)):
        return len(value) == 0
    return False


def as_text(value: Any) -> str:
    """把值转成可比较的文本：字符串原样，其它类型用稳定 JSON 表示。"""
    import json

    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False, sort_keys=True)
