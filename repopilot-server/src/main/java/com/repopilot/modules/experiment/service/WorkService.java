package com.repopilot.modules.experiment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.hash.Sha256;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.json.CanonicalJson;
import com.repopilot.common.registry.RunnerRegistry;
import com.repopilot.common.state.AttemptStatus;
import com.repopilot.common.state.ExperimentStatus;
import com.repopilot.common.state.RunStatus;
import com.repopilot.common.state.TaskOutcome;
import com.repopilot.common.storage.ArtifactStorage;
import com.repopilot.common.worker.LeaseTokens;
import com.repopilot.common.worker.WorkerProperties;
import com.repopilot.modules.agent.entity.AgentVersion;
import com.repopilot.modules.agent.service.AgentVersionService;
import com.repopilot.modules.dataset.entity.DatasetCase;
import com.repopilot.modules.dataset.service.DatasetVersionService;
import com.repopilot.modules.evaluation.entity.EvaluationBatch;
import com.repopilot.modules.evaluation.service.EvaluationBatchService;
import com.repopilot.modules.evaluation.service.EvaluationJobService;
import com.repopilot.modules.experiment.dto.WorkerDtos;
import com.repopilot.modules.experiment.entity.Experiment;
import com.repopilot.modules.experiment.entity.ExperimentTarget;
import com.repopilot.modules.experiment.mapper.ExperimentMapper;
import com.repopilot.modules.experiment.mapper.ExperimentTargetMapper;
import com.repopilot.modules.trace.entity.Artifact;
import com.repopilot.modules.trace.entity.EvaluationRun;
import com.repopilot.modules.trace.entity.RunAttempt;
import com.repopilot.modules.trace.entity.TraceEvent;
import com.repopilot.modules.trace.event.TraceEventTypes;
import com.repopilot.modules.trace.service.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 在线执行的 Worker 协议。
 *
 * <p>所有状态推进都遵循同一条模板：
 * <ol>
 *   <li>锁 Attempt 行，先校验令牌摘要（不泄露"这个 Attempt 是否存在"）；</li>
 *   <li>锁 Run 行，校验 {@code active_attempt_id} 与状态；</li>
 *   <li>校验租约未过期、硬截止未到、实验未取消；</li>
 *   <li>条件更新并检查影响行数。</li>
 * </ol>
 * 只做第 2、3 步而不加锁，会在"回收器刚判定过期、旧 Worker 同时提交"时把过期结果写成成功。
 *
 * <p>不实现任何 Agent 执行逻辑：V1 的 Runner 在 Python Worker 侧，本类只负责协议与持久化。
 */
@Service
public class WorkService {

    private static final Logger log = LoggerFactory.getLogger(WorkService.class);

    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SPAN_ID = Pattern.compile("^[0-9a-f]{16}$");
    private static final int MAX_EVENTS_PER_REQUEST = 500;
    private static final long MAX_EVENT_BYTES = 64 * 1024;
    private static final long MAX_EVENTS_TOTAL_BYTES = 1024 * 1024;
    private static final Set<String> ARTIFACT_TYPES = Set.of("OUTPUT", "STDOUT", "EVIDENCE");
    /** 候选窗口 = maxTasks × 该系数：并发领取时给"被别人抢先"留出替换空间。 */
    private static final int CLAIM_CANDIDATE_FACTOR = 5;
    private static final int CLAIM_CANDIDATE_LIMIT = 200;

