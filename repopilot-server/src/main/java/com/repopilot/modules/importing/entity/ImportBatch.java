package com.repopilot.modules.importing.entity;

import com.repopilot.common.state.*;
import java.time.Instant;

/**
 * 导入文件与进度，恢复时锁批次行。
 *
 * <p>对应表 {@code import_batches}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class ImportBatch {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** DATASET_CASES或OFFLINE_RUNS */
    private ImportType importType;
    /** 样本导入为草稿；轨迹导入为已发布 */
    private String datasetVersionId;
    /** OFFLINE_RUNS必须有OFFLINE草稿实验 */
    private String experimentId;
    /** 校验过的原文件 */
    private String fileArtifactId;
    /** 批次技术状态 */
    private BatchStatus status;
    /** 完成扫描后确定非空物理行数 */
    private Long totalCount;
    /** 含幂等复用行 */
    private Long successCount;
    /** 错误行数 */
    private Long failedCount;
    /** 成功行中的幂等复用子集 */
    private Long duplicateCount;
    /** 最后提交的连续物理行号 */
    private Long lastLineNo;
    /** 开始时间 */
    private Instant startedAt;
    /** 结束时间 */
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

    public ImportType getImportType() {
        return importType;
    }

    public void setImportType(ImportType importType) {
        this.importType = importType;
    }

    public String getDatasetVersionId() {
        return datasetVersionId;
    }

    public void setDatasetVersionId(String datasetVersionId) {
        this.datasetVersionId = datasetVersionId;
    }

    public String getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(String experimentId) {
        this.experimentId = experimentId;
    }

    public String getFileArtifactId() {
        return fileArtifactId;
    }

    public void setFileArtifactId(String fileArtifactId) {
        this.fileArtifactId = fileArtifactId;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public void setStatus(BatchStatus status) {
        this.status = status;
    }

    public Long getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Long totalCount) {
        this.totalCount = totalCount;
    }

    public Long getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(Long successCount) {
        this.successCount = successCount;
    }

    public Long getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(Long failedCount) {
        this.failedCount = failedCount;
    }

    public Long getDuplicateCount() {
        return duplicateCount;
    }

    public void setDuplicateCount(Long duplicateCount) {
        this.duplicateCount = duplicateCount;
    }

    public Long getLastLineNo() {
        return lastLineNo;
    }

    public void setLastLineNo(Long lastLineNo) {
        this.lastLineNo = lastLineNo;
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
