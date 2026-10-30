package com.repopilot.common.reliability.service;

import com.repopilot.common.reliability.mapper.InboxEventMapper;
import com.repopilot.common.reliability.mapper.OutboxEventMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox / Inbox 保留策略与分批清理（TASK-013-D）。
 *
 * <p><b>三条不能违反的规则</b>，每条都对应一类真实事故：
 * <ol>
 *   <li><b>只删"已完成且过期"的</b>。Outbox 只删 {@code status='PUBLISHED'}
 *       且 {@code published_at} 早于保留期的行——PENDING（含待重试、租约在途）
 *       与 FAILED <b>永不自动删除</b>。它们是排障与人工重放的唯一线索，
 *       定时任务把它们清掉等于"故障发生时证据已经没了"。</li>
 *   <li><b>Inbox 的保留期必须显著长于消息可能的最大重投窗口</b>。
 *       Inbox 是幂等闸门：删掉记录后，同一条消息再投一次会被当成新消息
 *       <b>重新执行副作用</b>。所以 Inbox 保留期取 Outbox 的数倍，
 *       并且绝不低于 Outbox 重试上限 × 退避窗口所能覆盖的时间跨度。</li>
 *   <li><b>分批删除</b>。一次性 {@code DELETE} 百万行会持锁数分钟、
 *       撑爆 undo log、阻塞所有写入。必须 {@code LIMIT} 批次 + 短事务，
 *       每批独立提交，让业务流量能插进来。</li>
 * </ol>
 *
 * <p><b>为什么 FAILED 不自动清理</b>：它代表"发布彻底失败、等人处理"。
 * 自动清理会让运维在还没看见时就失去证据。FAILED 的清理只允许显式人工触发
 * （{@link #purgeFailedOutbox}），且要求调用方明确传入时间下界。
 */
@Component
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final OutboxEventMapper outbox;
    private final InboxEventMapper inbox;
    private final TransactionTemplate tx;
    private final int outboxRetentionDays;
    private final int inboxRetentionDays;
    private final int batchSize;
    private final int maxBatchesPerRun;

    private Counter outboxDeleted;
    private Counter inboxDeleted;
    private Counter failedRounds;
    private final AtomicLong lastOutboxDeleted = new AtomicLong();
    private final AtomicLong lastInboxDeleted = new AtomicLong();
    private volatile String lastRunResult = "尚未执行";

    // TASK-013-E：清理任务的最后执行结果。
    //
    // **成功与失败分开记**，不合并成一个布尔：
    // "一直在失败"与"很久没被调度"是两种不同的故障，合并后都变成"不健康"，
    // 排查方向就丢了。合成一个"最后执行时间"也无法区分这两种情况。
    private final AtomicLong lastSuccessEpochSecond = new AtomicLong();
    private final AtomicLong lastFailureEpochSecond = new AtomicLong();
    private final AtomicLong lastRunEpochSecond = new AtomicLong();

    public RetentionService(OutboxEventMapper outbox, InboxEventMapper inbox,
                            PlatformTransactionManager transactionManager,
                            MeterRegistry registry,
                            @Value("${app.repopilot.retention.outbox-days:7}") int outboxRetentionDays,
                            @Value("${app.repopilot.retention.inbox-days:30}") int inboxRetentionDays,
                            @Value("${app.repopilot.retention.batch-size:500}") int batchSize,
                            @Value("${app.repopilot.retention.max-batches-per-run:20}") int maxBatchesPerRun) {
        this.outbox = outbox;
        this.inbox = inbox;
        this.tx = new TransactionTemplate(transactionManager);
        this.outboxRetentionDays = outboxRetentionDays;
        this.inboxRetentionDays = inboxRetentionDays;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;

        // 指标在构造期注册：MeterRegistry 由容器注入，构造器注入保证它一定就绪。
        // 不用 @PostConstruct + 字段持有的写法——两个 @PostConstruct 之间
        // 没有顺序保证，会出现"注册时 registry 还是 null"的偶发失败。
        this.outboxDeleted = Counter.builder("repopilot.retention.outbox.deleted")
                .description("清理任务删除的 Outbox 行数").register(registry);
        this.inboxDeleted = Counter.builder("repopilot.retention.inbox.deleted")
                .description("清理任务删除的 Inbox 行数").register(registry);
        this.failedRounds = Counter.builder("repopilot.retention.errors")
                .description("清理任务出现异常的轮次").register(registry);
        Gauge.builder("repopilot.retention.outbox.published.retained", this,
                        RetentionService::publishedPendingCleanup)
                .description("已发布但仍在保留期内的 Outbox 行数：即将被清理的存量")
                .register(registry);
        Gauge.builder("repopilot.retention.outbox.failed", outbox,
                        m -> m.countByStatus("FAILED").stream()
                                .mapToLong(c -> c.total).sum())
                .description("FAILED 事件数：永不自动清理，等人工处理")
                .register(registry);
        // 清理任务的最后执行结果（TASK-013-E）。
        // 在本类注册而不是由外部类代注册：这三个指标的取值源就在本类的字段里，
        // 放在别的类里会造成"指标注册了但取值永远为 0"的假象。
        Gauge.builder("repopilot.retention.last.success.epoch.seconds", this,
                        RetentionService::lastSuccessEpochSecond)
                .description("保留期清理最后一次成功完成的 epoch 秒：0 表示从未成功")
                .register(registry);
        Gauge.builder("repopilot.retention.last.failure.epoch.seconds", this,
                        RetentionService::lastFailureEpochSecond)
                .description("保留期清理最后一次失败的 epoch 秒：0 表示从未失败")
                .register(registry);
        Gauge.builder("repopilot.retention.last.run.epoch.seconds", this,
                        RetentionService::lastRunEpochSecond)
                .description("保留期清理最后一次执行（无论成败）的 epoch 秒")
                .register(registry);
    }

    private long publishedPendingCleanup() {
        return outbox.countByStatus("PUBLISHED").stream()
                .mapToLong(c -> c.total).sum();
    }

    /**
     * 一轮清理。
     *
     * <p>返回本轮实际删除的行数，供测试断言与运维查看。
     * <b>不抛异常</b>：清理是后台维护任务，一轮失败不影响业务，
     * 下一轮会继续。异常只记指标与日志。
     */
    @Scheduled(fixedDelayString = "${app.repopilot.retention.interval-ms:3600000}",
            initialDelayString = "${app.repopilot.retention.initial-delay-ms:600000}")
    public void purgeExpired() {
        long deletedOutbox = 0;
        long deletedInbox = 0;
        try {
            Instant outboxCutoff = Instant.now().minus(Duration.ofDays(outboxRetentionDays));
            Instant inboxCutoff = Instant.now().minus(Duration.ofDays(inboxRetentionDays));
            deletedOutbox = purgeOutboxInBatches(outboxCutoff);
            deletedInbox = purgeInboxInBatches(inboxCutoff);
            outboxDeleted.increment(deletedOutbox);
            inboxDeleted.increment(deletedInbox);
            this.lastOutboxDeleted.set(deletedOutbox);
            this.lastInboxDeleted.set(deletedInbox);
            this.lastRunResult = "成功：Outbox " + deletedOutbox + " 行，Inbox " + deletedInbox
                    + " 行（截止 " + outboxCutoff + " / " + inboxCutoff + "）";
            long now = Instant.now().getEpochSecond();
            this.lastSuccessEpochSecond.set(now);
            this.lastRunEpochSecond.set(now);
            if (deletedOutbox > 0 || deletedInbox > 0) {
                log.info("保留期清理完成：{}", lastRunResult);
            }
        } catch (RuntimeException ex) {
            failedRounds.increment();
            this.lastRunResult = "失败：" + ex.getMessage();
            long now = Instant.now().getEpochSecond();
            this.lastFailureEpochSecond.set(now);
            this.lastRunEpochSecond.set(now);
            log.warn("保留期清理失败（不影响业务，下轮重试）: {}", ex.getMessage());
        }
    }

    /**
     * 分批删除已发布且过期的 Outbox。
     *
     * <p>每批一个短事务：{@code DELETE ... WHERE status='PUBLISHED' AND
     * published_at < cutoff LIMIT n}。批次之间让出时间，
     * 避免连续大批量删除把行锁连成一片。
     */
    private long purgeOutboxInBatches(Instant cutoff) {
        long total = 0;
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            Integer deleted = tx.execute(status ->
                    outbox.deletePublishedBefore(cutoff, batchSize));
            if (deleted == null || deleted == 0) {
                break;
            }
            total += deleted;
            if (deleted < batchSize) {
                // 最后一批：已删干净，提前退出，避免多跑一次空查询
                break;
            }
        }
        return total;
    }

    /** 分批删除超过去重窗口的 Inbox。 */
    private long purgeInboxInBatches(Instant cutoff) {
        long total = 0;
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            Integer deleted = tx.execute(status ->
                    inbox.deleteOlderThan(cutoff, batchSize));
            if (deleted == null || deleted == 0) {
                break;
            }
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }

    /**
     * 人工清理 FAILED 事件（运维显式调用）。
     *
     * <p><b>默认不提供</b>：FAILED 是"等人处理"的证据，自动或随手清理都会
     * 让故障复盘失去依据。必须显式调用，且要求 {@code failedBefore} 早于
     * 足够长的时间，确保调用者确实看过这些事件。
     */
    public int purgeFailedOutbox(Instant failedBefore) {
        if (failedBefore == null) {
            throw new IllegalArgumentException("必须指定清理时间下界，避免误删未处理的失败事件");
        }
        int deleted = tx.execute(status -> outbox.deleteFailedBefore(failedBefore, batchSize));
        log.warn("人工清理 FAILED 事件 {} 行（早于 {}）", deleted, failedBefore);
        return deleted;
    }

    /** 运维查询：各状态事件数 + 保留期配置。 */
    public RetentionStatus status() {
        return new RetentionStatus(
                outbox.countByStatus(null),
                outboxRetentionDays,
                inboxRetentionDays,
                batchSize,
                lastOutboxDeleted.get(),
                lastInboxDeleted.get(),
                lastRunResult);
    }

    /** 最后一次成功清理的 epoch 秒；0 表示从未成功过。 */
    public long lastSuccessEpochSecond() {
        return lastSuccessEpochSecond.get();
    }

    /** 最后一次失败清理的 epoch 秒；0 表示从未失败过。 */
    public long lastFailureEpochSecond() {
        return lastFailureEpochSecond.get();
    }

    /** 最后一次执行清理的 epoch 秒（无论成败）；0 表示从未执行过。 */
    public long lastRunEpochSecond() {
        return lastRunEpochSecond.get();
    }

    public record RetentionStatus(List<OutboxEventMapper.StatusCount> outboxByStatus,
                                  int outboxRetentionDays,
                                  int inboxRetentionDays,
                                  int batchSize,
                                  long lastOutboxDeleted,
                                  long lastInboxDeleted,
                                  String lastRunResult) {
    }
}
