package com.repopilot.common.health;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private HealthService healthService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 复用与 Spring Boot 自动配置一致的 Jackson 行为（时间类型输出 ISO-8601 而非 epoch）
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .build());
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(healthService))
                .setMessageConverters(converter)
                .build();
    }

    @Test
    void shouldReturnUnifiedEnvelopeWithComponentDetails() throws Exception {
        HealthSnapshot snapshot = new HealthSnapshot(
                HealthStatus.UP,
                "repopilot-server",
                "0.1.0-SNAPSHOT",
                "dev",
                Instant.parse("2026-10-17T04:00:00Z"),
                List.of(new ComponentHealth("mysql", HealthStatus.UP, true, "MySQL 8.0.36", 3L)));
        when(healthService.snapshot()).thenReturn(snapshot);

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.application").value("repopilot-server"))
                .andExpect(jsonPath("$.data.version").value("0.1.0-SNAPSHOT"))
                .andExpect(jsonPath("$.data.components[0].name").value("mysql"))
                .andExpect(jsonPath("$.data.components[0].latencyMs").value(3))
                .andExpect(jsonPath("$.data.timestamp").value("2026-10-17T04:00:00Z"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void shouldStillReturnHttp200WhenDependencyDown() throws Exception {
        HealthSnapshot snapshot = new HealthSnapshot(
                HealthStatus.DOWN,
                "repopilot-server",
                "0.1.0-SNAPSHOT",
                "dev",
                Instant.parse("2026-10-17T04:00:00Z"),
                List.of(new ComponentHealth("rabbitmq", HealthStatus.DOWN, true, "Connection refused", 12L)));
        when(healthService.snapshot()).thenReturn(snapshot);

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("DOWN"))
                .andExpect(jsonPath("$.data.components[0].detail").value("Connection refused"));
    }
}