    private final RunStore runStore;
    private final ExperimentMapper experimentMapper;
    private final ExperimentTargetMapper targetMapper;
    private final ExperimentStatusService experimentStatus;
    private final ExperimentAggregationService aggregation;
    private final AgentVersionService agentVersionService;
    private final DatasetVersionService datasetVersionService;
    private final EvaluationBatchService batchService;
    private final EvaluationJobService jobService;
    private final com.repopilot.modules.evaluation.service.EvaluationClaimService evaluationClaimService;
    private final com.repopilot.modules.evaluation.service.JobAttemptService jobAttemptService;
    private final RunnerRegistry runnerRegistry;
    private final ArtifactStorage storage;
    private final WorkerProperties props;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public WorkService(RunStore runStore, ExperimentMapper experimentMapper,
                       ExperimentTargetMapper targetMapper,
                       ExperimentStatusService experimentStatus,
                       ExperimentAggregationService aggregation,
                       AgentVersionService agentVersionService,
                       DatasetVersionService datasetVersionService,
                       EvaluationBatchService batchService,
                       EvaluationJobService jobService,
                       com.repopilot.modules.evaluation.service.EvaluationClaimService evaluationClaimService,
                       com.repopilot.modules.evaluation.service.JobAttemptService jobAttemptService,
                       RunnerRegistry runnerRegistry,
                       ArtifactStorage storage,
                       WorkerProperties props,
                       ObjectMapper objectMapper,
                       PlatformTransactionManager transactionManager) {
        this.runStore = runStore;
        this.experimentMapper = experimentMapper;
        this.targetMapper = targetMapper;
        this.experimentStatus = experimentStatus;
        this.aggregation = aggregation;
        this.agentVersionService = agentVersionService;
        this.datasetVersionService = datasetVersionService;
        this.batchService = batchService;
        this.jobService = jobService;
        this.evaluationClaimService = evaluationClaimService;
        this.jobAttemptService = jobAttemptService;
        this.runnerRegistry = runnerRegistry;
        this.storage = storage;
        this.props = props;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ 领取

    public WorkerDtos.ClaimResult claim(WorkerDtos.ClaimRequest request) {
        String workerId = requireWorkerId(request.workerId());
        if (!props.allowedWorkerIds().isEmpty() && !props.allowedWorkerIds().contains(workerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "workerId 不在允许列表内: " + workerId);
        }
        // 能力分两类：RUN:* 领执行任务，EVAL:* 领评测 Job。拼错的 EVAL key 在这里就 400，
        // 不等到"Worker 一直空闲"才发现。
        com.repopilot.common.registry.WorkerCapabilities capabilities =
                com.repopilot.common.registry.WorkerCapabilities.parse(request.capabilities());
        List<com.repopilot.common.state.RunnerType> runnerTypes = capabilities.runners();
        List<String> evaluatorKeys = evaluationClaimService.requireSupportedKeys(capabilities.evaluatorKeys());
        int maxTasks = request.maxTasks() == null ? 1 : request.maxTasks();
        if (maxTasks < 1 || maxTasks > props.maxTasksLimit()) {
            throw BusinessException.field("maxTasks", "maxTasks 必须在 1~" + props.maxTasksLimit() + " 之间");
        }
        List<String> typeNames = runnerTypes.stream().map(Enum::name).toList();
        Instant now = Instant.now();

        List<WorkerDtos.WorkItem> tasks = tx.execute(status -> {
            List<WorkerDtos.WorkItem> items = new ArrayList<>(maxTasks);
            // 候选只是"看一眼有哪些可选"，真正的抢占在 lockClaimCandidate 里逐条加锁完成。
            // 候选窗口比 maxTasks 宽，避免并发时互相抢同一条导致有人领空。
            int candidateLimit = Math.min(maxTasks * CLAIM_CANDIDATE_FACTOR, CLAIM_CANDIDATE_LIMIT);
            List<String> candidates = runnerTypes.isEmpty()
                    ? List.of() : runStore.claimCandidates(typeNames, now, candidateLimit);
            Set<String> startedExperiments = new HashSet<>();
            for (String runId : candidates) {
                if (items.size() >= maxTasks) {
                    break;
                }
                // 条件在加锁语句里重新校验：实验被取消、已被别人领走、退避未到点都会返回空
                if (runStore.lockClaimCandidate(runId, typeNames, now).isEmpty()) {
                    continue;
                }
                EvaluationRun run = runStore.lockRun(runId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.STALE_ATTEMPT,
                                "Run 在领取过程中消失: " + runId));
                String token = LeaseTokens.newToken();
                Instant deadline = now.plus(props.taskDeadline());
                RunAttempt attempt = runStore.createAttempt(run, workerId, LeaseTokens.hash(token),
                        now, min(now.plus(props.lease()), deadline), deadline);
                items.add(toWorkItem(run, attempt, token));
                startedExperiments.add(run.getExperimentId());
            }
            // 有任务真正被领走，实验才从 QUEUED 进入 RUNNING（条件更新，已取消则影响 0 行）
            for (String experimentId : startedExperiments) {
                experimentMapper.markRunning(experimentId, now);
            }
            // 剩余名额给评测任务：两类任务共用一次领取，避免"只跑 Agent 的 Worker"占满队列
            if (!evaluatorKeys.isEmpty() && items.size() < maxTasks) {
                items.addAll(evaluationClaimService.claim(workerId, evaluatorKeys,
                        maxTasks - items.size(), now));
            }
            return items;
        });
        if (tasks != null && !tasks.isEmpty()) {
            log.info("Worker {} 领取 {} 个任务", workerId, tasks.size());
        }
        return new WorkerDtos.ClaimResult(tasks == null ? List.of() : tasks);
    }

