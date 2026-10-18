package com.repopilot.modules.evaluationconfig.entity;

import com.repopilot.common.state.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 算子实现与输入输出契约版本。
 *
 * <p>对应表 {@code evaluator_definitions}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class EvaluatorDefinition {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 算子稳定标识 */
    private String evaluatorKey;
    /** 版本号 */
    private Integer version;
    /** 名称 */
    private String displayName;
    /** 说明 */
    private String description;
    /** 算子分类 */
    private EvaluatorType evaluatorType;
    /** 注册的实现及代码或镜像版本 */
    private String implementationRef;
    /** 输入JSON Schema */
    private JsonNode inputSchemaJson;
    /** 输出JSON Schema */
    private JsonNode outputSchemaJson;
    /** 版本状态 */
    private VersionStatus status;
    /** 发布时间 */
    private Instant publishedAt;
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

    public String getEvaluatorKey() {
        return evaluatorKey;
    }

    public void setEvaluatorKey(String evaluatorKey) {
        this.evaluatorKey = evaluatorKey;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public EvaluatorType getEvaluatorType() {
        return evaluatorType;
    }

    public void setEvaluatorType(EvaluatorType evaluatorType) {
        this.evaluatorType = evaluatorType;
    }

    public String getImplementationRef() {
        return implementationRef;
    }

    public void setImplementationRef(String implementationRef) {
        this.implementationRef = implementationRef;
    }

    public JsonNode getInputSchemaJson() {
        return inputSchemaJson;
    }

    public void setInputSchemaJson(JsonNode inputSchemaJson) {
        this.inputSchemaJson = inputSchemaJson;
    }

    public JsonNode getOutputSchemaJson() {
        return outputSchemaJson;
    }

    public void setOutputSchemaJson(JsonNode outputSchemaJson) {
        this.outputSchemaJson = outputSchemaJson;
    }

    public VersionStatus getStatus() {
        return status;
    }

    public void setStatus(VersionStatus status) {
        this.status = status;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
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
