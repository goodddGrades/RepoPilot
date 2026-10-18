package com.repopilot.modules.evaluation.entity;

import com.repopilot.common.state.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 每个Job唯一最终自动结论。
 *
 * <p>对应表 {@code evaluation_results}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class EvaluationResult {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 所属Job */
    private String jobId;
    /** 产生结果的合法算子尝试 */
    private String jobAttemptId;
    /** 质量结论 */
    private Verdict verdict;
    /** 0到1，可空 */
    private BigDecimal score;
    /** 简要解释 */
    private String reason;
    /** 事件或附件引用 */
    private JsonNode evidenceJson;
    /** 完整结果，含缺失原因和实现信息 */
    private JsonNode resultJson;
    /** 生成来源 */
    private GeneratedBy generatedBy;
    /** UTC创建时间 */
    private Instant createdAt;


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

    public String getJobAttemptId() {
        return jobAttemptId;
    }

    public void setJobAttemptId(String jobAttemptId) {
        this.jobAttemptId = jobAttemptId;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public void setVerdict(Verdict verdict) {
        this.verdict = verdict;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
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

    public JsonNode getResultJson() {
        return resultJson;
    }

    public void setResultJson(JsonNode resultJson) {
        this.resultJson = resultJson;
    }

    public GeneratedBy getGeneratedBy() {
        return generatedBy;
    }

    public void setGeneratedBy(GeneratedBy generatedBy) {
        this.generatedBy = generatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
