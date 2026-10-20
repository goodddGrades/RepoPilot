package com.repopilot.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主体解析：默认不信任转发头，信任时才取首跳。
 *
 * <p>这是限流的"身份"边界。如果 {@code X-Forwarded-For} 被无条件采信，
 * 任何人只要每次换一个头就能无限次数——限流形同不存在。
 */
class RateLimitSubjectResolverTest {

    @Test
    void usesRemoteAddressByDefault() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "203.0.113.99");

        assertThat(resolver(false).resolve(request)).isEqualTo("ip:192.0.2.10");
    }

    @Test
    void usesFirstHopWhenTrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.99, 10.0.0.1");

        assertThat(resolver(true).resolve(request)).isEqualTo("ip:203.0.113.99");
    }

    @Test
    void malformedForwardedHeaderFallsBackToRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        // 非法字符 + 超长：不能让任意字符串进入哈希与日志。
        request.addHeader("X-Forwarded-For", "not-an-ip<script>");

        assertThat(resolver(true).resolve(request)).isEqualTo("ip:127.0.0.1");
    }

    @Test
    void blankRemoteAddressFallsBackToUnknown() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("   ");

        assertThat(resolver(false).resolve(request)).isEqualTo("ip:unknown");
    }

    @Test
    void subjectCarriesDimensionPrefix() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("2001:db8::1");

        // 前缀让"将来换成用户/租户维度"时旧 Key 不会被误读，日志里也能看出维度。
        assertThat(resolver(false).resolve(request)).isEqualTo("ip:2001:db8::1");
    }

    private RateLimitSubjectResolver resolver(boolean trustForwardedFor) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setTrustForwardedFor(trustForwardedFor);
        return new RateLimitSubjectResolver(properties);
    }
}
