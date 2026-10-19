package com.repopilot.modules.evaluation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.hash.Sha256;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.json.CanonicalJson;
import com.repopilot.common.state.AttemptStatus;
import com.repopilot.common.state.BatchStatus;
import com.repopilot.common.state.EvaluatorType;
import com.repopilot.common.state.ExperimentStatus;
import com.repopilot.common.state.GeneratedBy;
import com.repopilot.common.state.Verdict;
import com.repopilot.common.worker.LeaseTokens;
import com.repopilot.common.worker.WorkerProperties;
import com.repopilot.modules.evaluation.dto.EvaluationJobRow;
import com.repopilot.modules.evaluation.entity.EvaluationBatch;
import com.repopilot.modules.evaluation.entity.EvaluationJob;
import com.repopilot.modules.evaluation.entity.EvaluationJobAttempt;
import com.repopilot.modules.evaluation.entity.EvaluationResult;
import com.repopilot.modules.evaluation.mapper.EvaluationBatchMapper;
import com.repopilot.modules.evaluation.mapper.EvaluationJobAttemptMapper;
import com.repopilot.modules.evaluation.mapper.EvaluationJobMapper;
import com.repopilot.modules.evaluation.mapper.EvaluationResultMapper;
import com.repopilot.modules.experiment.dto.WorkerDtos;
import com.repopilot.modules.experiment.mapper.ExperimentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 评测 Job 的 Attempt 协议：心跳、完成、失败、过期回收。
 *
 * <p>与在线执行遵守同一套 fencing 规则（锁 Attempt → 校验令牌 → 校验父记录 →
 * 校验租约/截止/取消 → 条件更新），但业务含义不同：
 * <ul>
 *   <li>完成回调携带的是<b>结论</b>（verdict/score/evidence），不是 Agent 输出；</li>
 *   <li>{@code verdict=FAIL} 是质量失败，Job 仍然 COMPLETED——只有运行异常才让 Job FAILED；</li>
 *   <li>每个 Job 至多一条 Result（数据库唯一键），重复回调凭 completionHash 幂等。</li>
 * </ul>
 *
 * <p>结果体在写库前做三类校验，拒绝"看起来有结果但无法追溯"的提交：
 * 分数越界、{@code generatedBy} 与算子类型不符、证据引用跨 Run。
 */
@Service
public class JobAttemptService {

    private static final Logger log = LoggerFactory.getLogger(JobAttemptService.class);

    private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final int MAX_REASON_LENGTH = 4000;
    private static final int MAX_ERROR_MESSAGE = 1024;

