package com.repopilot.common.reliability.messaging;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动时幂等声明 RabbitMQ 拓扑（TASK-013）。
 *
 * <p><b>为什么在 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}</b>：
 * AMQP 默认配置<b>不要求 broker 可用</b>才能启动。若在构造期声明，
 * broker 宕机时会直接让整个应用启动失败——而本平台的设计是
 * "MQ 只是加速器，数据库扫描才是恢复通道"（见 {@link OutboxPublisherService}）。
 * broker 挂了应当是<b>降级</b>而不是<b>不可用</b>。
 * 声明放在应用就绪之后，失败只记ERROR 并继续，事件留在 PENDING 等broker 恢复。
 *
 * <p><b>为什么必须幂等</b>：{@code exchangeDeclare} / {@code queueDeclare} /
 * {@code queueBind} 都是幂等的——重复声明相同参数的实体不会报错，
 * 只有<b>参数不一致</b>时才会（PRECONDITION_FAILED，channel 被关闭）。
 * 因此本类刻意把参数写成常量而不是可配置的：一旦允许运维改队列名或类型，
 * 滚动发布期间新旧实例就会用不同参数声明同一实体，导致启动即失败。
 * 需要改拓扑时的正确做法是<b>新建一个队列并迁移消费者</b>，
 * 而不是原地改参数。
 *
 * <p><b>为什么用原生 AMQP API 而不是 Spring 的 Declarables</b>：
 * 见 {@link RabbitTopology} 的说明——这里还要被集成测试复用，
 * 集成测试会在"真实 broker"与"故障注入替身"两种模式下切换发布器。
 */
@Component
@ConditionalOnProperty(name = "repopilot.outbox.publisher", havingValue = "rabbit",
        matchIfMissing = true)
public class RabbitTopologyDeclarer {

    private static final Logger log = LoggerFactory.getLogger(RabbitTopologyDeclarer.class);

    private final ConnectionFactory connectionFactory;
    private final boolean declareOnStartup;

    public RabbitTopologyDeclarer(ConnectionFactory connectionFactory,
                                  @Value("${repopilot.rabbitmq.declare-topology-on-startup:true}")
                                  boolean declareOnStartup) {
        this.connectionFactory = connectionFactory;
        this.declareOnStartup = declareOnStartup;
    }

