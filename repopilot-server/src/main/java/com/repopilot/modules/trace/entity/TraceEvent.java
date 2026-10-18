package com.repopilot.modules.trace.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 规范化、追加式轨迹事件。
 *
 * <p>对应表 {@code trace_events}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class TraceEvent {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 全局幂等ULID；消息重放必须稳定 */
    private String eventId;
    /** 所属Run */
    private String runId;
    /** 在线必须指向其执行尝试，离线可空 */
    private String runAttemptId;
    /** 上游稳定标识 */
    private String sourceEventId;
    /** 事件规范化SHA256，用于同ID异载荷冲突 */
    private String eventHash;
    /** 合法32位小写十六进制非零 */
    private String traceId;
    /** 合法16位小写十六进制非零 */
    private String spanId;
    /** 父span，可缺失 */
    private String parentSpanId;
    /** 来源顺序，可重复，不作唯一键 */
    private Long sequenceNo;
    /** 见事件契约注册表 */
    private String eventType;
    /** UTC事件发生时间 */
    private Instant occurredAt;
    /** 脱敏载荷，单事件上限64KiB */
    private JsonNode payloadJson;
    /** 大对象引用 */
    private String artifactId;
    /** UTC创建时间 */
    private Instant createdAt;


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getRunAttemptId() {
        return runAttemptId;
    }

    public void setRunAttemptId(String runAttemptId) {
        this.runAttemptId = runAttemptId;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public void setSourceEventId(String sourceEventId) {
        this.sourceEventId = sourceEventId;
    }

    public String getEventHash() {
        return eventHash;
    }

    public void setEventHash(String eventHash) {
        this.eventHash = eventHash;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public void setSpanId(String spanId) {
        this.spanId = spanId;
    }

    public String getParentSpanId() {
        return parentSpanId;
    }

    public void setParentSpanId(String parentSpanId) {
        this.parentSpanId = parentSpanId;
    }

    public Long getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(Long sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public JsonNode getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(JsonNode payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
