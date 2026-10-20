package com.repopilot.common.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一条已经校验过的限流策略（运行期视图）。
 *
 * <p>与 {@link RateLimitProperties.Policy} 的区别：配置类是"可绑定的形状"，
 * 字段可为空、可缺省；这里是"可执行的值"，所有参数都已校验为正数、
 * 路径与方法都已归一化。构造即校验，非法配置在<b>启动时</b>失败，
 * 而不是等到第一个请求才发现除零或负数。
 *
 * <p>HTTP 方法用大写字符串而不是 {@code HttpMethod}：Spring 6 的
 * {@code HttpMethod} 是可扩展类型，{@code valueOf} 对未知方法的行为随版本变化
 * （返回 null 或构造临时实例），用它做白名单校验会"看起来校验了、实际没拦住"。
 *
 * @param name               策略名，进入 Redis key 与指标标签，必须稳定
 * @param capacity           桶容量（可承受的突发量）
 * @param refillPerSecond    令牌补充速率（个/秒），决定长期平均吞吐
 * @param costPerRequest     单次请求消耗的令牌数
 * @param ttl                空闲过期时间：桶在这么久没有写入后消失，下次从满桶开始
 * @param failureMode        Redis 不可用时的行为
 * @param paths              生效的路径模式（Ant 风格，由 Spring MVC 解析）
 * @param methods            生效的 HTTP 方法（大写）；只读请求不在其中
 */
public record RateLimitPolicy(
        String name,
        long capacity,
        double refillPerSecond,
        long costPerRequest,
        Duration ttl,
        FailureMode failureMode,
        List<String> paths,
        Set<String> methods
) {

    /** 允许出现在策略里的 HTTP 方法。用固定集合校验，不依赖框架的解析行为。 */
    private static final Set<String> KNOWN_METHODS =
            Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");

    /** Redis 不可用时的策略。 */
    public enum FailureMode {
        /**
         * 放行并记录降级。
         *
         * <p>用于<b>防滥用</b>型限流：限流本身不是鉴权，Redis 抖动时把整个写入入口
         * 全部 503，代价通常大于短时间失去节流。注意这<b>不是</b>退化成"每实例各自计数"，
         * 而是明确地不限流——后者会让人误以为仍有保护。
         */
        FAIL_OPEN,
        /**
         * 拒绝并返回 503。
         *
         * <p>用于<b>保护上游配额</b>型限流：Redis 不可用时继续放行会直接打满上游额度，
         * 那种损失不可逆，宁可让本次调用失败并可重试。
         */
        FAIL_CLOSED
    }

    public RateLimitPolicy {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("限流策略名不能为空");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("限流策略 " + name + " 的 capacity 必须大于 0");
        }
        if (!(refillPerSecond > 0)) {
            throw new IllegalArgumentException("限流策略 " + name + " 的 refill-tokens-per-second 必须大于 0");
        }
        if (costPerRequest <= 0) {
            throw new IllegalArgumentException("限流策略 " + name + " 的 cost-per-request 必须大于 0");
        }
        if (costPerRequest > capacity) {
            // 单次消耗超过容量时，桶永远凑不够一次请求：所有请求恒被拒。
            // 这几乎总是配置写错（例如把 cost 当成"每分钟次数"），启动即报错。
            throw new IllegalArgumentException(
                    "限流策略 " + name + " 的 cost-per-request(" + costPerRequest
                            + ") 不能大于 capacity(" + capacity + ")，否则永远无法放行");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("限流策略 " + name + " 的 ttl 必须大于 0");
        }
        if (paths == null || paths.isEmpty()) {
            throw new IllegalArgumentException("限流策略 " + name + " 必须声明至少一个 paths");
        }
        paths = List.copyOf(paths);
        Set<String> normalized = new java.util.LinkedHashSet<>();
        for (String method : (methods == null ? Set.<String>of() : methods)) {
            String upper = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
            if (upper.isEmpty()) {
                continue;
            }
            if (!KNOWN_METHODS.contains(upper)) {
                throw new IllegalArgumentException("限流策略 " + name + " 声明了未知 HTTP 方法: " + method);
            }
            normalized.add(upper);
        }
        methods = Set.copyOf(normalized);
    }

    /**
     * 空闲 TTL 是否足够让一个空桶补满。
     *
     * <p>如果 TTL 短于"从 0 补到 capacity"所需时间，桶会在还没补满时消失，
     * 下次从满桶开始——客户端只要停一会儿就能获得完整突发额度，
     * 相当于把容量放大成"每个 TTL 周期一次"。这里只做告警依据，
     * 不强制修正：确实有人需要短 TTL（例如按分钟重置的粗粒度配额）。
     */
    public boolean ttlCoversFullRefill() {
        double secondsToFull = capacity / refillPerSecond;
        return ttl.toMillis() >= Math.ceil(secondsToFull * 1000.0);
    }
}