    /**
     * 应用就绪后声明拓扑。
     *
     * <p>失败<b>不抛异常</b>：broker 不可用时应用仍应可用，
     * 事件留在 Outbox 等恢复。抛异常会让"消息系统故障"升级为"全站不可用"，
     * 而这正是 TASK-011 设计要避免的。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void declareOnStartup() {
        if (!declareOnStartup) {
            log.info("已按配置跳过 RabbitMQ 拓扑声明（repopilot.rabbitmq.declare-topology-on-startup=false）");
            return;
        }
        try {
            List<String> declared = declare();
            log.info("RabbitMQ 拓扑声明完成：{}", declared);
        } catch (Exception ex) {
            // 降级而不是崩溃：Outbox 扫描通道会在broker 恢复后补发，
            // 启动期失败只意味着这段时间的发布要等下一个周期。
            log.error("RabbitMQ 拓扑声明失败，事件将保留在 Outbox 等待补发"
                    + "（broker 恢复后需重新部署或重启应用以声明拓扑）: {}", describe(ex));
        }
    }

    /**
     * 幂等声明全部实体。返回声明了什么，供测试断言与运维日志使用。
     *
     * @throws RuntimeException broker 拒绝或不可达时抛出（由调用方决定是否降级）
     */
    public List<String> declare() {
        List<String> declared = new ArrayList<>();
        try (Connection connection = connectionFactory.createConnection();
             Channel channel = connection.createChannel(false)) {

            // 1) DLX 必须在业务交换机之前建好：业务队列要绑定它。
            channel.exchangeDeclare(RabbitTopology.DLX_EXCHANGE, "topic", true);
            declared.add("exchange " + RabbitTopology.DLX_EXCHANGE + " (topic,durable)");

            // 2) 业务交换机
            channel.exchangeDeclare(RabbitTopology.EVENTS_EXCHANGE, "topic", true);
            declared.add("exchange " + RabbitTopology.EVENTS_EXCHANGE + " (topic,durable)");

            // 3) 重试交换机（topic）
            //
            // 用 topic 而不是 headers 的原因见 RabbitTopology.RETRY_EXCHANGE 的说明：
            // headers + x-match=all 在真实 broker 上会忽略 x- 前缀的绑定字段，
            // 导致分档完全失效。这里改为 topic + 精确 routing key（retry.tN）。
            channel.exchangeDeclare(RabbitTopology.RETRY_EXCHANGE, "topic", true);
            declared.add("exchange " + RabbitTopology.RETRY_EXCHANGE + " (topic,durable)");

            // 3b) 再发布交换机：TTL 到期后的消息先到这里，由 Worker 复原
            // routing key 后重新投递回业务交换机。原因见REPUBLISH_EXCHANGE 的说明。
            channel.exchangeDeclare(RabbitTopology.REPUBLISH_EXCHANGE, "topic", true);
            declared.add("exchange " + RabbitTopology.REPUBLISH_EXCHANGE + " (topic,durable)");

            // 4) 死信队列：只从 DLX 收消息。
            //
            // binding 必须覆盖**全部业务 routing key**：AMQP 的 reject/nack
            // 无法改写死信的 routing key，死信必然沿用原 key 到达 DLX。
            // 只绑 dead.* 会导致死信被 DLX 收到却匹配不到队列、被 broker 丢弃。
            //
            // 这里绑具体 key 而非 # ：# 会把将来任何新事件类型的死信也吸进来，
            // 而新增事件时应该由声明处强制决定，不该被通配符静默接管。
            channel.queueDeclare(RabbitTopology.DEAD_LETTER_QUEUE, true, false, false,
                    Map.of("x-queue-type", "quorum"));
            declared.add("queue " + RabbitTopology.DEAD_LETTER_QUEUE + " (durable,quorum)");
            for (String deadKey : deadLetterKeys()) {
                channel.queueBind(RabbitTopology.DEAD_LETTER_QUEUE,
                        RabbitTopology.DLX_EXCHANGE, deadKey);
                declared.add("bind " + RabbitTopology.DEAD_LETTER_QUEUE + " <- "
                        + RabbitTopology.DLX_EXCHANGE + " [" + deadKey + "]");
            }

            // 5) 业务队列 + binding
            //
            // x-dead-letter-exchange **不是可选的**：没有它，消费者执行
            // basic_reject(requeue=false) 时 broker 会**直接丢弃**消息——
            // 日志显示"已拒绝"、计数显示"进了死信"，而 DLQ 里什么都没有。
            // 这正是"看起来实现了 DLQ、实际消息在消失"的典型静默故障。
            //
            // x-dead-letter-routing-key **故意不设**：让死信沿用原 routing key，
            // DLQ 才能用同一组 binding 收下所有事件类型的死信。
            // 一旦改成固定值（如 dead.unroutable），DLQ 就得为每种事件类型
            // 各绑一个 key，而"事件类型"是要能扩展的。
            Map<String, Object> businessQueueArgs = Map.of(
                    "x-queue-type", "quorum",
                    "x-dead-letter-exchange", RabbitTopology.DLX_EXCHANGE);

            for (Map.Entry<String, List<String>> entry : queueBindings().entrySet()) {
                String queue = entry.getKey();
                declareBusinessQueue(channel, queue, businessQueueArgs, declared);
                for (String routingKey : entry.getValue()) {
                    channel.queueBind(queue, RabbitTopology.EVENTS_EXCHANGE, routingKey);
                    declared.add("bind " + queue + " <- " + RabbitTopology.EVENTS_EXCHANGE
                            + " [" + routingKey + "]");
                }
            }

            // 6) 退避重试等待队列（每档一个，TTL 固定）
            //
            // 链路：业务队列 →（消费者显式重发）retry.v2 交换机 retry.tN →
            //      本队列（TTL 等待）→ TTL 到期死信到 republish 交换机 →
            //      再发布队列 → Worker 复原routing key 后重投业务交换机。
            //
            // TTL 到期后**不能**直接死信回业务交换机：那时 routing key 仍是
            // 合成键 retry.tN，业务交换机上没有它的 binding，消息会被静默丢弃。
            //
            // 业务队列**不直接指向重试交换机**——那会让"消费失败→重试队列→回原队列"
            // 变成无限循环（消息在两个队列之间来回，永远消费不完）。
            // 走重试必须由**消费者显式重新发布**到重试交换机，
            // 这样"该不该重试、重试几档"是消费者的决定，而不是 broker 的副作用。
            for (int tier = 1; tier <= RabbitTopology.RETRY_TIER_COUNT; tier++) {
                long ttl = RabbitTopology.RETRY_BACKOFF_MILLIS.get(tier - 1);
                String queue = RabbitTopology.retryQueueName(tier);
                Map<String, Object> retryArgs = new LinkedHashMap<>();
                retryArgs.put("x-queue-type", "quorum");
                retryArgs.put("x-message-ttl", ttl);
                // TTL 到期后进"再发布"交换机，而不是业务交换机。
                // x-dead-letter-routing-key 固定为 retry.republish：
                // 等待队列的 routing key 无意义，统一改写成这个合成键，
                // 由再发布交换机按精确键路由到再发布队列。
                retryArgs.put("x-dead-letter-exchange", RabbitTopology.REPUBLISH_EXCHANGE);
                retryArgs.put("x-dead-letter-routing-key", RabbitTopology.RK_REPUBLISH);
                channel.queueDeclare(queue, true, false, false, retryArgs);
                // 描述文本必须**如实反映声明的参数**。之前这里只写了 ttl，
                // 漏掉了 DLX —— 于是"退避队列的 DLX 指向再发布交换机"
                // 这条关键不变式在声明结果里根本看不到，只能靠读代码确认。
                // 集成测试正是靠断言这段文本才发现了这个偏差：
                // 声明的逻辑是对的，但**可观测的描述与真实拓扑不一致**，
                // 运维排查时会因此得出错误结论。
                declared.add("queue " + queue + " (durable,quorum,ttl=" + ttl
                        + "ms,dlx->" + RabbitTopology.REPUBLISH_EXCHANGE
                        + ",dlx-rk->" + RabbitTopology.RK_REPUBLISH + ")");
                // **精确键绑定，不用通配符**：retry.t1 只进t1 队列。
                // 用 retry.# 会让一条消息同时进 3 个队列，退避变成三倍投递。
                String retryKey = RabbitTopology.retryRoutingKey(tier);
                channel.queueBind(queue, RabbitTopology.RETRY_EXCHANGE, retryKey);
                declared.add("bind " + queue + " <- " + RabbitTopology.RETRY_EXCHANGE
                        + " [" + retryKey + "]");
            }

            // 7) 再发布队列：TTL 到期后的消息落在这里，等 Worker 重新投递。
            //
            // 队列**不放回业务队列**：直接死信回业务交换机时 routing key 是
            // retry.republish，匹配不到任何 binding，消息会被静默丢弃。
            //
            // **必须配 DLX**：这里会basic_reject(requeue=false)
            // （例如消息缺 x-original-routing-key 头、无法复原 routing key）。
            // 没有 x-dead-letter-exchange 时 broker 会**直接丢弃**被 reject 的消息——
            // 日志显示"已拒绝"、计数显示进了死信，而 DLQ 里什么都没有。
            // 这正是"看起来实现了隔离、实际消息在消失"的静默故障。
            channel.queueDeclare(RabbitTopology.REPUBLISH_QUEUE, true, false, false,
                    Map.of("x-queue-type", "quorum",
                            "x-dead-letter-exchange", RabbitTopology.DLX_EXCHANGE));
            declared.add("queue " + RabbitTopology.REPUBLISH_QUEUE
                    + " (durable,quorum,dlx->" + RabbitTopology.DLX_EXCHANGE + ")");
            channel.queueBind(RabbitTopology.REPUBLISH_QUEUE,
                    RabbitTopology.REPUBLISH_EXCHANGE, RabbitTopology.RK_REPUBLISH);
            declared.add("bind " + RabbitTopology.REPUBLISH_QUEUE + " <- "
                    + RabbitTopology.REPUBLISH_EXCHANGE + " [" + RabbitTopology.RK_REPUBLISH + "]");
        } catch (Exception ex) {
            throw new IllegalStateException("声明 RabbitMQ 拓扑失败: " + describe(ex), ex);
        }
        return declared;
    }

