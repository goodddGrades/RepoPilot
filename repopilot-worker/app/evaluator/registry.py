"""内置算子注册表。

Worker 启动时用本表校验声明的 ``EVAL:<key>`` 能力：声明了没有实现的 key 直接启动失败，
而不是"能领任务但永远算不出结果"。这与后端的校验方向相反但目的相同——
两端都不接受"看起来注册了、其实没有实现"的算子。

需要外部依赖的算子（judge.rubric / test.command）用**工厂**登记：只有真正声明
该能力时才构造，构造失败（例如 CLI 不存在）让启动失败——而不是等到领到任务
才发现算不动。

V1 不加载远程代码，也不按 ``implementationRef`` 反射：注册表就是白名单本身。
"""

from __future__ import annotations

from typing import Any, Callable

from app.evaluator.base import Evaluator
from app.evaluator.judge import JudgeRubric, default_rubrics
from app.evaluator.llm import ClaudeCliJudge, JudgeClient, StubJudge
from app.evaluator.rules import AnswerExactMatch, CostBudget, OutputNonEmpty, TraceToolError
from app.evaluator.testing import TestCommand

#: 无外部依赖的算子：直接实例化，单元测试可单独取用。
BUILTIN_EVALUATORS: tuple[Evaluator, ...] = (
    OutputNonEmpty(),
    AnswerExactMatch(),
    TraceToolError(),
    CostBudget(),
)

#: 需要运行期资源的算子：key -> 工厂。
_FACTORIES: dict[str, Callable[[Any], Evaluator]] = {
    "test.command": lambda settings: TestCommand(),
    "judge.rubric": lambda settings: _build_judge(settings),
}


def _build_judge(settings: Any) -> Evaluator:
    client = _judge_client(settings)
    return JudgeRubric(client, rubrics=default_rubrics())


def _judge_client(settings: Any) -> JudgeClient:
    """按配置选 Judge 供应商。

    ``WORKER_JUDGE_PROVIDER=stub`` 时用确定性桩，供 CI 与单元测试验证协议、
    校验与重试逻辑——桩的结果带 ``provider=stub`` 且 ``costKnown=False``，
    不会被当成真实模型调用。
    """
    provider = str(getattr(settings, "judge_provider", "") or "").lower()
    if provider == "stub":
        inject = str(getattr(settings, "judge_stub_inject", "") or "ok")
        verdict = str(getattr(settings, "judge_stub_verdict", "") or "PASS")
        return StubJudge(verdict=verdict, inject=inject)
    executable = getattr(settings, "judge_cli", "claude") or "claude"
    return ClaudeCliJudge(executable)


def available_keys() -> list[str]:
    """全部内置 key（含需要工厂的），用于状态接口如实报告。"""
    return [evaluator.key for evaluator in BUILTIN_EVALUATORS] + list(_FACTORIES)


def has(key: str) -> bool:
    return key in _FACTORIES or any(e.key == key for e in BUILTIN_EVALUATORS)


def build(key: str, settings: Any = None) -> Evaluator:
    """按 key 构造算子；未知 key 抛 :class:`RuntimeError`（启动失败，不是运行期跳过）。"""
    factory = _FACTORIES.get(key)
    if factory is not None:
        return factory(settings)
    for evaluator in BUILTIN_EVALUATORS:
        if evaluator.key == key:
            return evaluator
    raise RuntimeError(f"没有实现算子 {key}；可用算子: {', '.join(sorted(available_keys()))}")


def resolve(evaluator_keys: list[str], settings: Any = None) -> dict[str, Evaluator]:
    """把能力声明解析成实现；任何未实现的 key 都让启动失败。"""
    resolved: dict[str, Evaluator] = {}
    for key in evaluator_keys:
        resolved[key] = build(key, settings)
    return resolved


def get(key: str) -> Evaluator | None:
    """取一个**无外部依赖**的算子实例。

    需要运行期资源（CLI）的算子不在这里返回——调用方应走 :func:`build`，
    否则会拿到一个"看起来能跑、实际连供应商都没接上"的实例。
    """
    for evaluator in BUILTIN_EVALUATORS:
        if evaluator.key == key:
            return evaluator
    return None