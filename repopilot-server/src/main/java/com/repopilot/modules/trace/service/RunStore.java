package com.repopilot.modules.trace.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.state.AttemptStatus;
import com.repopilot.common.state.RunStatus;
import com.repopilot.common.state.TaskOutcome;
import com.repopilot.modules.trace.dto.RunListRow;
import com.repopilot.modules.trace.entity.Artifact;
import com.repopilot.modules.trace.entity.EvaluationRun;
import com.repopilot.modules.trace.entity.RunAttempt;
import com.repopilot.modules.trace.entity.TraceEvent;
import com.repopilot.modules.trace.mapper.ArtifactMapper;
import com.repopilot.modules.trace.mapper.EvaluationRunMapper;
import com.repopilot.modules.trace.mapper.RunAttemptMapper;
import com.repopilot.modules.trace.mapper.TraceEventMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Run / Attempt / Trace 的持久化入口。
 *
 * <p>只封装本模块（trace）的表。实验、Agent、评测配置的读取由调用方通过各自的
 * application 服务完成，避免把跨模块 Mapper 注入到这里。
 *
 * <p>所有"状态推进"都使用<b>条件更新 + 影响行数</b>，而不是先读后写：先读后写在并发下必然出现
 * "两个执行者都认为自己合法"。影响行数为 0 时由调用方决定抛 409 还是回滚。
 */
@Service
public class RunStore {

    /** 与 TASK-003 的数据集导入同一个分片口径：MySQL 单语句最多 65535 个占位符。 */
    public static final int CHUNK_SIZE = 500;

    private final EvaluationRunMapper runMapper;
    private final RunAttemptMapper attemptMapper;
    private final TraceEventMapper eventMapper;
    private final ArtifactMapper artifactMapper;

    public RunStore(EvaluationRunMapper runMapper, RunAttemptMapper attemptMapper,
                    TraceEventMapper eventMapper, ArtifactMapper artifactMapper) {
        this.runMapper = runMapper;
        this.attemptMapper = attemptMapper;
        this.eventMapper = eventMapper;
        this.artifactMapper = artifactMapper;
    }

    public void insertSlots(List<EvaluationRun> runs) {
        for (int from = 0; from < runs.size(); from += CHUNK_SIZE) {
            int to = Math.min(from + CHUNK_SIZE, runs.size());
            runMapper.batchInsert(runs.subList(from, to));
        }
    }

    public Optional<EvaluationRun> findRun(String id) {
        return runMapper.findById(id);
    }

    public EvaluationRun requireRun(String id) {
        return runMapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Run 不存在: " + id));
    }

    public Optional<EvaluationRun> lockRun(String id) {
        return runMapper.findByIdForUpdate(id);
    }

    public List<String> claimCandidates(List<String> runnerTypes, Instant now, int limit) {
        return runMapper.listClaimCandidates(runnerTypes, now, limit);
    }

    /**
     * 抢占一个候选 Run。
     *
     * <p>返回空表示：被别的 Worker 先锁住（{@code SKIP LOCKED}），或条件已不成立
     * （实验被取消、已被领取、退避未到点）。两种情况都不应由调用方重试同一 id。
     */
    public Optional<String> lockClaimCandidate(String id, List<String> runnerTypes, Instant now) {
        return runMapper.lockClaimCandidate(id, runnerTypes, now);
    }

    public int promotePending(Instant now) {
        return runMapper.promotePending(now);
    }

    public int promoteRetries(Instant now) {
        return runMapper.promoteRetries(now, now);
    }

