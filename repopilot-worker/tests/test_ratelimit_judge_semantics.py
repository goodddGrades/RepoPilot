"""限流与评测语义的边界：被限流不等于"模型判它不合格"。

这是本任务最容易出错、也最伤业务的一点：把 429 记成 FAIL，会让报告里出现
"某方案质量差"的结论，而真实原因是配额用尽。这里用真实算子链路证明：
限流 → 技术错误 → 可重试，**不产生任何 verdict / score**。
"""

from __future__ import annotations

from typing import Any

import pytest

from app.evaluator.base import EvaluationDependencyError
from app.evaluator.judge import JudgeRubric, default_rubrics
from app.evaluator.llm import JudgeRequest, JudgeResponse, StubJudge
from app.ratelimit.bucket import RateLimitUnavailable
from app.ratelimit.judge_guard import RateLimitedJudgeClient
from app.ratelimit.metrics import METRICS
from app.ratelimit.policy import RateLimitDecision, RateLimitPolicy

from tests.test_judge_evaluator import TRACE, config, make_input


class FixedLimiter:
    def __init__(self, *, allowed: bool, retry_after_ms: int = 3000,
                 unavailable: Exception | None = None) -> None:
        self.allowed = allowed
        self.retry_after_ms = retry_after_ms
        self.unavailable = unavailable
        self.calls = 0

    def acquire(self, policy: RateLimitPolicy, subject: str, cost: int | None = None):  # noqa: ANN201
        self.calls += 1
        if self.unavailable is not None:
            raise self.unavailable
        return RateLimitDecision(self.allowed, 0 if not self.allowed else 3, 10,
                                 self.retry_after_ms, policy.name, subject)


def guarded(limiter: FixedLimiter, inner: Any = None,
            *, failure_mode: str = "FAIL_CLOSED") -> JudgeRubric:
    policy = RateLimitPolicy("judge-model", 10, 0.2, 1, 600, failure_mode)
    client = RateLimitedJudgeClient(inner or StubJudge(), limiter, policy)
    return JudgeRubric(client, rubrics=default_rubrics())


@pytest.fixture(autouse=True)
def fresh_metrics() -> None:
    METRICS.reset()


def test_rate_limited_judge_is_retryable_technical_failure() -> None:
    limiter = FixedLimiter(allowed=False, retry_after_ms=3000)
    evaluator = guarded(limiter)

    with pytest.raises(EvaluationDependencyError) as excinfo:
        evaluator.evaluate(make_input(config()), TRACE)

    error = excinfo.value
    assert error.error_code == "JUDGE_RATE_LIMITED"
    assert error.retryable is True, "限流是可恢复的技术问题，不能标记为不可重试"
    # 关键：没有返回 EvaluationOutcome，就不存在 verdict / score 被写进结果的可能。
    assert METRICS.count("judge-model", "denied") == 1


def test_rate_limit_does_not_burn_fast_retries() -> None:
    # 算子内重试是 1s/3s，而配额恢复通常要几十秒：这三轮必然全败。
    # 带 retry_after 的失败应直接转任务级退避，只尝试一次。
    limiter = FixedLimiter(allowed=False, retry_after_ms=30000)
    evaluator = guarded(limiter)

    with pytest.raises(EvaluationDependencyError):
        evaluator.evaluate(make_input(config(maxAttempts=3)), TRACE)

    assert limiter.calls == 1, "应只尝试一次，把重试交给任务级退避"


def test_redis_outage_fails_closed_without_calling_model() -> None:
    limiter = FixedLimiter(allowed=True,
                           unavailable=RateLimitUnavailable("judge-model", OSError("refused")))
    inner = StubJudge()
    evaluator = guarded(limiter, inner, failure_mode="FAIL_CLOSED")

    with pytest.raises(EvaluationDependencyError) as excinfo:
        evaluator.evaluate(make_input(config()), TRACE)

    assert excinfo.value.error_code == "RATE_LIMIT_UNAVAILABLE"
    assert excinfo.value.retryable is True
    assert inner.calls == [], "失败关闭时不能绕过限流发起模型调用"


def test_redis_outage_fail_open_still_produces_a_verdict() -> None:
    limiter = FixedLimiter(allowed=True,
                           unavailable=RateLimitUnavailable("judge-model", OSError("refused")))
    evaluator = guarded(limiter, StubJudge(verdict="PASS", score=0.8), failure_mode="FAIL_OPEN")

    outcome = evaluator.evaluate(make_input(config()), TRACE)

    assert outcome.verdict == "PASS"
    assert outcome.score == pytest.approx(0.8)


def test_allowed_call_reaches_the_model_exactly_once() -> None:
    class CountingJudge(StubJudge):
        def __init__(self) -> None:
            super().__init__(verdict="PASS", score=1.0)
            self.invocations = 0

        def complete(self, request: JudgeRequest) -> JudgeResponse:
            self.invocations += 1
            return super().complete(request)

    inner = CountingJudge()
    evaluator = guarded(FixedLimiter(allowed=True), inner)

    evaluator.evaluate(make_input(config()), TRACE)

    assert inner.invocations == 1
    assert METRICS.count("judge-model", "allowed") == 1
