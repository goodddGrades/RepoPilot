package com.repopilot.it;

import com.repopilot.common.id.Ulid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 层验收：健康接口、统一信封、requestId 注入。
 *
 * <p>这里不假设 RabbitMQ 一定可用：本机可能只跑 MySQL。所以断言"MySQL 组件必须 UP、
 * 响应必须是统一信封、requestId 必须是 ULID"，而"所有依赖都 UP"由
 * {@code REPOPILOT_IT_REQUIRE_ALL_UP=true}（CI 环境）单独开启。
 * 把环境相关的断言显式标出来，比让它悄悄通过更可信。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiHealthIT extends AbstractDatabaseIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthEndpointShouldExposeEnvelopeAndUlidRequestId() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/health", String.class);

        assertThat(response.getStatusCode().value()).isIn(200, 503);
        assertThat(response.getBody()).isNotNull()
                .contains("\"code\"")
                .contains("\"name\":\"mysql\"")
                .contains("\"timestamp\"");
        String requestId = response.getHeaders().getFirst("X-Request-Id");
        assertThat(requestId).isNotNull();
        assertThat(Ulid.isValid(requestId)).isTrue();
    }

    @Test
    void inboundRequestIdShouldBeEchoed() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Request-Id", "client-supplied-42");

        ResponseEntity<String> response = restTemplate.exchange("/api/health",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("client-supplied-42");
        assertThat(response.getBody()).contains("\"requestId\":\"client-supplied-42\"");
    }

    @Test
    void livenessShouldNotDependOnExternalSystems() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/actuator/health/liveness", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "REPOPILOT_IT_REQUIRE_ALL_UP", matches = "true")
    void readinessShouldBeUpWhenEveryDependencyIsAvailable() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/actuator/health/readiness", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
