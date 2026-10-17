package com.repopilot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试：需要 Docker Compose 基础设施已启动，并通过 .env 提供连接信息。
 *
 * <p>未检测到 {@code MYSQL_PASSWORD} 环境变量时自动跳过（skipped），
 * 不会伪装成通过。
 *
 * <p>执行方式：
 * <pre>
 *   set -a &amp;&amp; source ../.env &amp;&amp; set +a
 *   ./mvnw test -Dtest=RepoPilotServerApplicationIT
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+")
class RepoPilotServerApplicationIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoads() {
        assertThat(dataSource).isNotNull();
    }

    @Test
    void flywayShouldHaveAppliedBaselineMigration() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("version")).isEqualTo("1");
            assertThat(rs.getBoolean("success")).isTrue();
        }
    }

    @Test
    void healthEndpointShouldReportAllDependenciesUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/health", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .isNotNull()
                .contains("\"status\":\"UP\"")
                .contains("\"name\":\"mysql\"")
                .contains("\"name\":\"rabbitmq\"")
                .containsPattern("\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T[^\"]*Z\"");
    }

    @Test
    void actuatorHealthShouldBeExposed() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
