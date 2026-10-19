package com.repopilot.common.reliability.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.hash.Sha256;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.reliability.entity.InboxEvent;
import com.repopilot.common.reliability.event.EventEnvelope;
import com.repopilot.common.reliability.mapper.InboxEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * 消费侧去重（TASK-011）。
 *
 * <p>整套机制建立在一条事实上：<b>消息可能重复，而且必须假设它一定会重复</b>。
 * 重复的来源至少有三个，每个都无法根除：
 * <ul>
 *   <li>confirm 丢失 → 发布器认为失败而重发（broker 其实已收到）；</li>
 *   <li>消费者写完 inbox 但 ACK 前崩溃 → broker 重投；</li>
 *   <li>发布器在"改租约"之后、"标 PUBLISHED"之前崩溃 → 租约过期后被重新领取。</li>
 * </ul>
 *
 * <p>因此本服务的目标不是"避免重复"，而是让重复**没有业务效果**：
 * 唯一键 {@code (consumer_name, event_id)} 让第二次写入拿不到行数，
 * 业务逻辑就不会重跑一遍。
 *
 * <p><b>关键约束</b>：本服务只做「短事务唤醒登记」，
 * <b>不执行任何长时间任务</b>。Agent 调模型可能跑几分钟，
 * 绝不能把 MQ 消息一直挂在未确认状态等它跑完——那会让队列积压、
 * prefetch 被占满、一个慢任务拖垮整个消费者。
 */
@Service
public class InboxService {

    private static final Logger log = LoggerFactory.getLogger(InboxService.class);

    /** 本消费者支持的最大信封版本。更高版本进隔离，不尝试解析。 */
    private static final int MAX_SUPPORTED_VERSION = EventEnvelope.CURRENT_SCHEMA_VERSION;

    private final InboxEventMapper inbox;
    private final TransactionTemplate tx;

    public InboxService(InboxEventMapper inbox, PlatformTransactionManager transactionManager) {
        this.inbox = inbox;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 登记结果。 */
    public enum Outcome {
        /** 首次处理。 */
        ACCEPTED,
        /** 已处理过的同内容消息：正常重复，静默 ACK。 */
        DUPLICATE,
        /** 同 eventId 但内容不同：数据损坏或上游 bug，必须隔离。 */
        PAYLOAD_CONFLICT,
        /** 不支持的信封版本：进隔离队列，不无限 requeue。 */
        UNSUPPORTED_VERSION,
        /** 事件类型与聚合类型不匹配：契约违规。 */
        MALFORMED
    }

    public record Result(Outcome outcome, String eventId, String detail) {
    }

    /**
     * 登记一次消费。
     *
     * <p><b>不校验业务资源是否存在</b>：这里的职责只有去重。
     * 真正的"资源已删除/已取消"判断在领取协议里——那里才有事务与状态机的上下文，
     * 在通知层重复判断只会造出两套可能不一致的规则。
     */
    public Result accept(String consumerName, JsonNode envelope) {
        EventEnvelope parsed = EventEnvelope.fromOutbox(envelope);

        if (parsed.eventId() == null || parsed.eventId().isBlank()) {
            return new Result(Outcome.MALFORMED, "", "缺少 eventId，无法去重");
        }
        if (!parsed.supportedBy(MAX_SUPPORTED_VERSION)) {
            // 不尝试"尽力解析"：猜错一次就会产生错误的状态变更，
            // 而正确处置（隔离 + 告警）只在版本升级后才会消失
            return new Result(Outcome.UNSUPPORTED_VERSION, parsed.eventId(),
                    "信封版本 " + parsed.schemaVersion() + " 不在支持范围 [1,"
                            + MAX_SUPPORTED_VERSION + "]，已隔离");
        }
        if (!parsed.structurallyConsistent()) {
            return new Result(Outcome.MALFORMED, parsed.eventId(),
                    "信封与契约不一致: eventType=" + parsed.eventType()
                            + " aggregateType=" + parsed.aggregateType());
        }

        String payloadHash = Sha256.ofUtf8(envelope.toString());
        String eventId = parsed.eventId();

        return tx.execute(status -> {
            var existing = inbox.findPayloadHash(consumerName, eventId);
            if (existing.isPresent()) {
                if (existing.get().equals(payloadHash)) {
                    // 正常重复：消息会重投，不产生业务效果
                    return new Result(Outcome.DUPLICATE, eventId, "已处理过同内容消息");
                }
                // 同一 eventId 不同内容：正常重复不会走到这里，
                // 出现就说明上游改写了已发出的事件或数据损坏——必须让运维看见
                log.error("Inbox 内容冲突：consumer={} eventId={} 已存摘要 {} 新摘要 {}",
                        consumerName, eventId, existing.get(), payloadHash);
                return new Result(Outcome.PAYLOAD_CONFLICT, eventId,
                        "同 eventId 出现不同内容（已存 " + existing.get() + "，新 " + payloadHash
                                + "），已隔离待人工处理");
            }

            InboxEvent entity = new InboxEvent();
            entity.setId(Ulid.newId());
            entity.setConsumerName(consumerName);
            entity.setEventId(eventId);
            entity.setPayloadHash(payloadHash);
            Instant now = Instant.now();
            entity.setProcessedAt(now);
            entity.setCreatedAt(now);
            try {
                inbox.insert(entity);
            } catch (org.springframework.dao.DuplicateKeyException dup) {
                // 并发重复：另一个线程刚插入。唯一键就是并发闸门，
                // 输给它和"已存在"等价——业务上这是一条重复消息
                return new Result(Outcome.DUPLICATE, eventId, "并发重复，已被另一消费者实例登记");
            }
            return new Result(Outcome.ACCEPTED, eventId, "已登记");
        });
    }
}
