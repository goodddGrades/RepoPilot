package com.repopilot.common.health;

/**
 * 依赖组件健康状态。
 */
public enum HealthStatus {

    /** 组件可用。 */
    UP,

    /** 必需组件不可用，服务不具备完整能力。 */
    DOWN,

    /** 可选组件降级：核心链路仍可用。 */
    DEGRADED,

    /** 未接入 / 无法判定（例如 V1 预留但尚未使用的 Redis）。 */
    UNKNOWN
}
