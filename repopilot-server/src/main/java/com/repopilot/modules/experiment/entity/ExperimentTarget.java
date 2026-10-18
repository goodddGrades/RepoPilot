package com.repopilot.modules.experiment.entity;

import java.time.Instant;

/**
 * 在线/离线比较对象。
 *
 * <p>对应表 {@code experiment_targets}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class ExperimentTarget {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 所属实验 */
    private String experimentId;
    /** 实验内稳定标识 */
    private String targetKey;
    /** 显示名称 */
    private String displayName;
    /** 离线可空；在线必须已发布 */
    private String agentVersionId;
    /** 每个样本的重复观察数 */
    private Integer repeatCount;
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

    public String getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(String experimentId) {
        this.experimentId = experimentId;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public void setTargetKey(String targetKey) {
        this.targetKey = targetKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAgentVersionId() {
        return agentVersionId;
    }

    public void setAgentVersionId(String agentVersionId) {
        this.agentVersionId = agentVersionId;
    }

    public Integer getRepeatCount() {
        return repeatCount;
    }

    public void setRepeatCount(Integer repeatCount) {
        this.repeatCount = repeatCount;
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
