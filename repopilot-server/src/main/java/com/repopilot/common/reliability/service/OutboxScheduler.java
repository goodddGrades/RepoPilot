package com.repopilot.common.reliability.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import com.repopilot.common.reliability.mapper.OutboxEventMapper;
import org.springframework.stereotype.Component;

/**
 * Outbox 发布循环与积压指标（TASK-011）。
 *
 * <p><b>这是"数据库扫描恢复通道"的实际执行体</b>：MQ 不可用时事件仍留在
 * {@code outbox_events} 的 PENDING 里，broker 恢复后被这里自然补发。
 * 因此 MQ 从"必需的依赖"降级为"加速器"——它挂了，业务照跑，只是慢。
 *
 * <p>指标只暴露运维真正会看的三件事：发了多少、还剩多少、最老的那条积压多久。
 * 不暴露"最近一次发布耗时"这类没人据此行动的数值。
 */
@Component
public class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxPublisherService publisher;
    private final OutboxEventMapper outbox;
    private final MeterRegistry registry;

    private Counter publishedCounter;
    private Counter failedRoundCounter;

    public OutboxScheduler(OutboxPublisherService publisher,
                           OutboxEventMapper outbox,
                           MeterRegistry registry) {
        this.publisher = publisher;
        this.outbox = outbox;
        this.registry = registry;
    }

    @PostConstruct
    void registerMetrics() {
        this.publishedCounter = Counter.builder("repopilot.outbox.published")
                .description("确认成功并标记 PUBLISHED 的事件数").register(registry);
        this.failedRoundCounter = Counter.builder("repopilot.outbox.scan.errors")
                .description("发布循环中出现异常的轮次").register(registry);
        Gauge.builder("repopilot.outbox.pending",
                        outbox, m -> m.countStalePending(java.time.Instant.now()))
                .description("仍待发布的事件数（MQ 不可用时会持续增长）")
                .register(registry);
        Gauge.builder("repopilot.outbox.oldest.pending.age.seconds", this,
                        OutboxScheduler::oldestPendingAgeSeconds)
                .description("最老的未发布事件已积压的秒数：发布链路卡住的直接信号")
                .register(registry);
    }

    /**
     * 一轮发布。
     *
     * <p>固定间隔而不是固定延迟：无论上一轮快慢都按节拍走，
     * 慢一轮不会让后续节奏整体漂移。
     */
    @Scheduled(fixedDelayString = "${app.repopilot.outbox.poll-interval-ms:1000}")
    public void publishRound() {
        try {
            int published = publisher.scanOnce();
            if (published > 0) {
                publishedCounter.increment(published);
            }
        } catch (RuntimeException ex) {
            failedRoundCounter.increment();
            // 记录后继续：一次异常不能让整个发布循环永久停摆，
            // 那会把所有事件卡在 PENDING 而无人察觉
            log.warn("Outbox 发布轮次异常（事件仍保留 PENDING，下轮重试）: {}", ex.getMessage());
        }
    }

    private long oldestPendingAgeSeconds() {
        return outbox.oldestPendingCreatedAt()
                .map(created -> java.time.Duration.between(created, java.time.Instant.now()).toSeconds())
                .orElse(0L);
    }
}
