package com.repopilot.modules.annotation.entity;

import com.repopilot.common.state.*;
import java.time.Instant;

/**
 * 人工复核当前状态。
 *
 * <p>对应表 {@code annotation_tasks}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class AnnotationTask {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 复核对象 */
    private String runId;
    /** 可针对单算子 */
    private String evaluationJobId;
    /** 标注状态 */
    private AnnotationTaskStatus status;
    /** 测试身份或未来用户ID，不建虚假users外键 */
    private String assigneeId;
    /** 领取时间 */
    private Instant claimedAt;
    /** 提交时间 */
    private Instant submittedAt;
    /** 审核时间 */
    private Instant reviewedAt;
    /** 乐观锁版本 */
    private Long version;
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

    public String getEvaluationJobId() {
        return evaluationJobId;
    }

    public void setEvaluationJobId(String evaluationJobId) {
        this.evaluationJobId = evaluationJobId;
    }

    public AnnotationTaskStatus getStatus() {
        return status;
    }

    public void setStatus(AnnotationTaskStatus status) {
        this.status = status;
    }

    public String getAssigneeId() {
        return assigneeId;
    }

    public void setAssigneeId(String assigneeId) {
        this.assigneeId = assigneeId;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
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
