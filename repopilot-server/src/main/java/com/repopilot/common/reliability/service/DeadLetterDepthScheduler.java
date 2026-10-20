package com.repopilot.common.reliability.service;

import com.repopilot.common.reliability.messaging.RabbitTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 死信队列深度的定时探测（TASK-013-E）。
 *
 * <p><b>为什么要有这个定时任务</b>：死信是"消费者最终没能处理"的消息，
 * 它不体现在任何数据库表里——
 * 在 {@code outbox_events} 里它仍然是 {@code PUBLISHED}，
 * 在 {@code inbox_events} 里它可能根本没有记录。
 * <b>只有 broker 知道死信有多少</b>。
 *
 * <p><b>为什么探测失败不当作 0</b>：0 表示"确定没有死信"，
 * 探测失败表示"不知道"。把"不知道"报成"确定没有"，
 * 会让告警在真正出问题时反而不响——这比没有这个指标更糟。
 *
 * <p><b>为什么用 passive declare</b>：带参数的 {@code queueDeclare}
 * 作用在已存在的队列上会让 broker <b>删除并重建</b>它，
 * 丢失 quorum 队列的 TTL 与 DLX 参数。实测依据见
 * {@code docs/reviews/task-013-amqp-facts.md} 第 14 条。
 */
@Component
@ConditionalOnProperty(name = "repopilot.outbox.publisher", havingValue = "rabbit",
        matchIfMissing = true)
public class DeadLetterDepthScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterDepthScheduler.class);

    private final MessagingObservability observability;
    private final ConnectionFactory connectionFactory;
    private final String deadLetterQueue;

    public DeadLetterDepthScheduler(MessagingObservability observability,
                                    ConnectionFactory connectionFactory,
                                    @Value("${repopilot.rabbitmq.dead-letter-queue:"
                                            + "repopilot.events.dlq}") String deadLetterQueue) {
        this.observability = observability;
        this.connectionFactory = connectionFactory;
        this.deadLetterQueue = deadLetterQueue;
    }

    /**
     * 每 30 秒探测一次。
     *
     * <p><b>为什么是 30 秒而不是更短</b>：死信是"要人来看"的东西，
     * 它的处置周期以分钟计。以秒级频率探测只会给 broker 增加无谓的连接压力，
     * 而 30 秒的延迟对"死信堆积"这种故障完全够用。
     */
    @Scheduled(fixedDelayString = "${app.repopilot.messaging.dlq-probe-interval-ms:30000}",
            initialDelayString = "${app.repopilot.messaging.dlq-probe-initial-delay-ms:10000}")
    public void probe() {
        try {
            observability.refreshDeadLetterDepth(connectionFactory, deadLetterQueue);
        } catch (RuntimeException ex) {
            // observability 内部已经处理了探测失败；这里兜底防止调度线程退出
            log.debug("死信深度探测异常（不影响业务）: {}", ex.getMessage());
        }
    }

    /** 队列名常量：供测试断言默认值与 {@link RabbitTopology} 一致。 */
    public static String defaultDeadLetterQueue() {
        return RabbitTopology.DEAD_LETTER_QUEUE;
    }
}