    /**
     * DLQ 需要绑定的全部 routing key = 全部业务键 + 再发布键。
     *
     * <p><b>为什么要加 {@link RabbitTopology#RK_REPUBLISH}</b>：
     * 再发布队列里的消息 routing key 是 {@code retry.republish}，
     * 它被 {@code basic_reject(requeue=false)} 时死信沿用这个键到达 DLX。
     * 只绑业务键的话，这条死信会被 DLX 收到但<b>匹配不到队列</b>，
     * 然后被 broker 直接丢弃——"无法复原 routing key 的消息"恰恰是
     * 最需要人工介入的消息，丢掉它等于让故障无从排查。
     */
    private List<String> deadLetterKeys() {
        List<String> keys = new ArrayList<>(RabbitTopology.DEAD_LETTER_KEYS);
        keys.add(RabbitTopology.RK_REPUBLISH);
        return keys;
    }

    /**
     * 声明业务队列；参数与既有队列不符时先删除再重建。
     *
     * <p><b>为什么必须处理参数不符</b>：{@code x-dead-letter-exchange} 是声明期参数，
     * {@code queueDeclare} 无法事后修改。若broker 上留着 TASK-011 时代声明的队列
     * （只有 {@code x-queue-type}、没有 DLX），按新参数 declare 会抛
     * {@code PRECONDITION_FAILED} 并<b>关掉整个 channel</b>——
     * 后续所有声明随之失败，拓扑处于半声明状态，比直接失败更难排查。
     *
     * <p><b>为什么用"试一次、失败再重建"而不是先查参数</b>：
     * AMQP 协议里 {@code queue.declare-passive} 的返回体<b>不包含队列参数</b>
     * （{@code AMQP.Queue.DeclareOk} 只有 queue/message-count/consumer-count），
     * 想读参数只能依赖 management HTTP 插件或客户端的私有 API。
     * 前者要求broker 开启 management（生产环境未必），后者随版本漂移。
     * 直接 declare 是唯一只用标准协议的做法：参数一致就成功，
     * 不一致就报错并重建，语义清晰且不依赖可选组件。
     *
     * <p><b>删除是有代价的</b>：队列中未消费的消息副本会丢。
     * 但这些事件的权威副本在 {@code outbox_events} 里（已 PUBLISHED 或可重投），
     * 丢的只是队列副本、不是业务事实——这正是 Outbox 模式的意义。
     * 因此删除是安全的，但<b>必须记入日志</b>，不能静默。
     */
    private void declareBusinessQueue(Channel channel, String queue,
                                     Map<String, Object> expectedArgs, List<String> declared) {
        try {
            channel.queueDeclare(queue, true, false, false, expectedArgs);
        } catch (IOException first) {
            if (!isPreconditionFailed(first)) {
                throw new IllegalStateException(
                        "声明队列 " + queue + " 失败: " + describe(first), first);
            }
            // PRECONDITION_FAILED 会让 channel 进入不可用状态，
            // 必须换一个干净 channel 继续，否则后面的 declare 全部会失败。
            log.warn("队列 {} 的声明参数与既有队列不一致（{}），"
                    + "将删除后按新参数重建；队列中未消费的副本会丢失，"
                    + "但权威副本在 outbox_events 中可重新投递", queue, describe(first));
            deleteAndRedeclare(channel, queue, expectedArgs, first);
        } catch (RuntimeException first) {
            throw first;
        }
        declared.add("queue " + queue + " (durable,quorum,dlx->" + RabbitTopology.DLX_EXCHANGE + ")");
    }

