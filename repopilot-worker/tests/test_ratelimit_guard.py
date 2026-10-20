"""Judge 限流装饰器：放行 / 拒绝 / 依赖故障三条路径。

这一层是"限流是否真的接在模型调用前面"的证据：所有断言都围绕
"内层 client 有没有被调用"，而不是"函数返回了什么"。
"""

from __future__ import annotations

import pytest

from app.ratelimit.bucket import RateLimitUnavailable
from app.ratelimit.judge_guard import RateLimitedJudgeClient
from app.ratelimit.metrics import METRICS, RateLimitMetrics
from app.ratelimit.policy import RateLimitDecision, RateLimitPolicy


class RecordingJudge:
    """可观测的 Judge 替身：只记调用次数，不做任何模型调用。"""

    name = "claude-cli"

    def __init__(self) -> None:
        self.calls: list[object] = []

    def complete(self, request):  # noqa: ANN001, ANN201 - 测试替身保持最小签名
        self.calls.append(request)
        raise AssertionError("内层 client 不该被调用到 parse 阶段")

    def describe(self) -> dict[str, object]:
        return {"provider": self.name, "verified": True}


class SimpleJudge(RecordingJudge):
    """正常返回的替身。"""

    def complete(self, request):  # noqa: ANN001, ANN201
        self.calls.append(request)
        from app.evaluator.llm import JudgeResponse

        return JudgeResponse(text="{}", usage={"stub": 1})


class FakeLimiter:
    def __init__(self, *, allowed: bool = True, retry_after_ms: int = 0,
                 unavailable: Exception | None = None) -> None:
        self.allowed = allowed
        self.retry_after_ms = retry_after_ms
        self.unavailable = unavailable
        self.subjects: list[str] = []

    def acquire(self, policy: RateLimitPolicy, subject: str, cost: int | None = None):  # noqa: ANN201
        self.subjects.append(subject)
        if self.unavailable is not None:
            raise self.unavailable
        return RateLimitDecision(self.allowed, 0 if not self.allowed else 5, 10,
                                 self.retry_after_ms, policy.name, subject)


def request(model: str = "claude-3-5-sonnet"):
    from app.evaluator.llm import JudgeRequest

    return JudgeRequest(system_prompt="s", user_prompt="u", json_schema={}, model=model,
                        temperature=0.0, max_tokens=16, timeout_seconds=5)


def guard(limiter: FakeLimiter, inner=None, *, dimension: str = "PROVIDER_MODEL",
          failure_mode: str = "FAIL_CLOSED") -> RateLimitedJudgeClient:  # noqa: ANN001
    policy = RateLimitPolicy("judge-model", 10, 0.2, 1, 600, failure_mode)
    return RateLimitedJudgeClient(inner or SimpleJudge(), limiter, policy, dimension=dimension)


@pytest.fixture(autouse=True)
def fresh_metrics() -> None:
    METRICS.reset()


def test_allowed_call_delegates_and_counts() -> None:
    inner = SimpleJudge()
    client = guard(FakeLimiter(allowed=True), inner)

    response = client.complete(request())

    assert response.usage == {"stub": 1}
    assert len(inner.calls) == 1
    assert METRICS.count("judge-model", "allowed") == 1
    assert METRICS.count("judge-model", "denied") == 0


def test_denied_call_never_reaches_the_model() -> None:
    inner = RecordingJudge()
    client = guard(FakeLimiter(allowed=False, retry_after_ms=2000), inner)

    with pytest.raises(Exception) as excinfo:
        client.complete(request())

    error = excinfo.value
    from app.evaluator.llm import JudgeCallError

    assert isinstance(error, JudgeCallError)
    assert error.error_code == "JUDGE_RATE_LIMITED"
    assert error.retryable is True, "限流必须可重试，不能记成质量失败"
    assert error.retry_after_seconds == pytest.approx(2.0)
    assert inner.calls == [], "被拒绝时绝不能发起模型调用"
    assert METRICS.count("judge-model", "denied") == 1


def test_unavailable_redis_fails_closed_by_default() -> None:
    inner = RecordingJudge()
    client = guard(FakeLimiter(unavailable=RateLimitUnavailable("judge-model", OSError("refused"))),
                   inner, failure_mode="FAIL_CLOSED")

    with pytest.raises(Exception) as excinfo:
        client.complete(request())

    from app.evaluator.llm import JudgeCallError

    error = excinfo.value
    assert isinstance(error, JudgeCallError)
    assert error.error_code == "RATE_LIMIT_UNAVAILABLE"
    assert error.retryable is True
    assert inner.calls == [], "失败关闭时不能绕过配额直接调用模型"
    assert METRICS.count("judge-model", "unavailable") == 1


def test_unavailable_redis_can_be_configured_to_fail_open() -> None:
    inner = SimpleJudge()
    client = guard(FakeLimiter(unavailable=RateLimitUnavailable("judge-model", OSError("refused"))),
                   inner, failure_mode="FAIL_OPEN")

    client.complete(request())

    assert len(inner.calls) == 1
    assert METRICS.count("judge-model", "unavailable") == 1
    assert METRICS.count("judge-model", "allowed") == 0, "降级放行不应算作正常放行"


@pytest.mark.parametrize(
    ("dimension", "expected"),
    [
        ("PROVIDER", "provider:claude-cli"),
        ("MODEL", "model:claude-3-5-sonnet"),
        ("PROVIDER_MODEL", "provider:claude-cli/model:claude-3-5-sonnet"),
    ],
)
def test_subject_dimension_is_configurable(dimension: str, expected: str) -> None:
    limiter = FakeLimiter()
    guard(limiter, dimension=dimension).complete(request())

    assert limiter.subjects == [expected]


def test_subject_never_contains_worker_identity() -> None:
    # 把 worker_id 放进主体等于每个 Worker 各自限流，总量仍是 N 倍——正是要修的问题。
    limiter = FakeLimiter()
    guard(limiter).complete(request())

    assert "worker" not in limiter.subjects[0].lower()


def test_describe_exposes_rate_limit_facts() -> None:
    detail = guard(FakeLimiter()).describe()

    assert detail["provider"] == "claude-cli"
    assert detail["rateLimit"]["name"] == "judge-model"
    assert detail["rateLimit"]["distributed"] is True
    assert detail["rateLimit"]["failureMode"] == "FAIL_CLOSED"


def test_invalid_dimension_is_rejected_at_construction() -> None:
    with pytest.raises(ValueError, match="维度"):
        guard(FakeLimiter(), dimension="TENANT")


def test_provider_name_is_passed_through_unchanged() -> None:
    # 包装层不能改写 provider：结果里的"谁给的判断"必须是真实供应商。
    assert guard(FakeLimiter()).name == "claude-cli"


def test_metrics_snapshot_is_grouped_by_policy_and_outcome() -> None:
    metrics = RateLimitMetrics()
    metrics.record_allowed("judge-model")
    metrics.record_denied("judge-model")

    assert metrics.snapshot() == {"judge-model": {"allowed": 1, "denied": 1, "unavailable": 0}}
