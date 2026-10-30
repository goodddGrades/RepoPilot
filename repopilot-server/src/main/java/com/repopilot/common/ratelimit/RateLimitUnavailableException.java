package com.repopilot.common.ratelimit;

/**
 * 限流依赖（Redis）不可用。
 *
 * <p>与 {@link RateLimitedException} 严格分开：这不是"你请求太快"，
 * 而是"限流系统本身故障"。合成同一个 429 会让运维无法区分
 * "业务被打满"与"Redis 挂了"，也误导客户端用更长的退避去重试一个永远不会好的配额。
 *
 * <p>是否把这个异常抛给调用方，由策略的 {@link RateLimitPolicy.FailureMode} 决定：
 * {@code FAIL_OPEN} 时只记录指标与日志后放行；{@code FAIL_CLOSED} 时转成 503。
 */
public class RateLimitUnavailableException extends RuntimeException {

    private final String policy;

    public RateLimitUnavailableException(String policy, Throwable cause) {
        super("限流依赖不可用（策略 " + policy + "）: " + rootMessage(cause), cause);
        this.policy = policy;
    }

    public String policy() {
        return policy;
    }

    private static String rootMessage(Throwable cause) {
        Throwable current = cause;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }
}
