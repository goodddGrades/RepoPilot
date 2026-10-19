package com.repopilot.modules.evaluation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.state.BatchStatus;
import com.repopilot.common.state.BatchTriggerType;
import com.repopilot.modules.evaluation.entity.EvaluationBatch;
import com.repopilot.modules.evaluation.entity.EvaluationBatchRun;
import com.repopilot.modules.evaluation.mapper.EvaluationBatchMapper;
import com.repopilot.modules.evaluation.mapper.EvaluationBatchRunMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 评测批次。
 *
 * <p>TASK-004 只创建 {@code INITIAL} 批次：实验启动时固定「这批 Run + 这个方案版本」，
 * 并写入 {@code evaluation_batch_runs} 的确定行。重评批次、Job 执行与结果聚合属于后续任务。
 */
@Service
public class EvaluationBatchService {

    private final EvaluationBatchMapper batchMapper;
    private final EvaluationBatchRunMapper batchRunMapper;
    private final ObjectMapper objectMapper;

    public EvaluationBatchService(EvaluationBatchMapper batchMapper,
                                  EvaluationBatchRunMapper batchRunMapper,
                                  ObjectMapper objectMapper) {
        this.batchMapper = batchMapper;
        this.batchRunMapper = batchRunMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建初始批次。
     *
     * <p>{@code expected_job_count} 在 Job 创建前冻结：它是"批次是否完整"的分母。
     * 如果等到 Job 都创建完再算，崩溃后重跑会把分母也改掉，批次永远不会被发现不完整。
     */
    public EvaluationBatch createInitial(String experimentId, String planVersionId,
                                         long expectedRunCount, long expectedJobCount,
                                         String createdBy, Instant now) {
        ObjectNode selection = objectMapper.createObjectNode();
        selection.put("scope", "ALL_SLOTS");
        selection.put("expectedRunCount", expectedRunCount);

        EvaluationBatch batch = new EvaluationBatch();
        batch.setId(Ulid.newId());
        batch.setExperimentId(experimentId);
        batch.setEvaluationPlanVersionId(planVersionId);
        batch.setBatchNo(batchMapper.nextBatchNo(experimentId));
        batch.setTriggerType(BatchTriggerType.INITIAL);
        batch.setSelectionJson(selection);
        batch.setStatus(BatchStatus.PENDING);
        batch.setExpectedJobCount(expectedJobCount);
        batch.setCreatedBy(createdBy);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batchMapper.insert(batch);
        return batch;
    }

    /** 固定批次的 Run 集合。与批次行同事务，提交后不再增删。 */
    public void registerRuns(String batchId, String experimentId, List<String> runIds, Instant now) {
        List<EvaluationBatchRun> rows = new ArrayList<>(runIds.size());
        for (String runId : runIds) {
            EvaluationBatchRun row = new EvaluationBatchRun();
            row.setBatchId(batchId);
            row.setRunId(runId);
            row.setExperimentId(experimentId);
            row.setCreatedAt(now);
            rows.add(row);
        }
        for (int from = 0; from < rows.size(); from += 500) {
            int to = Math.min(from + 500, rows.size());
            batchRunMapper.batchInsert(rows.subList(from, to));
        }
    }

    public EvaluationBatch findInitial(String experimentId) {
        return batchMapper.findInitialByExperiment(experimentId).orElse(null);
    }

    public int cancelNonTerminal(String experimentId, Instant now) {
        return batchMapper.cancelNonTerminalByExperiment(experimentId, now);
    }

    public EvaluationBatch require(String batchId) {
        return batchMapper.findById(batchId)
                .orElseThrow(() -> new com.repopilot.common.exception.BusinessException(
                        com.repopilot.common.api.ErrorCode.RESOURCE_NOT_FOUND,
                        "EvaluationBatch 不存在: " + batchId));
    }
}
