package com.repopilot.common.api;

import org.springframework.http.HttpStatus;

/**
 * 统一错误码。
 *
 * <p>取值与 HTTP 映射以 {@code docs/architecture/04-api-contract.md} 的错误码表为准。
 * 仅包含工程级通用错误码；业务错误码在后续 TASK 中按模块扩展
 * （例如 {@code AGENT_NOT_FOUND}），但必须保持 {@code HTTP 状态码 + 稳定字符串码} 的组合。
 *
 * <p>409 必须使用具体码（{@code RESOURCE_CONFLICT} / {@code IDEMPOTENCY_CONFLICT} /
 * {@code INVALID_STATE_TRANSITION} / {@code STALE_ATTEMPT} / {@code OPTIMISTIC_LOCK_CONFLICT}），
 * 不再保留可以兜底一切的 {@code BUSINESS_ERROR}：泛化冲突码会让调用方无法区分"重复提交"
 * 与"状态机不允许"，也会掩盖真正需要修复的数据问题。
 */
public enum ErrorCode {

    OK(HttpStatus.OK, "success"),

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "请求格式不正确"),
    UNSUPPORTED_EVENT_VERSION(HttpStatus.BAD_REQUEST, "事件信封版本不受支持"),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "未认证"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "无权限执行该操作"),

    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "请求的资源不存在"),

    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不被支持"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的请求内容类型"),

    RESOURCE_CONFLICT(HttpStatus.CONFLICT, "资源冲突或已存在"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "幂等键已用于不同请求"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "当前状态不允许该操作"),
    STALE_ATTEMPT(HttpStatus.CONFLICT, "执行尝试已过期或被其他尝试取代"),
    OPTIMISTIC_LOCK_CONFLICT(HttpStatus.CONFLICT, "并发修改冲突，请重试"),

    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "请求内容过大"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "依赖的基础设施不可用");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
