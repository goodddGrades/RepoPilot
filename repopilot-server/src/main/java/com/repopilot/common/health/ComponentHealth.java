package com.repopilot.common.health;

/**
 * 单个依赖组件的检查结果。
 *
 * @param name      组件名，例如 {@code mysql} / {@code rabbitmq} / {@code flyway} / {@code redis}
 * @param status    状态
 * @param required  是否为本服务的必需组件
 * @param detail    可读说明；失败时为异常摘要（不含堆栈与凭据）
 * @param latencyMs 本次探测耗时（毫秒）
 */
public record ComponentHealth(
        String name,
        HealthStatus status,
        boolean required,
        String detail,
        long latencyMs
) {
}
