"""限流策略与判定结果：参数校验必须与 Java 侧一致。

同一套阈值在两种语言里得到不同判定，会造成"Java 放行、Python 拒绝"这种
无法解释的现象，因此这里的边界条件与 `RateLimitPolicy.java` 逐条对齐。
"""

from __future__ import annotations

import pytest

from app.ratelimit.policy import (
    RateLimitDecision,
    RateLimitPolicy,
    policy_from_settings,
)


def make_policy(**overrides) -> RateLimitPolicy:
    params = {
        "name": "judge-model",
        "capacity": 10,
        "refill_per_second": 0.2,
        "cost_per_request": 1,
        "ttl_seconds": 600,
        "failure_mode": "FAIL_CLOSED",
    }
    params.update(overrides)
    return RateLimitPolicy(**params)


@pytest.mark.parametrize("capacity", [0, -1])
def test_capacity_must_be_positive(capacity: int) -> None:
    with pytest.raises(ValueError, match="capacity"):
        make_policy(capacity=capacity)


@pytest.mark.parametrize("refill", [0.0, -0.5])
def test_refill_must_be_positive(refill: float) -> None:
    with pytest.raises(ValueError, match="refill_per_second"):
        make_policy(refill_per_second=refill)


def test_cost_must_not_exceed_capacity() -> None:
    # cost > capacity 时桶永远凑不够一次请求：所有调用恒被拒且没有报错。
    with pytest.raises(ValueError, match="不能大于 capacity"):
        make_policy(capacity=5, cost_per_request=6)


@pytest.mark.parametrize("ttl", [0, -1.0])
def test_ttl_must_be_positive(ttl: float) -> None:
    with pytest.raises(ValueError, match="ttl_seconds"):
        make_policy(ttl_seconds=ttl)


def test_unknown_failure_mode_is_rejected() -> None:
    with pytest.raises(ValueError, match="failure_mode"):
        make_policy(failure_mode="FAIL_SOMETIMES")


def test_blank_name_is_rejected() -> None:
    with pytest.raises(ValueError, match="策略名"):
        make_policy(name="  ")


def test_ttl_covering_full_refill_is_detectable() -> None:
    # 10 个令牌、0.2/s → 补满需 50s
    assert make_policy(capacity=10, refill_per_second=0.2, ttl_seconds=50).ttl_covers_full_refill()
    assert not make_policy(capacity=10, refill_per_second=0.2, ttl_seconds=10).ttl_covers_full_refill()


def test_fail_closed_is_the_default_for_judge() -> None:
    assert make_policy().fail_closed is True
    assert make_policy(failure_mode="FAIL_OPEN").fail_closed is False


def test_decision_retry_after_is_never_zero_or_negative() -> None:
    # 返回 0 会让调用方立刻重试，而此刻额度必然还不够——一次拒绝变成一串拒绝。
    assert RateLimitDecision(False, 0, 10, 0, "judge-model", "provider:x").retry_after_seconds == 0.001
    assert RateLimitDecision(False, 0, 10, -5, "judge-model", "provider:x").retry_after_seconds == 0.001
    assert RateLimitDecision(False, 0, 10, 1500, "judge-model", "provider:x").retry_after_seconds == 1.5


def test_policy_from_settings_reads_all_fields() -> None:
    class FakeSettings:
        judge_rate_limit_capacity = 7
        judge_rate_limit_refill_per_second = 0.5
        judge_rate_limit_cost = 2
        judge_rate_limit_ttl_seconds = 120
        rate_limit_failure_mode = "fail_open"  # 小写也要接受

    policy = policy_from_settings(FakeSettings())

    assert policy.name == "judge-model"
    assert (policy.capacity, policy.refill_per_second, policy.cost_per_request) == (7, 0.5, 2)
    assert policy.ttl_seconds == 120
    assert policy.failure_mode == "FAIL_OPEN"


def test_policy_from_settings_rejects_invalid_combination() -> None:
    class BadSettings:
        judge_rate_limit_capacity = 2
        judge_rate_limit_refill_per_second = 0.5
        judge_rate_limit_cost = 5
        judge_rate_limit_ttl_seconds = 120
        rate_limit_failure_mode = "FAIL_CLOSED"

    with pytest.raises(ValueError, match="不能大于 capacity"):
        policy_from_settings(BadSettings())
