package com.repopilot.modules.importing.entity;

import java.time.Instant;

/**
 * 脱敏错误定位，不存原始敏感行。
 *
 * <p>对应表 {@code import_errors}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class ImportError {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 所属批次 */
    private String batchId;
    /** 物理行号 */
    private Long lineNo;
    /** 结构化原因 */
    private String errorCode;
    /** 脱敏字段级摘要 */
    private String errorMessage;
    /** 原始行SHA256 */
    private String recordHash;
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

    public String getRecordHash() {
        return recordHash;
    }

    public void setRecordHash(String recordHash) {
        this.recordHash = recordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
