package com.repopilot.modules.importing.entity;

import com.repopilot.common.state.*;
import java.time.Instant;

/**
 * 每行导入回执，确保重放不重复计数。
 *
 * <p>对应表 {@code import_records}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class ImportRecord {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 批次 */
    private String batchId;
    /** 从1开始的物理行号；空行不建回执 */
    private Long lineNo;
    /** 原始行字节SHA256，不持有原文 */
    private String recordHash;
    /** IMPORTED/REUSED/REJECTED */
    private ImportRecordStatus status;
    /** 成功时对应样本 */
    private String datasetCaseId;
    /** 历史运行导入时对应Run */
    private String runId;
    /** UTC创建时间 */
    private Instant createdAt;


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public Long getLineNo() {
        return lineNo;
    }

    public void setLineNo(Long lineNo) {
        this.lineNo = lineNo;
    }

    public String getRecordHash() {
        return recordHash;
    }

    public void setRecordHash(String recordHash) {
        this.recordHash = recordHash;
    }

    public ImportRecordStatus getStatus() {
        return status;
    }

    public void setStatus(ImportRecordStatus status) {
        this.status = status;
    }

    public String getDatasetCaseId() {
        return datasetCaseId;
    }

    public void setDatasetCaseId(String datasetCaseId) {
        this.datasetCaseId = datasetCaseId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
