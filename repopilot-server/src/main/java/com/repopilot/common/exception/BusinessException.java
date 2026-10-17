package com.repopilot.common.exception;

import com.repopilot.common.api.ErrorCode;

/**
 * 可预期的业务异常。
 *
 * <p>仅在业务规则被违反时抛出，由 {@code GlobalExceptionHandler} 转换为统一响应。
 * 不用于包装基础设施故障：数据库/消息队列不可用应抛出框架异常，最终落到 500/503。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(String message) {
        this(ErrorCode.BUSINESS_ERROR, message);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
