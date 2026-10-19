package com.repopilot.modules.evaluation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.persistence.DeadlockRetry;
import com.repopilot.common.state.AttemptStatus;
import com.repopilot.common.worker.LeaseTokens;
import com.repopilot.common.worker.WorkerProperties;
import com.repopilot.modules.evaluation.dto.EvaluationJobRow;
import com.repopilot.modules.evaluation.entity.EvaluationJobAttempt;
import com.repopilot.modules.evaluation.mapper.EvaluationJobAttemptMapper;
import com.repopilot.modules.evaluation.mapper.EvaluationJobMapper;
import com.repopilot.modules.evaluationconfig.mapper.EvaluatorDefinitionMapper;
import com.repopilot.modules.experiment.dto.WorkerDtos;
import com.repopilot.modules.trace.entity.EvaluationRun;
import com.repopilot.modules.trace.service.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 评测 Job 的领取（{@code kind=EVALUATION}）。
 *
 * <p>与在线执行共用同一个领取入口，但遵守两条不同规则：
 * <ul>
 *   <li><b>能力</b>：只有声明了 {@code EVAL:<key>} 的 Worker 才能领到对应算子，
 *       能力拼错或算子未发布直接 400，而不是"领不到但不报错"；</li>
 *   <li><b>串行</b>：同一 Run 在同一批次内按 {@code execution_order} 推进，
 *       前一个 Job 必须终态（COMPLETED/FAILED/SKIPPED）才轮到下一个。
 *       技术失败也算终态——后续取证算子照常执行，不自动跳过。</li>
 * </ul>
 *
 * <p>不同 Run 之间没有顺序关系，因此"Run 间并行"是天然的：候选查询按创建时间取，
 * 不会串行化整个批次。
 */
@Service
public class EvaluationClaimService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationClaimService.class);

    /** 候选窗口 = maxTasks × 该系数：并发领取时给"被别人抢先"留出替换空间。 */
    private static final int CLAIM_CANDIDATE_FACTOR = 5;
    private static final int CLAIM_CANDIDATE_LIMIT = 100;

    private final EvaluationJobMapper jobMapper;
    private final EvaluationJobAttemptMapper attemptMapper;
    private final EvaluatorDefinitionMapper evaluatorMapper;
    private final EvaluationInputService inputService;
    private final RunStore runStore;
    private final WorkerProperties props;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public EvaluationClaimService(EvaluationJobMapper jobMapper,
                                  EvaluationJobAttemptMapper attemptMapper,
                                  EvaluatorDefinitionMapper evaluatorMapper,
                                  EvaluationInputService inputService,
                                  RunStore runStore,
                                  WorkerProperties props,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager) {
        this.jobMapper = jobMapper;
        this.attemptMapper = attemptMapper;
        this.evaluatorMapper = evaluatorMapper;
        this.inputService = inputService;
        this.runStore = runStore;
        this.props = props;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 校验 Worker 声明的算子能力确实是已发布算子。
     *
     * <p>{@code EVAL:output.non_emty} 这种拼写错误如果被当作"暂时没有任务"，
     * 排查会从"Worker 为什么不干活"开始；这里直接拒绝，错误信息里带上能力字符串。
     */
    public List<String> requireSupportedKeys(List<String> evaluatorKeys) {
        if (evaluatorKeys == null || evaluatorKeys.isEmpty()) {
            return List.of();
        }
        Set<String> published = new HashSet<>(evaluatorMapper.listPublishedKeys());
        for (String key : evaluatorKeys) {
            if (!published.contains(key)) {
                throw BusinessException.field("capabilities",
                        "算子未发布或不存在: " + key + "（已发布: " + String.join(", ", published) + "）");
            }
        }
        return List.copyOf(evaluatorKeys);
    }

    /**
     * 领取评测任务。必须在调用方事务内执行，与 Run 领取共享同一次提交。
     */
    public List<WorkerDtos.WorkItem> claim(String workerId, List<String> evaluatorKeys,
                                           int maxTasks, Instant now) {
        if (evaluatorKeys.isEmpty() || maxTasks <= 0) {
            return List.of();
        }
        // 死锁重试：并发评测 Worker 抢同一批 Job 时，插入 job_attempts 与回写
        // evaluation_jobs 的行锁顺序偶发循环等待。重试安全：领取幂等，
        // 重放最多多领一个任务，不会产生重复 Attempt。
        List<WorkerDtos.WorkItem> items = DeadlockRetry.execute(tx, () -> tx.execute(status -> {
            int candidateLimit = Math.min(maxTasks * CLAIM_CANDIDATE_FACTOR, CLAIM_CANDIDATE_LIMIT);
            List<String> candidates = jobMapper.listClaimCandidates(evaluatorKeys, now,
                    props.maxAttemptsPerJob(), candidateLimit);
            List<WorkerDtos.WorkItem> claimed = new ArrayList<>(maxTasks);
            for (String jobId : candidates) {
                if (claimed.size() >= maxTasks) {
                    break;
                }
                Optional<EvaluationJobRow> locked = jobMapper.lockClaimCandidate(jobId,
                        evaluatorKeys, now, props.maxAttemptsPerJob());
                if (locked.isEmpty()) {
                    continue;
                }
                claimed.add(startAttempt(workerId, locked.get(), now));
            }
            return claimed;
        }));
        if (items != null && !items.isEmpty()) {
            log.info("Worker {} 领取 {} 个评测任务", workerId, items.size());
        }
        return items == null ? List.of() : items;
    }

    private WorkerDtos.WorkItem startAttempt(String workerId, EvaluationJobRow row, Instant now) {
        int attemptNo = (row.attemptCount == null ? 0 : row.attemptCount) + 1;
        String token = LeaseTokens.newToken();
        Instant deadline = now.plus(props.taskDeadline());
        Instant leaseUntil = now.plus(props.lease()).isBefore(deadline)
                ? now.plus(props.lease()) : deadline;

        EvaluationJobAttempt attempt = new EvaluationJobAttempt();
        attempt.setId(Ulid.newId());
        attempt.setJobId(row.jobId);
        attempt.setAttemptNo(attemptNo);
        attempt.setWorkerId(workerId);
        attempt.setStatus(AttemptStatus.RUNNING);
        attempt.setLeaseTokenHash(LeaseTokens.hash(token));
        attempt.setLeaseUntil(leaseUntil);
        attempt.setDeadlineAt(deadline);
        attempt.setStartedAt(now);
        attempt.setCreatedAt(now);
        attempt.setUpdatedAt(now);
        attemptMapper.insert(attempt);

        if (jobMapper.markRunning(row.jobId, attempt.getId(), attemptNo, now) != 1) {
            // 抢到了行锁却改不动状态：说明另一个执行者已经把 Job 推走。回滚本次 Attempt。
            throw new BusinessException(ErrorCode.STALE_ATTEMPT,
                    "评测 Job 已被其他执行者占用: " + row.jobId);
        }

        EvaluationRun run = runStore.requireRun(row.runId);
        return new WorkerDtos.WorkItem("EVALUATION", row.runId, attempt.getId(), attemptNo,
                token, leaseUntil, deadline,
                objectMapper.valueToTree(inputService.build(row, run)), row.jobId);
    }
}
