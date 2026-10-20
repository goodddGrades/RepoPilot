package com.repopilot.common.health.probe;

import com.repopilot.common.health.ComponentHealth;
import com.repopilot.common.health.HealthProbe;
import com.repopilot.common.health.HealthStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Redis 连通性探测。
 *
 * <p>TASK-014 起 Redis 不再是"仅编排的预留实例"：它是 API 入口限流的共享状态存储。
 * 这个探针因此从 {@code RedisReservedProbe}（固定 UNKNOWN）换成真实 PING——
 * 继续返回 UNKNOWN 会让"Redis 挂了，限流正在降级"这件事在健康页上完全不可见。
 *
 * <p>为什么 {@code required=false}：入口限流默认 {@code FAIL_OPEN}，
 * Redis 不可用时业务写入仍可进行（只是失去节流）。把它标成必需会让整个服务
 * 因一个非核心依赖变 DOWN，触发不必要的重启或摘流。
 * 真实状态通过 {@code DEGRADED} 与明细文案表达，不隐瞒。
 */
@Component
@Order(100)
public class RedisHealthProbe implements HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(RedisHealthProbe.class);
    private static final int TIMEOUT_MILLIS = 1000;

    private final StringRedisTemplate redis;

    public RedisHealthProbe(StringRedisTemplate redis) {
        this.redis = redis;
    }

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
        long start = System.nanoTime();
        try {
            String pong = redis.execute((RedisCallback<String>) RedisConnection::ping);
            if (!"PONG".equalsIgnoreCase(pong)) {
                return new ComponentHealth(name(), HealthStatus.DOWN, required(),
                        "PING 返回异常: " + pong, elapsedMillis(start));
            }
            return new ComponentHealth(name(), HealthStatus.UP, required(),
                    "PING PONG, 用途=分布式限流, " + version(), elapsedMillis(start));
        } catch (Exception ex) {
            log.warn("Redis 健康检查失败: {}", ex.getMessage());
            return new ComponentHealth(name(), HealthStatus.DOWN, required(),
                    ProbeDetails.of(ex), elapsedMillis(start));
        }
    }

    /** 版本不是必需信息：拿不到就标注未知，但不因此把 UP 降级。 */
    private String version() {
        try {
            Properties info = redis.execute((RedisCallback<Properties>) connection ->
                    connection.serverCommands().info("server"));
            String version = info == null ? null : info.getProperty("redis_version");
            return "version=" + (version == null ? "unknown" : version);
        } catch (Exception ex) {
            return "version=unknown";
        }
    }

    private long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
