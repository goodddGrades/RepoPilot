package com.repopilot.modules.annotation.entity;

import com.repopilot.common.state.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 追加式人工操作历史。
 *
 * <p>对应表 {@code annotation_records}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class AnnotationRecord {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 所属任务 */
    private String annotationTaskId;
    /** 操作者 */
    private String operatorId;
    /** CLAIM/SUBMIT/REVIEW/RETURN/CANCEL */
    private AnnotationAction action;
    /** 提交或审核的质量判断 */
    private Verdict verdict;
    /** 归因注册分类，允许OTHER */
    private String failureCategory;
    /** 解释 */
    private String reason;
    /** 事件、附件及标注证据 */
    private JsonNode evidenceJson;
    /** UTC创建时间 */
    private Instant createdAt;


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAnnotationTaskId() {
        return annotationTaskId;
    }

    public void setAnnotationTaskId(String annotationTaskId) {
        this.annotationTaskId = annotationTaskId;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(String operatorId) {
        this.operatorId = operatorId;
    }

    public AnnotationAction getAction() {
        return action;
    }

    public void setAction(AnnotationAction action) {
        this.action = action;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public void setVerdict(Verdict verdict) {
        this.verdict = verdict;
    }

    public String getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(String failureCategory) {
        this.failureCategory = failureCategory;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public JsonNode getEvidenceJson() {
        return evidenceJson;
    }

    public void setEvidenceJson(JsonNode evidenceJson) {
        this.evidenceJson = evidenceJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
