package com.repopilot.common.health.probe;

import com.repopilot.common.health.ComponentHealth;
import com.repopilot.common.health.HealthProbe;
import com.repopilot.common.health.HealthStatus;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Flyway 迁移状态探测。
 *
 * <p>存在未执行的迁移（PENDING）视为 DOWN：说明应用启动未能把库结构推进到预期版本，
 * 此时继续对外提供服务会产生不可预期的数据问题。
 */
@Component
@Order(30)
public class FlywayHealthProbe implements HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(FlywayHealthProbe.class);

    private final ObjectProvider<Flyway> flywayProvider;

    public FlywayHealthProbe(ObjectProvider<Flyway> flywayProvider) {
        this.flywayProvider = flywayProvider;
    }

    @Override
    public String name() {
        return "flyway";
    }

    @Override
    public boolean required() {
        return true;
    }

    @Override
    public ComponentHealth probe() {
        long start = System.nanoTime();
        Flyway flyway = flywayProvider.getIfAvailable();
        if (flyway == null) {
            return new ComponentHealth(name(), HealthStatus.UNKNOWN, required(),
                    "未配置 Flyway", elapsedMillis(start));
        }
        try {
            MigrationInfoService info = flyway.info();
            MigrationInfo[] all = info.all();
            // Flyway 11 的 MigrationState 未提供 isPending()，直接比较枚举常量
            long pending = Arrays.stream(all)
                    .map(MigrationInfo::getState)
                    .filter(Objects::nonNull)
                    .filter(state -> state == MigrationState.PENDING || state == MigrationState.ABOVE_TARGET)
                    .count();
            String currentVersion = info.current() == null || info.current().getVersion() == null
                    ? "none"
                    : info.current().getVersion().toString();
            String detail = "current=%s, applied=%d, pending=%d".formatted(currentVersion, all.length, pending);
            HealthStatus status = pending > 0 ? HealthStatus.DOWN : HealthStatus.UP;
            return new ComponentHealth(name(), status, required(), detail, elapsedMillis(start));
        } catch (Exception ex) {
            log.warn("Flyway 健康检查失败: {}", ex.getMessage());
            return new ComponentHealth(name(), HealthStatus.DOWN, required(), ProbeDetails.of(ex), elapsedMillis(start));
        }
    }

    private long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

}
