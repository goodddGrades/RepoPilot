package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.id.Ulid;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 与配置版本的真实 HTTP + 真实 MySQL 验收。
 *
 * <p>断言的重点不是"接口能返回 200"，而是几条并发/状态规则：
 * 版本号连续、发布后不可改、归档后不能建版本、幂等重放复用同一资源。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentApiIT extends AbstractDatabaseIT {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void duplicateCodeShouldReturnConflictAndKeepSingleRow() {
        String code = "dup-" + Ulid.newId();

        ResponseEntity<String> first = post("/api/v1/agents", Ulid.newId(),
                "{\"code\":\"" + code + "\",\"name\":\"第一个\",\"agentType\":\"MOCK\"}");
        assertThat(first.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> second = post("/api/v1/agents", Ulid.newId(),
                "{\"code\":\"" + code + "\",\"name\":\"第二个\",\"agentType\":\"MOCK\"}");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(codeOf(second)).isEqualTo("RESOURCE_CONFLICT");
        assertThat(count("SELECT COUNT(*) FROM agents WHERE code = ?", code)).isEqualTo(1);
    }

    @Test
    void versionsShouldBeNumberedContinuously() {
        String agentId = createAgent("ver-" + Ulid.newId());

        int v1 = createVersion(agentId, "{\"model\":\"m1\"}");
        int v2 = createVersion(agentId, "{\"model\":\"m2\"}");
        int v3 = createVersion(agentId, "{\"model\":\"m3\"}");

        assertThat(new int[]{v1, v2, v3}).containsExactly(1, 2, 3);
    }

    @Test
    void publishedVersionMustNotBeModifiable() {
        String agentId = createAgent("pub-" + Ulid.newId());
        createVersion(agentId, "{\"model\":\"m1\"}");
        JsonNode created = latestVersion(agentId);
        String versionId = created.get("id").asText();
        String hashBefore = jdbc.queryForObject(
                "SELECT config_hash FROM agent_versions WHERE id = ?", String.class, versionId);

        ResponseEntity<String> published = post("/api/v1/agent-versions/" + versionId + "/publish", Ulid.newId(), null);
        assertThat(published.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> updated = put("/api/v1/agent-versions/" + versionId, Ulid.newId(),
                "{\"runnerType\":\"MOCK\",\"config\":{\"model\":\"changed\"}}");
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(codeOf(updated)).isEqualTo("INVALID_STATE_TRANSITION");

        String hashAfter = jdbc.queryForObject(
                "SELECT config_hash FROM agent_versions WHERE id = ?", String.class, versionId);
        assertThat(hashAfter).isEqualTo(hashBefore);
    }

    @Test
    void publishingTwiceShouldBeRejectedAndKeepPublishedAt() {
        String agentId = createAgent("pub2-" + Ulid.newId());
        createVersion(agentId, "{\"model\":\"m1\"}");
        String versionId = latestVersion(agentId).get("id").asText();

        assertThat(post("/api/v1/agent-versions/" + versionId + "/publish", Ulid.newId(), null)
                .getStatusCode().value()).isEqualTo(200);
        String publishedAt = jdbc.queryForObject(
                "SELECT DATE_FORMAT(published_at, '%Y-%m-%d %H:%i:%s.%f') FROM agent_versions WHERE id = ?",
                String.class, versionId);

        ResponseEntity<String> again = post("/api/v1/agent-versions/" + versionId + "/publish", Ulid.newId(), null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(codeOf(again)).isEqualTo("INVALID_STATE_TRANSITION");

        String publishedAtAfter = jdbc.queryForObject(
                "SELECT DATE_FORMAT(published_at, '%Y-%m-%d %H:%i:%s.%f') FROM agent_versions WHERE id = ?",
                String.class, versionId);
        assertThat(publishedAtAfter).isEqualTo(publishedAt);
    }

    @Test
    void archivedAgentShouldNotAcceptNewVersion() {
        String agentId = createAgent("arch-" + Ulid.newId());

        ResponseEntity<String> patched = patch("/api/v1/agents/" + agentId, Ulid.newId(),
                "{\"status\":\"ARCHIVED\"}");
        assertThat(patched.getStatusCode().value()).isEqualTo(200);
        assertThat(patched.getBody()).contains("\"status\":\"ARCHIVED\"");

        ResponseEntity<String> created = post("/api/v1/agents/" + agentId + "/versions", Ulid.newId(),
                "{\"runnerType\":\"MOCK\",\"config\":{\"model\":\"m1\"}}");
        assertThat(created.getStatusCode().value()).isIn(400, 409);
        assertThat(count("SELECT COUNT(*) FROM agent_versions WHERE agent_id = ?", agentId)).isZero();
    }

    @Test
    void archivedAgentShouldNotBeReactivated() {
        String agentId = createAgent("back-" + Ulid.newId());
        patch("/api/v1/agents/" + agentId, Ulid.newId(), "{\"status\":\"ARCHIVED\"}");

        ResponseEntity<String> reactivate = patch("/api/v1/agents/" + agentId, Ulid.newId(),
                "{\"status\":\"ACTIVE\"}");
        assertThat(reactivate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(codeOf(reactivate)).isEqualTo("INVALID_STATE_TRANSITION");
    }

    @Test
    void sameIdempotencyKeyShouldReuseFirstResult() {
        String code = "idem-" + Ulid.newId();
        String key = Ulid.newId();
        String body = "{\"code\":\"" + code + "\",\"name\":\"幂等\",\"agentType\":\"MOCK\"}";

        ResponseEntity<String> first = post("/api/v1/agents", key, body);
        ResponseEntity<String> second = post("/api/v1/agents", key, body);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(dataId(second)).isEqualTo(dataId(first));
        assertThat(count("SELECT COUNT(*) FROM agents WHERE code = ?", code)).isEqualTo(1);
    }

    @Test
    void sameIdempotencyKeyWithDifferentBodyShouldConflict() {
        String key = Ulid.newId();
        post("/api/v1/agents", key, "{\"code\":\"idem-a-" + Ulid.newId() + "\",\"name\":\"A\",\"agentType\":\"MOCK\"}");

        ResponseEntity<String> conflict = post("/api/v1/agents", key,
                "{\"code\":\"idem-b-" + Ulid.newId() + "\",\"name\":\"B\",\"agentType\":\"MOCK\"}");
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(codeOf(conflict)).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void unknownAgentTypeShouldBeRejected() {
        ResponseEntity<String> response = post("/api/v1/agents", Ulid.newId(),
                "{\"code\":\"bad-" + Ulid.newId() + "\",\"name\":\"X\",\"agentType\":\"HUMAN\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("agentType");
    }

    // ---------------------------------------------------------------- helpers

    private String createAgent(String code) {
        ResponseEntity<String> response = post("/api/v1/agents", Ulid.newId(),
                "{\"code\":\"" + code + "\",\"name\":\"测试 Agent\",\"agentType\":\"MOCK\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return dataId(response);
    }

    private int createVersion(String agentId, String config) {
        ResponseEntity<String> response = post("/api/v1/agents/" + agentId + "/versions", Ulid.newId(),
                "{\"runnerType\":\"MOCK\",\"config\":" + config + "}");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        try {
            return objectMapper.readTree(response.getBody()).path("data").path("version").asInt();
        } catch (Exception ex) {
            throw new AssertionError("响应不是合法 JSON: " + response.getBody(), ex);
        }
    }

    private JsonNode latestVersion(String agentId) {
        String sql = "SELECT id, version FROM agent_versions WHERE agent_id = ? ORDER BY version DESC LIMIT 1";
        return jdbc.queryForObject(sql, (rs, i) -> {
            var node = objectMapper.createObjectNode();
            node.put("id", rs.getString("id"));
            node.put("version", rs.getInt("version"));
            return node;
        }, agentId);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String dataId(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody()).path("data").path("id").asText();
        } catch (Exception ex) {
            throw new AssertionError("响应不是合法 JSON: " + response.getBody(), ex);
        }
    }

    private String codeOf(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody()).path("code").asText();
        } catch (Exception ex) {
            throw new AssertionError("响应不是合法 JSON: " + response.getBody(), ex);
        }
    }

    private ResponseEntity<String> post(String path, String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", key);
        return restTemplate.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body == null ? "{}" : body, headers), String.class);
    }

    private ResponseEntity<String> put(String path, String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", key);
        return restTemplate.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> patch(String path, String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", key);
        return restTemplate.exchange(path, HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
    }
}
