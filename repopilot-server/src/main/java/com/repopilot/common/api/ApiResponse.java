package com.repopilot.common.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 统一 API 响应结构。
 *
 * <p>所有对外接口（含异常分支）均返回该结构，便于前端与自动化客户端统一解析：
 * <pre>
 * {
 *   "code": "OK",
 *   "message": "OK",
 *   "data": { ... },
 *   "errors": [ { "field": "name", "message": "不能为空" } ],
 *   "requestId": "6f1b9c0e...",
 *   "timestamp": "2026-10-17T04:12:31.512Z"
 * }
 * </pre>
 *
 * <p>约定：
 * <ul>
 *   <li>{@code code} 为稳定的业务/错误码字符串（{@link ErrorCode}），不做本地化。</li>
 *   <li>{@code message} 为可直接展示给开发者的简短描述，不包含堆栈或 SQL 细节。</li>
 *   <li>{@code requestId} 用于串联日志，取自 {@code X-Request-Id} 请求头或服务端生成。</li>
 *   <li>{@code timestamp} 统一使用 UTC（{@link Instant}）。</li>
 * </ul>
 *
 * @param code      响应码
 * @param message   描述
 * @param data      业务数据，异常时为 null
 * @param errors    字段级校验错误，非校验异常时为 null
 * @param requestId 请求追踪 ID
 * @param timestamp 服务端响应时间（UTC）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        String code,
        String message,
        T data,
        List<FieldViolation> errors,
        String requestId,
        Instant timestamp
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.name(), ErrorCode.OK.defaultMessage(), data, null,
                RequestIdHolder.current(), Instant.now());
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message) {
        return failure(errorCode, message, null);
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message, List<FieldViolation> errors) {
        return new ApiResponse<>(errorCode.name(), message, null, errors,
                RequestIdHolder.current(), Instant.now());
    }

    /** 便于测试与文档示例：判断是否为成功响应（不参与 JSON 序列化，保持响应结构稳定）。 */
    @JsonIgnore
    public boolean isSuccess() {
        return ErrorCode.OK.name().equals(code);
    }
}