    private WorkerDtos.WorkItem toWorkItem(EvaluationRun run, RunAttempt attempt, String token) {
        Experiment experiment = experimentMapper.findById(run.getExperimentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "实验不存在: " + run.getExperimentId()));
        ExperimentTarget target = targetMapper.findById(run.getExperimentTargetId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "实验 Target 不存在: " + run.getExperimentTargetId()));
        AgentVersion agentVersion = agentVersionService.requireVersion(target.getAgentVersionId());
        DatasetCase datasetCase = datasetVersionService.requireCase(run.getDatasetCaseId());
        JsonNode budget = experiment.getConfigJson() != null && experiment.getConfigJson().has("budget")
                ? experiment.getConfigJson().get("budget")
                : objectMapper.createObjectNode();

        WorkerDtos.CasePayload casePayload = new WorkerDtos.CasePayload(datasetCase.getId(),
                datasetCase.getCaseKey(), datasetCase.getTaskType(), datasetCase.getInputJson(),
                datasetCase.getExpectedJson(), datasetCase.getMetadataJson());
        WorkerDtos.RunInput input = new WorkerDtos.RunInput(experiment.getId(),
                target.getTargetKey(), agentVersion.getId(), agentVersion.getRunnerType().name(),
                agentVersion.getConfigJson(), budget, casePayload);
        // 转成 JsonNode：与 EVALUATION 任务共用 WorkItem.input 字段，序列化后的 JSON 形状不变
        return new WorkerDtos.WorkItem("RUN", run.getId(), attempt.getId(), attempt.getAttemptNo(),
                token, attempt.getLeaseUntil(), attempt.getDeadlineAt(),
                objectMapper.valueToTree(input), null);
    }

    // ------------------------------------------------------------------ 心跳

    public WorkerDtos.HeartbeatResult heartbeat(String attemptId, WorkerDtos.HeartbeatRequest request) {
        Instant now = Instant.now();
        return tx.execute(status -> {
            Live live = requireLiveAttempt(attemptId, request.leaseToken(), now);
            Instant leaseUntil = min(now.plus(props.lease()), live.attempt().getDeadlineAt());
            if (runStore.updateLease(attemptId, leaseUntil, now) != 1) {
                throw stale("续租失败，Attempt 已不在执行中: " + attemptId);
            }
            return new WorkerDtos.HeartbeatResult(leaseUntil, live.attempt().getDeadlineAt(), false);
        });
    }

    // ------------------------------------------------------------------ 事件

    public WorkerDtos.EventsResult appendEvents(String attemptId, WorkerDtos.EventsRequest request) {
        if (request.events() == null || request.events().isEmpty()) {
            throw BusinessException.field("events", "events 不能为空");
        }
        if (request.events().size() > MAX_EVENTS_PER_REQUEST) {
            throw BusinessException.field("events", "单批事件不能超过 " + MAX_EVENTS_PER_REQUEST + " 条");
        }
        Instant now = Instant.now();
        return tx.execute(status -> {
            Live live = requireLiveAttempt(attemptId, request.leaseToken(), now);
            if (live.run().getSealedAt() != null) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "Run 已封口，不再接受新的业务事件");
            }

            List<EventDraft> drafts = new ArrayList<>(request.events().size());
            List<String> eventIds = new ArrayList<>(request.events().size());
            long totalBytes = 0;
            for (int i = 0; i < request.events().size(); i++) {
                EventDraft draft = buildDraft(i, request.events().get(i), live.run(), attemptId, now);
                totalBytes += draft.payloadBytes();
                if (totalBytes > MAX_EVENTS_TOTAL_BYTES) {
                    throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE,
                            "单批事件总大小超过 1 MiB");
                }
                drafts.add(draft);
                eventIds.add(draft.eventId());
            }

            Map<String, TraceEvent> existing = new HashMap<>();
            for (TraceEvent event : runStore.lockEvents(eventIds)) {
                existing.put(event.getEventId(), event);
            }

            List<WorkerDtos.EventAckItem> acks = new ArrayList<>(drafts.size());
            for (EventDraft draft : drafts) {
                TraceEvent found = existing.get(draft.eventId());
                if (found != null) {
                    if (!found.getEventHash().equals(draft.hash())) {
                        throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                                "eventId " + draft.eventId() + " 已存在且内容不同");
                    }
                    acks.add(new WorkerDtos.EventAckItem(draft.eventId(), "DUPLICATE"));
                    continue;
                }
                runStore.insertEvent(draft.entity());
                acks.add(new WorkerDtos.EventAckItem(draft.eventId(), "ACCEPTED"));
            }
            return new WorkerDtos.EventsResult(acks);
        });
    }

    private EventDraft buildDraft(int index, WorkerDtos.EventItem item, EvaluationRun run,
                                  String attemptId, Instant now) {
        if (item == null) {
            throw BusinessException.field("events[" + index + "]", "事件不能为空");
        }
        String prefix = "events[" + index + "]";
        String eventType = item.eventType();
        TraceEventTypes.require(eventType, item.payload());

        // eventId 必须由 Worker 在**首次**生成事件时确定：后端代生成会让"网络重发"变成
        // 两条不同 eventId 的新事件，幂等契约（同 eventId 同 hash 返回 DUPLICATE）就失效了。
        String eventId = item.eventId();
        if (eventId == null || eventId.isBlank()) {
            throw BusinessException.field(prefix + ".eventId",
                    "eventId 不能为空：在线 Worker 必须在首次生成事件时确定 ULID，并在重发时保持不变");
        }
        if (!Ulid.isValid(eventId)) {
            throw BusinessException.field(prefix + ".eventId", "eventId 必须是 26 位 ULID");
        }
        String traceId = optionalPattern(prefix + ".traceId", item.traceId(), TRACE_ID,
                "必须是 32 位小写十六进制");
        String spanId = optionalPattern(prefix + ".spanId", item.spanId(), SPAN_ID,
                "必须是 16 位小写十六进制");
        String parentSpanId = optionalPattern(prefix + ".parentSpanId", item.parentSpanId(), SPAN_ID,
                "必须是 16 位小写十六进制");
        Instant occurredAt = item.occurredAt() == null ? now : item.occurredAt();

        JsonNode payload = item.payload();
        long payloadBytes = payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (payloadBytes > MAX_EVENT_BYTES) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE,
                    prefix + ".payload 超过 64 KiB");
        }

        String artifactId = null;
        if (item.artifactId() != null && !item.artifactId().isBlank()) {
            Artifact artifact = runStore.findArtifact(item.artifactId().trim())
                    .orElseThrow(() -> BusinessException.field(prefix + ".artifactId",
                            "Artifact 不存在: " + item.artifactId()));
            if (!run.getId().equals(artifact.getRunId())) {
                throw BusinessException.field(prefix + ".artifactId", "Artifact 不属于该 Run");
            }
            artifactId = artifact.getId();
        }

        ObjectNode hashNode = objectMapper.createObjectNode();
        hashNode.put("eventType", eventType);
        hashNode.put("occurredAt", occurredAt.truncatedTo(ChronoUnit.MILLIS).toString());
        if (item.sequenceNo() != null) {
            hashNode.put("sequenceNo", item.sequenceNo());
        }
        putIfPresent(hashNode, "traceId", traceId);
        putIfPresent(hashNode, "spanId", spanId);
        putIfPresent(hashNode, "parentSpanId", parentSpanId);
        putIfPresent(hashNode, "sourceEventId", item.sourceEventId());
        putIfPresent(hashNode, "artifactId", artifactId);
        hashNode.set("payload", payload);
        String hash = Sha256.ofUtf8(CanonicalJson.canonicalize(hashNode));

        TraceEvent entity = new TraceEvent();
        entity.setId(Ulid.newId());
        entity.setEventId(eventId);
        entity.setRunId(run.getId());
        entity.setRunAttemptId(attemptId);
        entity.setSourceEventId(item.sourceEventId());
        entity.setEventHash(hash);
        entity.setTraceId(traceId);
        entity.setSpanId(spanId);
        entity.setParentSpanId(parentSpanId);
        entity.setSequenceNo(item.sequenceNo());
        entity.setEventType(eventType);
        entity.setOccurredAt(occurredAt.truncatedTo(ChronoUnit.MILLIS));
        entity.setPayloadJson(payload);
        entity.setArtifactId(artifactId);
        entity.setCreatedAt(now);
        return new EventDraft(eventId, hash, payloadBytes, entity);
    }

    private record EventDraft(String eventId, String hash, long payloadBytes, TraceEvent entity) {
    }

    // ------------------------------------------------------------------ 完成

    public WorkerDtos.CompleteResult complete(String attemptId, WorkerDtos.CompleteRequest request) {
        if (request.taskOutcome() == null || request.taskOutcome().isBlank()) {
            throw BusinessException.field("taskOutcome", "taskOutcome 不能为空");
        }
        TaskOutcome taskOutcome = parseTaskOutcome(request.taskOutcome());
        if (request.output() != null && !request.output().isNull()
                && request.outputArtifactId() != null && !request.outputArtifactId().isBlank()) {
            throw BusinessException.field("outputArtifactId",
                    "output 与 outputArtifactId 只能提供一个");
        }
        if (request.expectedEventCount() == null || request.expectedEventCount() < 0) {
            throw BusinessException.field("expectedEventCount", "expectedEventCount 必须是非负整数");
        }
        if (request.eventManifestSha256() == null || request.eventManifestSha256().isBlank()) {
            throw BusinessException.field("eventManifestSha256", "eventManifestSha256 不能为空");
        }

        String completionHash = completionHash(request);
        Instant now = Instant.now();

        WorkerDtos.CompleteResult result = tx.execute(status -> {
            RunAttempt attempt = runStore.lockAttempt(attemptId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                            "RunAttempt 不存在: " + attemptId));
            requireToken(attempt, request.leaseToken());
            if (attempt.getStatus() != AttemptStatus.RUNNING) {
                if (attempt.getStatus() == AttemptStatus.COMPLETED
                        && completionHash.equals(attempt.getCompletionHash())) {
                    return new WorkerDtos.CompleteResult(attemptId, "COMPLETED");
                }
                throw stale("Attempt 已结束（" + attempt.getStatus() + "），不能再提交完成: " + attemptId);
            }

            Live live = requireLiveRun(attempt, now);
            requireArtifactBelongs(live.run().getId(), request.outputArtifactId());
            verifyEventManifest(attemptId, request.expectedEventCount(), request.eventManifestSha256());

            if (runStore.completeAttempt(attemptId, completionHash, request.output(),
                    blankToNull(request.outputArtifactId()), request.usage(), now) != 1) {
                throw stale("Attempt 状态已变化: " + attemptId);
            }
            if (runStore.sealSuccess(live.run().getId(), attemptId, taskOutcome, request.output(),
                    blankToNull(request.outputArtifactId()), request.usage(),
                    observationMetadata(request), now) != 1) {
                // 抛异常回滚 Attempt 的 COMPLETED：两者必须同时成立
                throw stale("Run 已被其他尝试接管，封口失败: " + live.run().getId());
            }

            EvaluationBatch batch = batchService.findInitial(live.run().getExperimentId());
            if (batch == null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "实验缺少 INITIAL 批次: " + live.run().getExperimentId());
            }
            jobService.createPendingJobs(live.run().getId(), batch.getId(),
                    batch.getEvaluationPlanVersionId(), attemptId);
            return new WorkerDtos.CompleteResult(attemptId, "COMPLETED");
        });

        aggregation.aggregateIfSettled(runStore.requireRun(runIdOf(attemptId)).getExperimentId());
        return result;
    }

    // ------------------------------------------------------------------ 失败

    public WorkerDtos.FailResult fail(String attemptId, WorkerDtos.FailRequest request) {
        if (request.errorCode() == null || request.errorCode().isBlank()) {
            throw BusinessException.field("errorCode", "errorCode 不能为空");
        }
        String errorCode = request.errorCode().trim();
        if (errorCode.length() > 64) {
            throw BusinessException.field("errorCode", "errorCode 长度不能超过 64");
        }
        String errorMessage = request.errorMessage() == null ? null : request.errorMessage().trim();
        if (errorMessage != null && errorMessage.length() > 1024) {
            errorMessage = errorMessage.substring(0, 1024);
        }
        String completionHash = completionHash(request);
        Instant now = Instant.now();
        boolean hint = Boolean.TRUE.equals(request.retryableHint());
        String finalMessage = errorMessage;

        Outcome outcome = tx.execute(status -> {
            RunAttempt attempt = runStore.lockAttempt(attemptId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                            "RunAttempt 不存在: " + attemptId));
            requireToken(attempt, request.leaseToken());
            if (attempt.getStatus() != AttemptStatus.RUNNING) {
                if (attempt.getCompletionHash() != null
                        && attempt.getCompletionHash().equals(completionHash)) {
                    // 同正文重放：回执里的重试信息以 Run 当前状态为准，不能凭空返回 false
                    EvaluationRun replayRun = runStore.lockRun(attempt.getRunId()).orElse(null);
                    Instant next = replayRun == null ? null : replayRun.getNextRetryAt();
                    return new Outcome(attempt.getStatus().name(), next != null, next);
                }
                throw stale("Attempt 已结束（" + attempt.getStatus() + "），不能再提交失败: " + attemptId);
            }
            Live live = requireLiveRun(attempt, now);

            AttemptStatus attemptStatus = isTimeout(errorCode)
                    ? AttemptStatus.TIMED_OUT : AttemptStatus.FAILED;
            if (runStore.failAttempt(attemptId, attemptStatus, completionHash, request.usage(),
                    errorCode, finalMessage, now) != 1) {
                throw stale("Attempt 状态已变化: " + attemptId);
            }

            boolean retry = hint && attempt.getAttemptNo() < props.maxAttemptsPerRun();
            if (retry) {
                Instant nextRetryAt = now.plus(backoff(attempt.getAttemptNo()));
                if (runStore.releaseForRetry(live.run().getId(), attemptId, nextRetryAt, now) != 1) {
                    throw stale("Run 状态已变化，无法安排重试: " + live.run().getId());
                }
                return new Outcome(attemptStatus.name(), true, nextRetryAt);
            }

            RunStatus runStatus = isTimeout(errorCode) ? RunStatus.TIMED_OUT : RunStatus.FAILED;
            if (runStore.markTerminal(live.run().getId(), attemptId, runStatus, now) != 1) {
                throw stale("Run 状态已变化，无法写入终态: " + live.run().getId());
            }
            return new Outcome(attemptStatus.name(), false, null);
        });

        if (outcome != null && !outcome.retryScheduled()) {
            aggregation.aggregateIfSettled(runStore.requireRun(runIdOf(attemptId)).getExperimentId());
        }
        return new WorkerDtos.FailResult(attemptId,
                outcome == null ? AttemptStatus.FAILED.name() : outcome.status(),
                runIdOf(attemptId), outcome != null && outcome.retryScheduled(),
                outcome == null ? null : outcome.nextRetryAt());
    }

    private record Outcome(String status, boolean retryScheduled, Instant nextRetryAt) {
    }

    // ------------------------------------------------------------------ Artifact

    public WorkerDtos.ArtifactView uploadArtifact(String runId, String attemptId, String leaseToken,
                                                  String artifactType, String contentType,
                                                  byte[] content) {
        String type = artifactType == null || artifactType.isBlank() ? "OUTPUT" : artifactType.trim();
        if (!ARTIFACT_TYPES.contains(type)) {
            throw BusinessException.field("artifactType",
                    "不支持的 artifactType: " + type + "，可选值: " + String.join(", ", ARTIFACT_TYPES));
        }
        if (content == null || content.length == 0) {
            throw BusinessException.field("file", "文件不能为空");
        }
        Instant now = Instant.now();
        Live live = tx.execute(status -> {
            RunAttempt attempt = runStore.lockAttempt(attemptId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                            "RunAttempt 不存在: " + attemptId));
            requireToken(attempt, leaseToken);
            if (!runId.equals(attempt.getRunId())) {
                throw BusinessException.field("runId", "Attempt 不属于该 Run");
            }
            return requireLiveRun(attempt, now);
        });
        if (live == null) {
            throw stale("Attempt 已不在执行中: " + attemptId);
        }

        ArtifactStorage.StoredObject stored = storage.put("runs/" + runId, content, contentType);
        try {
            Artifact artifact = new Artifact();
            artifact.setId(Ulid.newId());
            artifact.setRunId(runId);
            artifact.setArtifactType(type);
            artifact.setStorageProvider(stored.provider());
            artifact.setObjectKey(stored.objectKey());
            artifact.setContentType(contentType);
            artifact.setSizeBytes(stored.sizeBytes());
            artifact.setSha256(stored.sha256());
            artifact.setStatus(com.repopilot.common.state.ArtifactStatus.READY);
            artifact.setCreatedAt(now);
            tx.executeWithoutResult(status -> runStore.insertArtifact(artifact));
            return new WorkerDtos.ArtifactView(artifact.getId(), runId, type, contentType,
                    stored.sizeBytes(), stored.sha256(), "READY", now);
        } catch (RuntimeException ex) {
            // 元数据写失败：删掉刚写的对象，避免孤儿文件
            try {
                storage.delete(stored.objectKey());
            } catch (RuntimeException cleanup) {
                log.warn("Artifact 清理失败，需人工处理: {}", stored.objectKey(), cleanup);
            }
            throw ex;
        }
    }

    // ------------------------------------------------------------------ 后台维护

    /**
     * 扫描推进与回收。
     *
     * <p>三个动作都是幂等的：推进重复执行只是影响 0 行；回收对已终态的 Attempt 直接跳过。
     * 因此多实例同时扫描不会互相破坏。
     */
    public MaintenanceResult maintain() {
        Instant now = Instant.now();
        int promotedPending = runStore.promotePending(now);
        int promotedRetries = runStore.promoteRetries(now);
        int reaped = reapExpired(now);
        // 评测 Attempt 与执行 Attempt 分开回收：两者的重试预算与终态语义不同
        int reapedJobs = jobAttemptService.reapExpired();
        return new MaintenanceResult(promotedPending, promotedRetries, reaped, reapedJobs);
    }

    public record MaintenanceResult(int promotedPending, int promotedRetries, int reaped,
                                    int reapedJobs) {
    }

    private int reapExpired(Instant now) {
        List<RunAttempt> expired = runStore.expiredAttempts(now, 100);
        int reaped = 0;
        Set<String> affectedExperiments = new HashSet<>();
        for (RunAttempt candidate : expired) {
            try {
                boolean changed = Boolean.TRUE.equals(tx.execute(status -> reapOne(candidate.getId(), now)));
                if (changed) {
                    reaped++;
                }
                runStore.findAttempt(candidate.getId()).ifPresent(a ->
                        runStore.findRun(a.getRunId()).ifPresent(r -> affectedExperiments.add(r.getExperimentId())));
            } catch (RuntimeException ex) {
                log.warn("回收 Attempt {} 失败: {}", candidate.getId(), ex.getMessage());
            }
        }
        for (String experimentId : affectedExperiments) {
            aggregation.aggregateIfSettled(experimentId);
        }
        return reaped;
    }

    private boolean reapOne(String attemptId, Instant now) {
        Optional<RunAttempt> locked = runStore.lockAttempt(attemptId);
        if (locked.isEmpty() || locked.get().getStatus() != AttemptStatus.RUNNING) {
            return false;
        }
        RunAttempt attempt = locked.get();
        Optional<EvaluationRun> lockedRun = runStore.lockRun(attempt.getRunId());
        if (lockedRun.isEmpty()) {
            return false;
        }
        EvaluationRun run = lockedRun.get();
        if (run.getStatus() == RunStatus.CANCELLED) {
            return false;
        }
        boolean deadlineExceeded = attempt.getDeadlineAt() != null && !now.isBefore(attempt.getDeadlineAt());
        AttemptStatus attemptStatus = deadlineExceeded ? AttemptStatus.TIMED_OUT : AttemptStatus.FAILED;
        String errorCode = deadlineExceeded ? "DEADLINE_EXCEEDED" : "LEASE_EXPIRED";
        runStore.failAttempt(attemptId, attemptStatus, null, null, errorCode,
                "租约已过期，由回收器终止", now);

        if (!attempt.getId().equals(run.getActiveAttemptId())) {
            // 已被新 Attempt 取代：只终结旧 Attempt，不碰 Run
            return true;
        }
        boolean retry = attempt.getAttemptNo() < props.maxAttemptsPerRun()
                && run.getStatus() == RunStatus.RUNNING;
        if (retry) {
            runStore.releaseForRetry(run.getId(), attemptId, now.plus(backoff(attempt.getAttemptNo())), now);
        } else {
            RunStatus runStatus = deadlineExceeded ? RunStatus.TIMED_OUT : RunStatus.FAILED;
            runStore.markTerminal(run.getId(), attemptId, runStatus, now);
        }
        return true;
    }

    // ------------------------------------------------------------------ 校验

    private record Live(RunAttempt attempt, EvaluationRun run) {
    }

    /**
     * 校验租约与父状态；返回加锁后的 Attempt 与 Run。
     *
     * <p>租约过期返回 409 而不是"顺手回收"：回收有独立的退避与重试策略，
     * 在回调路径里顺手做会让一次失败请求产生两次状态变化，很难排查。
     */
    private Live requireLiveAttempt(String attemptId, String token, Instant now) {
        RunAttempt attempt = runStore.lockAttempt(attemptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "RunAttempt 不存在: " + attemptId));
        requireToken(attempt, token);
        if (attempt.getStatus() != AttemptStatus.RUNNING) {
            throw stale("Attempt 已结束（" + attempt.getStatus() + "）: " + attemptId);
        }
        return requireLiveRun(attempt, now);
    }

    private Live requireLiveRun(RunAttempt attempt, Instant now) {
        EvaluationRun run = runStore.lockRun(attempt.getRunId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Run 不存在: " + attempt.getRunId()));
        if (run.getStatus() != RunStatus.RUNNING
                || !attempt.getId().equals(run.getActiveAttemptId())) {
            throw stale("Attempt 已被取代或 Run 已不在执行中: " + attempt.getId());
        }
        if (now.isAfter(attempt.getLeaseUntil())) {
            throw stale("租约已过期: " + attempt.getId());
        }
        if (attempt.getDeadlineAt() != null && !now.isBefore(attempt.getDeadlineAt())) {
            throw stale("已超过硬截止时间: " + attempt.getId());
        }
        Experiment experiment = experimentMapper.findById(run.getExperimentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "实验不存在: " + run.getExperimentId()));
        if (experiment.getStatus() == ExperimentStatus.CANCELLED) {
            throw stale("实验已取消: " + run.getExperimentId());
        }
        return new Live(attempt, run);
    }

    private void requireToken(RunAttempt attempt, String token) {
        if (!LeaseTokens.matches(token, attempt.getLeaseTokenHash())) {
            // 与"Attempt 不存在"同一个错误码，避免通过响应差异枚举 Attempt
            throw stale("租约令牌不匹配: " + attempt.getId());
        }
    }

    private void verifyEventManifest(String attemptId, int expectedCount, String expectedSha) {
        List<String> ids = runStore.eventIdsOfAttempt(attemptId);
        if (ids.size() != expectedCount) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                    "事件数量不一致：后端已接受 " + ids.size() + "，Worker 声明 " + expectedCount);
        }
        String manifest = manifestSha(ids);
        if (!manifest.equals(expectedSha.trim().toLowerCase(java.util.Locale.ROOT))) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                    "事件清单摘要不一致，可能仍有事件未到达");
        }
    }

    /** 事件清单摘要：eventId 按 ASCII 升序，LF 连接，末尾无 LF。 */
    public static String manifestSha(List<String> eventIds) {
        List<String> sorted = new ArrayList<>(eventIds);
        sorted.sort(String::compareTo);
        return Sha256.ofUtf8(String.join("\n", sorted));
    }

    private void requireArtifactBelongs(String runId, String artifactId) {
        if (artifactId == null || artifactId.isBlank()) {
            return;
        }
        Artifact artifact = runStore.findArtifact(artifactId.trim())
                .orElseThrow(() -> BusinessException.field("outputArtifactId",
                        "Artifact 不存在: " + artifactId));
        if (!runId.equals(artifact.getRunId())) {
            throw BusinessException.field("outputArtifactId", "Artifact 不属于该 Run");
        }
    }

    /**
     * 观测完整性元数据。
     *
     * <p>契约里 {@code observationMetadata} 是可选字段，但 {@code evaluation_runs.observation_metadata_json}
     * 是 NOT NULL——所以缺省时由后端补一份**只陈述已知事实**的最小快照：
     * 我们知道输出是否存在、事件清单已按摘要核对过、最终结果来自 Runner 汇报，
     * 但不知道上游 Agent 自身的 Trace 是否完整，因此不写成"覆盖完整"。
     */
    private JsonNode observationMetadata(WorkerDtos.CompleteRequest request) {
        JsonNode declared = request.observationMetadata();
        if (declared != null && !declared.isNull()) {
            return declared;
        }
        boolean outputKnown = (request.output() != null && !request.output().isNull())
                || (request.outputArtifactId() != null && !request.outputArtifactId().isBlank());
        ObjectNode node = objectMapper.createObjectNode();
        node.put("outputKnown", outputKnown);
        node.put("traceCoverage", "EVENT_MANIFEST_ONLY");
        node.put("outcomeSource", "RUNNER_REPORTED");
        node.put("adapterVersion", "worker-protocol/1");
        return node;
    }

    private String completionHash(WorkerDtos.CompleteRequest request) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("taskOutcome", request.taskOutcome());
        node.set("output", request.output());
        putIfPresent(node, "outputArtifactId", request.outputArtifactId());
        node.set("usage", request.usage());
        node.set("observationMetadata", request.observationMetadata());
        node.put("expectedEventCount", request.expectedEventCount());
        node.put("eventManifestSha256", request.eventManifestSha256());
        return Sha256.ofUtf8(CanonicalJson.canonicalize(node));
    }

    private String completionHash(WorkerDtos.FailRequest request) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("errorCode", request.errorCode());
        node.put("errorMessage", request.errorMessage());
        node.put("retryableHint", Boolean.TRUE.equals(request.retryableHint()));
        node.set("usage", request.usage());
        return Sha256.ofUtf8(CanonicalJson.canonicalize(node));
    }

    private Duration backoff(int attemptNo) {
        Duration base = attemptNo <= 1 ? props.retryBackoffFirst() : props.retryBackoffSecond();
        // 抖动最多 20%：避免多个 Run 在同一秒重试，把瞬时故障放大成尖峰
        long jitter = java.util.concurrent.ThreadLocalRandom.current()
                .nextLong(Math.max(1, base.toMillis() / 5));
        return base.plusMillis(jitter);
    }

    private static boolean isTimeout(String errorCode) {
        return "TIMED_OUT".equals(errorCode) || "TIMEOUT".equals(errorCode)
                || "DEADLINE_EXCEEDED".equals(errorCode);
    }

    private static TaskOutcome parseTaskOutcome(String value) {
        try {
            return TaskOutcome.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("taskOutcome",
                    "不支持的 taskOutcome: " + value
                            + "，可选值: SUCCESS, FAILURE, NOT_APPLICABLE, UNKNOWN");
        }
    }

    private static String requireWorkerId(String value) {
        if (value == null || value.isBlank()) {
            throw BusinessException.field("workerId", "workerId 不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > 128) {
            throw BusinessException.field("workerId", "workerId 长度不能超过 128");
        }
        return trimmed;
    }

    private static String optionalPattern(String field, String value, Pattern pattern, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (!pattern.matcher(trimmed).matches()) {
            throw BusinessException.field(field, message);
        }
        return trimmed;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private String runIdOf(String attemptId) {
        return runStore.findAttempt(attemptId).map(RunAttempt::getRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "RunAttempt 不存在: " + attemptId));
    }

    private static BusinessException stale(String message) {
        return new BusinessException(ErrorCode.STALE_ATTEMPT, message);
    }
}
