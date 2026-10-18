package com.repopilot.modules.evaluationconfig.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.state.EvaluatorType;
import com.repopilot.common.state.VersionStatus;
import com.repopilot.modules.evaluationconfig.entity.EvaluationPlan;
import com.repopilot.modules.evaluationconfig.entity.EvaluationPlanItem;
import com.repopilot.modules.evaluationconfig.entity.EvaluationPlanVersion;
import com.repopilot.modules.evaluationconfig.entity.EvaluatorDefinition;

import java.time.Instant;
import java.util.List;

/**
 * 评测算子与方案模块的请求/响应形状。
 *
 * <p>响应字段严格按契约列出：方案项只暴露 {@code id/evaluatorDefinitionId/executionOrder/required/config}。
 * 刻意不在项里内联 {@code evaluatorKey}——前端的做法是先取一次 {@code GET /evaluators} 建索引，
 * 而不是让每个方案项携带一份可能过期的算子名称。
 */
public final class EvaluationDtos {

    private EvaluationDtos() {
    }

    public record CreatePlanRequest(String code, String name, String description) {
    }

    public record PlanView(String id, String code, String name, String description,
                           Instant createdAt, Instant updatedAt) {

        public static PlanView from(EvaluationPlan entity) {
            return new PlanView(entity.getId(), entity.getCode(), entity.getName(),
                    entity.getDescription(), entity.getCreatedAt(), entity.getUpdatedAt());
        }
    }

    /** 方案项输入；{@code executionOrder} 省略时按数组下标自动编号（从 1 开始）。 */
    public record PlanItemRequest(String evaluatorDefinitionId, Integer executionOrder,
                                  Boolean required, JsonNode config) {
    }

    public record PlanVersionRequest(JsonNode config, List<PlanItemRequest> items) {
    }

    public record PlanItemView(String id, String evaluatorDefinitionId, Integer executionOrder,
                               boolean required, JsonNode config) {

        public static PlanItemView from(EvaluationPlanItem entity) {
            return new PlanItemView(entity.getId(), entity.getEvaluatorDefinitionId(),
                    entity.getExecutionOrder(), Boolean.TRUE.equals(entity.getRequired()),
                    entity.getConfigJson());
        }
    }

    public record PlanVersionView(String id, String planId, Integer version, VersionStatus status,
                                  JsonNode config, List<PlanItemView> items, String contentHash,
                                  Instant publishedAt, Instant createdAt, Instant updatedAt) {

        public static PlanVersionView from(EvaluationPlanVersion entity, List<PlanItemView> items) {
            return new PlanVersionView(entity.getId(), entity.getPlanId(), entity.getVersion(),
                    entity.getStatus(), entity.getConfigJson(),
                    items == null ? List.of() : List.copyOf(items),
                    entity.getContentHash(), entity.getPublishedAt(),
                    entity.getCreatedAt(), entity.getUpdatedAt());
        }
    }

    /** 算子定义只读视图；{@code implementationRef} 是逻辑引用，不返回可执行细节。 */
    public record EvaluatorView(String id, String evaluatorKey, Integer version, String displayName,
                                String description, EvaluatorType evaluatorType, String implementationRef,
                                JsonNode inputSchema, JsonNode outputSchema, VersionStatus status,
                                Instant publishedAt, Instant createdAt, Instant updatedAt) {

        public static EvaluatorView from(EvaluatorDefinition entity) {
            return new EvaluatorView(entity.getId(), entity.getEvaluatorKey(), entity.getVersion(),
                    entity.getDisplayName(), entity.getDescription(), entity.getEvaluatorType(),
                    entity.getImplementationRef(), entity.getInputSchemaJson(), entity.getOutputSchemaJson(),
                    entity.getStatus(), entity.getPublishedAt(),
                    entity.getCreatedAt(), entity.getUpdatedAt());
        }
    }
}
