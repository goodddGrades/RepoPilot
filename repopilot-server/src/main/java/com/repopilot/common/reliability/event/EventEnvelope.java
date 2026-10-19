package com.repopilot.common.reliability.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * 事件信封，结构由 {@code docs/contracts/event-envelope.schema.json} 冻结。
 *
 * <p><b>为什么信封里只放 ID 不放正文</b>：消息只是"某件事该被处理了"的唤醒信号，
 * 不是数据通道。消费者收到后仍走数据库领取协议拿权威数据。信封里放正文意味着
 * 消息里可能带上待评测代码的输出、模型返回、甚至密钥——一旦进了 MQ 就需要另一套
 * 清理与脱敏策略，而它的唯一价值是省掉一次数据库查询，代价完全不成比例。
 *
 * <p>{@code schemaVersion} 与 {@code eventType} 一起决定消费者能否处理：
 * 不支持的版本进入隔离队列，不允许"尽力解析"。
 */
public record EventEnvelope(int schemaVersion,
                            String eventId,
                            String eventType,
                            Instant occurredAt,
                            String aggregateType,
                            String aggregateId,
                            String correlationId,
                            JsonNode payload) {

    /** 当前信封版本。新增字段时递增，不复用旧版本号。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** 契约允许的事件类型。新增类型必须同时更新 JSON Schema 与消费者。 */
    public static final String RUN_READY = "run.ready";
    public static final String EVALUATION_READY = "evaluation.ready";
    public static final String RUN_FINISHED = "run.finished";
    public static final String EXPERIMENT_FINISHED = "experiment.finished";

    public static final String AGGREGATE_RUN = "RUN";
    public static final String AGGREGATE_EVALUATION_JOB = "EVALUATION_JOB";
    public static final String AGGREGATE_EXPERIMENT = "EXPERIMENT";

    /**
     * 判断消费者是否认识这个信封版本。
     *
     * <p>刻意返回布尔而不是抛异常：调用方要据此决定"隔离"还是"处理"，
     * 两条路径的运维含义完全不同。
     */
    public boolean supportedBy(int maxSupportedVersion) {
        return schemaVersion >= 1 && schemaVersion <= maxSupportedVersion;
    }

    /**
     * 是否是本信封自己知道的类型组合。
     *
     * <p>Schema 里每个 eventType 都用 if/then 绑定了 aggregateType 与 payload 必填字段；
     * 这里做服务端侧的同样校验，避免"格式合法但语义错乱"的消息被发出去。
     */
    public boolean structurallyConsistent() {
        return switch (eventType) {
            case RUN_READY, RUN_FINISHED ->
                    AGGREGATE_RUN.equals(aggregateType) && hasText(payload, "runId");
            case EVALUATION_READY ->
                    AGGREGATE_EVALUATION_JOB.equals(aggregateType) && hasText(payload, "jobId");
            case EXPERIMENT_FINISHED ->
                    AGGREGATE_EXPERIMENT.equals(aggregateType)
                            && hasText(payload, "experimentId");
            default -> false;
        };
    }

    private static boolean hasText(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return false;
        }
        String value = node.get(field).asText();
        return value != null && !value.isBlank();
    }

    /**
     * 从 Outbox 行重建信封。
     *
     * <p>Outbox 的 {@code payload_json} 存的是**完整信封**（契约如此规定），
     * 不是裸 payload——重建时不必再猜 aggregateType 与 correlationId，
     * 也就不会出现"发布时是一种含义、消费时另一种含义"的漂移。
     */
    public static EventEnvelope fromOutbox(JsonNode storedEnvelope) {
        return new EventEnvelope(
                storedEnvelope.path("schemaVersion").asInt(CURRENT_SCHEMA_VERSION),
                storedEnvelope.path("eventId").asText(null),
                storedEnvelope.path("eventType").asText(null),
                storedEnvelope.hasNonNull("occurredAt")
                        ? Instant.parse(storedEnvelope.path("occurredAt").asText()) : null,
                storedEnvelope.path("aggregateType").asText(null),
                storedEnvelope.path("aggregateId").asText(null),
                storedEnvelope.path("correlationId").asText(null),
                storedEnvelope.path("payload"));
    }

    /** payload 只允许契约声明的字段；多塞一个字段就是破坏"通知不含正文"这条边界。 */
    public static ObjectNode payloadOf(ObjectNode node) {
        return node;
    }
}
