package com.repopilot.modules.agent.entity;

import com.repopilot.common.state.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 发布后不可变的执行配置。
 *
 * <p>对应表 {@code agent_versions}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class AgentVersion {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 所属Agent */
    private String agentId;
    /** 从1递增 */
    private Integer version;
    /** Runner种类 */
    private RunnerType runnerType;
    /** 完整配置快照，仅含secretRef */
    private JsonNode configJson;
    /** 规范化配置SHA256 */
    private String configHash;
    /** 版本状态 */
    private VersionStatus status;
    /** UTC发布时间 */
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

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public RunnerType getRunnerType() {
        return runnerType;
    }

    public void setRunnerType(RunnerType runnerType) {
        this.runnerType = runnerType;
    }

    public JsonNode getConfigJson() {
        return configJson;
    }

    public void setConfigJson(JsonNode configJson) {
        this.configJson = configJson;
    }

    public String getConfigHash() {
        return configHash;
    }

    public void setConfigHash(String configHash) {
        this.configHash = configHash;
    }

    public VersionStatus getStatus() {
        return status;
    }

    public void setStatus(VersionStatus status) {
        this.status = status;
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
