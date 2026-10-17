package com.repopilot.common.health;

import java.time.Instant;
import java.util.List;

/**
 * 服务健康快照，{@code GET /api/health} 的响应数据体。
 *
 * @param status      聚合状态：全部 UP 为 UP；必需组件 DOWN 为 DOWN；其余为 DEGRADED
 * @param application 应用名
 * @param version     应用版本
 * @param profile     当前激活的 Spring Profile
 * @param timestamp   采集时间（UTC）
 * @param components  各组件检查结果
 */
public record HealthSnapshot(
        HealthStatus status,
        String application,
        String version,
        String profile,
        Instant timestamp,
        List<ComponentHealth> components
) {
}
