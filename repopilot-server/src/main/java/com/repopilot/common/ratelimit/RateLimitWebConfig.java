package com.repopilot.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 按配置注册入口限流拦截器。
 *
 * <p>每条策略一个拦截器实例，路径与方法都来自配置——新增一个受限入口是改
 * {@code application.yml}，不是改 Java 代码。
 *
 * <p>{@code enabled=false} 时整个配置类不生效：不注册拦截器、不解析策略，
 * 连"策略必须存在"的校验都不会触发。这样关闭限流的部署不会因为
 * 留着空的 policies 而起不来。
 */
@Configuration
@ConditionalOnProperty(prefix = "repopilot.rate-limit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RateLimitWebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RateLimitWebConfig.class);

    private final RateLimitProperties properties;
    private final TokenBucketRateLimiter limiter;
    private final RateLimitSubjectResolver subjectResolver;
    private final RateLimitMetrics metrics;
    private final List<RateLimitPolicy> policies;

    public RateLimitWebConfig(RateLimitProperties properties, TokenBucketRateLimiter limiter,
                              RateLimitSubjectResolver subjectResolver, RateLimitMetrics metrics) {
        this.properties = properties;
        this.limiter = limiter;
        this.subjectResolver = subjectResolver;
        this.metrics = metrics;
        // 构造即校验：非法配置让应用起不来，而不是等到第一个请求才 500。
        this.policies = properties.resolvedPolicies();
        for (RateLimitPolicy policy : policies) {
            if (!policy.ttlCoversFullRefill()) {
                // 不是致命错误，但会实质放大额度：桶在补满前过期，下次又从满桶开始。
                log.warn("限流策略 {} 的 ttl({}) 短于补满所需时间({}s)，"
                                + "空闲客户端会周期性地获得完整突发额度",
                        policy.name(), policy.ttl(),
                        Math.ceil(policy.capacity() / policy.refillPerSecond()));
            }
        }
        log.info("入口限流已启用：环境={}，策略={}",
                properties.getEnvironment(),
                policies.stream().map(RateLimitPolicy::name).toList());
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        for (RateLimitPolicy policy : policies) {
            registry.addInterceptor(new RateLimitInterceptor(policy, limiter, subjectResolver, metrics))
                    .addPathPatterns(policy.paths().toArray(String[]::new));
        }
    }
}
