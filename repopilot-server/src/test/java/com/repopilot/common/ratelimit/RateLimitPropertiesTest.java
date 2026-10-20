package com.repopilot.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置 → 运行期策略的转换。
 *
 * <p>重点在"配置写错时启动就失败"：限流配错但服务照跑，是最糟的一种状态——
 * 它看起来有保护，实际没有，而且没人会去查。
 */
class RateLimitPropertiesTest {

    @Test
    void resolvesPoliciesInDeclarationOrder() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnvironment("it");
        properties.setPolicies(ordered(
                "api-write", policyProps(30, 0.5, List.of("/api/v1/experiments"), List.of("POST")),
                "api-bulk", policyProps(5, 0.05, List.of("/api/v1/imports"), List.of("post"))));

        List<RateLimitPolicy> resolved = properties.resolvedPolicies();

        assertThat(resolved).extracting(RateLimitPolicy::name)
                .containsExactly("api-write", "api-bulk");
        assertThat(resolved.get(1).methods()).containsExactly("POST");
    }

    @Test
    void emptyPolicyMapIsRejectedWhenEnabled() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setPolicies(new LinkedHashMap<>());

        assertThatThrownBy(properties::resolvedPolicies)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有任何策略");
    }

    @Test
    void blankEnvironmentIsRejected() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnvironment(" ");
        properties.setPolicies(ordered("api-write",
                policyProps(30, 0.5, List.of("/api/v1/experiments"), List.of("POST"))));

        assertThatThrownBy(properties::resolvedPolicies)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("environment");
    }

    @Test
    void unknownHttpMethodFailsAtStartup() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setPolicies(ordered("api-write",
                policyProps(30, 0.5, List.of("/api/v1/experiments"), List.of("FETCH"))));

        // 方法白名单校验在 RateLimitPolicy 构造里（唯一实现处），
        // 配置层只负责搬运；抛的是参数异常而不是状态异常，这里如实断言。
        assertThatThrownBy(properties::resolvedPolicies)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知 HTTP 方法");
    }

    @Test
    void blankPathsAreDroppedAndEmptyResultIsRejected() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setPolicies(ordered("api-write",
                policyProps(30, 0.5, List.of("  "), List.of("POST"))));

        assertThatThrownBy(properties::resolvedPolicies)
                .isInstanceOf(IllegalArgumentException.class); // paths 为空，由策略构造拒绝
    }

    @Test
    void failureModeDefaultsToFailOpenAndMapsToPolicy() {
        RateLimitProperties.Policy props = policyProps(30, 0.5,
                List.of("/api/v1/experiments"), List.of("POST"));
        RateLimitProperties properties = new RateLimitProperties();
        properties.setPolicies(ordered("api-write", props));

        assertThat(props.getFailureMode()).isEqualTo(RateLimitProperties.FailureMode.FAIL_OPEN);
        assertThat(properties.resolvedPolicies().get(0).failureMode())
                .isEqualTo(RateLimitPolicy.FailureMode.FAIL_OPEN);

        props.setFailureMode(RateLimitProperties.FailureMode.FAIL_CLOSED);
        assertThat(properties.resolvedPolicies().get(0).failureMode())
                .isEqualTo(RateLimitPolicy.FailureMode.FAIL_CLOSED);
    }

    @Test
    void ttlIsParsedAsDuration() {
        RateLimitProperties.Policy props = policyProps(5, 0.05,
                List.of("/api/v1/imports"), List.of("POST"));
        props.setTtl(Duration.ofMinutes(30));
        RateLimitProperties properties = new RateLimitProperties();
        properties.setPolicies(ordered("api-bulk", props));

        assertThat(properties.resolvedPolicies().get(0).ttl()).isEqualTo(Duration.ofMinutes(30));
    }

    private Map<String, RateLimitProperties.Policy> ordered(String first, RateLimitProperties.Policy a,
                                                           String second, RateLimitProperties.Policy b) {
        Map<String, RateLimitProperties.Policy> map = new LinkedHashMap<>();
        map.put(first, a);
        map.put(second, b);
        return map;
    }

    private Map<String, RateLimitProperties.Policy> ordered(String name, RateLimitProperties.Policy policy) {
        Map<String, RateLimitProperties.Policy> map = new LinkedHashMap<>();
        map.put(name, policy);
        return map;
    }

    private RateLimitProperties.Policy policyProps(long capacity, double refill,
                                                   List<String> paths, List<String> methods) {
        RateLimitProperties.Policy policy = new RateLimitProperties.Policy();
        policy.setCapacity(capacity);
        policy.setRefillTokensPerSecond(refill);
        policy.setPaths(paths);
        policy.setMethods(methods);
        return policy;
    }
}
