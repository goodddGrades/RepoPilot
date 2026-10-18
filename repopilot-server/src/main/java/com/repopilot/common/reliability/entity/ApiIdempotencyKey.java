package com.repopilot.common.reliability.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 创建与状态动作的请求幂等。
 *
 * <p>对应表 {@code api_idempotency_keys}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class ApiIdempotencyKey {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 身份+操作+资源范围的规范化摘要标识 */
    private String scope;
    /** 调用者提供的请求标识 */
    private String idempotencyKey;
    /** 规范化正文摘要 */
    private String requestHash;
    /** 已创建或已操作资源类型 */
    private String resourceType;
    /** 结果资源ID，多态无直接外键 */
    private String resourceId;
    /** 原始成功响应状态 */
    private Integer httpStatus;
    /** 原始成功响应data，返回时使用当前requestId */
    private JsonNode responseJson;
    /** 至少7天，业务唯一键永久生效 */
    private Instant expiresAt;
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

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public JsonNode getResponseJson() {
        return responseJson;
    }

    public void setResponseJson(JsonNode responseJson) {
        this.responseJson = responseJson;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
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
