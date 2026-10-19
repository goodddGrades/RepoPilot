"""内置算子注册表。

Worker 启动时用本表校验声明的 ``EVAL:<key>`` 能力：声明了没有实现的 key 直接启动失败，
而不是"能领任务但永远算不出结果"。这与后端的校验方向相反但目的相同——
两端都不接受"看起来注册了、其实没有实现"的算子。

V1 不加载远程代码，也不按 ``implementationRef`` 反射：注册表就是白名单本身。
"""

from __future__ import annotations

from app.evaluator.base import Evaluator
from app.evaluator.rules import AnswerExactMatch, CostBudget, OutputNonEmpty, TraceToolError

BUILTIN_EVALUATORS: tuple[Evaluator, ...] = (
    OutputNonEmpty(),
    AnswerExactMatch(),
    TraceToolError(),
    CostBudget(),
)

_BY_KEY: dict[str, Evaluator] = {evaluator.key: evaluator for evaluator in BUILTIN_EVALUATORS}


def builtin_keys() -> list[str]:
    return list(_BY_KEY)


def resolve(evaluator_keys: list[str]) -> dict[str, Evaluator]:
    """把能力声明解析成实现；任何未实现的 key 都让启动失败。"""
    resolved: dict[str, Evaluator] = {}
    for key in evaluator_keys:
        evaluator = _BY_KEY.get(key)
        if evaluator is None:
            raise RuntimeError(
                f"没有实现算子 {key}；可用算子: {', '.join(sorted(_BY_KEY))}")
        resolved[key] = evaluator
    return resolved


def get(key: str) -> Evaluator | None:
    return _BY_KEY.get(key)
