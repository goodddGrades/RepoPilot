package com.repopilot.modules.evaluation.entity;

import java.time.Instant;

/**
 * 批次固定登记的Run集合；主键即(batch_id, run_id)，无独立生命周期。
 *
 * <p>对应表 {@code evaluation_batch_runs}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class EvaluationBatchRun {

    /** 评测批次 */
    private String batchId;
    /** 参与该批次的Run */
    private String runId;
    /** 冗余自批次与Run，供两条复合外键强制同实验 */
    private String experimentId;
    /** 加入时间 */
    private Instant createdAt;


    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(String experimentId) {
        this.experimentId = experimentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
