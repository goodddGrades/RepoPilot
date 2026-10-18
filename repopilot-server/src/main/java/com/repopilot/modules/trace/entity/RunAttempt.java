package com.repopilot.modules.trace.entity;

import com.repopilot.common.state.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 每次执行尝试；失败重试保留历史。
 *
 * <p>对应表 {@code run_attempts}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class RunAttempt {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 逻辑Run */
    private String runId;
    /** 单调递增 */
    private Integer attemptNo;
    /** Worker实例标识 */
    private String workerId;
    /** 尝试状态 */
    private AttemptStatus status;
    /** 随机Token摘要，不存明文 */
    private String leaseTokenHash;
    /** 数据库UTC租约截止 */
    private Instant leaseUntil;
    /** 硬截止，续租不得越过 */
    private Instant deadlineAt;
    /** 完成回调的规范化摘要，支持重放 */
    private String completionHash;
    /** 尝试输出，失败亦可留存 */
    private JsonNode outputJson;
    /** 大输出附件 */
    private String outputArtifactId;
    /** 本尝试用量，包括重试成本 */
    private JsonNode usageJson;
    /** 领取时间 */
    private Instant startedAt;
    /** 结束时间 */
    private Instant finishedAt;
    /** 结构化错误码 */
    private String errorCode;
    /** 已脱敏错误摘要 */
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

    public Integer getAttemptNo() {
        return attemptNo;
    }

    public void setAttemptNo(Integer attemptNo) {
        this.attemptNo = attemptNo;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public void setStatus(AttemptStatus status) {
        this.status = status;
    }

    public String getLeaseTokenHash() {
        return leaseTokenHash;
    }

    public void setLeaseTokenHash(String leaseTokenHash) {
        this.leaseTokenHash = leaseTokenHash;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Instant leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public void setDeadlineAt(Instant deadlineAt) {
        this.deadlineAt = deadlineAt;
    }

    public String getCompletionHash() {
        return completionHash;
    }

    public void setCompletionHash(String completionHash) {
        this.completionHash = completionHash;
    }

    public JsonNode getOutputJson() {
        return outputJson;
    }

    public void setOutputJson(JsonNode outputJson) {
        this.outputJson = outputJson;
    }

    public String getOutputArtifactId() {
        return outputArtifactId;
    }

    public void setOutputArtifactId(String outputArtifactId) {
        this.outputArtifactId = outputArtifactId;
    }

    public JsonNode getUsageJson() {
        return usageJson;
    }

    public void setUsageJson(JsonNode usageJson) {
        this.usageJson = usageJson;
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
