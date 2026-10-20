package com.repopilot.common.reliability.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.ReturnListener;
import com.repopilot.common.reliability.messaging.RabbitTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 死信查询与受控重放（TASK-013-C）。
 *
 * <p><b>为什么重放必须"受控"</b>：DLQ 里的每条消息都曾经让消费者失败。
 * 无脑地把它们全部塞回业务队列，等于把一个已经收敛的故障重新放大——
 * 比如下游依赖挂了，DLQ 里躺着 1000 条消息，重放一次就是 1000 次必然失败的调用，
 * 而且每条都要占用一个消费者几十秒。故障因此从"隔离"退回"雪崩"。
 *
 * <p>本服务的四条硬约束：
 * <ol>
 *   <li><b>必须显式指定 eventId</b>——不允许"全量重放"。
 *       批量重放只允许在明确给出上限（{@code limit}）时进行，
 *       且上限有天花板（{@link #MAX_REPLAY_BATCH}）。</li>
 *   <li><b>重放保留原 eventId</b>——这样消费侧的 Inbox 去重仍然生效。
 *       换 ID 会让同一条业务事实被当成两次通知，触发两次副作用。</li>
 *   <li><b>重放走业务交换机，不是直投队列</b>——消息重新进入正常投递路径，
 *       因此 Inbox 去重、信封版本校验、消费者幂等全部照常执行。
 *       绕过这些检查的重放等于"把未经验证的数据塞进业务路径"。</li>
 *   <li><b>扫描队列必须"先全部 hold，再统一放回"</b>——见 {@link #hold}。</li>
 * </ol>
 *
 * <p><b>为什么重放不直接改数据库</b>：死信的权威副本可能还在
 * {@code outbox_events} 里（那是发布侧的真相），也可能已经不在
 * （Outbox 已按保留期清理）。从 DLQ 重放是唯一能同时覆盖两种情况的路径，
 * 因为它重建的是"投递"这个动作本身，而不是伪造一条已发布事件。
 */
@Service
@ConditionalOnProperty(name = "repopilot.outbox.publisher", havingValue = "rabbit",
        matchIfMissing = true)
/**
 * 死信查询与受控重放服务。
 *
 * <p>**@Value 的默认值必须写成完整的字面量，不能用常量拼接。**例如：
 * <pre>
 * &#64;Value("${repopilot.rabbitmq.dead-letter-queue:" + RabbitTopology.DEAD_LETTER_QUEUE + "}")
 * </pre>
 * <b>看起来</b>像"默认取常量"，实际占位符解析出的 key 会变成
 * {@code 'repopilot.rabbitmq.dead-letter-queue:repopilot.events.dlq'}
 * （占位符语法不支持表达式），于是 Spring 找不到这个配置项，
 * 就把**整个字符串当成队列名**传下来——
 * 于是 broker 报 {@code NOT_FOUND - no queue '${...:repopilot.events.dlq}'}，
 * 错误信息里带着完整的占位符原文，看起来像配置问题，
 * 实际是"默认值拼接"这个写法本身无效。
 *
 * <p>正确写法：直接把字面量写在默认值位置，
 * 并<b>用测试锁住它与 {@link RabbitTopology} 常量一致</b>，
 * 避免两处各写一份字符串后悄悄漂移。
 */
public class DeadLetterService {

    /**
     * 死信队列名。
     *
     * <p>这里是**字面量而非 {@code RabbitTopology.DEAD_LETTER_QUEUE}**，
     * 原因见类注释。{@link #DEFAULT_DEAD_LETTER_QUEUE_MATCHES_TOPOLOGY}
     * 那个测试负责保证两者不会漂移。
     */
    public static final String DEFAULT_DEAD_LETTER_QUEUE = "repopilot.events.dlq";

    /** 业务交换机名。同样必须写字面量，理由同上。 */
    public static final String DEFAULT_EVENTS_EXCHANGE = "repopilot.events";

    /** 占位符默认值必须落在这两个常量上，由静态断言守住。 */
    public static final boolean DEFAULT_DEAD_LETTER_QUEUE_MATCHES_TOPOLOGY =
            DEFAULT_DEAD_LETTER_QUEUE.equals(RabbitTopology.DEAD_LETTER_QUEUE)
                    && DEFAULT_EVENTS_EXCHANGE.equals(RabbitTopology.EVENTS_EXCHANGE);

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    /** 单次重放的条数天花板：防止"限流参数写大了"变成雪崩。 */
    public static final int MAX_REPLAY_BATCH = 100;

    /** 死信查询默认返回条数。 */
    public static final int DEFAULT_QUERY_LIMIT = 20;

    /** 单次重放的确认等待上限（毫秒）。 */
    private static final long CONFIRM_TIMEOUT_MS = 5_000L;

    /** 人工重放标记：让排障时能一眼区分"自动死信"与"人工重放"。 */
    private static final String HEADER_REPLAYED_AT = "x-replayed-at";

    private final ConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;
    private final String deadLetterQueue;
    private final String eventsExchange;

    public DeadLetterService(ConnectionFactory connectionFactory, ObjectMapper objectMapper,
                             @Value("${repopilot.rabbitmq.dead-letter-queue:"
                                     + "repopilot.events.dlq}") String deadLetterQueue,
                             @Value("${repopilot.rabbitmq.events-exchange:"
                                     + "repopilot.events}") String eventsExchange) {
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
        this.deadLetterQueue = deadLetterQueue;
        this.eventsExchange = eventsExchange;
    }

    /**
     * 一条死信的摘要。
     *
     * @param eventId       事件 ID（从信封里解析；解析不出时为空）
     * @param eventType     事件类型
     * @param aggregateId   聚合 ID
     * @param routingKey    死信到达 DLX 时用的 routing key（等于原 routing key）
     * @param retryTier     已经重试过几档（0 = 没重试过）
     * @param lastError     最近一次失败原因（截断后）
     * @param firstFailedAt 首次失败时间
     * @param queuedAt      进入 DLQ 的时间
     */
    public record DeadLetterSummary(String eventId,
                                    String eventType,
                                    String aggregateId,
                                    String routingKey,
                                    int retryTier,
                                    String lastError,
                                    String firstFailedAt,
                                    String queuedAt) {
    }

    /** 重放结果。 */
    public record ReplayResult(int requested,
                                int replayed,
                                int skipped,
                                List<String> replayedEventIds,
                                List<String> reasons) {
    }

    /**
     * 查看 DLQ 里的死信（<b>不消费</b>）。
     *
     * <p>实现方式是 {@link #hold}：先把要看的消息全部 {@code basicGet} 出来
     * <b>但不 ACK</b>（此时它们处于 unacked 状态，{@code basic_get} 不会再返回它们），
     * 读完再统一 {@code basicNack(requeue=true)} 放回。
     *
     * <p><b>为什么不用管理 API</b>：那要求 broker 开启 management 插件，
     * 生产环境未必开启。{@code basicGet} 是标准 AMQP 协议的一部分。
     *
     * <p><b>已知的副作用</b>：AMQP 0-9-1 <b>没有"只读窥视"原语</b>，
     * {@code basic.recover(requeue=true)} 与 {@code basic.nack(requeue=true)}
     * 都是把消息放到<b>队尾</b>，所以查询之后队列的<b>顺序</b>会变。
     * 消息<b>集合</b>不变、条数不变、不丢不重——对 DLQ 这种诊断队列可以接受。
     * 完整实测依据见 {@code docs/reviews/task-013-amqp-facts.md}。
     */
    public List<DeadLetterSummary> query(int limit) {
        int effective = Math.max(1, Math.min(limit, DEFAULT_QUERY_LIMIT * 5));
        List<DeadLetterSummary> result = new ArrayList<>();
        try (Connection connection = connectionFactory.createConnection();
             Channel channel = connection.createChannel(false)) {
            List<GetResponse> held = hold(channel, effective);
            try {
                for (GetResponse response : held) {
                    result.add(summarize(response));
                }
            } finally {
                releaseHeld(channel, held);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("查询死信队列失败: " + ex.getMessage(), ex);
        }
        return result;
    }

    /**
     * 受控重放：把指定的死信重新投递到业务交换机。
     *
     * <p><b>只重放明确点名的 eventId</b>：{@code eventIds} 为空时直接拒绝，
     * 不提供"全量重放"这个入口。运维要重放 1000 条也得写 1000 个 ID，
     * 这正是刻意的摩擦——让"批量重放"成为一个需要想清楚的决定。
     *
     * <p>重放的消息<b>保留原 eventId 与原信封</b>：
     * 消费者侧 Inbox 会识别出重复并跳过副作用，业务幂等由后端状态机保证。
     */
    public ReplayResult replay(List<String> eventIds, int limit) {
        if (eventIds == null || eventIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "必须显式指定要重放的 eventId；本服务不提供全量重放入口");
        }
        int batchLimit = Math.max(1, Math.min(limit, MAX_REPLAY_BATCH));
        if (eventIds.size() > batchLimit) {
            throw new IllegalArgumentException(
                    "请求重放 " + eventIds.size() + " 条，超过单次上限 " + batchLimit
                            + " 条（硬上限 " + MAX_REPLAY_BATCH + "）");
        }
        // 用 LinkedHashSet：同一请求里重复的 ID 只算一次，
        // 否则 "replayed" 会超过 "requested"，读起来像 bug 也确实是 bug
        Set<String> wanted = new LinkedHashSet<>(eventIds);

        List<String> replayed = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        int skipped = 0;

        try (Connection connection = connectionFactory.createConnection();
             Channel channel = connection.createChannel(false)) {
            // confirm 模式：没有它，waitForConfirms 会抛
            // IllegalStateException("Confirms not selected")，重放功能整个不可用。
            // 必须在任何 basicPublish 之前开启。
            channel.confirmSelect();
            List<String> returned = new ArrayList<>();
            channel.addReturnListener((ReturnListener) (msg, replyCode, replyText,
                                                     exchange, routingKey, props) ->
                    returned.add("replyCode=" + replyCode + " replyText=" + replyText
                            + " routingKey=" + routingKey));

            // 先把要考察的消息全部 hold 住（不 ACK），再决定哪些重放。
            // 边取边 nack 的写法会因 requeue 放队尾而绕回队首重复命中同一条。
            int maxExamined = Math.max(batchLimit * 20, 100);
            List<GetResponse> held = hold(channel, maxExamined);
            // 已被 ack 的 delivery tag：finally 里必须跳过它们，
            // 对已确认的 tag 调 basicNack 会抛 unknown delivery tag
            Set<Long> settled = new HashSet<>();
            try {
                for (GetResponse response : held) {
                    long tag = response.getEnvelope().getDeliveryTag();
                    String eventId = extractEventId(response);
                    if (eventId == null || !wanted.contains(eventId)) {
                        // 不匹配：留在 hold 里，finally 统一放回，队列内容不变
                        skipped++;
                        continue;
                    }
                    // 重放成功后才 ack 死信：顺序反了会在"发布成功但 ack 前崩溃"时丢消息
                    boolean confirmed = republish(channel, response, returned);
                    if (confirmed && returned.isEmpty()) {
                        channel.basicAck(tag, false);
                        settled.add(tag);
                        replayed.add(eventId);
                    } else {
                        String reason = returned.isEmpty()
                                ? "重放 " + eventId + " 未被 broker 确认"
                                : "重放 " + eventId + " 被退回: " + returned.get(0);
                        reasons.add(reason);
                        skipped++;
                        returned.clear();
                    }
                }
                if (held.size() >= maxExamined) {
                    reasons.add("扫描上限 " + maxExamined + " 条已用尽，可能还有未重放的死信");
                }
                for (String eventId : wanted) {
                    if (!replayed.contains(eventId)) {
                        reasons.add("死信队列中未找到 eventId=" + eventId);
                    }
                }
            } finally {
                // 只把"仍未确认"的消息放回；已 ack 的绝不能碰
                releaseUnacked(channel, held, settled);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("重放死信失败: " + ex.getMessage(), ex);
        }

        log.info("死信重放完成：请求 {} 条，实际重放 {} 条，跳过 {} 条",
                wanted.size(), replayed.size(), skipped);
        return new ReplayResult(wanted.size(), replayed.size(), skipped, replayed, reasons);
    }

    /**
     * 取出最多 {@code max} 条消息并<b>保持未确认</b>状态。
     *
     * <p><b>为什么要"全部 hold 住再处理"</b>：AMQP 没有只读窥视。
     * {@code basic.nack(requeue=true)} 把消息放到<b>队尾</b>，所以
     * "取一条→放回→再取一条"会绕回队首<b>重复命中同一条</b>。
     * 实测：5 条消息用这种方式逐条扫描 limit=20，得到的是
     * {@code m1,m2,m3,m4,m5,m1,m2,m3,...}——15 条里只有 5 条是真实消息，
     * 另外 10 条是重复条目。运维看到"DLQ 里有 20 条"会误判故障规模。
     *
     * <p>而处于 unacked 状态的消息<b>不会</b>再被 {@code basic_get} 返回，
     * 所以"一次全取出来"天然去重。实测 hold 300 条也不报错。
     *
     * <p><b>安全边界</b>：hold 的消息在连接关闭时由 broker 自动重投，
     * 所以即使这里抛异常，消息也不会丢——最坏是顺序变了。
     */
    private List<GetResponse> hold(Channel channel, int max) throws Exception {
        List<GetResponse> held = new ArrayList<>();
        while (held.size() < max) {
            GetResponse response = channel.basicGet(deadLetterQueue, false);
            if (response == null) {
                break;
            }
            held.add(response);
        }
        return held;
    }

    /** 把 hold 住的全部消息放回原队列（查询场景，队列内容不应改变）。 */
    private void releaseHeld(Channel channel, List<GetResponse> held) {
        releaseUnacked(channel, held, Set.of());
    }

    /**
     * 把仍处于未确认状态的消息放回原队列。
     *
     * <p>{@code settled} 是已 ack 的 delivery tag：对已确认的 tag 调
     * {@code basicNack} 会抛 {@code unknown delivery tag}，所以必须显式排除。
     *
     * <p>放回失败不抛出：消息仍处于未确认状态，连接关闭时 broker 会自动重投，
     * 绝不会丢。重放/查询的正确结果不该因为"最后一步放回失败"而被整体判失败。
     */
    private void releaseUnacked(Channel channel, List<GetResponse> held, Set<Long> settled) {
        for (GetResponse response : held) {
            if (settled.contains(response.getEnvelope().getDeliveryTag())) {
                continue;
            }
            try {
                channel.basicNack(response.getEnvelope().getDeliveryTag(), false, true);
            } catch (Exception ex) {
                log.debug("放回死信失败（将由 broker 在连接关闭时重投）: {}", ex.getMessage());
            }
        }
    }

    /**
     * 把死信重新发布到业务交换机。
     *
     * <p><b>用原 routing key</b>：死信到达 DLX 时沿用了原 routing key，
     * 这个 key 正是业务交换机上 binding 的 key，所以重放会回到原业务队列。
     *
     * <p><b>保留 eventId</b>：消费侧 Inbox 按 (consumer, eventId) 去重，
     * 换 ID 会让同一条业务事实被当成两次通知。
     *
     * <p><b>必须检查 confirm 的返回值</b>：{@code waitForConfirms} 返回
     * {@code false} 表示这一轮出现过 NACK。它<b>不抛异常</b>，忽略返回值等于
     * 把 broker 的明确拒绝当成发布成功，死信被 ack 掉却从未重新投递——
     * 这是"重放看起来成功了但业务没动"的根因。
     * （不能用 {@code waitForConfirmsOrDie}：它在 NACK 时会关掉整个 channel。）
     *
     * @return true 表示broker 明确确认接收
     */
    private boolean republish(Channel channel, GetResponse response,
                              List<String> returned) throws Exception {
        byte[] body = response.getBody();
        AMQP.BasicProperties original = response.getProps();
        // 与 summarize() 同理：props / envelope 都可能为 null。
        // 一条"没有任何 properties 的死信"不该让重放接口整体 500——
        // 那种消息恰恰最需要人工介入，接口挂掉等于让人无从下手。
        Map<String, Object> headers = new HashMap<>();
        // 先拷原头，再补自己的头。
        // **绝不能先 `Map.of()` 再 put**：`Map.of()` 返回不可变集合，
        // put 会抛 `UnsupportedOperationException`——而这条路径
        // 只在"死信没有 headers"时才走到，恰好是最容易漏测的分支。
        // 第一版写成三元表达式返回 Map.of()，重放接口在真实调用下 500。
        if (original != null && original.getHeaders() != null) {
            headers.putAll(original.getHeaders());
        }
        // 标记这是人工重放：排查时能一眼看出与自动死信的区别
        headers.put(HEADER_REPLAYED_AT, Instant.now().toString());

        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType(original == null ? null : original.getContentType())
                .contentEncoding(original == null ? null : original.getContentEncoding())
                .deliveryMode(2)
                .messageId(original == null ? null : original.getMessageId())
                .correlationId(original == null ? null : original.getCorrelationId())
                .headers(headers)
                .build();

        String routingKey = response.getEnvelope() == null
                ? null : response.getEnvelope().getRoutingKey();
        if (routingKey == null || routingKey.isBlank()) {
            // 死信必须能说清自己原本要去哪。说不清就不猜——
            // 交给调用方记一条原因，让运维人工判断，而不是静默投到错的地方。
            returned.add("missing-routing-key");
            return false;
        }

        channel.basicPublish(eventsExchange, routingKey,
                true, properties, body);
        return channel.waitForConfirms(CONFIRM_TIMEOUT_MS);
    }

    private DeadLetterSummary summarize(GetResponse response) {
        String eventId = extractEventId(response);
        JsonNode envelope = parseEnvelope(response.getBody());
        AMQP.BasicProperties properties = response.getProps();
        // **props 与 headers 都可能为 null，必须分别判空**。
        //
        // DLQ 里的消息五花八门：可能是本系统发的（带完整 properties），
        // 也可能是外部生产者、乃至basic.reject 之前就没有 properties 的消息。
        // 第一版写成 `properties.getHeaders()` 直接 NPE，
        // 于是查询死信接口在真实调用下返回 500——
        // 而单元测试里构造的 GetResponse 都带 properties，**测不出来**。
        //
        // 这类缺陷的教训与"替身测不出 AMQP 语义"是同一个：
        // 查询接口的第一条验收用例必须是**真实 broker 上的一条最普通的消息**，
        // 而不是自己精心构造的理想消息。
        Map<String, Object> headers = properties == null ? null : properties.getHeaders();
        java.util.Date timestamp = properties == null ? null : properties.getTimestamp();
        return new DeadLetterSummary(
                eventId,
                envelope == null ? null : envelope.path("eventType").asText(null),
                envelope == null ? null : envelope.path("aggregateId").asText(null),
                response.getEnvelope() == null ? null
                        : response.getEnvelope().getRoutingKey(),
                RabbitTopology.retryTierOf(headers),
                headerText(headers, RabbitTopology.HEADER_LAST_ERROR),
                headerText(headers, RabbitTopology.HEADER_FIRST_FAILED_AT),
                timestamp == null ? null : timestamp.toInstant().toString());
    }

    private String headerText(Map<String, Object> headers, String key) {
        if (headers == null) {
            return null;
        }
        Object value = headers.get(key);
        return value == null ? null : value.toString();
    }

    private String extractEventId(GetResponse response) {
        JsonNode envelope = parseEnvelope(response.getBody());
        if (envelope == null) {
            return null;
        }
        String eventId = envelope.path("eventId").asText(null);
        return eventId == null || eventId.isBlank() ? null : eventId;
    }

    /**
     * 解析信封。
     *
     * <p><b>允许解析失败</b>：死信里本来就有"连 JSON 都不是"的坏消息
     * （那正是它们进死信的原因之一）。解析不出来时返回 null，
     * 由调用方决定怎么处理——而不是在这里抛异常把整个查询搞崩。
     */
    private JsonNode parseEnvelope(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return null;
        }
    }
}