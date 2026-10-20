package com.repopilot.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 限流配置（{@code repopilot.rate-limit}）。
 *
 * <p>阈值一律来自配置，业务代码里不出现任何数字：改阈值不该需要改代码、
 * 重新编译和发版。默认值是<b>开发环境的保守起点</b>，不是生产推荐值——
 * 生产该按实际上游配额与实例数反推，见 {@code docs/modules/rate-limiting.md}。
 *
 * <p>刻意不提供"全局默认策略"这种东西：每条限流规则必须显式声明自己的
 * 容量、速率与路径。默认值会让"忘了配"变成一个看起来在保护、实际按别人
 * 的额度跑的假象。
 */
@ConfigurationProperties(prefix = "repopilot.rate-limit")
public class RateLimitProperties {

    /** 总开关。关闭时不注册任何拦截器，也不连 Redis。 */
    private boolean enabled = true;

    /**
     * Key 前缀。进入 Redis 的第一段命名空间，便于与其它业务 key 区分、
     * 也便于用 {@code SCAN repopilot:ratelimit:*} 单独清理。
     */
    private String keyPrefix = "repopilot:ratelimit";

    /**
     * 环境隔离段（dev / it / prod…）。
     *
     * <p>必须显式配置：同一套 Redis 被多环境共用时，"测试把 dev 的额度吃光"
     * 是最难排查的一类问题——两边看到的都是 429，但谁都算不出为什么。
     */
    private String environment = "dev";

    /**
     * 是否信任 {@code X-Forwarded-For}。
     *
     * <p>默认 false。只有确实位于可信反向代理之后才可打开：该请求头由客户端
     * 自由填写，直接信任等于允许调用方伪造主体、绕过限流。
     */
    private boolean trustForwardedFor = false;

    /** 策略名 → 策略参数。 */
    private Map<String, Policy> policies = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public boolean isTrustForwardedFor() {
        return trustForwardedFor;
    }

    public void setTrustForwardedFor(boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    public Map<String, Policy> getPolicies() {
        return policies;
    }

    public void setPolicies(Map<String, Policy> policies) {
        this.policies = policies;
    }

    /**
     * 把配置转换成已校验的运行期策略。
     *
     * <p>启动时调用一次。任何非法值都直接抛异常让进程起不来——
     * "限流配错了但服务还在跑"比"限流没开"更危险，因为没人会去查。
     */
    public List<RateLimitPolicy> resolvedPolicies() {
        List<RateLimitPolicy> resolved = new ArrayList<>();
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalStateException("repopilot.rate-limit.key-prefix 不能为空");
        }
        if (environment == null || environment.isBlank()) {
            throw new IllegalStateException("repopilot.rate-limit.environment 不能为空");
        }
        policies.forEach((name, policy) -> resolved.add(policy.toPolicy(name)));
        if (resolved.isEmpty()) {
            throw new IllegalStateException(
                    "repopilot.rate-limit.enabled=true 但没有任何策略；"
                            + "请配置至少一个 repopilot.rate-limit.policies.<name>，或显式关闭限流");
        }
        return List.copyOf(resolved);
    }

    /** 单个策略的绑定形状。字段可缺省，转换时统一校验。 */
    public static class Policy {

        /** 桶容量：允许的瞬时突发量。 */
        private long capacity = 30;

        /** 补充速率（令牌/秒）：长期平均吞吐上限 = refill × 60 次/分钟。 */
        private double refillTokensPerSecond = 0.5;

        /** 单次请求消耗。导入这类"一次干很多活"的入口应大于 1。 */
        private long costPerRequest = 1;

        /** 空闲过期：超过这个时间没有写入，桶被回收，下次从满桶开始。 */
        private Duration ttl = Duration.ofMinutes(10);

        /** Redis 不可用时的行为，默认放行（API 入口以可用性优先）。 */
        private FailureMode failureMode = FailureMode.FAIL_OPEN;

        /** 生效路径（Ant 风格）。 */
        private List<String> paths = new ArrayList<>();

        /** 生效方法，默认只拦写操作。 */
        private List<String> methods = new ArrayList<>(List.of("POST"));

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            this.capacity = capacity;
        }

        public double getRefillTokensPerSecond() {
            return refillTokensPerSecond;
        }

        public void setRefillTokensPerSecond(double refillTokensPerSecond) {
            this.refillTokensPerSecond = refillTokensPerSecond;
        }

        public long getCostPerRequest() {
            return costPerRequest;
        }

        public void setCostPerRequest(long costPerRequest) {
            this.costPerRequest = costPerRequest;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public FailureMode getFailureMode() {
            return failureMode;
        }

        public void setFailureMode(FailureMode failureMode) {
            this.failureMode = failureMode;
        }

        public List<String> getPaths() {
            return paths;
        }

        public void setPaths(List<String> paths) {
            this.paths = paths;
        }

        public List<String> getMethods() {
            return methods;
        }

        public void setMethods(List<String> methods) {
            this.methods = methods;
        }

        RateLimitPolicy toPolicy(String name) {
            List<String> normalizedPaths = paths.stream()
                    .filter(path -> path != null && !path.isBlank())
                    .map(String::trim)
                    .toList();
            // 显式写了 `failure-mode:` 却没值时绑定结果是 null：按 API 入口的
            // 默认（放行）处理，而不是抛 NPE 让整个应用起不来。
            FailureMode mode = failureMode == null ? FailureMode.FAIL_OPEN : failureMode;
            // 方法与路径的归一化、未知方法的拒绝，统一在 RateLimitPolicy 构造里做：
            // 配置类只负责"把 yml 读成对象"，校验只有一处实现。
            Set<String> httpMethods = new LinkedHashSet<>(methods == null ? List.of() : methods);
            return new RateLimitPolicy(name, capacity, refillTokensPerSecond, costPerRequest,
                    ttl, mode.toPolicyMode(), normalizedPaths, httpMethods);
        }
    }

    /** 与 {@link RateLimitPolicy.FailureMode} 同名的绑定别名，避免配置里写内部类全名。 */
    public enum FailureMode {
        FAIL_OPEN,
        FAIL_CLOSED;

        RateLimitPolicy.FailureMode toPolicyMode() {
            return this == FAIL_CLOSED
                    ? RateLimitPolicy.FailureMode.FAIL_CLOSED
                    : RateLimitPolicy.FailureMode.FAIL_OPEN;
        }
    }
}