    /**
     * 判断异常是否是 broker 的 PRECONDITION_FAILED（参数不符）。
     *
     * <p><b>不能只看 {@code getMessage()}</b>：AMQP 客户端在收到
     * {@code channel.close} 帧时抛的是 {@link com.rabbitmq.client.ShutdownSignalException}，
     * 它的 {@code getMessage()} 常常是 {@code null}——真正的 broker 回复在
     * {@code getReason()} 里（形如 {@code PRECONDITION_FAILED - inequivalent arg 'type'}）。
     * 只看 message 会把"参数不符需重建"误判成"声明失败"，于是永远不重建。
     */
    private boolean isPreconditionFailed(Throwable ex) {
        Throwable cursor = ex;
        int guard = 0;
        while (cursor != null && guard++ < 10) {
            String text = describe(cursor);
            if (text.contains("PRECONDITION_FAILED")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private String describe(Throwable ex) {
        StringBuilder text = new StringBuilder();
        if (ex.getMessage() != null) {
            text.append(ex.getMessage());
        }
        if (ex instanceof com.rabbitmq.client.ShutdownSignalException signal
                && signal.getReason() != null) {
            text.append(' ').append(signal.getReason());
        }
        return text.toString();
    }

    /**
     * 删除旧队列并用干净连接重新声明。
     *
     * <p>用<b>新连接</b>而不是新channel：PRECONDITION_FAILED 之后当前 channel
     * 已被 broker 关闭，同一连接上的新 channel 也不一定能用；
     * 而 queueDelete 失败时若继续 declare，会再次 PRECONDITION_FAILED 并死循环。
     */
    private void deleteAndRedeclare(Channel channel, String queue,
                                    Map<String, Object> expectedArgs, Exception original) {
        try {
            channel.queueDelete(queue);
        } catch (Exception ex) {
            throw new IllegalStateException("参数不符的队列 " + queue
                    + " 无法删除（原始错误: " + describe(original)
                    + "，删除错误: " + describe(ex)
                    + "）。请手动删除该队列后重启应用", ex);
        }
        try (Connection fresh = connectionFactory.createConnection();
             Channel clean = fresh.createChannel(false)) {
            clean.queueDeclare(queue, true, false, false, expectedArgs);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "重建队列 " + queue + " 失败: " + describe(ex), ex);
        }
    }

    /**
     * 队列 → 该队列关心的 routing key 列表。
     *
     * <p>用 {@code run.ready} 与 {@code run.finished} 两个<b>精确</b>键而不是
     * {@code run.*}：topic 的 {@code *} 只匹配<b>一个</b>词，
     * 加 {@code run.#} 又会把未来的 {@code run.something.else} 一起吞进来。
     * 精确键让"新事件类型默认不消费"成为默认行为——新增事件时必须
     * 有意识地决定谁消费它，而不是被通配符静默吸走。
     */
    private Map<String, List<String>> queueBindings() {
        Map<String, List<String>> bindings = new LinkedHashMap<>();
        bindings.put(RabbitTopology.RUN_QUEUE,
                List.of(RabbitTopology.RK_RUN_READY, RabbitTopology.RK_RUN_FINISHED));
        bindings.put(RabbitTopology.EVALUATION_QUEUE,
                List.of(RabbitTopology.RK_EVALUATION_READY));
        bindings.put(RabbitTopology.EXPERIMENT_QUEUE,
                List.of(RabbitTopology.RK_EXPERIMENT_FINISHED));
        return bindings;
    }
}
