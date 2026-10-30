package com.repopilot.modules.experiment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 后台推进器：把 PENDING 槽位推到 QUEUED，并回收过期租约。
 *
 * <p>为什么必须有它：实验提交事务提交后、领取发生前，如果进程崩溃，
 * 内存里"已经可以领取"的假设就消失了；只有数据库状态 + 周期性扫描能让同一批 Run 继续往前走。
 *
 * <p>扫描异常只记录不抛出：一次数据库抖动不应该让调度线程永久停止（Spring 的
 * {@code fixedDelay} 会在异常后继续，但显式捕获能给出可读日志）。
 */
@Component
@ConditionalOnProperty(name = "repopilot.worker.scheduler-enabled", havingValue = "true",
        matchIfMissing = true)
public class RunMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(RunMaintenanceScheduler.class);

    private final WorkService workService;

    public RunMaintenanceScheduler(WorkService workService) {
        this.workService = workService;
    }

    @Scheduled(fixedDelayString = "${repopilot.worker.scan-interval-ms:2000}")
    public void tick() {
        try {
            WorkService.MaintenanceResult result = workService.maintain();
            if (result.promotedPending() > 0 || result.promotedRetries() > 0
                    || result.reaped() > 0 || result.reapedJobs() > 0) {
                log.info("执行扫描：推进 {} 个 PENDING、{} 个重试，回收 {} 个过期执行 Attempt、{} 个过期评测 Attempt",
                        result.promotedPending(), result.promotedRetries(), result.reaped(),
                        result.reapedJobs());
            }
        } catch (RuntimeException ex) {
            log.warn("执行扫描失败，将在下一轮重试: {}", ex.getMessage(), ex);
        }
    }
}
