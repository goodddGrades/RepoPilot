package com.repopilot.modules.experiment.entity;

import com.repopilot.common.state.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 固定版本的实验。
 *
 * <p>对应表 {@code experiments}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class Experiment {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 名称 */
    private String name;
    /** 说明 */
    private String description;
    /** ONLINE或OFFLINE */
    private RunMode mode;
    /** 固定已发布样本版本 */
    private String datasetVersionId;
    /** 固定已发布方案版本 */
    private String evaluationPlanVersionId;
    /** 技术状态 */
    private ExperimentStatus status;
    /** 运行预算等不可变提交快照 */
    private JsonNode configJson;
    /** 开始时冻结的槽位总数 */
    private Long expectedRunCount;
    /** 首次开始 */
    private Instant startedAt;
    /** 终态时间 */
    private Instant finishedAt;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public RunMode getMode() {
        return mode;
    }

    public void setMode(RunMode mode) {
        this.mode = mode;
    }

    public String getDatasetVersionId() {
        return datasetVersionId;
    }

    public void setDatasetVersionId(String datasetVersionId) {
        this.datasetVersionId = datasetVersionId;
    }

    public String getEvaluationPlanVersionId() {
        return evaluationPlanVersionId;
    }

    public void setEvaluationPlanVersionId(String evaluationPlanVersionId) {
        this.evaluationPlanVersionId = evaluationPlanVersionId;
    }

    public ExperimentStatus getStatus() {
        return status;
    }

    public void setStatus(ExperimentStatus status) {
        this.status = status;
    }

    public JsonNode getConfigJson() {
        return configJson;
    }

    public void setConfigJson(JsonNode configJson) {
        this.configJson = configJson;
    }

    public Long getExpectedRunCount() {
        return expectedRunCount;
    }

    public void setExpectedRunCount(Long expectedRunCount) {
        this.expectedRunCount = expectedRunCount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
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
