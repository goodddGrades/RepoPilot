package com.repopilot.common.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void okShouldCarryPayloadAndSuccessCode() {
        MDC.put(RequestIdHolder.MDC_KEY, "req-123");

        ApiResponse<String> response = ApiResponse.ok("payload");

        assertThat(response.code()).isEqualTo("OK");
        assertThat(response.message()).isEqualTo("success");
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.data()).isEqualTo("payload");
        assertThat(response.errors()).isNull();
        assertThat(response.requestId()).isEqualTo("req-123");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void failureShouldCarryErrorCodeAndViolations() {
        MDC.put(RequestIdHolder.MDC_KEY, "req-456");

        ApiResponse<Void> response = ApiResponse.failure(
                ErrorCode.VALIDATION_ERROR,
                "请求参数校验失败",
                List.of(new FieldViolation("name", "不能为空")));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.data()).isNull();
        assertThat(response.errors()).containsExactly(new FieldViolation("name", "不能为空"));
        assertThat(response.requestId()).isEqualTo("req-456");
    }

    @Test
    void requestIdShouldBeNullOutsideWebRequest() {
        ApiResponse<String> response = ApiResponse.ok("x");

        assertThat(response.requestId()).isNull();
    }

    @Test
    void errorCodeShouldExposeHttpStatus() {
        assertThat(ErrorCode.OK.httpStatus().value()).isEqualTo(200);
        assertThat(ErrorCode.VALIDATION_ERROR.httpStatus().value()).isEqualTo(400);
        assertThat(ErrorCode.NOT_FOUND.httpStatus().value()).isEqualTo(404);
        assertThat(ErrorCode.INTERNAL_ERROR.httpStatus().value()).isEqualTo(500);
        assertThat(ErrorCode.DEPENDENCY_UNAVAILABLE.httpStatus().value()).isEqualTo(503);
    }
}
