package com.repopilot.common.health.probe;

import com.repopilot.common.health.ComponentHealth;
import com.repopilot.common.health.HealthProbe;
import com.repopilot.common.health.HealthStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * RabbitMQ 连通性探测。
 *
 * <p>真实建立一次 AMQP 连接并立即关闭，用于验证账号、vhost 与网络可达性。
 * 本阶段不声明任何交换机/队列，避免提前固化业务拓扑。
 */
@Component
@Order(20)
public class RabbitMqHealthProbe implements HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqHealthProbe.class);

    private final ConnectionFactory connectionFactory;

    public RabbitMqHealthProbe(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public String name() {
        return "rabbitmq";
    }

    @Override
    public boolean required() {
        return true;
    }

    @Override
    public ComponentHealth probe() {
        long start = System.nanoTime();
        Connection connection = null;
        try {
            connection = connectionFactory.createConnection();
            Map<String, Object> serverProperties = connection.getDelegate().getServerProperties();
            Object brokerVersion = serverProperties == null ? null : serverProperties.get("version");
            String detail = "broker=%s, vhost=%s".formatted(
                    brokerVersion == null ? "unknown" : brokerVersion.toString(),
                    connectionFactory.getVirtualHost());
            return new ComponentHealth(name(), HealthStatus.UP, required(), detail, elapsedMillis(start));
        } catch (Exception ex) {
            log.warn("RabbitMQ 健康检查失败: {}", ex.getMessage());
            return new ComponentHealth(name(), HealthStatus.DOWN, required(), summarize(ex), elapsedMillis(start));
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception closeEx) {
                    log.debug("关闭 RabbitMQ 健康检查连接时出错: {}", closeEx.getMessage());
                }
            }
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
