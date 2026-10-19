package com.repopilot.common.reliability.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.hash.Sha256;
import com.repopilot.common.id.Ulid;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ReturnListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RabbitMQ 事件发布实现。
 *
 * <p>三件事必须同时成立，"发布成功"才有意义：
 * <ul>
 *   <li><b>持久化消息</b>（{@code deliveryMode=PERSISTENT}）+
 *       <b>durable 队列</b>：broker 重启后消息还在；</li>
 *   <li><b>publisher confirms</b>：等 broker 明确说"收到了"；</li>
 *   <li><b>mandatory + return</b>：路由不到任何队列时 broker 会退回。
 *       没有这一项，{@code publish()} 返回成功但没有任何消费者会收到——
 *       拓扑配错时的经典静默故障。</li>
 * </ul>
 *
 * <p><b>每条消息一个独立 channel</b>而不是共享 {@code RabbitTemplate}：
 * confirm 回调是 channel 级状态，共享模板上覆盖回调会让并发发布互相串台——
 * A 消息的 nack 可能被记到 B 消息头上。独立 channel 的代价是一次 AMQP 往返，
 * 换来的是"每条消息的确认结果只属于它自己"。
 *
 * <p><b>关于 confirm 丢失</b>：confirm 超时不代表 broker 没收到，这是"不确定"不是"失败"。
 * {@link PublishOutcome#uncertain} 让 Outbox 保留 PENDING 并重试，于是可能出现重复投递
 * ——这正是 inbox 去重存在的原因。本实现不宣称恰好一次，
 *也<b>不用 publisher confirm 推断 Agent 是否完成</b>。
 *
 * <p><b>为什么是本bean 而不是共享 RabbitTemplate</b>：
 * {@code matchIfMissing} 让它在没有显式配置时生效；而当配置指向别的实现
 * （如故障注入替身）时 {@code havingValue} 不匹配，本 bean 不注册——
 * 这样两个实现不会同时成为 {@code @Primary}，也不会出现"两个候选"的启动失败。
 */
@Component
@Primary
@ConditionalOnProperty(name = "repopilot.outbox.publisher", havingValue = "rabbit",
        matchIfMissing = true)
public class RabbitEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisher.class);

    /** 确认等待上限。超过则判为 unknown（可能已送达），交给重试与去重。 */
    private static final long CONFIRM_TIMEOUT_MS = 5_000L;

    private final ConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;
    private final String publisherId;

    public RabbitEventPublisher(ConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
        this.publisherId = "rabbit-" + Ulid.newId();
    }

    @Override
    public String publisherId() {
        return publisherId;
    }

    @Override
    public boolean available() {
        try (Connection connection = connectionFactory.createConnection()) {
            return connection.isOpen();
        } catch (RuntimeException ex) {
            log.debug("RabbitMQ 不可用: {}", ex.getMessage());
            return false;
        }
    }

    @Override
    public PublishOutcome publish(String routingKey, JsonNode envelope) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(envelope);
        } catch (Exception ex) {
            // 序列化失败是**永久错误**：重试多少次结果都一样，必须靠有限重试收敛到 FAILED
            return PublishOutcome.failed("信封序列化失败: " + ex.getMessage());
        }

        String eventId = envelope.path("eventId").asText(Ulid.newId());
        AMQP.BasicProperties properties = buildProperties(envelope, body, eventId);

        try (Connection connection = connectionFactory.createConnection();
             com.rabbitmq.client.Channel channel = connection.createChannel(false)) {
            AtomicReference<String> returned = new AtomicReference<>();
            channel.addReturnListener((ReturnListener) (returnedMessage, replyCode, replyText,
                                             exchange, returnedRoutingKey, returnedProps) ->
                    returned.compareAndSet(null,
                            "replyCode=" + replyCode + " replyText=" + replyText));
            channel.confirmSelect();
            // mandatory=true：路由不到队列时 broker 退回而不是默默丢弃
            channel.basicPublish(routingKey, "", true, properties, body);
            try {
                // 逐条等待确认：一条消息一个 channel，确认结果只属于它自己
                channel.waitForConfirms(CONFIRM_TIMEOUT_MS);
            } catch (Exception ex) {
                // 超时/中断：不能断言失败，broker 可能已经收下
                return PublishOutcome.uncertain("等待 confirm 失败: " + ex.getMessage());
            }
            if (returned.get() != null) {
                // ack 与 return 可同时发生：路由不到队列时 broker 照样 ack。
                // 只看 confirm 会把"发到没有消费者的队列"当成成功
                return PublishOutcome.returned(returned.get());
            }
            return PublishOutcome.success();
        } catch (Exception ex) {
            // 连接失败 / channel 关闭 / IO 异常：都是可重试的基础设施问题
            return PublishOutcome.failed("AMQP 失败: " + ex.getMessage());
        }
    }

    private static AMQP.BasicProperties buildProperties(JsonNode envelope, byte[] body,
                                                         String eventId) {
        return new AMQP.BasicProperties.Builder()
                .contentType(MessageProperties.CONTENT_TYPE_JSON)
                .contentEncoding(StandardCharsets.UTF_8.name())
                // 2 = PERSISTENT：broker 重启后仍在。少了这一行，
                // 队列里的消息会随连接断开消失，而日志里"发布成功"照旧
                .deliveryMode(2)
                .messageId(eventId)
                .headers(Map.of(
                        "eventType", envelope.path("eventType").asText(""),
                        "schemaVersion", envelope.path("schemaVersion").asInt(1),
                        // 内容摘要：消费者用它发现「同 eventId 异 payload」——
                        // 那是数据损坏或上游 bug，必须隔离而不是当正常重复静默 ACK
                        "payloadHash", Sha256.ofBytes(body)))
                .build();
    }
}
