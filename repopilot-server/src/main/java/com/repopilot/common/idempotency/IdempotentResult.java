package com.repopilot.common.idempotency;

/**
 * 一次幂等请求的结果。
 *
 * @param data       业务数据
 * @param httpStatus 首次执行时的状态码（201/200），重放时原样返回
 * @param replayed   true 表示结果来自回执缓存，本次没有执行写入
 */
public record IdempotentResult<T>(T data, int httpStatus, boolean replayed) {

    public static <T> IdempotentResult<T> created(T data) {
        return new IdempotentResult<>(data, 201, false);
    }

    public static <T> IdempotentResult<T> ok(T data) {
        return new IdempotentResult<>(data, 200, false);
    }
}