    private final EvaluationJobAttemptMapper attemptMapper;
    private final EvaluationJobMapper jobMapper;
    private final EvaluationBatchMapper batchMapper;
    private final EvaluationResultMapper resultMapper;
    private final ExperimentMapper experimentMapper;
    private final EvidenceValidator evidenceValidator;
    private final EvaluationAggregationService aggregation;
    private final WorkerProperties props;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public JobAttemptService(EvaluationJobAttemptMapper attemptMapper,
                             EvaluationJobMapper jobMapper,
                             EvaluationBatchMapper batchMapper,
                             EvaluationResultMapper resultMapper,
                             ExperimentMapper experimentMapper,
                             EvidenceValidator evidenceValidator,
                             EvaluationAggregationService aggregation,
                             WorkerProperties props,
                             ObjectMapper objectMapper,
                             PlatformTransactionManager transactionManager) {
        this.attemptMapper = attemptMapper;
        this.jobMapper = jobMapper;
        this.batchMapper = batchMapper;
        this.resultMapper = resultMapper;
        this.experimentMapper = experimentMapper;
        this.evidenceValidator = evidenceValidator;
        this.aggregation = aggregation;
        this.props = props;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ 心跳

    public WorkerDtos.HeartbeatResult heartbeat(String attemptId, String leaseToken) {
        Instant now = Instant.now();
        return tx.execute(status -> {
            EvaluationJobAttempt attempt = lockAttempt(attemptId);
            requireToken(attempt, leaseToken);
            if (attempt.getStatus() != AttemptStatus.RUNNING) {
                throw stale("Attempt 已结束（" + attempt.getStatus() + "）: " + attemptId);
            }
            EvaluationJob job = requireJob(attempt.getJobId());
            requireActive(job, attempt, now);
            Instant leaseUntil = min(now.plus(props.lease()), attempt.getDeadlineAt());
            if (attemptMapper.renewLease(attemptId, leaseUntil, now) != 1) {
                throw stale("续租失败，Attempt 已不在执行中: " + attemptId);
            }
            return new WorkerDtos.HeartbeatResult(leaseUntil, attempt.getDeadlineAt(), false);
        });
    }

    // ------------------------------------------------------------------ 完成

    public WorkerDtos.JobCompleteResult complete(String attemptId, WorkerDtos.JobCompleteRequest request) {
        Verdict verdict = parseVerdict(request.verdict());
        GeneratedBy generatedBy = parseGeneratedBy(request.generatedBy());
        BigDecimal score = normalizeScore(request.score());
        String reason = truncate(request.reason(), MAX_REASON_LENGTH);
        JsonNode result = requireObject("result", request.result());
        String completionHash = completionHash(request);

        Instant now = Instant.now();
        WorkerDtos.JobCompleteResult receipt = tx.execute(status -> {
            EvaluationJobAttempt attempt = lockAttempt(attemptId);
            requireToken(attempt, request.leaseToken());
            if (attempt.getStatus() != AttemptStatus.RUNNING) {
                if (attempt.getStatus() == AttemptStatus.COMPLETED
                        && completionHash.equals(attempt.getCompletionHash())) {
                    return new WorkerDtos.JobCompleteResult(attemptId, "COMPLETED", attempt.getJobId());
                }
                throw stale("Attempt 已结束（" + attempt.getStatus() + "），不能再提交结果: " + attemptId);
            }
            EvaluationJob job = requireJob(attempt.getJobId());
            requireActive(job, attempt, now);

            EvaluationJobRow detail = jobMapper.findJobDetail(job.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                            "评测 Job 定义不存在: " + job.getId()));
            requireGeneratedByMatches(detail.evaluatorType, generatedBy);
            JsonNode evidence = evidenceValidator.validate(request.evidence(), job.getRunId());
            requireNotCancelled(detail);

            if (attemptMapper.markCompleted(attemptId, completionHash, result, request.usage(), now) != 1) {
                throw stale("Attempt 状态已变化: " + attemptId);
            }
            insertResult(job, attemptId, verdict, score, reason, evidence, result, generatedBy, now);
            if (jobMapper.markCompleted(job.getId(), attemptId, now) != 1) {
                // 回滚 Attempt 与 Result：三者必须同时成立
                throw stale("Job 已被其他尝试接管: " + job.getId());
            }
            return new WorkerDtos.JobCompleteResult(attemptId, "COMPLETED", job.getId());
        });

        aggregation.onJobSettled(receipt.jobId());
        return receipt;
    }

