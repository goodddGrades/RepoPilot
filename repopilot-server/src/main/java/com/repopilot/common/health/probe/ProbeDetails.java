package com.repopilot.common.health.probe;

/**
 * 健康探测的说明文本工具。
 *
 * <p>异常信息可能很长（JDBC 驱动会返回多行报文），直接放进响应体会让 payload 膨胀，
 * 也可能无意间带出连接串等细节。这里统一压成单行并截断长度。
 */
final class ProbeDetails {

    private static final int MAX_LENGTH = 200;

    private ProbeDetails() {
    }

    static String of(Throwable throwable) {
        String message = throwable.getMessage();
        String raw = (message == null || message.isBlank())
                ? throwable.getClass().getSimpleName()
                : message;
        String singleLine = raw.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= MAX_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_LENGTH) + "…";
    }
}
