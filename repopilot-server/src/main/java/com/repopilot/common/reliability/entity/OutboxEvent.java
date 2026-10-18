package com.repopilot.common.reliability.entity;

import com.repopilot.common.state.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 事务消息，TASK-002建表，TASK-011实现发布。
 *
 * <p>对应表 {@code outbox_events}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class OutboxEvent {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 聚合类型 */
    private String aggregateType;
    /** 多态聚合ID，服务层校验，无虚假多态外键 */
    private String aggregateId;
    /** 事件类型 */
    private String eventType;
    /** 信封版本 */
    private Integer eventVersion;
    /** 完整稳定信封，不含正文与密钥 */
    private JsonNode payloadJson;
    /** 发布状态 */
    private OutboxStatus status;
    /** 发布重试次数 */
    private Integer retryCount;
    /** 下次发布时间 */
    private Instant nextRetryAt;
    /** 当前发布器 */
    private String publisherId;
    /** 发布领取租约 */
    private Instant leaseUntil;
    /** 发布领取隔离令牌摘要 */
    private String claimTokenHash;
    /** 确认发布时间 */
    private Instant publishedAt;
    /** UTC创建时间 */
    private Instant createdAt;
    /** UTC更新时间 */
    private Instant updatedAt;


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Integer getEventVersion() {
        return eventVersion;
    }

    public void setEventVersion(Integer eventVersion) {
        this.eventVersion = eventVersion;
    }

    public JsonNode getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(JsonNode payloadJson) {
        this.payloadJson = payloadJson;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public void setStatus(OutboxStatus status) {
        this.status = status;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public String getPublisherId() {
        return publisherId;
    }

    public void setPublisherId(String publisherId) {
        this.publisherId = publisherId;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Instant leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public String getClaimTokenHash() {
        return claimTokenHash;
    }

    public void setClaimTokenHash(String claimTokenHash) {
        this.claimTokenHash = claimTokenHash;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
