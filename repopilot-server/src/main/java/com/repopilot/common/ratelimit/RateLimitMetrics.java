package com.repopilot.common.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 限流指标。
 *
 * <p>只增加三类计数，不新建监控体系（项目已用 Actuator + Micrometer）：
 *
 * <ul>
 *   <li>{@code outcome=allowed} —— 放行；长期为 0 说明阈值为 0 或策略没生效；</li>
 *   <li>{@code outcome=denied} —— 超过限额（429）；突增即"业务被打满"或"阈值过低"；</li>
 *   <li>{@code outcome=unavailable} —— Redis 故障导致的降级（放行或拒绝）。</li>
 * </ul>
 *
 * <p>{@code unavailable} 必须独立成一条：把它混进 {@code denied}，
 * 会让"Redis 挂了导致所有请求被拒"看起来像"客户端太吵"，
 * 而这两件事的处置完全不同（修 Redis vs 放宽阈值）。
 *
 * <p>标签只有 {@code policy} 与 {@code outcome} 两个，且都是低基数枚举值。
 * 不把 subject（IP、模型名）做成标签：那会让时间序列数量随客户端数量增长。
 */
@Component
public class RateLimitMetrics {

    public static final String METRIC = "repopilot.ratelimit.requests";

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    public RateLimitMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordAllowed(String policy) {
        counter(policy, "allowed").increment();
    }

    public void recordDenied(String policy) {
        counter(policy, "denied").increment();
    }

    public void recordUnavailable(String policy) {
        counter(policy, "unavailable").increment();
    }

    /** 测试与运维排查用：按 policy/outcome 读取当前累计值。 */
    public double count(String policy, String outcome) {
        Counter counter = counters.get(key(policy, outcome));
        return counter == null ? 0.0 : counter.count();
    }

    private Counter counter(String policy, String outcome) {
        return counters.computeIfAbsent(key(policy, outcome), ignored -> Counter.builder(METRIC)
                .description("限流判定结果（allowed/denied/unavailable）")
                .tag("policy", policy)
                .tag("outcome", outcome)
                .register(registry));
    }

    private String key(String policy, String outcome) {
        return policy + '|' + outcome;
    }
}
