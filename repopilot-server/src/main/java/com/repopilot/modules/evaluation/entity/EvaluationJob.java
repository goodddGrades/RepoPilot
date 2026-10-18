package com.repopilot.modules.evaluation.entity;

import com.repopilot.common.state.*;
import java.time.Instant;

/**
 * 批次内Run×方案项的逻辑评测任务；Run必须已登记在evaluation_batch_runs。
 *
 * <p>对应表 {@code evaluation_jobs}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class EvaluationJob {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 所属Run，必须已登记在本批次 */
    private String runId;
    /** 所属评测批次 */
    private String batchId;
    /** 冗余自批次，用于复合外键锁定方案项归属 */
    private String evaluationPlanVersionId;
    /** 固定方案项，服务层检查属于实验方案 */
    private String evaluationPlanItemId;
    /** 技术状态 */
    private JobStatus status;
    /** 已创建尝试数 */
    private Integer attemptCount;
    /** 当前尝试 */
    private String activeAttemptId;
    /** 退避时间 */
    private Instant nextRetryAt;
    /** 封口Run及固定算子输入摘要 */
    private String inputHash;
    /** 首次领取 */
    private Instant startedAt;
    /** 终态时间 */
    private Instant finishedAt;
    /** 最终技术失败原因 */
    private String errorCode;
    /** 脱敏错误摘要 */
    private String errorMessage;
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

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getEvaluationPlanVersionId() {
        return evaluationPlanVersionId;
    }

    public void setEvaluationPlanVersionId(String evaluationPlanVersionId) {
        this.evaluationPlanVersionId = evaluationPlanVersionId;
    }

    public String getEvaluationPlanItemId() {
        return evaluationPlanItemId;
    }

    public void setEvaluationPlanItemId(String evaluationPlanItemId) {
        this.evaluationPlanItemId = evaluationPlanItemId;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getActiveAttemptId() {
        return activeAttemptId;
    }

    public void setActiveAttemptId(String activeAttemptId) {
        this.activeAttemptId = activeAttemptId;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public String getInputHash() {
        return inputHash;
    }

    public void setInputHash(String inputHash) {
        this.inputHash = inputHash;
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

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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
