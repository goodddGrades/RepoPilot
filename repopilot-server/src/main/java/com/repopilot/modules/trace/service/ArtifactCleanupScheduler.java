package com.repopilot.modules.trace.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 孤儿 Artifact 的周期清理。
 *
 * <p>默认每小时一次、宽限期 24 小时。集成测试里关掉（用例直接调用服务方法），
 * 避免后台线程在断言过程中改动存储内容。
 */
@Component
@ConditionalOnProperty(name = "repopilot.artifact.cleanup.enabled", havingValue = "true",
        matchIfMissing = true)
public class ArtifactCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ArtifactCleanupScheduler.class);

    private final ArtifactMaintenanceService maintenance;
    private final Duration grace;

    public ArtifactCleanupScheduler(
            ArtifactMaintenanceService maintenance,
            @Value("${repopilot.artifact.cleanup.grace-seconds:86400}") long graceSeconds) {
        this.maintenance = maintenance;
        this.grace = Duration.ofSeconds(graceSeconds);
    }

    @Scheduled(fixedDelayString = "${repopilot.artifact.cleanup.interval-ms:3600000}",
            initialDelayString = "${repopilot.artifact.cleanup.initial-delay-ms:600000}")
    public void tick() {
        try {
            int deleted = maintenance.deleteOrphanObjects(Instant.now().minus(grace));
            if (deleted > 0) {
                log.info("Artifact 清理：删除 {} 个无引用对象（宽限期 {}）", deleted, grace);
            }
        } catch (RuntimeException ex) {
            // 一次失败不能让调度线程停摆；下一轮继续
            log.warn("Artifact 清理失败，将在下一轮重试: {}", ex.getMessage(), ex);
        }
    }
}
