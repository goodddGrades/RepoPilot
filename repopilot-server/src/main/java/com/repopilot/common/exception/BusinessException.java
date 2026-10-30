package com.repopilot.common.exception;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.api.FieldViolation;

import java.util.List;

/**
 * 可预期的业务异常。
 *
 * <p>仅在业务规则被违反时抛出，由 {@code GlobalExceptionHandler} 转换为统一响应。
 * 不用于包装基础设施故障：数据库/消息队列不可用应抛出框架异常，最终落到 500/503。
 *
 * <p>不再提供"默认 409 业务错误"的便捷构造器：409 必须由调用方选定具体错误码
 * （重复资源、幂等冲突、非法状态迁移、过期尝试、乐观锁冲突），否则监控无法区分原因。
 *
 * <p>{@link #getErrors()} 携带字段级信息。契约里"400 + field=cases"这类响应
 * 不能只靠 message 表达——前端要靠 {@code field} 定位到具体表单项。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<FieldViolation> errors;

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, List<FieldViolation> errors) {
        super(message);
        this.errorCode = errorCode;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /** 单个字段的校验错误，最常见的用法。 */
    public static BusinessException field(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message,
                List.of(new FieldViolation(field, message)));
    }

    /** 多个字段/行的校验错误，导入场景一次返回全部行级错误。 */
    public static BusinessException fields(ErrorCode errorCode, String message, List<FieldViolation> errors) {
        return new BusinessException(errorCode, message, errors);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public List<FieldViolation> getErrors() {
        return errors;
    }
}
