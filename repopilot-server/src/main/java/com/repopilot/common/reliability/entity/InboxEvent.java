package com.repopilot.common.reliability.entity;

import java.time.Instant;

/**
 * 通知去重记录，TASK-011启用。
 *
 * <p>对应表 {@code inbox_events}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class InboxEvent {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 消费者逻辑名称 */
    private String consumerName;
    /** 消息信封eventId，区别于TraceEvent */
    private String eventId;
    /** 重复信封内容一致性检查 */
    private String payloadHash;
    /** 短事务处理提交时间 */
    private Instant processedAt;
    /** UTC创建时间 */
    private Instant createdAt;


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConsumerName() {
        return consumerName;
    }

    public void setConsumerName(String consumerName) {
        this.consumerName = consumerName;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
