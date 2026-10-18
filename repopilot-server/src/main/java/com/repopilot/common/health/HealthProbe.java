package com.repopilot.common.health;

/**
 * 依赖组件探测接口。
 *
 * <p>实现类必须自行捕获异常并转换为 {@link HealthStatus#DOWN}，
 * 不允许让探测异常扩散到接口层。
 */
public interface HealthProbe {

    /** 组件名，用于响应与日志。 */
    String name();

    /** 是否为必需组件：必需组件 DOWN 时整体状态为 DOWN。 */
    boolean required();

    /** 执行一次真实探测。 */
    ComponentHealth probe();
}
