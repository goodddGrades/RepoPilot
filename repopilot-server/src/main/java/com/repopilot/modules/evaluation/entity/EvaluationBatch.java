package com.repopilot.modules.evaluation.entity;

import com.repopilot.common.state.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 一次对待评测Run集合按固定方案版本的批量评测；Run集合由evaluation_batch_runs固定，重评创建新批次；每实验最多一个INITIAL批次由生成列唯一键强制。
 *
 * <p>对应表 {@code evaluation_batches}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class EvaluationBatch {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 所属实验 */
    private String experimentId;
    /** 本批次使用的已发布方案版本，可不同于实验初始版本 */
    private String evaluationPlanVersionId;
    /** 实验内递增，从1开始 */
    private Integer batchNo;
    /** INITIAL/REEVALUATION/MANUAL */
    private BatchTriggerType triggerType;
    /** 重评来源批次，首次批次为空 */
    private String parentBatchId;
    /** 生成列：INITIAL批次取experiment_id，其余为NULL；唯一键利用MySQL多NULL允许的特性，保证每实验最多一个INITIAL批次 */
    private String initialExperimentId;
    /** Run选择范围快照：显式runIds或过滤条件与排除原因 */
    private JsonNode selectionJson;
    /** 技术状态 */
    private BatchStatus status;
    /** 创建Job前冻结的应有Job数 */
    private Long expectedJobCount;
    /** 发起人或调度器标识，不做虚假用户外键 */
    private String createdBy;
    /** 首次开始时间 */
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

    public String getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(String experimentId) {
        this.experimentId = experimentId;
    }

    public String getEvaluationPlanVersionId() {
        return evaluationPlanVersionId;
    }

    public void setEvaluationPlanVersionId(String evaluationPlanVersionId) {
        this.evaluationPlanVersionId = evaluationPlanVersionId;
    }

    public Integer getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(Integer batchNo) {
        this.batchNo = batchNo;
    }

    public BatchTriggerType getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(BatchTriggerType triggerType) {
        this.triggerType = triggerType;
    }

    public String getParentBatchId() {
        return parentBatchId;
    }

    public void setParentBatchId(String parentBatchId) {
        this.parentBatchId = parentBatchId;
    }

    public String getInitialExperimentId() {
        return initialExperimentId;
    }

    public void setInitialExperimentId(String initialExperimentId) {
        this.initialExperimentId = initialExperimentId;
    }

    public JsonNode getSelectionJson() {
        return selectionJson;
    }

    public void setSelectionJson(JsonNode selectionJson) {
        this.selectionJson = selectionJson;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public void setStatus(BatchStatus status) {
        this.status = status;
    }

    public Long getExpectedJobCount() {
        return expectedJobCount;
    }

    public void setExpectedJobCount(Long expectedJobCount) {
        this.expectedJobCount = expectedJobCount;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
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
