package com.repopilot.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等摘要的行为：语义相同 → 同摘要；语义变了 → 摘要必须变。
 *
 * <p>如果"改了 body 摘要却没变"，后果是用户改了请求内容仍然拿回旧结果，
 * 而且完全没有报错；反之如果"键序不同摘要就变"，前端一次字段顺序变化就会变成 409。
 */
class IdempotencyDigestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void keyOrderShouldNotChangeDigest() throws Exception {
        String a = IdempotencyDigest.of("POST", "/api/v1/agents",
                mapper.readTree("{\"code\":\"a\",\"name\":\"n\"}"));
        String b = IdempotencyDigest.of("POST", "/api/v1/agents",
                mapper.readTree("{\"name\":\"n\",\"code\":\"a\"}"));
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    void bodyChangeShouldChangeDigest() throws Exception {
        String a = IdempotencyDigest.of("POST", "/api/v1/agents",
                mapper.readTree("{\"code\":\"a\"}"));
        String b = IdempotencyDigest.of("POST", "/api/v1/agents",
                mapper.readTree("{\"code\":\"b\"}"));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void methodAndPathShouldParticipate() throws Exception {
        String a = IdempotencyDigest.of("POST", "/api/v1/agents", mapper.readTree("{}"));
        String b = IdempotencyDigest.of("POST", "/api/v1/datasets", mapper.readTree("{}"));
        String c = IdempotencyDigest.of("PATCH", "/api/v1/agents", mapper.readTree("{}"));
        assertThat(a).isNotEqualTo(b).isNotEqualTo(c);
    }

    @Test
    void extraArgumentShouldParticipate() throws Exception {
        // 导入场景：body 相同（空），但文件不同 → 必须视为不同请求
        String a = IdempotencyDigest.of("POST", "/import", null, "sha-1");
        String b = IdempotencyDigest.of("POST", "/import", null, "sha-2");
        assertThat(a).isNotEqualTo(b);
    }
}