    /**
     * 创建 Attempt 并占用 Run。
     *
     * <p>两步都在调用方的事务内：插入 Attempt 后若 {@code markRunning} 失败（另一执行者已占用），
     * 抛出的异常会让整个事务回滚，不会留下"有 Attempt 但没有 Run 引用"的孤儿行。
     */
    public RunAttempt createAttempt(EvaluationRun run, String workerId, String tokenHash,
                                    Instant now, Instant leaseUntil, Instant deadlineAt) {
        int attemptNo = (run.getAttemptCount() == null ? 0 : run.getAttemptCount()) + 1;
        RunAttempt attempt = new RunAttempt();
        attempt.setId(com.repopilot.common.id.Ulid.newId());
        attempt.setRunId(run.getId());
        attempt.setAttemptNo(attemptNo);
        attempt.setWorkerId(workerId);
        attempt.setStatus(AttemptStatus.RUNNING);
        attempt.setLeaseTokenHash(tokenHash);
        attempt.setLeaseUntil(leaseUntil);
        attempt.setDeadlineAt(deadlineAt);
        attempt.setStartedAt(now);
        attempt.setCreatedAt(now);
        attempt.setUpdatedAt(now);
        attemptMapper.insert(attempt);

        int updated = runMapper.markRunning(run.getId(), attempt.getId(), attemptNo, now, now);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.STALE_ATTEMPT,
                    "Run 已被其他执行者领取或状态已变化: " + run.getId());
        }
        return attempt;
    }

    public Optional<RunAttempt> lockAttempt(String id) {
        return attemptMapper.findByIdForUpdate(id);
    }

    public Optional<RunAttempt> findAttempt(String id) {
        return attemptMapper.findById(id);
    }

    public int updateLease(String attemptId, Instant leaseUntil, Instant now) {
        return attemptMapper.updateLease(attemptId, leaseUntil, now);
    }

    public int completeAttempt(String attemptId, String completionHash, JsonNode output,
                               String outputArtifactId, JsonNode usage, Instant now) {
        return attemptMapper.complete(attemptId, completionHash, output, outputArtifactId, usage, now);
    }

    public int failAttempt(String attemptId, AttemptStatus status, String completionHash,
                           JsonNode usage, String errorCode, String errorMessage, Instant now) {
        return attemptMapper.fail(attemptId, status, completionHash, usage, errorCode, errorMessage, now);
    }

    public int sealSuccess(String runId, String attemptId, TaskOutcome taskOutcome, JsonNode output,
                           String outputArtifactId, JsonNode usage, JsonNode observationMetadata, Instant now) {
        return runMapper.sealSuccess(runId, attemptId, taskOutcome, output, outputArtifactId,
                usage, observationMetadata, now);
    }

    public int markTerminal(String runId, String attemptId, RunStatus status, Instant now) {
        return runMapper.markTerminal(runId, attemptId, status, now);
    }

    public int releaseForRetry(String runId, String attemptId, Instant nextRetryAt, Instant now) {
        return runMapper.releaseForRetry(runId, attemptId, nextRetryAt, now);
    }

    public int cancelRunningAttempts(String experimentId, Instant now) {
        return attemptMapper.cancelRunningByExperiment(experimentId, now);
    }

    public int cancelNonTerminal(String experimentId, Instant now) {
        return runMapper.cancelNonTerminalByExperiment(experimentId, now);
    }

    public long countNonTerminal(String experimentId) {
        return runMapper.countNonTerminal(experimentId);
    }

    public long countCompleted(String experimentId) {
        return runMapper.countCompleted(experimentId);
    }

    public long countByExperiment(String experimentId) {
        return runMapper.countByExperiment(experimentId);
    }

    public List<RunListRow> search(String experimentId, String targetId, String caseKey,
                                   RunStatus status, TaskOutcome taskOutcome, int limit, int offset) {
        return runMapper.search(experimentId, targetId, caseKey, status, taskOutcome, limit, offset);
    }

    public long count(String experimentId, String targetId, String caseKey,
                      RunStatus status, TaskOutcome taskOutcome) {
        return runMapper.count(experimentId, targetId, caseKey, status, taskOutcome);
    }

    public List<RunAttempt> attempts(String runId, int limit, int offset) {
        return attemptMapper.listByRun(runId, limit, offset);
    }

    public long countAttempts(String runId) {
        return attemptMapper.countByRun(runId);
    }

    public List<RunAttempt> expiredAttempts(Instant now, int limit) {
        return attemptMapper.findExpired(now, limit);
    }

    public List<TraceEvent> lockEvents(List<String> eventIds) {
        return eventIds.isEmpty() ? List.of() : eventMapper.findByEventIdsForUpdate(eventIds);
    }

    public void insertEvent(TraceEvent event) {
        eventMapper.insert(event);
    }

    public List<String> eventIdsOfAttempt(String attemptId) {
        return eventMapper.listEventIdsByAttempt(attemptId);
    }

    /** TASK-007：评测输入的事件清单。在线按 accepted Attempt 取，离线按 Run 取。 */
    public List<String> eventIdsOfRun(String runId, String attemptId) {
        return attemptId == null
                ? eventMapper.listEventIdsByRun(runId)
                : eventMapper.listEventIdsByAttempt(attemptId);
    }

    /** 证据引用校验：事件是否属于该 Run。 */
    public boolean eventBelongsToRun(String eventId, String runId) {
        return eventMapper.countEventInRun(eventId, runId) > 0;
    }

    public long countEvents(String attemptId) {
        return eventMapper.countByAttempt(attemptId);
    }

    public List<TraceEvent> events(String runId, String attemptId, int limit, int offset) {
        return eventMapper.listByRun(runId, attemptId, limit, offset);
    }

    public long countEventsOfRun(String runId, String attemptId) {
        return eventMapper.countByRun(runId, attemptId);
    }

    public Optional<Artifact> findArtifact(String id) {
        return artifactMapper.findById(id);
    }

    public void insertArtifact(Artifact artifact) {
        artifactMapper.insert(artifact);
    }

    // ---- TASK-005：离线导入 ----

    public Optional<EvaluationRun> findBySourceIdentity(String experimentId, String targetId,
                                                       String sourceSystem, String sourceRecordId) {
        return runMapper.findBySourceIdentity(experimentId, targetId, sourceSystem, sourceRecordId);
    }

    public Optional<EvaluationRun> findBySlot(String targetId, String caseId, int repeatIndex) {
        return runMapper.findBySlot(targetId, caseId, repeatIndex);
    }

    public List<EvaluationRun> listByExperiment(String experimentId) {
        return runMapper.listByExperiment(experimentId);
    }

    public int markEvaluating(String runId, Instant now) {
        return runMapper.markEvaluating(runId, now);
    }

    public void insertRun(EvaluationRun run) {
        runMapper.insert(run);
    }

    /** 离线事件批量写入：按 500 分片，避免多值 INSERT 超过占位符上限。 */
    public void insertEvents(List<TraceEvent> events) {
        for (int from = 0; from < events.size(); from += CHUNK_SIZE) {
            int to = Math.min(from + CHUNK_SIZE, events.size());
            eventMapper.batchInsert(events.subList(from, to));
        }
    }

    // ---- TASK-006：轨迹回放 ----

    /**
     * 键集分页取一页事件。
     *
     * @param afterAt 上一页最后一条的 {@code occurredAt}；null 表示第一页
     * @param afterId 上一页最后一条的数据库行 id，仅与 {@code afterAt} 一起使用
     */
    public List<TraceEvent> eventPage(String runId, String attemptId, String eventType,
                                      Instant afterAt, String afterId, int limit) {
        // 绑定成 UTC 墙钟（LocalDateTime）而不是 Timestamp：见 TraceEventMapper#listPage 的说明，
        // 同一个值换一种绑定方式，优化器就会从 range 退化回 run_id 的 ref 扫描。
        java.time.LocalDateTime bound = afterAt == null
                ? null : java.time.LocalDateTime.ofInstant(afterAt, java.time.ZoneOffset.UTC);
        return eventMapper.listPage(runId, attemptId, eventType, bound, afterId, limit);
    }

    /** 事件引用的 Artifact 中，确实归属该 Run 的那部分 id。 */
    public java.util.Set<String> artifactIdsOwnedByRun(String runId, List<String> ids) {
        if (ids.isEmpty()) {
            return java.util.Set.of();
        }
        return java.util.Set.copyOf(artifactMapper.findIdsOwnedByRun(runId, ids));
    }

    /** 清理孤儿对象时用来算差集。 */
    public List<String> allArtifactObjectKeys() {
        return artifactMapper.listObjectKeys();
    }

    // ---- TASK-007：评测收口 ----

    public List<com.repopilot.modules.trace.dto.RunListRow> listByBatch(String batchId,
                                                                        int limit, int offset) {
        return runMapper.listByBatch(batchId, limit, offset);
    }

    public long countByBatch(String batchId) {
        return runMapper.countByBatch(batchId);
    }

    public int markEvaluated(String runId, Instant now) {
        return runMapper.markEvaluated(runId, now);
    }

    public int markEvaluationFailed(String runId, Instant now) {
        return runMapper.markEvaluationFailed(runId, now);
    }
}
