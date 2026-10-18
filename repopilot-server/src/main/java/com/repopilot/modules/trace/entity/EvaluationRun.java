package com.repopilot.modules.trace.entity;

import com.repopilot.common.state.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 逻辑观察与最终接受输出。
 *
 * <p>对应表 {@code evaluation_runs}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class EvaluationRun {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 所属实验 */
    private String experimentId;
    /** 所属对象 */
    private String experimentTargetId;
    /** 固定样本；服务层校验属于实验版本 */
    private String datasetCaseId;
    /** 逻辑重复编号，与Attempt不同 */
    private Integer repeatIndex;
    /** 首次创建此Run的批次 */
    private String importBatchId;
    /** 须与实验相同 */
    private RunMode mode;
    /** 技术状态 */
    private RunStatus status;
    /** 业务任务结果 */
    private TaskOutcome taskOutcome;
    /** 离线必填，在线可空 */
    private String sourceSystem;
    /** 外部记录标识，成对出现 */
    private String sourceRecordId;
    /** 原始规范化导入记录摘要 */
    private String sourceRecordHash;
    /** 合法OTel Trace ID；不能填ULID */
    private String traceId;
    /** 非OTel来源追踪引用 */
    private String externalTraceRef;
    /** 最终接受的规范化输出，小于等于64KiB */
    private JsonNode outputJson;
    /** 较大输出引用，不与output_json同时有值 */
    private String outputArtifactId;
    /** 最终观察用量、币种、来源与缺失标记 */
    private JsonNode usageJson;
    /** outputKnown、traceCoverage、outcomeSource与适配器版本等观测完整性 */
    private JsonNode observationMetadataJson;
    /** 已创建的执行尝试数 */
    private Integer attemptCount;
    /** 当前唯一执行尝试 */
    private String activeAttemptId;
    /** 最终被接受尝试；离线为空 */
    private String acceptedAttemptId;
    /** 再次可领取时间 */
    private Instant nextRetryAt;
    /** 执行数据封口时间 */
    private Instant sealedAt;
    /** 开始时间 */
    private Instant startedAt;
    /** 技术终态时间 */
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

    public String getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(String experimentId) {
        this.experimentId = experimentId;
    }

    public String getExperimentTargetId() {
        return experimentTargetId;
    }

    public void setExperimentTargetId(String experimentTargetId) {
        this.experimentTargetId = experimentTargetId;
    }

    public String getDatasetCaseId() {
        return datasetCaseId;
    }

    public void setDatasetCaseId(String datasetCaseId) {
        this.datasetCaseId = datasetCaseId;
    }

    public Integer getRepeatIndex() {
        return repeatIndex;
    }

    public void setRepeatIndex(Integer repeatIndex) {
        this.repeatIndex = repeatIndex;
    }

    public String getImportBatchId() {
        return importBatchId;
    }

    public void setImportBatchId(String importBatchId) {
        this.importBatchId = importBatchId;
    }

    public RunMode getMode() {
        return mode;
    }

    public void setMode(RunMode mode) {
        this.mode = mode;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public TaskOutcome getTaskOutcome() {
        return taskOutcome;
    }

    public void setTaskOutcome(TaskOutcome taskOutcome) {
        this.taskOutcome = taskOutcome;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getSourceRecordId() {
        return sourceRecordId;
    }

    public void setSourceRecordId(String sourceRecordId) {
        this.sourceRecordId = sourceRecordId;
    }

    public String getSourceRecordHash() {
        return sourceRecordHash;
    }

    public void setSourceRecordHash(String sourceRecordHash) {
        this.sourceRecordHash = sourceRecordHash;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getExternalTraceRef() {
        return externalTraceRef;
    }

    public void setExternalTraceRef(String externalTraceRef) {
        this.externalTraceRef = externalTraceRef;
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

    public JsonNode getObservationMetadataJson() {
        return observationMetadataJson;
    }

    public void setObservationMetadataJson(JsonNode observationMetadataJson) {
        this.observationMetadataJson = observationMetadataJson;
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

    public String getAcceptedAttemptId() {
        return acceptedAttemptId;
    }

    public void setAcceptedAttemptId(String acceptedAttemptId) {
        this.acceptedAttemptId = acceptedAttemptId;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public Instant getSealedAt() {
        return sealedAt;
    }

    public void setSealedAt(Instant sealedAt) {
        this.sealedAt = sealedAt;
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
