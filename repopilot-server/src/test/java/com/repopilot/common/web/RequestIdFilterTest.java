package com.repopilot.common.web;

import com.repopilot.common.api.RequestIdHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequestIdFilterTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new EchoController())
                .addFilter(new RequestIdFilter())
                .build();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldGenerateRequestIdWhenHeaderMissing() throws Exception {
        mockMvc.perform(get("/ping"))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdHolder.HEADER_NAME));
    }

    @Test
    void shouldReuseInboundRequestId() throws Exception {
        mockMvc.perform(get("/ping").header(RequestIdHolder.HEADER_NAME, "client-trace-001"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdHolder.HEADER_NAME, "client-trace-001"));
    }

    @Test
    void shouldReplaceInvalidInboundRequestId() throws Exception {
        mockMvc.perform(get("/ping").header(RequestIdHolder.HEADER_NAME, "bad id with spaces"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdHolder.HEADER_NAME,
                        org.hamcrest.Matchers.not("bad id with spaces")));
    }

    @Test
    void shouldClearMdcAfterRequest() throws Exception {
        mockMvc.perform(get("/ping")).andExpect(status().isOk());

        assertThat(MDC.get(RequestIdHolder.MDC_KEY)).isNull();
    }

    @RestController
    static class EchoController {
        @GetMapping("/ping")
        String ping() {
            return "pong";
        }
    }
}
