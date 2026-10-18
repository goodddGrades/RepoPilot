package com.repopilot.common.web;

import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.api.FieldViolation;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.jsonl.JsonlParseException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理器：把各类异常收敛为统一响应结构。
 *
 * <p>安全约束：
 * <ul>
 *   <li>响应体不返回堆栈、SQL 或内部类名；</li>
 *   <li>5xx 记录完整堆栈，4xx 只记录简要信息；</li>
 *   <li>未知异常统一返回 {@link ErrorCode#INTERNAL_ERROR}，绝不吞掉异常。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        List<FieldViolation> errors = ex.getErrors().isEmpty() ? null : ex.getErrors();
        log.warn("业务异常: code={}, message={}, fields={}", ex.getErrorCode(), ex.getMessage(),
                errors == null ? "-" : errors);
        return build(ex.getErrorCode(), ex.getMessage(), errors);
    }

    /**
     * 文件级解析失败（超限、编码错、单行过长）。
     *
     * <p>这类异常在写库之前抛出，因此响应错误即代表"库内零写入"。
     */
    @ExceptionHandler(JsonlParseException.class)
    public ResponseEntity<ApiResponse<Void>> handleJsonlParse(JsonlParseException ex) {
        log.warn("导入文件不可接受: code={}, field={}, message={}",
                ex.getErrorCode(), ex.getField(), ex.getMessage());
        return build(ex.getErrorCode(), ex.getMessage(),
                List.of(new FieldViolation(ex.getField(), ex.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toViolation)
                .toList();
        log.warn("参数校验失败: {}", violations);
        return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(this::toViolation)
                .collect(Collectors.toList());
        log.warn("参数校验失败: {}", violations);
        return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), violations);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体无法解析: {}", ex.getMostSpecificCause().getMessage());
        return build(ErrorCode.BAD_REQUEST, "请求体格式不正确", null);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex) {
        log.warn("请求参数不合法: {}", ex.getMessage());
        return build(ErrorCode.BAD_REQUEST, ErrorCode.BAD_REQUEST.defaultMessage(), null);
    }

    @ExceptionHandler({NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        log.warn("资源不存在: {}", ex.getResourcePath());
        return build(ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND.defaultMessage(), null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("请求方法不支持: {}", ex.getMessage());
        return build(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.defaultMessage(), null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.warn("内容类型不支持: {}", ex.getMessage());
        return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE.defaultMessage(), null);
    }

    /**
     * 唯一键冲突：重复业务键、重复幂等键、重复批次编号都走这里。
     *
     * <p>不把驱动原文透出：MySQL 的 1062 报文包含表名与索引名，属于实现细节；
     * 调用方拿到稳定的 409 与资源冲突语义即可，具体原因查服务端日志。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateKey(DuplicateKeyException ex) {
        log.warn("唯一约束冲突: {}", rootMessage(ex));
        return build(ErrorCode.RESOURCE_CONFLICT, "资源已存在或违反唯一约束", null);
    }

    /**
     * 其余完整性约束失败（外键、CHECK）。
     *
     * <p>这类错误通常意味着调用链漏了校验，因此记 warn 而不是 error：它是可预期的输入问题，
     * 不是服务故障。响应同样不包含 SQL 与约束名。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("数据完整性约束失败: {}", rootMessage(ex));
        return build(ErrorCode.VALIDATION_ERROR, "数据不满足完整性约束", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("未处理异常", ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), null);
    }

    private String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    private FieldViolation toViolation(FieldError fieldError) {
        return new FieldViolation(fieldError.getField(), fieldError.getDefaultMessage());
    }

    private FieldViolation toViolation(ConstraintViolation<?> violation) {
        return new FieldViolation(violation.getPropertyPath().toString(), violation.getMessage());
    }

    private ResponseEntity<ApiResponse<Void>> build(ErrorCode code, String message, List<FieldViolation> errors) {
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.failure(code, message, errors));
    }
}
