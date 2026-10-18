package com.repopilot.modules.evaluationconfig.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 顺序执行的固定算子配置。
 *
 * <p>对应表 {@code evaluation_plan_items}，仅用于持久化，不作为 API 响应直接返回：
 * 对外 DTO 由后续 TASK 按契约裁剪，避免把内部列暴露成接口字段。
 */
public class EvaluationPlanItem {

    /** 应用层生成ULID，ascii_bin */
    private String id;
    /** 所属方案版本 */
    private String planVersionId;
    /** 固定算子版本 */
    private String evaluatorDefinitionId;
    /** 从1递增且版本内唯一 */
    private Integer executionOrder;
    /** 影响技术完成，不等同于质量通过 */
    private Boolean required;
    /** 算子参数与阈值，空配置用对象 */
    private JsonNode configJson;
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

    public String getPlanVersionId() {
        return planVersionId;
    }

    public void setPlanVersionId(String planVersionId) {
        this.planVersionId = planVersionId;
    }

    public String getEvaluatorDefinitionId() {
        return evaluatorDefinitionId;
    }

    public void setEvaluatorDefinitionId(String evaluatorDefinitionId) {
        this.evaluatorDefinitionId = evaluatorDefinitionId;
    }

    public Integer getExecutionOrder() {
        return executionOrder;
    }

    public void setExecutionOrder(Integer executionOrder) {
        this.executionOrder = executionOrder;
    }

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }

    public JsonNode getConfigJson() {
        return configJson;
    }

    public void setConfigJson(JsonNode configJson) {
        this.configJson = configJson;
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
