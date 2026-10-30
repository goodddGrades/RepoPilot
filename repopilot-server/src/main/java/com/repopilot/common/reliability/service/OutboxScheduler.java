package com.repopilot.common.reliability.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p><b>为什么提供禁用开关</b>：这个调度器以 1 秒节拍扫描全表并发布 PENDING 事件。
 * 集成测试里它会造成**跨用例的干扰**——它会抢走别的用例刚插入的 PENDING 记录
 * 并发布掉，于是"这条事件还没被发布"的断言偶发失败，
 * 而单独跑那个用例却通过。
 * <b>这种"混跑失败、单跑通过"的失败模式最难查</b>：它看起来像产品缺陷，
 * 实际是测试环境里有个后台线程在改被测对象。
 * 集成测试必须能把它关掉，让每个用例只看到自己造的数据。
 */
@Component
public class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxPublisherService publisher;
    private final OutboxEventMapper outbox;
    private final MeterRegistry registry;

    private Counter publishedCounter;
    private Counter failedRoundCounter;
    private Counter publishFailureCounter;
    private Counter deadLetteredCounter;

    /** 发布循环是否启用。集成测试关掉它，避免抢走别的用例造的数据。 */
    private final boolean schedulerEnabled;

    public OutboxScheduler(OutboxPublisherService publisher,
                           OutboxEventMapper outbox,
                           MeterRegistry registry,
                           @Value("${app.repopilot.outbox.scheduler-enabled:true}")
                           boolean schedulerEnabled) {
        this.publisher = publisher;
        this.outbox = outbox;
        this.registry = registry;
        this.schedulerEnabled = schedulerEnabled;
    }

    @PostConstruct
    void registerMetrics() {
        this.publishedCounter = Counter.builder("repopilot.outbox.published")
                .description("确认成功并标记 PUBLISHED 的事件数").register(registry);
        this.failedRoundCounter = Counter.builder("repopilot.outbox.scan.errors")
                .description("发布循环中出现异常的轮次").register(registry);
        // TASK-013：发布失败次数此前无法观测——只有日志。
        // "发不出去"和"发得慢"是两种不同的故障，必须能分别告警。
        this.publishFailureCounter = Counter.builder("repopilot.outbox.publish.failed")
                .description("单条事件发布未成功（不可路由/连接故障/confirm 超时）").register(registry);
        // 重试耗尽进FAILED：这类事件永不自动清理，等人工介入。
        this.deadLetteredCounter = Counter.builder("repopilot.outbox.exhausted")
                .description("重试达到上限被置 FAILED 的事件数：需要人工重新投递")
                .register(registry);
        Gauge.builder("repopilot.outbox.pending",
                        outbox, m -> m.countStalePending(java.time.Instant.now()))
                .description("仍待发布的事件数（MQ 不可用时会持续增长）")
                .register(registry);
        Gauge.builder("repopilot.outbox.oldest.pending.age.seconds", this,
                        OutboxScheduler::oldestPendingAgeSeconds)
                .description("最老的未发布事件已积压的秒数：发布链路卡住的直接信号")
                .register(registry);
        Gauge.builder("repopilot.outbox.failed", outbox,
                        m -> m.countByStatus("FAILED").stream()
                                .mapToLong(c -> c.total).sum())
                .description("FAILED 事件存量：永不自动清理")
                .register(registry);
        // 让发布器把每条事件的终局结果回报给指标（只观测，不参与决策）
        publisher.setObserver(this::recordPublishResult);
    }

    /** 由发布器在每条事件得出终局结果时回调，供指标计数。 */
    void recordPublishResult(boolean success, boolean exhausted) {
        if (success) {
            return;
        }
        publishFailureCounter.increment();
        if (exhausted) {
            deadLetteredCounter.increment();
        }
    }

    /**
     * 一轮发布。
     *
     * <p>固定间隔而不是固定延迟：无论上一轮快慢都按节拍走，
     * 慢一轮不会让后续节奏整体漂移。
     *
     * <p><b>禁用开关</b>：集成测试用 {@code app.repopilot.outbox.scheduler-enabled=false}
     * 关掉这个循环。理由见类注释：它会抢走别的用例的 PENDING 记录，
     * 让"这条事件还没被发布"的断言变得不确定。
     * 指标注册不受影响——关掉循环不等于关掉观测。
     */
    @Scheduled(fixedDelayString = "${app.repopilot.outbox.poll-interval-ms:1000}")
    public void publishRound() {
        if (!schedulerEnabled) {
            return;
        }
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
