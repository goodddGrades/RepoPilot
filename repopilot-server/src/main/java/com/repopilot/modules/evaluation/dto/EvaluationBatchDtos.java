package com.repopilot.modules.evaluation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.state.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 评测批次与结果的对外视图。
 *
 * <p>Job 视图留在 {@code RunDtos.JobView}：它在 Run 详情页里出现，形状由 Run 的读取接口决定。
 * 这里只放批次自己的字段——批次是"结果版本的载体"，与单个 Run 的展示无关。
 */
public final class EvaluationBatchDtos {

    private EvaluationBatchDtos() {
    }

    /** 批次内 Job 的计数。字段名与前端一致，避免前端再翻译一次。 */
    public record JobCounts(long total, long pending, long running, long completed,
                            long failed, long skipped) {

        public static JobCounts of(List<StatusCountRow> rows) {
            long pending = 0;
            long running = 0;
            long completed = 0;
            long failed = 0;
            long skipped = 0;
            for (StatusCountRow row : rows) {
                long total = row.total == null ? 0 : row.total;
                switch (row.status == null ? "" : row.status) {
                    case "PENDING" -> pending += total;
                    case "RUNNING" -> running += total;
                    case "COMPLETED" -> completed += total;
                    case "FAILED" -> failed += total;
                    case "SKIPPED" -> skipped += total;
                    default -> {
                        // CHECK 之外的状态不会入库；出现了也不猜分类
                    }
                }
            }
            return new JobCounts(pending + running + completed + failed + skipped,
                    pending, running, completed, failed, skipped);
        }
    }

    /**
     * 批次视图。
     *
     * <p>{@code selection} 原样回传创建时的依据（"为什么选这些 Run"），
     * 但范围真相始终是 {@code evaluation_batch_runs}；前端不要用它重新推导成员。
     */
    public record BatchView(String id,
                            String experimentId,
                            String evaluationPlanVersionId,
                            int batchNo,
                            String triggerType,
                            String parentBatchId,
                            BatchStatus status,
                            long expectedJobCount,
                            long runCount,
                            JobCounts jobs,
                            JsonNode selection,
                            Instant createdAt,
                            Instant startedAt,
                            Instant finishedAt) {
    }

    /**
     * 新建重评批次的请求。
     *
     * <p>{@code triggerType} 只接受 REEVALUATION / MANUAL：INITIAL 由实验启动事务创建，
     * 通过这个接口再造一个 INITIAL 会破坏"初始批次唯一"。
     */
    public record CreateBatchRequest(String planVersionId,
                                     String triggerType,
                                     String parentBatchId,
                                     List<String> runIds,
                                     Filter filter) {
    }

    /** 按属性选择 Run；与 {@code runIds} 二选一。创建时一次性解析为具体行。 */
    public record Filter(String targetId, String caseKey, String status, String taskOutcome) {
    }
}
