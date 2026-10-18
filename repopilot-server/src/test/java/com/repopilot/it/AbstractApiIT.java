package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * HTTP 断言基类：只放"发请求 + 读响应"的重复代码。
 *
 * <p>刻意不把断言也收进来：每个用例该断言什么是它自己的事，
 * 基类一旦提供 {@code assertSuccess()}，就会有人用它掩盖掉本该检查的状态码。
 */
abstract class AbstractApiIT extends AbstractDatabaseIT {

    @Autowired
    protected TestRestTemplate restTemplate;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbc;

    protected ResponseEntity<String> post(String path, String key, String body) {
        return exchange(HttpMethod.POST, path, key, body, MediaType.APPLICATION_JSON);
    }

    protected ResponseEntity<String> put(String path, String key, String body) {
        return exchange(HttpMethod.PUT, path, key, body, MediaType.APPLICATION_JSON);
    }

    protected ResponseEntity<String> patch(String path, String key, String body) {
        return exchange(HttpMethod.PATCH, path, key, body, MediaType.APPLICATION_JSON);
    }

    protected ResponseEntity<String> delete(String path, String key) {
        return exchange(HttpMethod.DELETE, path, key, null, MediaType.APPLICATION_JSON);
    }

    protected ResponseEntity<String> get(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, HttpEntity.EMPTY, String.class);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String key,
                                            String body, MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        if (contentType != null) {
            headers.setContentType(contentType);
        }
        if (key != null) {
            headers.add("Idempotency-Key", key);
        }
        return restTemplate.exchange(path, method,
                new HttpEntity<>(body == null ? "{}" : body, headers), String.class);
    }

    /** 响应 {@code data} 节点；解析失败视为用例失败（而不是返回 null 让断言变得更模糊）。 */
    protected JsonNode data(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody()).path("data");
        } catch (Exception ex) {
            throw new AssertionError("响应不是合法 JSON: " + response.getBody(), ex);
        }
    }

    protected String dataId(ResponseEntity<String> response) {
        return data(response).path("id").asText();
    }

    protected String codeOf(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody()).path("code").asText();
        } catch (Exception ex) {
            throw new AssertionError("响应不是合法 JSON: " + response.getBody(), ex);
        }
    }

    /** 第一个字段级错误；没有 errors 时返回空对象，便于断言"没有错误"。 */
    protected JsonNode firstError(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody()).path("errors").path(0);
        } catch (Exception ex) {
            throw new AssertionError("响应不是合法 JSON: " + response.getBody(), ex);
        }
    }

    protected long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
