package com.repopilot.common.reliability.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 消费侧的观测计数（TASK-013-E）。
 *
 * <p><b>为什么用"回调上报"而不是"消费者轮询数据库"</b>：
 * 消费结果的<b>真相在消费者进程里</b>——它知道一条消息是 ACK 了、
 * 进了退避队列还是进了死信。后端数据库看不到这些：
 * 一条被 reject 进 DLQ 的消息在 {@code outbox_events} 里仍然是
 * {@code PUBLISHED}（发布确实成功了），在 {@code inbox_events} 里
 * 也可能<b>没有</b>记录（它压根没被处理到登记那一步）。
 *
 * <p>所以后端只能从"死信队列深度"这类外部信号推断，
 * 而消费者报上来的计数是精确的。两个来源都要有：
 * <ul>
 *   <li>后端侧：队列深度、DLQ 深度（外部可观测，不依赖消费者在线）；</li>
 *   <li>消费者侧：成功/失败/重试的精确计数（依赖消费者上报）。</li>
 * </ul>
 *
 * <p><b>为什么计数是无方向的累计值而不是速率</b>：
 * Micrometer 的 {@code Counter} 语义是"单调递增的事件数"，
 * 速率由监控系统（Prometheus 的 {@code rate()}）负责计算。
 * 在应用里自己算速率，等于把采样窗口的语义硬编码进业务代码。
 */
@Component
public class ConsumerMetrics {

    private static final Logger log = LoggerFactory.getLogger(ConsumerMetrics.class);

    private final Counter consumedCounter;
    private final Counter failedCounter;
    private final Counter retriedCounter;
    private final Counter deadLetteredCounter;
    private final Counter duplicateCounter;
    private final Counter republishedCounter;
    private final Counter rejectedCounter;

    public ConsumerMetrics(MeterRegistry registry) {
        this.consumedCounter = Counter.builder("repopilot.consumer.consumed")
                .description("消费成功并 ACK 的消息数").register(registry);
        this.failedCounter = Counter.builder("repopilot.consumer.failed")
                .description("消费失败的消息数（不论是否可重试）").register(registry);
        this.retriedCounter = Counter.builder("repopilot.consumer.retried")
                .description("进入退避重试的消息数").register(registry);
        this.deadLetteredCounter = Counter.builder("repopilot.consumer.dead_lettered")
                .description("进入死信队列的消息数：需要人工介入").register(registry);
        this.duplicateCounter = Counter.builder("repopilot.consumer.duplicate")
                .description("命中 Inbox 去重被跳过副作用的消息数").register(registry);
        this.republishedCounter = Counter.builder("repopilot.consumer.republished")
                .description("退避到期后被重新投递回业务交换机��消息数").register(registry);
        this.rejectedCounter = Counter.builder("repopilot.consumer.rejected")
                .description("拒绝动作本身失败的消息数：消息将由 broker 重投").register(registry);
    }

    /**
     * 上报一次消费结果。
     *
     * <p>参数是<b>本轮的增量</b>而不是累计值：调用方手里通常只有增量
     * （本轮处理了几条），累计值的差分由上报方自己负责。
     *
     * @param consumed   本轮成功处理并 ACK 的条数
     * @param failed     本轮处理失败的条数
     * @param retried    本轮进入退避重试的条数
     * @param deadLettered 本轮进入死信的条数
     * @param duplicate  本轮命中 Inbox 去重的条数
     * @param republished 本轮重新投递回业务交换机的条数
     * @param rejected   本轮拒绝动作失败的条数
     */
    public void record(int consumed, int failed, int retried, int deadLettered,
                       int duplicate, int republished, int rejected) {
        add(consumedCounter, consumed);
        add(failedCounter, failed);
        add(retriedCounter, retried);
        add(deadLetteredCounter, deadLettered);
        add(duplicateCounter, duplicate);
        add(republishedCounter, republished);
        add(rejectedCounter, rejected);
    }

    private void add(Counter counter, int delta) {
        if (delta > 0) {
            counter.increment(delta);
        }
    }

    /**
     * 注册 DLQ 深度 gauge。
     *
     * <p><b>为什么 DLQ 深度比"死信总数"更该被盯</b>：
     * 死信总数是累计的，只会增长；DLQ 深度是<b>当前存量</b>，
     * 它下降只可能因为有人真的处理掉了死信。
     * 一个只看累计数的告警，在第一次故障之后就永远处于触发状态，
     * 于是真正的新故障反而被淹没。
     */
    public void registerDeadLetterDepthGauge(MeterRegistry registry,
                                             java.util.function.IntSupplier supplier) {
        Gauge.builder("repopilot.consumer.dlq.depth", supplier, s -> s.getAsInt())
                .description("死信队列当前存量：需要人工排查或重放")
                .register(registry);
    }

    /**
     * 注册消费延迟 gauge。
     *
     * <p>延迟来自"发布确认 → 消费者登记"的时间差。
     * 它比"消费者处理耗时"更能反映链路健康：
     * 处理耗时长说明 Worker 慢，延迟高说明<b>消息在链路里排了队</b>。
     */
    public void registerDeliveryLatencyGauge(MeterRegistry registry, String name,
                                             String description,
                                             java.util.function.DoubleSupplier supplier) {
        Gauge.builder(name, supplier, s -> s.getAsDouble())
                .description(description)
                .register(registry);
    }

    /** 供测试与排障查看当前注册的消费指标名，避免"指标名写错了但没人发现"。 */
    public static java.util.List<String> metricNames() {
        return java.util.List.of(
                "repopilot.consumer.consumed",
                "repopilot.consumer.failed",
                "repopilot.consumer.retried",
                "repopilot.consumer.dead_lettered",
                "repopilot.consumer.duplicate",
                "repopilot.consumer.republished",
                "repopilot.consumer.rejected");
    }
}
