"""Judge 调用前的分布式限流。

**接入位置的选择理由**：本仓库唯一可观测的模型调用边界是
:meth:`JudgeClient.complete`。一次 ``complete`` 对应一次
``claude -p`` 进程调用——CLI 内部是否又发了几次 HTTP、是否自带重试，
RepoPilot 看不到，也不该假装看得到。因此这里限制的是
**"Worker 发起的模型调用次数"**，不是"上游 HTTP 请求数"。

如果将来换成 SDK/HTTP 直连（``HttpJsonJudge``），边界不变：
仍然是每次 ``complete`` 消耗一个令牌，因为那时一次 ``complete`` 就是一次
``/v1/chat/completions`` 请求。

被限流时抛 ``JUDGE_RATE_LIMITED``（``retryable=True``），沿用项目已有的
"技术错误 → 任务可重试"语义：**不会**被写成 FAIL 或任何分数。
"""

from __future__ import annotations

import logging
from typing import Any

from app.evaluator.llm import JudgeCallError, JudgeClient, JudgeRequest, JudgeResponse
from app.ratelimit.bucket import RateLimitUnavailable, RedisTokenBucket
from app.ratelimit.metrics import METRICS
from app.ratelimit.policy import RateLimitPolicy

log = logging.getLogger("app.ratelimit.judge")


class RateLimitedJudgeClient:
    """在真正调用模型之前申请一个令牌。

    实现 ``JudgeClient`` 协议（``name`` / ``complete`` / ``describe``），
    因此可以原样塞进 ``JudgeRubric``，算子本身不需要知道限流的存在。
    """

    def __init__(self, inner: JudgeClient, limiter: RedisTokenBucket,
                 policy: RateLimitPolicy, *, dimension: str = "PROVIDER_MODEL") -> None:
        upper = (dimension or "PROVIDER_MODEL").upper()
        if upper not in ("PROVIDER", "MODEL", "PROVIDER_MODEL"):
            raise ValueError(
                f"judge 限流维度必须是 PROVIDER / MODEL / PROVIDER_MODEL，实际 {dimension!r}")
        self._inner = inner
        self._limiter = limiter
        self._policy = policy
        self._dimension = upper

    # ------------------------------------------------------------------ 协议

    @property
    def name(self) -> str:
        """如实透传供应商名：状态接口与结果里的 provider 不能被包装层改写。"""
        return self._inner.name

    @property
    def policy(self) -> RateLimitPolicy:
        return self._policy

    @property
    def dimension(self) -> str:
        return self._dimension

    def complete(self, request: JudgeRequest) -> JudgeResponse:
        subject = self._subject(request)
        try:
            decision = self._limiter.acquire(self._policy, subject)
        except RateLimitUnavailable as exc:
            METRICS.record_unavailable(self._policy.name)
            if self._policy.fail_closed:
                # 保护上游配额的场景：Redis 不可用时继续放行会直接打满额度，
                # 那种损失不可逆。宁可让本次调用失败并可重试。
                log.error("限流依赖不可用，策略 %s 为 FAIL_CLOSED，拒绝本次模型调用: %s",
                          self._policy.name, exc)
                raise JudgeCallError(
                    "RATE_LIMIT_UNAVAILABLE",
                    f"限流依赖不可用，本次模型调用未发起（策略 {self._policy.name}）",
                    retryable=True, retry_after_seconds=1.0) from exc
            log.warning("限流依赖不可用，策略 %s 配置为 FAIL_OPEN，本次放行: %s",
                        self._policy.name, exc)
            return self._inner.complete(request)

        if not decision.allowed:
            METRICS.record_denied(self._policy.name)
            # 抛可重试的技术错误：算子会把它转成 EvaluationDependencyError，
            # 最终记成"技术失败可重试"，绝不会变成 FAIL 或 0 分。
            raise JudgeCallError(
                "JUDGE_RATE_LIMITED",
                f"本地令牌桶拒绝（策略 {self._policy.name}，主体 {subject}），"
                f"约 {decision.retry_after_seconds:.1f}s 后可用",
                retryable=True, retry_after_seconds=decision.retry_after_seconds)

        METRICS.record_allowed(self._policy.name)
        return self._inner.complete(request)

    def describe(self) -> dict[str, Any]:
        # 透传内层描述，再附加限流事实：结果里能看出"这次调用受哪条策略约束"。
        detail = dict(self._inner.describe())
        detail["rateLimit"] = {
            **self._policy.as_dict(),
            "dimension": self._dimension,
            "distributed": True,
        }
        return detail

    # ------------------------------------------------------------------ 主体

    def _subject(self, request: JudgeRequest) -> str:
        """限流主体。

        ``PROVIDER_MODEL``（默认）把"同一模型的额度"在**所有 Worker 实例间**共享，
        同时让不同模型互不挤占。若上游配额按账号（而非模型）计算，
        应改成 ``PROVIDER``，此时同一提供方的所有模型共用一个桶。

        刻意**不**把 worker_id 放进主体：那会让每个 Worker 各自限流，
        总请求量仍然是 N 倍——正是本任务要消除的问题。
        """
        provider = (self.name or "unknown").strip() or "unknown"
        model = (getattr(request, "model", "") or "unknown").strip() or "unknown"
        if self._dimension == "PROVIDER":
            return f"provider:{provider}"
        if self._dimension == "MODEL":
            return f"model:{model}"
        return f"provider:{provider}/model:{model}"
