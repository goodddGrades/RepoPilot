package com.repopilot.common.reliability.service;

import com.repopilot.common.hash.Sha256;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.reliability.entity.OutboxEvent;
import com.repopilot.common.reliability.mapper.OutboxEventMapper;
import com.repopilot.common.reliability.messaging.EventPublisher;
import com.repopilot.common.reliability.messaging.PublishOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Outbox 发布器（TASK-011）。
 *
 * <p><b>三条不可违反的规则</b>：
 * <ol>
 *   <li><b>不持锁跨网络</b>：领取在短事务里完成（加锁→改租约→提交），
 *       网络往返在事务外做。否则一条慢消息会把整行锁住 5 秒，
 *       期间所有其他发布器都卡在这行上。</li>
 *   <li><b>确认回写必须 fencing</b>：只有 claimToken 匹配**且租约未过期**的发布器
 *       才能标 PUBLISHED。租约过期的失效发布器回来写确认必须拿到 0 行——
 *       否则它会把新领取者正在处理的事件误标成已发布，该事件就永远不会再被投递。</li>
 *   <li><b>不确定不等于失败</b>：confirm 超时按可重试处理而不是直接判死。
 *       代价是可能重复投递，由消费者 inbox 去重兜住。</li>
 * </ol>
 *
 * <p><b>MQ 只是加速</b>：{@link #scanOnce()} 在 MQ 不可用时照常工作——它扫的是数据库。
 * 因此"消息丢了"不会变成"任务永远不做"，最坏只是慢一个轮询周期。
 */
@Service
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    private final OutboxEventMapper outbox;
    private final EventPublisher publisher;
    private final TransactionTemplate tx;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final int batchLimit;
    private final Random jitter = new Random();

    public OutboxPublisherService(OutboxEventMapper outbox, EventPublisher publisher,
                                  PlatformTransactionManager transactionManager,
                                  @Value("${app.repopilot.outbox.lease-seconds:30}") int leaseSeconds,
                                  @Value("${app.repopilot.outbox.max-attempts:8}") int maxAttempts,
                                  @Value("${app.repopilot.outbox.batch-limit:50}") int batchLimit) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.tx = new TransactionTemplate(transactionManager);
        this.leaseSeconds = leaseSeconds;
        this.maxAttempts = maxAttempts;
        this.batchLimit = batchLimit;
    }

    /**
     * 一轮扫描：领取一批、逐条发布、逐条回写。
     *
     * <p>返回本轮成功发布的条数，供指标采集。<b>不抛异常</b>——
     * 发布器是后台任务，单条消息的问题不该让整个循环停下来。
     */
    public int scanOnce() {
        if (!publisher.available()) {
            // MQ 不可用不是故障：数据库扫描通道本身就是恢复路径。
            // 这里直接返回，事件留在 PENDING，等 broker 恢复后自然补发。
            log.debug("RabbitMQ 不可用，本轮跳过发布（数据库事件已保留，恢复后自动补发）");
            return 0;
        }
        Instant now = Instant.now();
        List<String> candidates = outbox.listPublishableIds(now, maxAttempts, batchLimit);
        int published = 0;
        for (String id : candidates) {
            if (publishOne(id)) {
                published++;
            }
        }
        return published;
    }

    /** 领取 → 发布 → 回写，一条事件一个短事务。 */
    private boolean publishOne(String eventId) {
        Claim claim = claim(eventId);
        if (claim == null) {
            // 被别的发布器抢走，或候选查询之后状态已变
            return false;
        }
        try {
            PublishOutcome outcome = publisher.publish(claim.eventType(), claim.envelope());
            return applyOutcome(eventId, claim.claimTokenHash(), outcome);
        } catch (RuntimeException ex) {
            log.warn("发布事件 {} 异常，安排重试: {}", eventId, ex.getMessage());
            applyOutcome(eventId, claim.claimTokenHash(),
                    PublishOutcome.failed("发布异常: " + ex.getMessage()));
            return false;
        }
    }

    /**
     * 短事务领取：加锁 → 校验 → 写租约与令牌 → 提交。
     *
     * <p>事务在这一步就结束了，返回后才做网络 IO。
     */
    private Claim claim(String eventId) {
        return tx.execute(status -> {
            Instant now = Instant.now();
            Optional<OutboxEvent> locked = outbox.lockPublishable(eventId, now, maxAttempts);
            if (locked.isEmpty()) {
                return null;
            }
            OutboxEvent event = locked.get();
            String claimToken = Ulid.newId();
            Instant leaseUntil = now.plusSeconds(leaseSeconds);
            int updated = outbox.claimForPublish(eventId, publisher.publisherId(),
                    leaseUntil, Sha256.ofUtf8(claimToken), now);
            if (updated != 1) {
                return null;
            }
            // 路由键就是 eventType：交换机按类型分发，队列按需要绑定
            // 投递的是**原始信封 JSON**：发布器不解释内容，只搬运。
            // 在这里反序列化成对象再序列化一次没有任何收益，却多一次字段丢失的机会
            return new Claim(eventId, event.getEventType(), event.getPayloadJson(),
                    Sha256.ofUtf8(claimToken));
        });
    }

    /**
     * 按发布结果回写。
     *
     * <p>三种结果对应三种数据库动作，没有第四种：
     * <ul>
     *   <li>确认路由成功 → PUBLISHED（终态）；</li>
     *   <li>失败/退回 → 退避重试，仍是 PENDING；</li>
     *   <li>重试次数已超上限 → FAILED，等待人工重新投递。</li>
     * </ul>
     */
    private boolean applyOutcome(String eventId, String claimTokenHash, PublishOutcome outcome) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Instant now = Instant.now();
            if (outcome.routed()) {
                int updated = outbox.markPublished(eventId, claimTokenHash, now);
                if (updated != 1) {
                    // fencing 挡住了：这条事件的租约已过期并被别人领走。
                    // 不做任何写入——新领取者会重新发布它
                    log.warn("事件 {} 的发布确认被 fencing 拒绝（租约已失效），不标记已发布", eventId);
                    return false;
                }
                return true;
            }

            int attempt = retryCountOf(eventId);
            if (attempt + 1 >= maxAttempts) {
                int failed = outbox.markFailedPermanently(eventId, claimTokenHash, now);
                if (failed == 1) {
                    log.error("事件 {} 发布重试 {} 次仍失败，置 FAILED 等待人工重新投递。原因: {}",
                            eventId, attempt + 1, outcome.detail());
                }
                return false;
            }
            outbox.scheduleRetry(eventId, claimTokenHash, now.plus(backoff(attempt)), now);
            log.warn("事件 {} 发布未成功（{}），{} 秒后重试（第 {}/{} 次）",
                    eventId, outcome.detail(), backoff(attempt).getSeconds(), attempt + 1, maxAttempts);
            return false;
        }));
    }

    private int retryCountOf(String eventId) {
        return outbox.findById(eventId).map(e -> e.getRetryCount() == null ? 0 : e.getRetryCount())
                .orElse(0);
    }

    /**
     * 指数退避 + 抖动。
     *
     * <p>抖动是必需的：没有它，一批同时失败的事件会在同一毫秒集体重试，
     * 把刚恢复的 broker 再打垮一次（惊群）。
     */
    private Duration backoff(int attemptNo) {
        long base = Math.min(5L << Math.min(attemptNo, 6), 300L);
        return Duration.ofSeconds(base + jitter.nextInt(5));
    }

    /** 人工重新投递运维入口：FAILED → PENDING，保留同 event_id。 */
    public boolean requeue(String eventId) {
        return outbox.requeueFailed(eventId, Instant.now()) == 1;
    }

    /** PENDING 积压数量：MQ 不可用或发布器停摆时的告警依据。 */
    public long pendingCount() {
        return outbox.countStalePending(Instant.now());
    }

    private record Claim(String eventId, String eventType,
                         com.fasterxml.jackson.databind.JsonNode envelope,
                         String claimTokenHash) {
    }
}
