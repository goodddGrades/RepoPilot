package com.repopilot.common.reliability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.reliability.entity.OutboxEvent;
import com.repopilot.common.reliability.event.EventEnvelope;
import com.repopilot.common.reliability.mapper.OutboxEventMapper;
import com.repopilot.common.state.OutboxStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 业务事务内的 Outbox 写入（TASK-011）。
 *
 * <p><b>唯一正确的用法是在业务事务里调用它</b>：方法用
 * {@code Propagation.MANDATORY} 而不是默认的 REQUIRED——
 * <ul>
 *   <li>REQUIRED 会让"忘记加事务"的情况静默成功：Outbox 单独提交，
 *       而业务状态回滚了，于是出现一条指向不存在聚合的通知；</li>
 *   <li>REQUIRED 也会让"事务已标记回滚"的情况把通知一起吞掉，
 *       调用方以为写成功了实际没有。</li>
 * </ul>
 * MANDATORY 让这两种错误在第一次调用时就炸出来：要么你在事务里，要么明确报错。
 *
 * <p>这是"至少一次"而不是"恰好一次"：事务保证了「状态变了」与「通知写了」同生共死，
 * 但发布环节仍可能重复投递。去重由消费者侧的 inbox 负责（见 {@code InboxService}）。
 */
@Service
public class OutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

    private final OutboxEventMapper outbox;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxEventMapper outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    /**
     * 写一条 {@code run.ready}：Run 已就绪，可以被 Worker 领取执行。
     *
     * @param runId       Run
     * @param correlationId 关联 ID，通常是触发这次状态变化的实验或导入批次
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String writeRunReady(String runId, String correlationId) {
        return write(EventEnvelope.RUN_READY, EventEnvelope.AGGREGATE_RUN, runId,
                correlationId, payload("runId", runId));
    }

    /** 写一条 {@code evaluation.ready}：评测 Job 已就绪。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public String writeEvaluationReady(String jobId, String correlationId) {
        return write(EventEnvelope.EVALUATION_READY, EventEnvelope.AGGREGATE_EVALUATION_JOB,
                jobId, correlationId, payload("jobId", jobId));
    }

    /** 写一条 {@code run.finished}：Run 到达终态（成功、失败、超时、取消都算）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public String writeRunFinished(String runId, String correlationId) {
        return write(EventEnvelope.RUN_FINISHED, EventEnvelope.AGGREGATE_RUN, runId,
                correlationId, payload("runId", runId));
    }

    /** 写一条 {@code experiment.finished}：实验到达终态。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public String writeExperimentFinished(String experimentId, String correlationId) {
        return write(EventEnvelope.EXPERIMENT_FINISHED, EventEnvelope.AGGREGATE_EXPERIMENT,
                experimentId, correlationId, payload("experimentId", experimentId));
    }

    /**
     * 写一条 Outbox 事件。
     *
     * <p>event_id 在这里生成并成为 {@code outbox_events.id}：这样"消息 ID"与"Outbox 行 ID"
     * 是同一个值，运维从消息追到 Outbox 行不需要再做一次映射，
     * 消费者按 {@code event_id} 去重时也天然对得上。
     */
    private String write(String eventType, String aggregateType, String aggregateId,
                         String correlationId, ObjectNode payload) {
        Instant now = Instant.now();
        String eventId = Ulid.newId();

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("schemaVersion", EventEnvelope.CURRENT_SCHEMA_VERSION);
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", now.toString());
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId);
        // correlationId 缺省回落到聚合自身：没有上游时也要有一个可追溯的关联点，
        // 而不是留 null 让排查时断链。
        envelope.put("correlationId", correlationId == null || correlationId.isBlank()
                ? aggregateId : correlationId);
        envelope.set("payload", payload);

        // 服务端自检：发不出去的信封比不发更糟，因为它会在重试里反复失败。
        if (!new EventEnvelope(EventEnvelope.CURRENT_SCHEMA_VERSION, eventId, eventType,
                now, aggregateType, aggregateId,
                envelope.path("correlationId").asText(), payload).structurallyConsistent()) {
            throw new IllegalStateException("事件信封与契约不一致，拒绝写入 Outbox: " + eventType);
        }

        OutboxEvent entity = new OutboxEvent();
        entity.setId(eventId);
        entity.setAggregateType(aggregateType);
        entity.setAggregateId(aggregateId);
        entity.setEventType(eventType);
        entity.setEventVersion(EventEnvelope.CURRENT_SCHEMA_VERSION);
        entity.setPayloadJson(envelope);
        entity.setStatus(OutboxStatus.PENDING);
        entity.setRetryCount(0);
        // 首次即可发布：new_retry_at 为 NULL 表示"没有退避约束"
        entity.setNextRetryAt(null);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        outbox.insert(entity);

        log.debug("Outbox 已写入 eventId={} type={} aggregate={}", eventId, eventType, aggregateId);
        return eventId;
    }

    private ObjectNode payload(String field, String value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(field, value);
        return node;
    }
}
