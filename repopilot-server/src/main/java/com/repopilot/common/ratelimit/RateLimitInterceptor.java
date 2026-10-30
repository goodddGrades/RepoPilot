package com.repopilot.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 一条策略对应的入口限流拦截器。
 *
 * <p>为什么用拦截器而不是 Filter：拦截器运行在 DispatcherServlet 内部，
 * 抛出的异常会被 {@code @RestControllerAdvice} 统一转成项目响应结构并带上
 * {@code Retry-After}；Filter 在 DispatcherServlet 之前，只能手写 JSON，
 * 很容易与统一信封漂移（多一个字段、少一层包装）。
 *
 * <p>只拦"确实会做重活"的写入口：
 * <ul>
 *   <li>健康检查、静态资源、只读查询不在策略路径里，也不在策略方法里；</li>
 *   <li>{@code handler} 不是 {@link HandlerMethod} 时直接放行（资源处理器、错误页）；</li>
 *   <li>方法不匹配时放行——路径模式无法区分 GET/POST，这一层是必要的补充。</li>
 * </ul>
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimitPolicy policy;
    private final TokenBucketRateLimiter limiter;
    private final RateLimitSubjectResolver subjectResolver;
    private final RateLimitMetrics metrics;

    public RateLimitInterceptor(RateLimitPolicy policy, TokenBucketRateLimiter limiter,
                                RateLimitSubjectResolver subjectResolver, RateLimitMetrics metrics) {
        this.policy = policy;
        this.limiter = limiter;
        this.subjectResolver = subjectResolver;
        this.metrics = metrics;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        if (!appliesTo(request.getMethod())) {
            return true;
        }

        String subject = subjectResolver.resolve(request);
        RateLimitDecision decision;
        try {
            decision = limiter.acquire(policy, subject);
        } catch (RateLimitUnavailableException ex) {
            metrics.recordUnavailable(policy.name());
            if (policy.failureMode() == RateLimitPolicy.FailureMode.FAIL_OPEN) {
                // 明确降级：放行 + 响应头 + WARN 日志 + unavailable 指标。
                // 四者缺一不可——只打日志会让这个状态在监控里完全隐形。
                log.warn("限流依赖不可用，策略 {} 配置为 FAIL_OPEN，本次放行: {}",
                        policy.name(), ex.getMessage());
                response.setHeader("X-RateLimit-Status", "unavailable");
                return true;
            }
            throw ex;
        }

        response.setHeader("X-RateLimit-Policy", policy.name());
        response.setHeader("X-RateLimit-Limit", Long.toString(decision.capacity()));
        response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        if (!decision.allowed()) {
            metrics.recordDenied(policy.name());
            throw new RateLimitedException(decision);
        }
        metrics.recordAllowed(policy.name());
        return true;
    }

    private boolean appliesTo(String method) {
        if (policy.methods().isEmpty()) {
            return true;
        }
        return policy.methods().contains(method.toUpperCase(java.util.Locale.ROOT));
    }
}
