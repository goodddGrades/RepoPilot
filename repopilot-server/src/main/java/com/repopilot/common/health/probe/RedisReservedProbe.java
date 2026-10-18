package com.repopilot.common.health.probe;

import com.repopilot.common.health.ComponentHealth;
import com.repopilot.common.health.HealthProbe;
import com.repopilot.common.health.HealthStatus;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Redis 预留探测。
 *
 * <p>V1 不引入 Redis 客户端，也不承载任何业务逻辑，因此这里显式返回
 * {@link HealthStatus#UNKNOWN} 并注明原因——不使用伪造的 UP 状态。
 */
@Component
@Order(100)
public class RedisReservedProbe implements HealthProbe {

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    public ComponentHealth probe() {
        return new ComponentHealth(name(), HealthStatus.UNKNOWN, required(),
                "V1 预留：容器已编排，但服务端尚未接入 Redis 客户端", 0L);
    }
}
