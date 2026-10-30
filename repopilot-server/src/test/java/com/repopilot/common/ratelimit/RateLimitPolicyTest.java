package com.repopilot.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 策略参数的边界校验。
 *
 * <p>这些不是"为了覆盖率"的测试：每一个非法值如果被放过，运行时都会变成
 * 一个安静的错误——容量为 0 变成全拒、cost 大于容量变成永久拒绝、
 * 速率为 0 变成除零或永不恢复。启动时失败远好过线上才发现。
 */
class RateLimitPolicyTest {

    @Test
    void capacityMustBePositive() {
        assertThatThrownBy(() -> policy(0, 1.0, 1, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }

    @Test
    void refillMustBePositive() {
        assertThatThrownBy(() -> policy(10, 0.0, 1, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refill-tokens-per-second");
        assertThatThrownBy(() -> policy(10, -1.0, 1, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void costMustNotExceedCapacity() {
        // cost > capacity 时桶永远凑不够一次请求：所有请求恒被拒，且没有任何报错。
        assertThatThrownBy(() -> policy(5, 1.0, 6, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能大于 capacity");
    }

    @Test
    void ttlMustBePositive() {
        assertThatThrownBy(() -> policy(10, 1.0, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
    }

    @Test
    void pathsMustNotBeEmpty() {
        assertThatThrownBy(() -> new RateLimitPolicy("p", 10, 1.0, 1, Duration.ofMinutes(1),
                RateLimitPolicy.FailureMode.FAIL_OPEN, List.of(), Set.of("POST")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paths");
    }

    @Test
    void ttlShorterThanFullRefillIsDetectable() {
        // capacity 10、速率 1/s → 补满需 10s；ttl 5s 不够，会被启动日志点名。
        assertThat(policy(10, 1.0, 1, Duration.ofSeconds(5)).ttlCoversFullRefill()).isFalse();
        assertThat(policy(10, 1.0, 1, Duration.ofSeconds(10)).ttlCoversFullRefill()).isTrue();
        assertThat(policy(10, 0.5, 1, Duration.ofSeconds(30)).ttlCoversFullRefill()).isTrue();
    }

    private RateLimitPolicy policy(long capacity, double refill, long cost, Duration ttl) {
        return new RateLimitPolicy("api-write", capacity, refill, cost, ttl,
                RateLimitPolicy.FailureMode.FAIL_OPEN,
                List.of("/api/v1/experiments"), Set.of("POST"));
    }
}
