package com.repopilot.common.web;

import com.repopilot.common.api.ApiResponse;
import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.api.FieldViolation;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.jsonl.JsonlParseException;
import com.repopilot.common.ratelimit.RateLimitDecision;
import com.repopilot.common.ratelimit.RateLimitUnavailableException;
import com.repopilot.common.ratelimit.RateLimitedException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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
     * 超过限流阈值（HTTP 429）。
     *
     * <p>必须排在 {@link BusinessException} 之前被选中（Spring 按异常类型最具体者优先），
     * 因为这里要多做两件通用处理器做不到的事：
     * <ul>
     *   <li>写入 {@code Retry-After}：值来自 Redis 里真实的令牌缺口与补充速率，
     *       不是拍脑袋的常数；</li>
     *   <li>把剩余额度明确置 0，客户端不必猜测。</li>
     * </ul>
     */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimited(RateLimitedException ex) {
        RateLimitDecision decision = ex.decision();
        log.warn("触发限流: policy={}, capacity={}, retryAfter={}s",
                decision.policy(), decision.capacity(), ex.retryAfterSeconds());
        return ResponseEntity.status(ErrorCode.RATE_LIMITED.httpStatus())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
                .header("X-RateLimit-Policy", decision.policy())
                .header("X-RateLimit-Limit", Long.toString(decision.capacity()))
                .header("X-RateLimit-Remaining", "0")
                .body(ApiResponse.failure(ErrorCode.RATE_LIMITED, ex.getMessage()));
    }

    /**
     * 限流依赖（Redis）不可用，且策略要求失败关闭（HTTP 503）。
     *
     * <p>刻意不使用 429：429 的语义是"你请求太快，慢点再来"，
     * 而这里客户端并没有超速——是服务端自己的依赖坏了。用 429 会让调用方
     * 误以为要加长退避，也会让监控把基础设施故障统计成业务限流。
     */
    @ExceptionHandler(RateLimitUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimitUnavailable(RateLimitUnavailableException ex) {
        log.error("限流依赖不可用，策略 {} 配置为 FAIL_CLOSED，请求被拒绝", ex.policy());
        return ResponseEntity.status(ErrorCode.SERVICE_UNAVAILABLE.httpStatus())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ApiResponse.failure(ErrorCode.SERVICE_UNAVAILABLE,
                        "限流依赖暂不可用，本次请求未被受理，请稍后重试"));
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

    /**
     * 上传超过容器上限。
     *
     * <p>必须在读取请求体阶段就转成 413：契约里"超过 20 MiB 整批拒绝且不写入任何数据"，
     * 若落到 {@code INTERNAL_ERROR}，调用方会以为服务出错并重试，白传一遍大文件。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("上传内容超过上限: {}", ex.getMessage());
        return build(ErrorCode.PAYLOAD_TOO_LARGE, "上传文件超过大小上限", null);
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
