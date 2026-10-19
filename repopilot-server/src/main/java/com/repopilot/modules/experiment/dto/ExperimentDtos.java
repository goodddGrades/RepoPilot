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
                                 long importedRunCount,
                                 long missingRunCount,
                                 List<TargetView> targets,
                                 Instant startedAt,
                                 Instant finishedAt,
                                 Instant createdAt,
                                 Instant updatedAt) {

        public static ExperimentView from(Experiment entity, List<ExperimentTarget> targets) {
            return from(entity, targets, -1L);
        }

        /**
         * @param importedRunCount 已导入的 Run 数；传 {@code -1} 表示"未计算"（列表页不逐行统计）
         */
        public static ExperimentView from(Experiment entity, List<ExperimentTarget> targets,
                                          long importedRunCount) {
            return from(entity, targets, importedRunCount, entity.getExpectedRunCount());
        }

        /**
         * @param expectedRunCount 完整槽位数。离线实验由调用方按"已发布样本 × repeatCount"算出
         *                         （DRAFT 阶段 {@code expected_run_count} 还是 0），这样详情页在
         *                         导入过程中就能回答"还差多少条轨迹"；在线实验直接传实体值。
         */
        public static ExperimentView from(Experiment entity, List<ExperimentTarget> targets,
                                          long importedRunCount, Long expectedRunCount) {
            long expected = expectedRunCount == null ? 0L : expectedRunCount;
            boolean computed = importedRunCount >= 0;
            long missing = computed && expected > importedRunCount ? expected - importedRunCount : 0L;
            return new ExperimentView(entity.getId(), entity.getName(), entity.getDescription(),
                    entity.getMode(), entity.getStatus(), entity.getDatasetVersionId(),
                    entity.getEvaluationPlanVersionId(), entity.getConfigJson(),
                    expected,
                    computed ? importedRunCount : 0L,
                    missing,
                    targets.stream().map(TargetView::from).toList(),
                    entity.getStartedAt(), entity.getFinishedAt(),
                    entity.getCreatedAt(), entity.getUpdatedAt());
        }
    }

    /** 启动/取消只回传身份与状态：契约规定 {@code {id,status}}，不重复整个实验。 */
    public record StatusResult(String id, String status) {
    }
}
