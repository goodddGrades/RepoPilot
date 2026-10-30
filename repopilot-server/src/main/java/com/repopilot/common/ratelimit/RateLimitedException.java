package com.repopilot.common.ratelimit;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;

/**
 * 请求超过限额。
 *
 * <p>继承 {@link BusinessException} 以复用统一响应结构（{@code code=RATE_LIMITED}），
 * 额外携带 {@link RateLimitDecision}：{@code Retry-After} 必须来自真实计算，
 * 不能写死一个"看起来合理"的常数——客户端按错误的时间重试，
 * 要么继续撞墙，要么白等。
 *
 * <p>错误码固定 {@link ErrorCode#RATE_LIMITED}（HTTP 429），
 * 与"Redis 不可用"（503）严格区分：前者是<b>业务上就该慢下来</b>，
 * 后者是<b>基础设施故障</b>，两者的处置方式完全不同。
 */
public class RateLimitedException extends BusinessException {

    private final RateLimitDecision decision;

    public RateLimitedException(RateLimitDecision decision) {
        super(ErrorCode.RATE_LIMITED, message(decision));
        this.decision = decision;
    }

    public RateLimitDecision decision() {
        return decision;
    }

    public long retryAfterSeconds() {
        return decision.retryAfterSeconds();
    }

    private static String message(RateLimitDecision decision) {
        return "请求过于频繁（策略 %s，容量 %d，剩余 %d），请在 %d 秒后重试"
                .formatted(decision.policy(), decision.capacity(), decision.remaining(),
                        decision.retryAfterSeconds());
    }
}
