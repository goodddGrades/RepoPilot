package com.repopilot.common.ratelimit;

/**
 * 一次限流判定的结果。
 *
 * <p>四个字段都是"调用方需要知道的最小事实"，不做过度封装：
 * 是否放行、还剩多少、以及被拒时多久后可以重试。剩余量只用于展示与观测，
 * <b>决策已经在 Redis 侧完成</b>——不要用这个值在应用层再做一次判断，
 * 那等于把原子语义拆回两段。
 *
 * @param allowed          是否放行
 * @param remaining        扣减后剩余令牌（向下取整，可能为 0）
 * @param capacity         桶容量
 * @param retryAfterMillis 被拒时"多久后够一次消耗"，放行时为 0
 * @param policy           策略名（用于日志、指标与错误信息）
 * @param subject          限流主体（已脱敏的标识，用于日志排查；不含凭据）
 */
public record RateLimitDecision(
        boolean allowed,
        long remaining,
        long capacity,
        long retryAfterMillis,
        String policy,
        String subject
) {

    /**
     * 被拒时给客户端的等待秒数。
     *
     * <p>向上取整并至少为 1：返回 0 会让客户端立刻重试，
     * 而此刻额度必然还不够，等于把一次 429 变成一串 429。
     * 毫秒值异常（负）时同样退回 1，不返回负数让客户端无从解析。
     */
    public long retryAfterSeconds() {
        if (retryAfterMillis <= 0) {
            return 1L;
        }
        return Math.max(1L, (retryAfterMillis + 999L) / 1000L);
    }
}
