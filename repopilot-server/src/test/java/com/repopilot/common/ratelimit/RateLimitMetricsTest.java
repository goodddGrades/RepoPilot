package com.repopilot.common.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指标口径：allowed / denied / unavailable 必须是三条独立计数。
 *
 * <p>最容易犯的错是把 {@code unavailable} 并进 {@code denied}：那会让
 * "Redis 挂了导致请求被拒"看起来像"客户端太吵"，而这两件事的处置完全不同。
 */
class RateLimitMetricsTest {

    @Test
    void countsAreSeparatedByOutcomeAndPolicy() {
        RateLimitMetrics metrics = new RateLimitMetrics(new SimpleMeterRegistry());

        metrics.recordAllowed("api-write");
        metrics.recordAllowed("api-write");
        metrics.recordDenied("api-write");
        metrics.recordUnavailable("api-write");
        metrics.recordDenied("api-bulk");

        assertThat(metrics.count("api-write", "allowed")).isEqualTo(2.0);
        assertThat(metrics.count("api-write", "denied")).isEqualTo(1.0);
        assertThat(metrics.count("api-write", "unavailable")).isEqualTo(1.0);
        assertThat(metrics.count("api-bulk", "denied")).isEqualTo(1.0);
        assertThat(metrics.count("api-bulk", "allowed")).isZero();
    }

    @Test
    void unknownPairReadsAsZeroNotException() {
        RateLimitMetrics metrics = new RateLimitMetrics(new SimpleMeterRegistry());

        assertThat(metrics.count("never-used", "denied")).isZero();
    }
}
