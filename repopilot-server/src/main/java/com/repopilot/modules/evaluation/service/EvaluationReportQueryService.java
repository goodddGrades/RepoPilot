package com.repopilot.modules.evaluation.service;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.state.BatchStatus;
import com.repopilot.common.state.JobStatus;
import com.repopilot.common.state.Verdict;
import com.repopilot.modules.evaluation.dto.JobListRow;
import com.repopilot.modules.evaluation.entity.EvaluationBatch;
import com.repopilot.modules.evaluation.entity.EvaluationJobAttempt;
import com.repopilot.modules.evaluation.entity.EvaluationResult;
import com.repopilot.modules.evaluation.mapper.EvaluationBatchMapper;
import com.repopilot.modules.evaluation.mapper.EvaluationJobAttemptMapper;
import com.repopilot.modules.evaluation.mapper.EvaluationJobMapper;
import com.repopilot.modules.evaluation.mapper.EvaluationResultMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 评测模块面向报告的只读门面（TASK-010 新增）。
 *
 * <p>与 {@link EvaluationVerdictQueryService} 同源：报告模块不建自己的 Mapper，
 * 也不跨模块访问本模块的表——它需要"这个批次里每个 Run 的每个算子判了什么、
 * 哪些 Job 技术失败、重试花了多少"这几个问题，就收敛成这里的几个方法。
 *
 * <p>全部只读。报告不产生也不修改任何评测数据。
 */
@Service
public class EvaluationReportQueryService {

    /** 批次选择范围：默认取初始批次；{@code LATEST} 取最新创建的批次。 */
    public enum BatchScope {
        INITIAL, LATEST;

        public static BatchScope parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return INITIAL;
            }
            try {
                return valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "batchScope 只能是 INITIAL 或 LATEST，收到: " + raw);
            }
        }
    }

    private final EvaluationBatchMapper batchMapper;
    private final EvaluationJobMapper jobMapper;
    private final EvaluationJobAttemptMapper jobAttemptMapper;
    private final EvaluationResultMapper resultMapper;

    public EvaluationReportQueryService(EvaluationBatchMapper batchMapper,
                                        EvaluationJobMapper jobMapper,
                                        EvaluationJobAttemptMapper jobAttemptMapper,
                                        EvaluationResultMapper resultMapper) {
        this.batchMapper = batchMapper;
        this.jobMapper = jobMapper;
        this.jobAttemptMapper = jobAttemptMapper;
        this.resultMapper = resultMapper;
    }

    /**
     * 解析报告要读哪个批次。
     *
     * <p>默认 {@code INITIAL}：未显式指定时固定取初始批次，而不是"最新那个"——
     * 否则同一份报告在重评之后会悄悄换掉基数，用户看到的前后两屏数字不是一回事。
     * {@code LATEST} 取最新创建的批次，**包含 RUNNING/FAILED**，不回退到上一个成功批次：
     * 批次失败本身就是需要被看见的事实，回退等于把它藏起来。
     */
    public EvaluationBatch resolveBatch(String experimentId, String batchId, BatchScope scope) {
        if (batchId != null && !batchId.isBlank()) {
            EvaluationBatch batch = batchMapper.findById(batchId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                            "评测批次不存在: " + batchId));
            if (!batch.getExperimentId().equals(experimentId)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "批次 " + batchId + " 不属于实验 " + experimentId);
            }
            return batch;
        }
        if (scope == BatchScope.LATEST) {
            List<EvaluationBatch> batches = batchMapper.listByExperiment(
                    experimentId, null, 1, 0);
            if (batches.isEmpty()) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "实验还没有评测批次: " + experimentId);
            }
            return batches.get(0);
        }
        return batchMapper.findInitialByExperiment(experimentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "实验还没有初始评测批次: " + experimentId));
    }

    /** 该批次的全部 Job 明细（含算子 key、方案项、执行顺序、Job 技术状态）。 */
    public List<JobListRow> jobsOfBatch(String batchId) {
        return jobMapper.listByBatch(batchId);
    }

    /** 该批次全部 Job Attempt（含被重试掉的），用于"平台实际执行成本"与 Judge 成本。 */
    public List<EvaluationJobAttempt> attemptsOfBatch(String batchId) {
        return jobAttemptMapper.listByBatch(batchId);
    }

    /** 按 Job id 索引结论；没有结论的 Job 不出现在返回的 Map 里（缺失≠FAIL）。 */
    public Map<String, EvaluationResult> resultsByJobId(List<String> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            return Map.of();
        }
        return resultMapper.findByJobIds(jobIds).stream()
                .collect(Collectors.toMap(EvaluationResult::getJobId, Function.identity(),
                        (a, b) -> a));
    }

    public Optional<EvaluationBatch> findBatch(String batchId) {
        return batchMapper.findById(batchId);
    }

    /** 批次状态原样透出，报告不自行推断——批次是 RUNNING 就得显示 RUNNING。 */
    public BatchStatus statusOf(EvaluationBatch batch) {
        return batch.getStatus();
    }

    /**
     * 供报告判断"这个 Job 是不是技术失败"。
     *
     * <p>技术失败（Job 没跑完）与质量失败（Job 跑完但判FAIL）必须分开计数：
     * 前者是平台没交付证据，后者是交付了证据且证据为否。混在一起会让"算子通过率"
     * 把平台故障算成被测对象的问题。
     *
     * <p>判定条件是"没有结论**且** Job 不是 COMPLETED"。只判前者不够：
     * COMPLETED 但没写结论是数据异常，也得算进去，不能悄悄算成通过。
     */
    public boolean isTechnicalFailure(JobListRow job, Verdict verdict) {
        return verdict == null && job.status != JobStatus.COMPLETED;
    }
}