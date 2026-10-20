"""限流指标（进程内计数）。

只记三个数：放行、拒绝、依赖故障。不引入 Prometheus 客户端——
本仓库没有指标服务器，凭空加一套 exporter 属于"重复建设监控系统"。
状态接口 ``/worker/status`` 会把这个快照原样暴露，便于演示与排障。

为什么 ``unavailable`` 必须独立：把它并进 ``denied`` 会让
"Redis 挂了导致模型调用被拒"看起来像"上游太吵"，两者的处置完全不同。
"""

from __future__ import annotations

import threading
from collections import defaultdict
from typing import Any

OUTCOMES = ("allowed", "denied", "unavailable")


class RateLimitMetrics:
    """线程安全的计数器。

    Worker 只有一个执行线程，但消费者回调、状态接口和测试会并发读，
    所以仍然加锁：读到的快照必须是某个一致时刻的值，不能是"读到一半被改"。
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._counts: dict[tuple[str, str], int] = defaultdict(int)

    def record(self, policy: str, outcome: str) -> None:
        if outcome not in OUTCOMES:
            raise ValueError(f"未知的限流结果: {outcome!r}，应为 {OUTCOMES}")
        with self._lock:
            self._counts[(policy, outcome)] += 1

    def record_allowed(self, policy: str) -> None:
        self.record(policy, "allowed")

    def record_denied(self, policy: str) -> None:
        self.record(policy, "denied")

    def record_unavailable(self, policy: str) -> None:
        self.record(policy, "unavailable")

    def count(self, policy: str, outcome: str) -> int:
        with self._lock:
            return self._counts.get((policy, outcome), 0)

    def snapshot(self) -> dict[str, dict[str, int]]:
        """按策略分组、补齐三个结果，便于稳定断言与展示。"""
        with self._lock:
            policies = sorted({policy for policy, _ in self._counts})
            return {
                policy: {outcome: self._counts.get((policy, outcome), 0) for outcome in OUTCOMES}
                for policy in policies
            }

    def reset(self) -> None:
        with self._lock:
            self._counts.clear()

    def as_dict(self) -> dict[str, Any]:
        return self.snapshot()


#: 进程级单例：状态接口在没有 runtime（例如仅构造 app）时也能读到真实计数。
METRICS = RateLimitMetrics()
