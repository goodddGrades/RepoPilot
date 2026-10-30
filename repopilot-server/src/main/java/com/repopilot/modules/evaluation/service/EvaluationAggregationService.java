package com.repopilot.modules.evaluation.service;

import com.repopilot.common.state.BatchStatus;
import com.repopilot.common.state.BatchTriggerType;
import com.repopilot.common.state.JobStatus;
import com.repopilot.common.state.RunStatus;
import com.repopilot.modules.evaluation.dto.EvaluationBatchDtos;
import com.repopilot.modules.evaluation.dto.StatusCountRow;
import com.repopilot.modules.evaluation.dto.RunJobAggregateRow;
import com.repopilot.modules.evaluation.entity.EvaluationBatch;
import com.repopilot.modules.evaluation.entity.EvaluationJob;
import com.repopilot.modules.evaluation.mapper.EvaluationBatchMapper;
import com.repopilot.modules.evaluation.mapper.EvaluationJobMapper;
import com.repopilot.modules.experiment.service.ExperimentAggregationService;
import com.repopilot.modules.trace.entity.EvaluationRun;
import com.repopilot.modules.trace.service.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Job / Run / Batch / Experiment 的终态聚合。
 *
 * <p>四个层级依次收口，每一步都只在自己"确实可以判定"时写入：
 * <ol>
 *   <li><b>Run</b>：只由 {@code INITIAL} 批次决定。required Job 全部 COMPLETED → COMPLETED；
 *       任一 required 技术失败（FAILED/SKIPPED）→ FAILED。质量 FAIL（verdict）不参与——
 *       业务失败仍然是"评测完成"。</li>
 *   <li><b>Batch</b>：Job 全部终态且批次内 Run 全部终态才判定。后者是为了让
 *       "执行就失败、从未产生 Job"的 Run 不再把批次卡在 RUNNING。</li>
 *   <li><b>Experiment</b>：交给既有的 {@link ExperimentAggregationService}，
 *       它只在没有非终态 Run 时收敛；重评批次不会回写实验状态。</li>
 * </ol>
 *
 * <p>所有写入都是条件更新：重复收口影响 0 行，并发取消优先。
 */
@Service
public class EvaluationAggregationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationAggregationService.class);

    private final EvaluationJobMapper jobMapper;
    private final EvaluationBatchMapper batchMapper;
    private final RunStore runStore;
    private final ExperimentAggregationService experimentAggregation;
    private final TransactionTemplate tx;

    public EvaluationAggregationService(EvaluationJobMapper jobMapper,
                                        EvaluationBatchMapper batchMapper,
                                        RunStore runStore,
                                        ExperimentAggregationService experimentAggregation,
                                        PlatformTransactionManager transactionManager) {
        this.jobMapper = jobMapper;
        this.batchMapper = batchMapper;
        this.runStore = runStore;
        this.experimentAggregation = experimentAggregation;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Job 刚进入终态后的收口入口。可以重复调用。 */
    public void onJobSettled(String jobId) {
        EvaluationJob job = jobMapper.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        EvaluationBatch batch = batchMapper.findById(job.getBatchId()).orElse(null);
        if (batch == null) {
            return;
        }
        boolean runSettled = settleRunIfReady(batch, job.getRunId());
        settleBatchIfReady(batch);
        if (runSettled) {
            experimentAggregation.aggregateIfSettled(batch.getExperimentId());
        }
    }

    /**
     * Run 的评测收口。
     *
     * @return true 表示 Run 本次真的被推进到终态（调用方据此决定是否聚合实验）
     */
    private boolean settleRunIfReady(EvaluationBatch batch, String runId) {
        if (batch.getTriggerType() != BatchTriggerType.INITIAL) {
            // 重评复用已完成的 Run：不得因为新批次的失败把历史 Run 改成 FAILED
            return false;
        }
        RunJobAggregateRow agg = jobMapper.aggregateRunJobs(batch.getId(), runId);
        if (agg == null || nz(agg.nonTerminalJobs) > 0) {
            return false;
        }
        EvaluationRun run = runStore.findRun(runId).orElse(null);
        if (run == null || run.getStatus() != RunStatus.EVALUATING) {
            return false;
        }
        long requiredJobs = nz(agg.requiredJobs);
        long requiredFailed = nz(agg.requiredFailed);
        long requiredCompleted = nz(agg.requiredCompleted);
        Instant now = Instant.now();
        if (requiredFailed > 0) {
            int updated = runStore.markEvaluationFailed(runId, now);
            if (updated == 1) {
                log.info("Run {} 因 {} 个 required 算子技术失败收口为 FAILED", runId, requiredFailed);
            }
            return updated == 1;
        }
        if (requiredJobs == 0 || requiredCompleted == requiredJobs) {
            int updated = runStore.markEvaluated(runId, now);
            if (updated == 1) {
                log.info("Run {} 的 {} 个 required 算子全部完成，收口为 COMPLETED", runId, requiredJobs);
            }
            return updated == 1;
        }
        return false;
    }

    private void settleBatchIfReady(EvaluationBatch batch) {
        if (batch.getStatus() != BatchStatus.RUNNING) {
            return;
        }
        List<StatusCountRow> counts = jobMapper.countByStatus(batch.getId());
        EvaluationBatchDtos.JobCounts jobs = EvaluationBatchDtos.JobCounts.of(counts);
        if (jobs.pending() > 0 || jobs.running() > 0) {
            return;
        }
        if (jobMapper.countNonTerminalRuns(batch.getId()) > 0) {
            return;
        }
        BatchStatus target;
        if (jobs.completed() > 0) {
            target = jobs.failed() > 0 || jobs.skipped() > 0
                    ? BatchStatus.PARTIAL_FAILED : BatchStatus.COMPLETED;
        } else {
            target = BatchStatus.FAILED;
        }
        int updated = batchMapper.markTerminal(batch.getId(), target, Instant.now());
        if (updated == 1) {
            log.info("评测批次 {} 收口为 {}（完成 {} / 失败 {} / 跳过 {}）", batch.getId(), target,
                    jobs.completed(), jobs.failed(), jobs.skipped());
        }
    }

    /** 批次状态是否还能再产生 Job；领取取消后的批次不应再发出任务。 */
    boolean isOpen(EvaluationBatch batch) {
        return batch.getStatus() == BatchStatus.PENDING || batch.getStatus() == BatchStatus.RUNNING;
    }

    /** 仅用于诊断：某 Job 是否已终态。 */
    static boolean isTerminal(JobStatus status) {
        return status == JobStatus.COMPLETED || status == JobStatus.FAILED || status == JobStatus.SKIPPED;
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }
}
