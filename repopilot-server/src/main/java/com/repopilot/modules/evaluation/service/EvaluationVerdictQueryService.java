package com.repopilot.modules.evaluation.service;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.state.Verdict;
import com.repopilot.modules.evaluation.entity.EvaluationJob;
import com.repopilot.modules.evaluation.entity.EvaluationResult;
import com.repopilot.modules.evaluation.mapper.EvaluationJobMapper;
import com.repopilot.modules.evaluation.mapper.EvaluationResultMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 评测模块对外的只读门面（TASK-009 新增）。
 *
 * <p>为什么不让标注模块直接用 {@code EvaluationJobMapper}：模块之间只能通过
 * 应用层服务协作，禁止跨模块访问他人 Mapper。这层门面把"某个 Run 的自动结论是什么"
 * 这一个问题收敛成方法，避免下游把 SQL 与表结构一起复制出去。
 *
 * <p>全部只读：人工标注**永不**回写评测结果，因此这里没有任何 update。
 */
@Service
public class EvaluationVerdictQueryService {

    private final EvaluationJobMapper jobMapper;
    private final EvaluationResultMapper resultMapper;

    public EvaluationVerdictQueryService(EvaluationJobMapper jobMapper,
                                         EvaluationResultMapper resultMapper) {
        this.jobMapper = jobMapper;
        this.resultMapper = resultMapper;
    }

    /** 指定算子任务的自动结论；未产出结果时返回 {@code null}（不是 FAIL，也不是空字符串）。 */
    public Verdict verdictOfJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return null;
        }
        return resultMapper.findByJobId(jobId)
                .map(EvaluationResult::getVerdict)
                .orElse(null);
    }

    /**
     * 某个 Run 的汇总自动结论，取最严重的一个。
     *
     * <p>多个算子时任一 FAIL 即 FAIL；否则有 INDETERMINATE 即 INDETERMINATE；
     * 再否则取第一个结论。之所以要"最严重"：派单与人工复核关心的是有没有问题，
     * 用最后一个或第一个都可能把 FAIL 盖掉。
     */
    public Verdict dominantVerdictOfRun(String runId) {
        List<String> jobIds = jobMapper.findByRunIdsOfRun(runId).stream()
                .map(EvaluationJob::getId).toList();
        if (jobIds.isEmpty()) {
            return null;
        }
        List<EvaluationResult> results = resultMapper.findByJobIds(jobIds);
        if (results.isEmpty()) {
            return null;
        }
        boolean hasFail = results.stream().anyMatch(r -> r.getVerdict() == Verdict.FAIL);
        if (hasFail) {
            return Verdict.FAIL;
        }
        boolean hasIndeterminate =
                results.stream().anyMatch(r -> r.getVerdict() == Verdict.INDETERMINATE);
        if (hasIndeterminate) {
            return Verdict.INDETERMINATE;
        }
        return results.get(0).getVerdict();
    }

    /** 校验 Job 存在并返回；跨 Run 归属校验留给调用方（它才知道期望的 Run）。 */
    public EvaluationJob requireJob(String jobId) {
        return jobMapper.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "评测任务不存在: " + jobId));
    }

}