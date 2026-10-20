package com.repopilot.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实客户端连不上 Redis 时的映射。
 *
 * <p>不用 Mock 伪造异常：这里连的是一个**真实存在但必然拒绝连接**的端口
 * （TCP 1），走的是 Lettuce 真实的连接失败路径。Mock 只能证明"我写的 if 会执行"，
 * 证明不了真实客户端抛出的异常类型确实被 {@link RateLimitUnavailableException} 接住——
 * 而那正是失败策略能否生效的前提。
 *
 * <p>这条用例不需要任何基础设施：端口 1 在任何机器上都不会有人监听。
 */
class RedisTokenBucketRateLimiterUnavailableTest {

    @Test
    @Timeout(30)
    void connectionFailureIsMappedToUnavailable() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 1);
        // 500ms 足够：本机端口 1 必然是 RST，不需要等默认的 60s 超时。
        factory.setTimeout(500L);
        factory.setShareNativeConnection(false);
        factory.afterPropertiesSet();
        try {
            RateLimitProperties properties = new RateLimitProperties();
            RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(
                    new StringRedisTemplate(factory), properties);
            RateLimitPolicy policy = new RateLimitPolicy("api-write", 10, 1.0, 1,
                    Duration.ofMinutes(1), RateLimitPolicy.FailureMode.FAIL_OPEN,
                    List.of("/api/v1/experiments"), Set.of("POST"));

            assertThatThrownBy(() -> limiter.acquire(policy, "ip:192.0.2.10"))
                    .isInstanceOf(RateLimitUnavailableException.class)
                    .hasMessageContaining("api-write");
        } finally {
            factory.destroy();
        }
    }
}
