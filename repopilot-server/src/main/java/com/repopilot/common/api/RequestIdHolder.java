package com.repopilot.common.api;

import org.slf4j.MDC;

/**
 * 请求追踪 ID 的上下文读写工具。
 *
 * <p>由 {@code RequestIdFilter} 在请求进入时写入 MDC，在响应结束时清理。
 * 日志模板（logback-spring.xml）与响应体统一从这里取值。
 */
public final class RequestIdHolder {

    /** 请求头名称，用于外部链路透传。 */
    public static final String HEADER_NAME = "X-Request-Id";

    /** MDC 键名。 */
    public static final String MDC_KEY = "requestId";

    private RequestIdHolder() {
    }

    /** 返回当前线程的请求 ID；异步或非 Web 线程下可能为 null。 */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
