package com.repopilot.common.reliability.service;

import com.repopilot.common.reliability.mapper.ReliabilityMetricsMapper;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消息链路的<b>链路级观测</b>（TASK-013-E）。
 *
 * <p>任务书要求的七类观测指标在这里与另外三个类分工：
 * <table border="1">
 *   <caption>指标归属</caption>
 *   <tr><th>指标</th><th>位置</th></tr>
 *   <tr><td>Outbox 待发布数 / 最老年龄 / 发布失败数</td><td>{@link OutboxScheduler}</td></tr>
 *   <tr><td>消费成功 / 失败 / 重试 / 死信计数</td><td>{@link ConsumerMetrics}（消费者上报）</td></tr>
 *   <tr><td>Inbox 重复命中数</td><td>{@link InboxMetrics}</td></tr>
 *   <tr><td>清理处理量 / 失败数</td><td>{@link RetentionService}</td></tr>
 *   <tr><td><b>消费者处理延迟 / 积压 / 租约超时</b></td><td><b>本类</b></td></tr>
 *   <tr><td><b>清理任务最后执行结果</b></td><td><b>本类</b></td></tr>
 * </table>
 *
 * <p><b>为什么延迟要单独测"发布→登记"而不是"消费者处理耗时"</b>：
 * 处理耗时长说明 Worker 慢（换机器/调并发）；
 * 端到端延迟高说明<b>消息在链路里排了队</b>（队列积压或消费者不足）。
 * 两种病因的处置完全不同，必须分开。
 *
 * <p><b>为什么用 Gauge 而不是 Counter</b>：这些都是<b>瞬时状态</b>
 * （现在积压多少、现在最慢的那条等了多久），
 * 不是"发生了多少次"。用 Counter 记瞬时值会得到一个只增不减的假象。
 */
@Component
public class MessagingObservability {

    private static final Logger log = LoggerFactory.getLogger(MessagingObservability.class);

    private final ReliabilityMetricsMapper metrics;
    private final MeterRegistry registry;

    /** DLQ 深度缓存：探测 broker 是网络操作，不能放进 gauge 的取值函数里。 */
    private final AtomicLong deadLetterDepth = new AtomicLong(0);

    public MessagingObservability(ReliabilityMetricsMapper metrics, MeterRegistry registry) {
        this.metrics = metrics;
        this.registry = registry;
        registerGauges();
    }

    private void registerGauges() {
        Gauge.builder("repopilot.consumer.backlog.runs", metrics,
                        m -> m.countQueuedRuns())
                .description("待 Worker 领取的 Run 数（QUEUED）：任务侧真实积压")
                .register(registry);

        Gauge.builder("repopilot.consumer.backlog.oldest.age.seconds", metrics,
                        m -> m.oldestQueuedRunAgeSeconds())
                .description("最老的待领取 Run 已等待的秒数：积压的直接信号")
                .register(registry);

        Gauge.builder("repopilot.consumer.lease.expired", metrics,
                        m -> m.countExpiredRunningAttempts())
                .description("租约已过期但仍是 RUNNING 的 Attempt 数：Worker 失联信号")
                .register(registry);

        Gauge.builder("repopilot.consumer.delivery.latency.avg.seconds", metrics,
                        m -> m.averageDeliveryLatencySeconds())
                .description("消息端到端平均延迟（发布确认→Inbox 登记）")
                .register(registry);

        Gauge.builder("repopilot.consumer.delivery.latency.max.seconds", metrics,
                        m -> m.maxDeliveryLatencySeconds())
                .description("消息端到端最坏延迟：平均值会掩盖的那一条")
                .register(registry);

        Gauge.builder("repopilot.consumer.dlq.depth", deadLetterDepth,
                        AtomicLong::doubleValue)
                .description("死信队列当前存量：需要人工排查或重放")
                .register(registry);
    }

    /**
     * 探测死信队列深度并更新缓存值。
     *
     * <p><b>为什么用 passive declare 而不是管理 API</b>：
     * 管理 API 要求 broker 开启 management 插件，生产环境未必开启。
     * {@code queueDeclarePassive} 是标准 AMQP 的一部分，只读不改动队列。
     *
     * <p><b>关键：绝不能带参数地 declare</b>。带参数的
     * {@code queueDeclare} 作用在已存在的队列上时，broker 会
     * <b>按新参数删除并重建该队列</b>——quorum 队列的
     * {@code x-message-ttl}、{@code x-dead-letter-*} 会全部消失。
     * 这不是理论风险，是本任务实测踩过的坑（见
     * {@code docs/reviews/task-013-amqp-facts.md} 第 14 条）。
     *
     * <p>探测失败按"深度未知"处理（保留上一次的值并记日志），
     * 不把它当作 0：0 表示"确定没有死信"，而探测失败是"不知道"。
     * 把"不知道"报成"确定没有"会让告警在真正出问题时反而不响。
     */
    public void refreshDeadLetterDepth(ConnectionFactory connectionFactory, String queue) {
        try (Connection connection = connectionFactory.createConnection();
             Channel channel = connection.createChannel(false)) {
            // passive=true：只问"存在吗、有多少条"，不碰队列参数
            int count = channel.queueDeclarePassive(queue).getMessageCount();
            deadLetterDepth.set(count);
        } catch (Exception ex) {
            // 保留旧值：探测失败不等于队列空了
            log.debug("探测死信队列深度失败（保留上次值 {}）: {}",
                    deadLetterDepth.get(), ex.getMessage());
        }
    }

    /** 当前缓存的死信深度（供测试与运维查询）。 */
    public long deadLetterDepth() {
        return deadLetterDepth.get();
    }
}
