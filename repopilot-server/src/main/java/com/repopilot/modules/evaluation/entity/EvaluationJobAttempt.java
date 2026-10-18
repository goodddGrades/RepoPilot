package com.repopilot.modules.evaluation.entity;

import com.repopilot.common.state.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 算子重试历史与租约。
 *
 * <p>对应表 {@code evaluation_job_attempts}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class EvaluationJobAttempt {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 所属Job */
    private String jobId;
    /** 单调递增 */
    private Integer attemptNo;
    /** Worker实例 */
    private String workerId;
    /** 尝试状态 */
    private AttemptStatus status;
    /** Token摘要 */
    private String leaseTokenHash;
    /** 租约截止 */
    private Instant leaseUntil;
    /** 硬截止 */
    private Instant deadlineAt;
    /** 回调摘要 */
    private String completionHash;
    /** 本次尝试结果，仅最终合法尝试晋升Result */
    private JsonNode resultJson;
    /** Judge等实际成本 */
    private JsonNode usageJson;
    /** 技术错误码 */
    private String errorCode;
    /** 脱敏错误摘要 */
    private String errorMessage;
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

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
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

    public JsonNode getResultJson() {
        return resultJson;
    }

    public void setResultJson(JsonNode resultJson) {
        this.resultJson = resultJson;
    }

    public JsonNode getUsageJson() {
        return usageJson;
    }

    public void setUsageJson(JsonNode usageJson) {
        this.usageJson = usageJson;
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