    private void insertResult(EvaluationJob job, String attemptId, Verdict verdict, BigDecimal score,
                              String reason, JsonNode evidence, JsonNode result, GeneratedBy generatedBy,
                              Instant now) {
        EvaluationResult entity = new EvaluationResult();
        entity.setId(Ulid.newId());
        entity.setJobId(job.getId());
        entity.setJobAttemptId(attemptId);
        entity.setVerdict(verdict);
        entity.setScore(score);
        entity.setReason(reason);
        entity.setEvidenceJson(evidence);
        entity.setResultJson(result);
        entity.setGeneratedBy(generatedBy);
        entity.setCreatedAt(now);
        try {
            resultMapper.insert(entity);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // 唯一键 (job_id) 兜底：服务层判断被并发绕过时，这里必须显式冲突而不是静默覆盖
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                    "该 Job 已存在最终结果，不能写入第二份: " + job.getId());
        }
    }

    // ------------------------------------------------------------------ 失败

    public WorkerDtos.JobFailResult fail(String attemptId, WorkerDtos.JobFailRequest request) {
        String errorCode = requireErrorCode(request.errorCode());
        String errorMessage = truncate(request.errorMessage(), MAX_ERROR_MESSAGE);
        boolean hint = Boolean.TRUE.equals(request.retryableHint());
        String completionHash = failHash(request);

        Instant now = Instant.now();
        Outcome outcome = tx.execute(status -> {
            EvaluationJobAttempt attempt = lockAttempt(attemptId);
            requireToken(attempt, request.leaseToken());
            if (attempt.getStatus() != AttemptStatus.RUNNING) {
                if (completionHash.equals(attempt.getCompletionHash()) && stillOwnsOutcome(attempt)) {
                    EvaluationJob replayJob = jobMapper.findById(attempt.getJobId()).orElse(null);
                    Instant next = replayJob == null ? null : replayJob.getNextRetryAt();
                    return new Outcome(attempt.getStatus().name(), replayJob == null ? null : replayJob.getId(),
                            next != null, next);
                }
                throw stale("Attempt 已结束（" + attempt.getStatus() + "），不能再提交失败: " + attemptId);
            }
            EvaluationJob job = requireJob(attempt.getJobId());
            // 失败回调不校验租约是否刚过期：租约过期时回收器会做同样的事，
            // 这里如果再报 STALE，Worker 会以为"没记上"而反复重试。
            AttemptStatus attemptStatus = isTimeout(errorCode)
                    ? AttemptStatus.TIMED_OUT : AttemptStatus.FAILED;
            if (attemptMapper.markFailed(attemptId, attemptStatus, completionHash,
                    request.usage(), errorCode, errorMessage, now) != 1) {
                throw stale("Attempt 状态已变化: " + attemptId);
            }

            boolean retry = hint && nz(attempt.getAttemptNo()) < props.maxAttemptsPerJob()
                    && job.getStatus() == com.repopilot.common.state.JobStatus.RUNNING
                    && attempt.getId().equals(job.getActiveAttemptId());
            if (retry) {
                Instant nextRetryAt = now.plus(backoff(attempt.getAttemptNo()));
                if (jobMapper.releaseForRetry(job.getId(), attemptId, nextRetryAt,
                        errorCode, errorMessage, now) != 1) {
                    throw stale("Job 状态已变化，无法安排重试: " + job.getId());
                }
                return new Outcome(attemptStatus.name(), job.getId(), true, nextRetryAt);
            }
            jobMapper.markFailed(job.getId(), attemptId, errorCode, errorMessage, now);
            return new Outcome(attemptStatus.name(), job.getId(), false, null);
        });

        if (outcome != null && !outcome.retryScheduled() && outcome.jobId() != null) {
            aggregation.onJobSettled(outcome.jobId());
        }
        return new WorkerDtos.JobFailResult(attemptId,
                outcome == null ? AttemptStatus.FAILED.name() : outcome.status(),
                outcome == null ? null : outcome.jobId(),
                outcome != null && outcome.retryScheduled(),
                outcome == null ? null : outcome.nextRetryAt());
    }

    private record Outcome(String status, String jobId, boolean retryScheduled, Instant nextRetryAt) {
    }

    /**
     * 失败回执的重放只在"Job 的当前状态仍由这次失败造成"时允许：
     * 要么 Job 被释放等待重试（active 为空且 nextRetryAt 已排期），
     * 要么 Job 因这次失败终态（active 仍是本 Attempt）。
     * 一旦 Job 被后续 Attempt 领取或完成，同一正文再来就是迟到回执，按 STALE 拒绝——
     * 成功历史不能被旧的失败回执以"幂等重放"的名义再次触碰。
     */
    private boolean stillOwnsOutcome(EvaluationJobAttempt attempt) {
        EvaluationJob job = jobMapper.findById(attempt.getJobId()).orElse(null);
        if (job == null) {
            return false;
        }
        if (job.getActiveAttemptId() == null) {
            return job.getNextRetryAt() != null;
        }
        return job.getActiveAttemptId().equals(attempt.getId());
    }

    // ------------------------------------------------------------------ 回收

    /**
     * 回收过期 Attempt。
     *
     * <p>与在线执行同构：只终结 Attempt，然后由"是否还有预算"决定安排重试还是把 Job 判失败。
     * 旧 Attempt 的 Result（如果存在）不会被删除——但正常情况下 Result 只在完成事务里创建，
     * 被回收的 Attempt 不会有 Result，所以"旧执行者晚到的结果"也无法追加第二份。
     */
    public int reapExpired() {
        Instant now = Instant.now();
        List<EvaluationJobAttempt> expired = attemptMapper.findExpired(now, 100);
        int reaped = 0;
        for (EvaluationJobAttempt candidate : expired) {
            String jobId = null;
            try {
                jobId = tx.execute(status -> reapOne(candidate.getId(), now));
                if (jobId != null) {
                    reaped++;
                    aggregation.onJobSettled(jobId);
                }
            } catch (RuntimeException ex) {
                log.warn("回收评测 Attempt {} 失败: {}", candidate.getId(), ex.getMessage());
            }
        }
        return reaped;
    }

    private String reapOne(String attemptId, Instant now) {
        EvaluationJobAttempt attempt = attemptMapper.lockById(attemptId).orElse(null);
        if (attempt == null || attempt.getStatus() != AttemptStatus.RUNNING) {
            return null;
        }
        EvaluationJob job = jobMapper.findById(attempt.getJobId()).orElse(null);
        if (job == null) {
            return null;
        }
        boolean deadlineExceeded = attempt.getDeadlineAt() != null && !now.isBefore(attempt.getDeadlineAt());
        AttemptStatus attemptStatus = deadlineExceeded ? AttemptStatus.TIMED_OUT : AttemptStatus.FAILED;
        String errorCode = deadlineExceeded ? "DEADLINE_EXCEEDED" : "LEASE_EXPIRED";
        attemptMapper.markFailed(attemptId, attemptStatus, null, null, errorCode,
                "租约已过期，由回收器终止", now);

        if (!attemptId.equals(job.getActiveAttemptId())) {
            return null;
        }
        boolean retry = nz(attempt.getAttemptNo()) < props.maxAttemptsPerJob()
                && job.getStatus() == com.repopilot.common.state.JobStatus.RUNNING;
        if (retry) {
            jobMapper.releaseForRetry(job.getId(), attemptId,
                    now.plus(backoff(attempt.getAttemptNo())), errorCode,
                    "租约已过期，等待重试", now);
        } else {
            jobMapper.markFailed(job.getId(), attemptId, errorCode, "租约已过期，重试预算耗尽", now);
        }
        return job.getId();
    }

    // ------------------------------------------------------------------ 校验

    private EvaluationJobAttempt lockAttempt(String attemptId) {
        return attemptMapper.lockById(attemptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "EvaluationJobAttempt 不存在: " + attemptId));
    }

    private EvaluationJob requireJob(String jobId) {
        return jobMapper.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "评测 Job 不存在: " + jobId));
    }

    /**
     * 校验 Job 仍由这个 Attempt 持有，且未过租约/截止、批次与实验未取消。
     *
     * <p>锁顺序固定为 Attempt → Job：先锁 Job 再锁 Attempt 会在"回收器"与"回调"之间
     * 形成交叉等待。
     */
    private void requireActive(EvaluationJob job, EvaluationJobAttempt attempt, Instant now) {
        if (job.getStatus() != com.repopilot.common.state.JobStatus.RUNNING
                || !attempt.getId().equals(job.getActiveAttemptId())) {
            throw stale("Attempt 已被取代或 Job 已不在执行中: " + attempt.getId());
        }
        if (now.isAfter(attempt.getLeaseUntil())) {
            throw stale("租约已过期: " + attempt.getId());
        }
        if (attempt.getDeadlineAt() != null && !now.isBefore(attempt.getDeadlineAt())) {
            throw stale("已超过硬截止时间: " + attempt.getId());
        }
    }

    private void requireNotCancelled(EvaluationJobRow detail) {
        EvaluationBatch batch = batchMapper.findById(detail.batchId).orElse(null);
        if (batch == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评测批次不存在: " + detail.batchId);
        }
        if (batch.getStatus() == BatchStatus.CANCELLED) {
            throw stale("评测批次已取消: " + batch.getId());
        }
        ExperimentStatus status = experimentMapper.findById(batch.getExperimentId())
                .map(e -> e.getStatus()).orElse(null);
        if (status == ExperimentStatus.CANCELLED) {
            throw stale("实验已取消: " + batch.getExperimentId());
        }
    }

    private void requireToken(EvaluationJobAttempt attempt, String token) {
        if (!LeaseTokens.matches(token, attempt.getLeaseTokenHash())) {
            throw stale("租约令牌不匹配: " + attempt.getId());
        }
    }

    private void requireGeneratedByMatches(EvaluatorType type, GeneratedBy generatedBy) {
        GeneratedBy expected = switch (type) {
            case RULE, TRACE, COST -> GeneratedBy.RULE;
            case TEST -> GeneratedBy.TEST;
            case LLM_JUDGE -> GeneratedBy.LLM;
        };
        if (generatedBy != expected) {
            throw BusinessException.field("generatedBy",
                    "算子类型 " + type + " 的结果必须标记 generatedBy=" + expected
                            + "，收到: " + generatedBy);
        }
    }

    private static Verdict parseVerdict(String value) {
        if (value == null || value.isBlank()) {
            throw BusinessException.field("verdict", "verdict 不能为空");
        }
        try {
            return Verdict.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("verdict",
                    "不支持的 verdict: " + value + "，可选值: PASS, FAIL, INDETERMINATE, NOT_APPLICABLE");
        }
    }

    private static GeneratedBy parseGeneratedBy(String value) {
        if (value == null || value.isBlank()) {
            throw BusinessException.field("generatedBy", "generatedBy 不能为空");
        }
        try {
            return GeneratedBy.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("generatedBy",
                    "不支持的 generatedBy: " + value + "，可选值: RULE, TEST, LLM");
        }
    }

    /**
     * 分数统一 0~1，最多 5 位小数（列定义 DECIMAL(6,5)）。
     *
     * <p>越界或多出小数位一律 400，不做静默四舍五入：分数是被比较的量，
     * 悄悄改精度会让"同一结果复算"对不上。
     */
    private static BigDecimal normalizeScore(BigDecimal score) {
        if (score == null) {
            return null;
        }
        if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0) {
            throw BusinessException.field("score", "score 必须在 0~1 之间: " + score);
        }
        try {
            return score.setScale(5, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw BusinessException.field("score", "score 最多 5 位小数: " + score);
        }
    }

    private JsonNode requireObject(String field, JsonNode value) {
        if (value == null || value.isNull()) {
            throw BusinessException.field(field, field + " 不能为空");
        }
        if (!value.isObject()) {
            throw BusinessException.field(field, field + " 必须是 JSON 对象");
        }
        return value;
    }

    private static String requireErrorCode(String value) {
        if (value == null || value.isBlank()) {
            throw BusinessException.field("errorCode", "errorCode 不能为空");
        }
        String code = value.trim();
        if (!ERROR_CODE.matcher(code).matches()) {
            throw BusinessException.field("errorCode",
                    "errorCode 必须是大写字母开头的 A-Z0-9_ 组合，长度 1~64: " + code);
        }
        return code;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private Duration backoff(int attemptNo) {
        Duration base = attemptNo <= 1 ? props.retryBackoffFirst() : props.retryBackoffSecond();
        long jitter = java.util.concurrent.ThreadLocalRandom.current()
                .nextLong(Math.max(1, base.toMillis() / 5));
        return base.plusMillis(jitter);
    }

    private static boolean isTimeout(String errorCode) {
        return "TIMED_OUT".equals(errorCode) || "TIMEOUT".equals(errorCode)
                || "DEADLINE_EXCEEDED".equals(errorCode);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static BusinessException stale(String message) {
        return new BusinessException(ErrorCode.STALE_ATTEMPT, message);
    }

    /**
     * 完成正文摘要（不含 leaseToken）。
     *
     * <p>为什么去掉 token：同一结果可能由 Worker 用同一 token 重发（响应丢失），
     * token 变了下标就变，幂等性会失效。正文本身的任何变化都应该被视为"不同结论"。
     */
    private String completionHash(WorkerDtos.JobCompleteRequest request) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("verdict", request.verdict());
        node.put("score", request.score() == null ? null : request.score().stripTrailingZeros().toPlainString());
        node.put("reason", request.reason());
        node.set("evidence", request.evidence());
        node.set("result", request.result());
        node.put("generatedBy", request.generatedBy());
        node.set("usage", request.usage());
        return Sha256.ofUtf8(CanonicalJson.canonicalize(node));
    }

    private String failHash(WorkerDtos.JobFailRequest request) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("errorCode", request.errorCode());
        node.put("errorMessage", request.errorMessage());
        node.put("retryableHint", Boolean.TRUE.equals(request.retryableHint()));
        node.set("usage", request.usage());
        return Sha256.ofUtf8(CanonicalJson.canonicalize(node));
    }
}
