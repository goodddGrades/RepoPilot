package com.repopilot.common.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code Retry-After} 的换算。
 *
 * <p>这是客户端唯一用来"等多久"的依据，取值必须单调、不返回 0、不返回负数。
 * 返回 0 会让客户端立刻重试，而此刻额度必然还不够——一次 429 变成一串 429；
 * 返回负数会让严格解析的 HTTP 客户端直接报错。
 */
class RateLimitDecisionTest {

    @Test
    void allowedDecisionCarriesRemainingQuota() {
        RateLimitDecision decision = new RateLimitDecision(true, 7, 10, 0, "api-write", "ip:1.1.1.1");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(7);
        assertThat(decision.capacity()).isEqualTo(10);
    }

    @Test
    void retryAfterRoundsUpToWholeSeconds() {
        assertThat(rejected(1).retryAfterSeconds()).isEqualTo(1);
        assertThat(rejected(1000).retryAfterSeconds()).isEqualTo(1);
        assertThat(rejected(1001).retryAfterSeconds()).isEqualTo(2);
        assertThat(rejected(2500).retryAfterSeconds()).isEqualTo(3);
        assertThat(rejected(60_000).retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void retryAfterNeverReturnsNonPositive() {
        // 毫秒值异常（0 或负）时退回 1 秒，而不是把 0/负数透给客户端。
        assertThat(rejected(0).retryAfterSeconds()).isEqualTo(1);
        assertThat(rejected(-5).retryAfterSeconds()).isEqualTo(1);
    }

    private RateLimitDecision rejected(long retryAfterMillis) {
        return new RateLimitDecision(false, 0, 10, retryAfterMillis, "api-write", "ip:1.1.1.1");
    }
}
