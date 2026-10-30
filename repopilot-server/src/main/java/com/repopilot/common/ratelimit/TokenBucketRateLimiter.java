package com.repopilot.common.ratelimit;

/**
 * 令牌桶限流器。
 *
 * <p>只有一个方法：按策略与主体申请一次配额。刻意不提供
 * "查询剩余额度"这类只读方法——那个值在并发下立刻过期，
 * 用它做业务判断必然出错；真正需要的是"这一次能不能过"。
 */
public interface TokenBucketRateLimiter {

    /**
     * 申请一次配额。
     *
     * @param policy  已校验的策略
     * @param subject 限流主体（调用方保证已脱敏，不含凭据）
     * @return 判定结果；被拒时 {@code allowed=false} 且带等待时间
     * @throws RateLimitUnavailableException 限流依赖不可用（由调用方按策略决定放行或拒绝）
     */
    RateLimitDecision acquire(RateLimitPolicy policy, String subject);
}
