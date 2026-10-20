package com.repopilot.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis Key 的命名规则。
 *
 * <p>Key 设计有三条硬要求，任何一条破了都会在生产上出问题：
 * <ul>
 *   <li>命名空间 + 环境隔离：否则测试会吃掉开发环境的额度，且无法单独清理；</li>
 *   <li>不落原始主体：IP 原样进键空间等于把客户端地址沉淀到 Redis 与慢查询日志里；</li>
 *   <li>不落任何凭据：API Key / 模型密钥绝不能出现在 key 里。</li>
 * </ul>
 */
class RedisTokenBucketRateLimiterKeyTest {

    private RateLimitPolicy policy() {
        return new RateLimitPolicy("api-write", 30, 0.5, 1, Duration.ofMinutes(10),
                RateLimitPolicy.FailureMode.FAIL_OPEN,
                List.of("/api/v1/experiments"), Set.of("POST"));
    }

    @Test
    void keyCarriesNamespaceEnvironmentPolicyAndHash() {
        RateLimitProperties properties = properties("repopilot:ratelimit", "it");

        String key = limiter(properties).key(policy(), "ip:192.0.2.10");

        assertThat(key).matches("repopilot:ratelimit:it:api-write:[0-9a-f]{16}");
        // 原始地址不进入 key：只有不可逆摘要。
        assertThat(key).doesNotContain("192.0.2.10");
    }

    @Test
    void sameSubjectAndPolicyProducesSameKey() {
        RedisTokenBucketRateLimiter limiter = limiter(properties("repopilot:ratelimit", "it"));

        assertThat(limiter.key(policy(), "ip:192.0.2.10"))
                .isEqualTo(limiter.key(policy(), "ip:192.0.2.10"));
    }

    @Test
    void differentSubjectsDoNotShareABucket() {
        RedisTokenBucketRateLimiter limiter = limiter(properties("repopilot:ratelimit", "it"));

        assertThat(limiter.key(policy(), "ip:192.0.2.10"))
                .isNotEqualTo(limiter.key(policy(), "ip:192.0.2.11"));
    }

    @Test
    void differentPoliciesDoNotShareABucket() {
        RedisTokenBucketRateLimiter limiter = limiter(properties("repopilot:ratelimit", "it"));
        RateLimitPolicy bulk = new RateLimitPolicy("api-bulk", 5, 0.05, 1, Duration.ofMinutes(30),
                RateLimitPolicy.FailureMode.FAIL_OPEN,
                List.of("/api/v1/imports"), Set.of("POST"));

        assertThat(limiter.key(policy(), "ip:192.0.2.10"))
                .isNotEqualTo(limiter.key(bulk, "ip:192.0.2.10"));
    }

    @Test
    void differentEnvironmentsDoNotShareABucket() {
        String itKey = limiter(properties("repopilot:ratelimit", "it")).key(policy(), "ip:192.0.2.10");
        String devKey = limiter(properties("repopilot:ratelimit", "dev")).key(policy(), "ip:192.0.2.10");

        assertThat(itKey).isNotEqualTo(devKey);
        assertThat(itKey).contains(":it:");
        assertThat(devKey).contains(":dev:");
    }

    @Test
    void configuredPrefixIsHonoured() {
        assertThat(limiter(properties("custom:ns", "it")).key(policy(), "ip:192.0.2.10"))
                .startsWith("custom:ns:it:api-write:");
    }

    @Test
    void keyContractMatchesPythonImplementation() {
        // 值不是现算的：它与 Python 侧测试硬编码的是同一个字面量。
        // 任何一端改了分隔符、哈希截断长度或字符编码，这条用例就会红——
        // 而不是等到生产上出现"Java 与 Worker 各有一份额度"才发现。
        RateLimitPolicy judgePolicy = new RateLimitPolicy("judge-model", 10, 0.2, 1,
                Duration.ofMinutes(10), RateLimitPolicy.FailureMode.FAIL_CLOSED,
                List.of("/api/v1/experiments"), Set.of("POST"));

        assertThat(limiter(properties("repopilot:ratelimit", "it"))
                .key(judgePolicy, "provider:claude-cli/model:claude-3-5-sonnet"))
                .isEqualTo("repopilot:ratelimit:it:judge-model:426175e89fef3590");
    }

    private RateLimitProperties properties(String prefix, String environment) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setKeyPrefix(prefix);
        properties.setEnvironment(environment);
        return properties;
    }

    private RedisTokenBucketRateLimiter limiter(RateLimitProperties properties) {
        // 只测 key 的拼装：execute 不会被调用，模板不需要真的连上 Redis。
        return new RedisTokenBucketRateLimiter(new StringRedisTemplate(), properties);
    }
}
