package com.repopilot.common.api;

import org.springframework.http.HttpStatus;

/**
 * 统一错误码。
 *
 * <p>仅包含工程级通用错误码；业务错误码在后续 TASK 中按模块扩展
 * （例如 {@code AGENT_NOT_FOUND}），但必须保持 {@code HTTP 状态码 + 稳定字符串码} 的组合。
 */
public enum ErrorCode {

    OK(HttpStatus.OK, "OK"),

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "请求格式不正确"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不被支持"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的请求内容类型"),

    BUSINESS_ERROR(HttpStatus.CONFLICT, "业务规则校验失败"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误"),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "依赖的基础设施不可用");

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
