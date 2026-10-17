package com.repopilot.common.health.probe;

import com.repopilot.common.health.ComponentHealth;
import com.repopilot.common.health.HealthProbe;
import com.repopilot.common.health.HealthStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.concurrent.TimeUnit;

/**
 * MySQL 连通性探测。
 *
 * <p>使用连接池获取真实连接并调用 {@link Connection#isValid(int)}，
 * 而不是仅检查配置或缓存的连接对象。
 */
@Component
@Order(10)
public class MySqlHealthProbe implements HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(MySqlHealthProbe.class);
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    public MySqlHealthProbe(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public boolean required() {
        return true;
    }

    @Override
    public ComponentHealth probe() {
        long start = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
                return new ComponentHealth(name(), HealthStatus.DOWN, required(), "连接校验未通过", elapsedMillis(start));
            }
            DatabaseMetaData metaData = connection.getMetaData();
            String detail = "%s %s, schema=%s".formatted(
                    metaData.getDatabaseProductName(),
                    metaData.getDatabaseProductVersion(),
                    connection.getCatalog());
            return new ComponentHealth(name(), HealthStatus.UP, required(), detail, elapsedMillis(start));
        } catch (Exception ex) {
            log.warn("MySQL 健康检查失败: {}", ex.getMessage());
            return new ComponentHealth(name(), HealthStatus.DOWN, required(), summarize(ex), elapsedMillis(start));
        }
    }

    private long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private String summarize(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
