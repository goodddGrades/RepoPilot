package com.repopilot.modules.experiment.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.state.ExperimentStatus;
import com.repopilot.common.state.RunMode;
import com.repopilot.modules.experiment.entity.Experiment;
import com.repopilot.modules.experiment.entity.ExperimentTarget;

import java.time.Instant;
import java.util.List;

/**
 * 实验相关 DTO。
 *
 * <p>创建请求里的 {@code targets} 是列表而不是 Map：顺序在响应里保持稳定，
 * 前端不必自己排序；同一实验内 {@code targetKey} 唯一由服务层与唯一键共同保证。
 */
public final class ExperimentDtos {

    private ExperimentDtos() {
    }

    public record CreateRequest(String name,
                                String description,
                                String mode,
                                String datasetVersionId,
                                String evaluationPlanVersionId,
                                JsonNode config,
                                List<TargetRequest> targets) {
    }

    public record TargetRequest(String targetKey,
                                String displayName,
                                String agentVersionId,
                                Integer repeatCount) {
    }

    public record TargetView(String id,
                             String targetKey,
                             String displayName,
                             String agentVersionId,
                             int repeatCount) {

        public static TargetView from(ExperimentTarget entity) {
            return new TargetView(entity.getId(), entity.getTargetKey(), entity.getDisplayName(),
                    entity.getAgentVersionId(), entity.getRepeatCount() == null ? 0 : entity.getRepeatCount());
        }
    }

    public record ExperimentView(String id,
                                 String name,
                                 String description,
                                 RunMode mode,
                                 ExperimentStatus status,
                                 String datasetVersionId,
                                 String evaluationPlanVersionId,
                                 JsonNode config,
                                 long expectedRunCount,
                                 List<TargetView> targets,
                                 Instant startedAt,
                                 Instant finishedAt,
                                 Instant createdAt,
                                 Instant updatedAt) {

        public static ExperimentView from(Experiment entity, List<ExperimentTarget> targets) {
            return new ExperimentView(entity.getId(), entity.getName(), entity.getDescription(),
                    entity.getMode(), entity.getStatus(), entity.getDatasetVersionId(),
                    entity.getEvaluationPlanVersionId(), entity.getConfigJson(),
                    entity.getExpectedRunCount() == null ? 0L : entity.getExpectedRunCount(),
                    targets.stream().map(TargetView::from).toList(),
                    entity.getStartedAt(), entity.getFinishedAt(),
                    entity.getCreatedAt(), entity.getUpdatedAt());
        }
    }

    /** 启动/取消只回传身份与状态：契约规定 {@code {id,status}}，不重复整个实验。 */
    public record StatusResult(String id, String status) {
    }
}
