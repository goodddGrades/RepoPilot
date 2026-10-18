package com.repopilot.modules.dataset.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 版本内样本身份。
 *
 * <p>对应表 {@code dataset_cases}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class DatasetCase {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 所属版本 */
    private String datasetVersionId;
    /** 版本内稳定key，大小写敏感 */
    private String caseKey;
    /** 任务类型，由注册表校验 */
    private String taskType;
    /** 规范化任务输入 */
    private JsonNode inputJson;
    /** 期望结果，未知时NULL */
    private JsonNode expectedJson;
    /** 标签与扩展信息，不存密钥 */
    private JsonNode metadataJson;
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

    public String getDatasetVersionId() {
        return datasetVersionId;
    }

    public void setDatasetVersionId(String datasetVersionId) {
        this.datasetVersionId = datasetVersionId;
    }

    public String getCaseKey() {
        return caseKey;
    }

    public void setCaseKey(String caseKey) {
        this.caseKey = caseKey;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public JsonNode getInputJson() {
        return inputJson;
    }

    public void setInputJson(JsonNode inputJson) {
        this.inputJson = inputJson;
    }

    public JsonNode getExpectedJson() {
        return expectedJson;
    }

    public void setExpectedJson(JsonNode expectedJson) {
        this.expectedJson = expectedJson;
    }

    public JsonNode getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(JsonNode metadataJson) {
        this.metadataJson = metadataJson;
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
