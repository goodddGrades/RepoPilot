package com.repopilot.common.exception;

import com.repopilot.common.api.ErrorCode;

/**
 * 可预期的业务异常。
 *
 * <p>仅在业务规则被违反时抛出，由 {@code GlobalExceptionHandler} 转换为统一响应。
 * 不用于包装基础设施故障：数据库/消息队列不可用应抛出框架异常，最终落到 500/503。
 *
 * <p>不再提供"默认 409 业务错误"的便捷构造器：409 必须由调用方选定具体错误码
 * （重复资源、幂等冲突、非法状态迁移、过期尝试、乐观锁冲突），否则监控无法区分原因。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
