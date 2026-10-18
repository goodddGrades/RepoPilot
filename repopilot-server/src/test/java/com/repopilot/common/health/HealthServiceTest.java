package com.repopilot.common.health;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HealthServiceTest {

    @Test
    void shouldReportUpWhenAllComponentsUp() {
        HealthService service = new HealthService(
                List.of(probe("mysql", true, HealthStatus.UP), probe("redis", false, HealthStatus.UNKNOWN)),
                environment());

        HealthSnapshot snapshot = service.snapshot();

        assertThat(snapshot.status()).isEqualTo(HealthStatus.UP);
        assertThat(snapshot.components()).hasSize(2);
        assertThat(snapshot.application()).isEqualTo("repopilot-server");
        assertThat(snapshot.profile()).isEqualTo("dev");
        assertThat(snapshot.timestamp()).isNotNull();
    }

    @Test
    void shouldReportDownWhenRequiredComponentDown() {
        HealthService service = new HealthService(
                List.of(probe("mysql", true, HealthStatus.UP), probe("rabbitmq", true, HealthStatus.DOWN)),
                environment());

        assertThat(service.snapshot().status()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void shouldReportDegradedWhenOnlyOptionalComponentDown() {
        HealthService service = new HealthService(
                List.of(probe("mysql", true, HealthStatus.UP), probe("redis", false, HealthStatus.DOWN)),
                environment());

        assertThat(service.snapshot().status()).isEqualTo(HealthStatus.DEGRADED);
    }

    @Test
    void unknownOptionalComponentShouldNotDegradeService() {
        HealthService service = new HealthService(
                List.of(probe("mysql", true, HealthStatus.UP), probe("redis", false, HealthStatus.UNKNOWN)),
                environment());

        assertThat(service.snapshot().status()).isEqualTo(HealthStatus.UP);
    }

    private HealthProbe probe(String name, boolean required, HealthStatus status) {
        return new HealthProbe() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean required() {
                return required;
            }

            @Override
            public ComponentHealth probe() {
                return new ComponentHealth(name, status, required, "test", 1L);
            }
        };
    }

    private MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("spring.application.name", "repopilot-server")
                .withProperty("spring.application.version", "0.1.0-SNAPSHOT")
                .withProperty("spring.profiles.active", "dev");
    }
}
