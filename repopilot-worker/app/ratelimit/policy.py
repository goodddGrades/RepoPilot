"""限流策略与判定结果。

参数校验与 Java 侧 ``RateLimitPolicy`` 保持一致：同一套阈值在两个语言里
必须得到同样的判定，否则"Java 放行、Python 拒绝"会变成无法解释的现象。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

FAILURE_MODES = ("FAIL_CLOSED", "FAIL_OPEN")
DIMENSIONS = ("PROVIDER", "MODEL", "PROVIDER_MODEL")


@dataclass(frozen=True)
class RateLimitPolicy:
    """一条已校验的令牌桶策略。

    ``failure_mode`` 的默认值由调用方决定，不在本类里硬编码：
    Judge 侧用 ``FAIL_CLOSED``（保护上游配额），其它场景可用 ``FAIL_OPEN``。
    """

    name: str
    capacity: int
    refill_per_second: float
    cost_per_request: int
    ttl_seconds: float
    failure_mode: str = "FAIL_CLOSED"

    def __post_init__(self) -> None:
        if not self.name or not self.name.strip():
            raise ValueError("限流策略名不能为空")
        if self.capacity <= 0:
            raise ValueError(f"限流策略 {self.name} 的 capacity 必须大于 0")
        if not self.refill_per_second > 0:
            raise ValueError(f"限流策略 {self.name} 的 refill_per_second 必须大于 0")
        if self.cost_per_request <= 0:
            raise ValueError(f"限流策略 {self.name} 的 cost_per_request 必须大于 0")
        if self.cost_per_request > self.capacity:
            # cost > capacity 时桶永远凑不够一次请求：所有调用恒被拒，且没有任何报错。
            raise ValueError(
                f"限流策略 {self.name} 的 cost_per_request({self.cost_per_request}) "
                f"不能大于 capacity({self.capacity})，否则永远无法放行")
        if not self.ttl_seconds > 0:
            raise ValueError(f"限流策略 {self.name} 的 ttl_seconds 必须大于 0")
        if self.failure_mode not in FAILURE_MODES:
            raise ValueError(
                f"限流策略 {self.name} 的 failure_mode 必须是 {FAILURE_MODES} 之一，"
                f"实际 {self.failure_mode!r}")

    @property
    def fail_closed(self) -> bool:
        return self.failure_mode == "FAIL_CLOSED"

    def ttl_covers_full_refill(self) -> bool:
        """空闲 TTL 是否够把空桶补满；不够时日志会点名（会周期性放大额度）。"""
        return self.ttl_seconds * 1000 >= (self.capacity / self.refill_per_second) * 1000

    def as_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "capacity": self.capacity,
            "refillPerSecond": self.refill_per_second,
            "costPerRequest": self.cost_per_request,
            "ttlSeconds": self.ttl_seconds,
            "failureMode": self.failure_mode,
        }


@dataclass(frozen=True)
class RateLimitDecision:
    """一次令牌桶判定的结果。"""

    allowed: bool
    remaining: int
    capacity: int
    retry_after_ms: int
    policy: str
    subject: str

    @property
    def retry_after_seconds(self) -> float:
        """被拒时建议的等待秒数；至少 1 毫秒，绝不返回 0 或负数。

        返回 0 会让调用方立刻重试，而此刻额度必然还不够——一次拒绝变成一串拒绝。
        """
        return max(0.001, self.retry_after_ms / 1000.0)

    def as_dict(self) -> dict[str, Any]:
        return {
            "allowed": self.allowed,
            "remaining": self.remaining,
            "capacity": self.capacity,
            "retryAfterMs": self.retry_after_ms,
            "policy": self.policy,
            # subject 是 "provider:xxx/model:yyy" 这类维度标识，不含任何凭据。
            "subject": self.subject,
        }


def policy_from_settings(settings: Mapping[str, Any] | Any) -> RateLimitPolicy:
    """从配置构造 Judge 侧的限流策略。

    用 ``getattr`` 而不是下标：``Settings`` 是 pydantic 模型，测试里也可能传
    一个简单的命名空间对象。缺字段直接暴露为 AttributeError/默认值，
    **不静默兜底成某个"看起来合理"的阈值**——那会让配置失效而无人察觉。
    """
    return RateLimitPolicy(
        name="judge-model",
        capacity=int(getattr(settings, "judge_rate_limit_capacity", 10)),
        refill_per_second=float(getattr(settings, "judge_rate_limit_refill_per_second", 0.2)),
        cost_per_request=int(getattr(settings, "judge_rate_limit_cost", 1)),
        ttl_seconds=float(getattr(settings, "judge_rate_limit_ttl_seconds", 600)),
        failure_mode=str(getattr(settings, "rate_limit_failure_mode", "FAIL_CLOSED")).upper(),
    )
