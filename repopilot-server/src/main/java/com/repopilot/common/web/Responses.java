package com.repopilot.common.web;

import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.idempotency.IdempotentResult;
import org.springframework.http.ResponseEntity;

/** 把服务层结果包成统一信封；状态码来自首次执行结果（重放时保持原状态码）。 */
public final class Responses {

    private Responses() {
    }

    public static <T> ResponseEntity<ApiResponse<T>> of(IdempotentResult<T> result) {
        return ResponseEntity.status(result.httpStatus()).body(ApiResponse.ok(result.data()));
    }

    public static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    public static <T> ResponseEntity<ApiResponse<T>> created(T data) {
        return ResponseEntity.status(201).body(ApiResponse.ok(data));
    }
}
