package com.repopilot.common.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 拦截器的分支行为。
 *
 * <p>三类最容易被写错、又最难在人工点页面时发现的分支：
 * <ul>
 *   <li>失败策略：Redis 挂了到底放行还是 503，必须与配置严格一致；</li>
 *   <li>跳过规则：只读请求、静态资源不能因为"路径匹配"被顺带限流；</li>
 *   <li>异常类型：限流是 429，依赖故障是 503，混用会让监控统计失真。</li>
 * </ul>
 */
class RateLimitInterceptorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RateLimitMetrics metrics = new RateLimitMetrics(registry);
    private final RateLimitSubjectResolver resolver = new RateLimitSubjectResolver(new RateLimitProperties());

    @Test
    void allowedRequestPassesAndPublishesQuotaHeaders() throws Exception {
        RateLimitInterceptor interceptor = interceptor(RateLimitPolicy.FailureMode.FAIL_OPEN,
                (policy, subject) -> new RateLimitDecision(true, 7, 30, 0, policy.name(), subject));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean passed = interceptor.preHandle(post("/api/v1/experiments"), response, handler());

        assertThat(passed).isTrue();
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("30");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("7");
        assertThat(metrics.count("api-write", "allowed")).isEqualTo(1.0);
    }

    @Test
    void deniedRequestThrowsRateLimitedWithRetryAfter() throws Exception {
        RateLimitInterceptor interceptor = interceptor(RateLimitPolicy.FailureMode.FAIL_OPEN,
                (policy, subject) -> new RateLimitDecision(false, 0, 30, 1500, policy.name(), subject));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(post("/api/v1/experiments"), response, handler()))
                .isInstanceOf(RateLimitedException.class)
                .satisfies(ex -> assertThat(((RateLimitedException) ex).retryAfterSeconds()).isEqualTo(2));
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(metrics.count("api-write", "denied")).isEqualTo(1.0);
    }

    @Test
    void failOpenPassesWhenRedisIsUnavailable() throws Exception {
        RateLimitInterceptor interceptor = interceptor(RateLimitPolicy.FailureMode.FAIL_OPEN,
                (policy, subject) -> {
                    throw new RateLimitUnavailableException(policy.name(),
                            new IllegalStateException("connection refused"));
                });
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean passed = interceptor.preHandle(post("/api/v1/experiments"), response, handler());

        assertThat(passed).isTrue();
        assertThat(response.getHeader("X-RateLimit-Status")).isEqualTo("unavailable");
        // 降级必须留下指标：只打日志会让这个状态在监控上完全隐形。
        assertThat(metrics.count("api-write", "unavailable")).isEqualTo(1.0);
        assertThat(metrics.count("api-write", "allowed")).isZero();
    }

    @Test
    void failClosedRejectsWhenRedisIsUnavailable() throws Exception {
        RateLimitInterceptor interceptor = interceptor(RateLimitPolicy.FailureMode.FAIL_CLOSED,
                (policy, subject) -> {
                    throw new RateLimitUnavailableException(policy.name(),
                            new IllegalStateException("connection refused"));
                });

        assertThatThrownBy(() -> interceptor.preHandle(post("/api/v1/experiments"),
                new MockHttpServletResponse(), handler()))
                .isInstanceOf(RateLimitUnavailableException.class);
        assertThat(metrics.count("api-write", "unavailable")).isEqualTo(1.0);
    }

    @Test
    void nonHandlerMethodIsNotLimited() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RateLimitInterceptor interceptor = interceptor(RateLimitPolicy.FailureMode.FAIL_OPEN,
                (policy, subject) -> {
                    calls.incrementAndGet();
                    return new RateLimitDecision(true, 1, 30, 0, policy.name(), subject);
                });

        // 静态资源与错误页的 handler 不是 HandlerMethod：不该被业务限流波及。
        boolean passed = interceptor.preHandle(post("/api/v1/experiments"),
                new MockHttpServletResponse(), new Object());

        assertThat(passed).isTrue();
        assertThat(calls).hasValue(0);
    }

    @Test
    void readRequestsAreNotLimited() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RateLimitInterceptor interceptor = interceptor(RateLimitPolicy.FailureMode.FAIL_OPEN,
                (policy, subject) -> {
                    calls.incrementAndGet();
                    return new RateLimitDecision(false, 0, 30, 1000, policy.name(), subject);
                });
        MockHttpServletRequest request = post("/api/v1/experiments");
        request.setMethod(HttpMethod.GET.name());

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler())).isTrue();
        assertThat(calls).hasValue(0);
    }

    private RateLimitInterceptor interceptor(RateLimitPolicy.FailureMode mode,
                                             TokenBucketRateLimiter limiter) {
        RateLimitPolicy policy = new RateLimitPolicy("api-write", 30, 0.5, 1,
                Duration.ofMinutes(10), mode,
                List.of("/api/v1/experiments"), Set.of("POST"));
        return new RateLimitInterceptor(policy, limiter, resolver, metrics);
    }

    private MockHttpServletRequest post(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI(path);
        request.setRemoteAddr("192.0.2.10");
        return request;
    }

    private HandlerMethod handler() throws NoSuchMethodException {
        return new HandlerMethod(new SampleHandler(), SampleHandler.class.getMethod("handle"));
    }

    /** 仅用于构造合法的 HandlerMethod；不会被真正调用。 */
    static class SampleHandler {
        public void handle() {
        }
    }
}
