package com.repopilot.modules.trace.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.state.AttemptStatus;
import com.repopilot.common.state.JobStatus;
import com.repopilot.common.state.RunMode;
import com.repopilot.common.state.RunStatus;
import com.repopilot.common.state.TaskOutcome;
import com.repopilot.modules.trace.entity.EvaluationRun;
import com.repopilot.modules.trace.entity.RunAttempt;
import com.repopilot.modules.trace.entity.TraceEvent;

import java.time.Instant;
import java.util.List;

/**
 * Run / Attempt / Trace 的对外视图。
 *
 * <p>刻意不回传 {@code leaseTokenHash}、{@code lease_token_hash} 等执行凭证字段：
 * 这些是 Worker 与后端之间的短期授权，进入业务响应只会扩大泄露面。
 * Trace 视图同样只给摘要与规范化字段，不在这里复制 TASK-006 的检索/游标接口。
 */
public final class RunDtos {

    private RunDtos() {
    }

    public record RunView(String id,
                          String experimentId,
                          String experimentTargetId,
                          String targetKey,
                          String datasetCaseId,
                          String caseKey,
                          int repeatIndex,
                          RunMode mode,
                          RunStatus status,
                          TaskOutcome taskOutcome,
                          int attemptCount,
                          String acceptedAttemptId,
                          Instant sealedAt,
                          Instant startedAt,
                          Instant finishedAt,
                          Instant createdAt) {

        public static RunView from(EvaluationRun run, String targetKey, String caseKey) {
            return new RunView(run.getId(), run.getExperimentId(), run.getExperimentTargetId(),
                    targetKey, run.getDatasetCaseId(), caseKey,
                    run.getRepeatIndex() == null ? 0 : run.getRepeatIndex(),
                    run.getMode(), run.getStatus(), run.getTaskOutcome(),
                    run.getAttemptCount() == null ? 0 : run.getAttemptCount(),
                    run.getAcceptedAttemptId(), run.getSealedAt(),
                    run.getStartedAt(), run.getFinishedAt(), run.getCreatedAt());
        }

        public static RunView from(RunListRow row) {
            return new RunView(row.id, row.experimentId, row.experimentTargetId, row.targetKey,
                    row.datasetCaseId, row.caseKey,
                    row.repeatIndex == null ? 0 : row.repeatIndex, row.mode, row.status,
                    row.taskOutcome, row.attemptCount == null ? 0 : row.attemptCount,
                    row.acceptedAttemptId, row.sealedAt, row.startedAt, row.finishedAt, row.createdAt);
        }
    }

    public record AttemptView(String id,
                              int attemptNo,
                              String workerId,
                              AttemptStatus status,
                              Instant leaseUntil,
                              Instant deadlineAt,
                              String errorCode,
                              String errorMessage,
                              Instant startedAt,
                              Instant finishedAt,
                              Instant createdAt) {

        public static AttemptView from(RunAttempt entity) {
            return new AttemptView(entity.getId(),
                    entity.getAttemptNo() == null ? 0 : entity.getAttemptNo(),
                    entity.getWorkerId(), entity.getStatus(), entity.getLeaseUntil(),
                    entity.getDeadlineAt(), entity.getErrorCode(), entity.getErrorMessage(),
                    entity.getStartedAt(), entity.getFinishedAt(), entity.getCreatedAt());
        }
    }

    /**
     * Trace 事件视图。
     *
     * <p>{@code payload} 原样返回：它是被评测 Agent 的观测数据，前端必须转义展示，
     * 服务端不做"看起来像 HTML 就删掉"的过滤——那会破坏证据完整性。
     */
    public record EventView(String eventId,
                            String attemptId,
                            String eventType,
                            Long sequenceNo,
                            Instant occurredAt,
                            JsonNode payload) {

        public static EventView from(TraceEvent entity) {
            return new EventView(entity.getEventId(), entity.getRunAttemptId(),
                    entity.getEventType(), entity.getSequenceNo(), entity.getOccurredAt(),
                    entity.getPayloadJson());
        }
    }

    /** 按批次分组的 Job 摘要；TASK-004 的 Job 只可能处于 PENDING/SKIPPED。 */
    public record JobBatchSummary(String batchId,
                                  int batchNo,
                                  String planVersionId,
                                  int total,
                                  int pending,
                                  int running,
                                  int completed,
                                  int failed,
                                  int skipped) {
    }

    public record JobView(String id,
                          String batchId,
                          int batchNo,
                          String evaluatorKey,
                          int evaluatorVersion,
                          int executionOrder,
                          boolean required,
                          JobStatus status,
                          int attemptCount,
                          String errorCode) {
    }

    /**
     * Run 详情。
     *
     * <p>{@code sourceSystem}/{@code sourceRecordId} 只为离线导入的 Run 有值：在线执行没有
     * "来源记录"这回事。把它们放在顶层而不是 {@code run} 里，是因为列表视图不需要这两个字段，
     * 而追溯单条离线轨迹时它们是第一手依据。
     */
    public record RunDetail(RunView run,
                            String sourceSystem,
                            String sourceRecordId,
                            JsonNode output,
                            String outputArtifactId,
                            JsonNode usage,
                            JsonNode observationMetadata,
                            long traceEventCount,
                            List<AttemptView> attempts,
                            List<JobBatchSummary> jobBatches,
                            List<JobView> jobs) {
    }

    /** {@code GET /runs/{id}/evaluation-jobs} 的响应：按批次分组 + 明细。 */
    public record JobSummaryView(List<JobBatchSummary> batches, List<JobView> jobs) {
    }
}
