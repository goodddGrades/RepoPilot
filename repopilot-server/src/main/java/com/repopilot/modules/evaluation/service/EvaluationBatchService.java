package com.repopilot.modules.evaluation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.api.PageQuery;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.idempotency.IdempotencyDigest;
import com.repopilot.common.idempotency.IdempotencyService;
import com.repopilot.common.idempotency.IdempotentResult;
import com.repopilot.common.state.BatchStatus;
import com.repopilot.common.state.BatchTriggerType;
import com.repopilot.common.state.ExperimentStatus;
import com.repopilot.common.state.RunStatus;
import com.repopilot.common.state.TaskOutcome;
import com.repopilot.modules.evaluation.dto.EvaluationBatchDtos;
import com.repopilot.modules.evaluation.entity.EvaluationBatch;
import com.repopilot.modules.evaluation.entity.EvaluationBatchRun;
import com.repopilot.modules.evaluation.mapper.EvaluationBatchMapper;
import com.repopilot.modules.evaluation.mapper.EvaluationBatchRunMapper;
import com.repopilot.modules.evaluation.mapper.EvaluationJobAttemptMapper;
import com.repopilot.modules.evaluation.mapper.EvaluationJobMapper;
import com.repopilot.modules.evaluationconfig.entity.EvaluationPlanItem;
import com.repopilot.modules.evaluationconfig.service.EvaluationPlanVersionService;
import com.repopilot.modules.experiment.entity.Experiment;
import com.repopilot.modules.experiment.mapper.ExperimentMapper;
import com.repopilot.modules.trace.dto.RunDtos;
import com.repopilot.modules.trace.entity.EvaluationRun;
import com.repopilot.modules.trace.service.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 评测批次。
 *
 * <p>批次是"结果版本的载体"：一次评测 = 一个批次 + 冻结的 Run 集合 + 一个已发布方案版本。
 * TASK-004 只创建 {@code INITIAL}；TASK-007 补齐重评批次、读取与取消。
 *
 * <p>重评的三条硬规则在写入前就拒绝，而不是事后靠约定：
 * <ul>
 *   <li>只接受**已封口**的 Run（{@code sealed_at} 非空、状态 EVALUATING/COMPLETED），
 *       因此不会触发任何新的 Agent 执行；</li>
 *   <li>Run 必须属于同一个实验——数据库复合外键也会拒绝跨实验组合；</li>
 *   <li>批次 → Run 登记行 → Job 在同一事务写入，提交后范围不可修改。</li>
 * </ul>
 *
 * <p>初始批次不提供取消入口：取消初始批次会让 Run 停在 EVALUATING，
 * 正确的操作是取消实验（同时结束 Run 与所有批次）。
 */
@Service
public class EvaluationBatchService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationBatchService.class);

    private static final int MAX_RUNS_PER_BATCH = 10000;
    private static final String SYSTEM_ACTOR = "system";

    private final EvaluationBatchMapper batchMapper;
    private final EvaluationBatchRunMapper batchRunMapper;
    private final EvaluationJobMapper jobMapper;
    private final EvaluationJobAttemptMapper attemptMapper;
    private final EvaluationJobService jobService;
    private final EvaluationPlanVersionService planVersionService;
    private final ExperimentMapper experimentMapper;
    private final RunStore runStore;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public EvaluationBatchService(EvaluationBatchMapper batchMapper,
                                  EvaluationBatchRunMapper batchRunMapper,
                                  EvaluationJobMapper jobMapper,
                                  EvaluationJobAttemptMapper attemptMapper,
                                  EvaluationJobService jobService,
                                  EvaluationPlanVersionService planVersionService,
                                  ExperimentMapper experimentMapper,
                                  RunStore runStore,
                                  IdempotencyService idempotency,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager) {
        this.batchMapper = batchMapper;
        this.batchRunMapper = batchRunMapper;
        this.jobMapper = jobMapper;
        this.attemptMapper = attemptMapper;
        this.jobService = jobService;
        this.planVersionService = planVersionService;
        this.experimentMapper = experimentMapper;
        this.runStore = runStore;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ 初始批次

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
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "EvaluationBatch 不存在: " + batchId));
    }

    // ------------------------------------------------------------------ 重评批次

    /**
     * 新建重评批次。
     *
     * <p>请求体用 {@link JsonNode} 而不是 record：幂等摘要基于规范化后的原始正文，
     * 先转成 record 再序列化会丢掉"请求里到底写了什么"这一事实。
     */
    public IdempotentResult<EvaluationBatchDtos.BatchView> createBatch(String experimentId, JsonNode body,
                                                                  String idemKey) {
        String path = "/api/v1/experiments/" + experimentId + "/evaluation-batches";
        String scope = "POST " + path;
        String hash = IdempotencyDigest.of("POST", path, body);
        Optional<IdempotentResult<EvaluationBatchDtos.BatchView>> cached =
                idempotency.replay(scope, idemKey, hash, EvaluationBatchDtos.BatchView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        EvaluationBatchDtos.CreateBatchRequest request = parseCreateRequest(body);
        Experiment experiment = experimentMapper.findById(experimentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "实验不存在: " + experimentId));
        if (experiment.getStatus() == ExperimentStatus.DRAFT
                || experiment.getStatus() == ExperimentStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "实验处于 " + experiment.getStatus() + " 状态，不能新建评测批次");
        }
        planVersionService.requirePublishedVersion(request.planVersionId());
        BatchTriggerType trigger = parseTrigger(request.triggerType());
        if (request.parentBatchId() != null && !request.parentBatchId().isBlank()) {
            EvaluationBatch parent = require(request.parentBatchId().trim());
            if (!experimentId.equals(parent.getExperimentId())) {
                throw BusinessException.field("parentBatchId",
                        "父批次不属于该实验: " + request.parentBatchId());
            }
        }

        List<EvaluationRun> runs = resolveRuns(experimentId, request);
        if (runs.isEmpty()) {
            throw BusinessException.field("runIds", "没有解析到任何可重评的 Run");
        }
        List<EvaluationPlanItem> items = planVersionService.listItems(request.planVersionId());
        if (items.isEmpty()) {
            throw BusinessException.field("planVersionId", "方案版本没有任何算子项");
        }
        Instant now = Instant.now();

        EvaluationBatchDtos.BatchView view = tx.execute(status -> {
            EvaluationBatch batch = new EvaluationBatch();
            batch.setId(Ulid.newId());
            batch.setExperimentId(experimentId);
            batch.setEvaluationPlanVersionId(request.planVersionId());
            batch.setBatchNo(batchMapper.nextBatchNo(experimentId));
            batch.setTriggerType(trigger);
            batch.setParentBatchId(blankToNull(request.parentBatchId()));
            batch.setSelectionJson(selectionJson(request, runs));
            batch.setStatus(BatchStatus.PENDING);
            batch.setExpectedJobCount((long) runs.size() * items.size());
            batch.setCreatedBy(SYSTEM_ACTOR);
            batch.setCreatedAt(now);
            batch.setUpdatedAt(now);
            batchMapper.insert(batch);
            registerRuns(batch.getId(), experimentId,
                    runs.stream().map(EvaluationRun::getId).toList(), now);
            for (EvaluationRun run : runs) {
                // 复用 run 的 acceptedAttemptId：在线取 accepted Attempt，离线为 null
                jobService.createPendingJobs(run.getId(), batch.getId(),
                        request.planVersionId(), run.getAcceptedAttemptId());
            }
            return toView(batch);
        });

        idempotency.record(scope, idemKey, hash, "evaluation_batch", view.id(), 202, view);
        log.info("实验 {} 新建评测批次 {}（{} 个 Run × {} 个算子）", experimentId, view.id(),
                runs.size(), items.size());
        return new IdempotentResult<>(view, 202, false);
    }

    private EvaluationBatchDtos.CreateBatchRequest parseCreateRequest(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw BusinessException.field("body", "请求体必须是对象");
        }
        String planVersionId = text(body, "planVersionId");
        if (planVersionId == null) {
            throw BusinessException.field("planVersionId", "planVersionId 不能为空");
        }
        List<String> runIds = new ArrayList<>();
        JsonNode ids = body.get("runIds");
        if (ids != null && !ids.isNull()) {
            if (!ids.isArray()) {
                throw BusinessException.field("runIds", "runIds 必须是数组");
            }
            for (JsonNode id : ids) {
                if (id == null || id.asText().isBlank()) {
                    throw BusinessException.field("runIds", "runIds 不能包含空值");
                }
                runIds.add(id.asText().trim());
            }
        }
        JsonNode filterNode = body.get("filter");
        EvaluationBatchDtos.Filter filter = null;
        if (filterNode != null && !filterNode.isNull()) {
            if (!filterNode.isObject()) {
                throw BusinessException.field("filter", "filter 必须是对象");
            }
            filter = new EvaluationBatchDtos.Filter(text(filterNode, "targetId"),
                    text(filterNode, "caseKey"), text(filterNode, "status"),
                    text(filterNode, "taskOutcome"));
        }
        return new EvaluationBatchDtos.CreateBatchRequest(planVersionId,
                text(body, "triggerType"), text(body, "parentBatchId"), runIds, filter);
    }

    private static BatchTriggerType parseTrigger(String value) {
        if (value == null) {
            // 默认重评：这个接口不可能创建 INITIAL，缺省值不改变语义
            return BatchTriggerType.REEVALUATION;
        }
        BatchTriggerType trigger;
        try {
            trigger = BatchTriggerType.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("triggerType",
                    "不支持的 triggerType: " + value + "，可选值: REEVALUATION, MANUAL");
        }
        if (trigger == BatchTriggerType.INITIAL) {
            throw BusinessException.field("triggerType",
                    "INITIAL 批次由实验启动事务创建，不能通过该接口创建");
        }
        return trigger;
    }

    /**
     * 把 runIds 或 filter 一次性解析成具体 Run。
     *
     * <p>解析结果立即写入 {@code evaluation_batch_runs}；之后的查询只读这张登记表，
     * 不再重新执行过滤条件——否则"批次范围"会随时间漂移。
     */
    private List<EvaluationRun> resolveRuns(String experimentId, EvaluationBatchDtos.CreateBatchRequest request) {
        List<EvaluationRun> resolved = new ArrayList<>();
        if (request.runIds() != null && !request.runIds().isEmpty()) {
            Set<String> unique = new LinkedHashSet<>(request.runIds());
            if (unique.size() > MAX_RUNS_PER_BATCH) {
                throw BusinessException.field("runIds",
                        "单批次 Run 数不能超过 " + MAX_RUNS_PER_BATCH);
            }
            for (String runId : unique) {
                EvaluationRun run = runStore.findRun(runId)
                        .orElseThrow(() -> BusinessException.field("runIds",
                                "Run 不存在: " + runId));
                requireReevaluable(experimentId, run);
                resolved.add(run);
            }
            return resolved;
        }
        if (request.filter() != null) {
            List<com.repopilot.modules.trace.dto.RunListRow> rows = runStore.search(experimentId,
                    request.filter().targetId(), request.filter().caseKey(),
                    parseRunStatus(request.filter().status()),
                    parseTaskOutcome(request.filter().taskOutcome()),
                    MAX_RUNS_PER_BATCH, 0);
            for (com.repopilot.modules.trace.dto.RunListRow row : rows) {
                EvaluationRun run = runStore.findRun(row.id).orElse(null);
                if (run == null) {
                    continue;
                }
                if (isReevaluable(run)) {
                    resolved.add(run);
                }
            }
            return resolved;
        }
        throw BusinessException.field("runIds", "必须提供 runIds 或 filter");
    }

    private void requireReevaluable(String experimentId, EvaluationRun run) {
        if (!experimentId.equals(run.getExperimentId())) {
            throw BusinessException.field("runIds", "Run 不属于该实验: " + run.getId());
        }
        if (run.getStatus() == RunStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "已取消的 Run 不能重评: " + run.getId());
        }
        if (!isReevaluable(run)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "只有已封口的 Run 才能重评（当前 " + run.getStatus()
                            + (run.getSealedAt() == null ? "，未封口" : "") + "）: " + run.getId());
        }
    }

    /** 已封口且在评测中/已完成：既不重跑 Agent，也不会引用失败 Attempt。 */
    private static boolean isReevaluable(EvaluationRun run) {
        if (run.getSealedAt() == null) {
            return false;
        }
        return run.getStatus() == RunStatus.EVALUATING || run.getStatus() == RunStatus.COMPLETED;
    }

    private JsonNode selectionJson(EvaluationBatchDtos.CreateBatchRequest request, List<EvaluationRun> runs) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("scope", request.runIds() != null && !request.runIds().isEmpty()
                ? "EXPLICIT_RUN_IDS" : "FILTER");
        node.put("resolvedRunCount", runs.size());
        if (request.filter() != null) {
            ObjectNode filter = node.putObject("filter");
            putIfPresent(filter, "targetId", request.filter().targetId());
            putIfPresent(filter, "caseKey", request.filter().caseKey());
            putIfPresent(filter, "status", request.filter().status());
            putIfPresent(filter, "taskOutcome", request.filter().taskOutcome());
        }
        if (request.parentBatchId() != null && !request.parentBatchId().isBlank()) {
            node.put("parentBatchId", request.parentBatchId().trim());
        }
        ArrayNode ids = node.putArray("runIds");
        // 只保留前 100 个：selection 是"当初为什么选这些"的依据，不是范围真相，
        // 范围以 evaluation_batch_runs 为准，完整清单不在 JSON 里重复一份。
        runs.stream().limit(100).forEach(run -> ids.add(run.getId()));
        return node;
    }

    // ------------------------------------------------------------------ 读取

    public PageResponse<EvaluationBatchDtos.BatchView> list(String experimentId, String status,
                                                       Integer page, Integer pageSize) {
        experimentMapper.findById(experimentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "实验不存在: " + experimentId));
        PageQuery query = PageQuery.of(page, pageSize);
        BatchStatus parsed = parseBatchStatus(status);
        List<EvaluationBatchDtos.BatchView> items = batchMapper
                .listByExperiment(experimentId, parsed, query.pageSize(), query.offset())
                .stream().map(this::toView).toList();
        long total = batchMapper.countByExperiment(experimentId, parsed);
        return PageResponse.of(items, query, total);
    }

    public EvaluationBatchDtos.BatchView detail(String batchId) {
        return toView(require(batchId));
    }

    public PageResponse<RunDtos.RunView> runs(String batchId, Integer page, Integer pageSize) {
        require(batchId);
        PageQuery query = PageQuery.of(page, pageSize);
        List<RunDtos.RunView> items = runStore.listByBatch(batchId, query.pageSize(), query.offset())
                .stream().map(RunDtos.RunView::from).toList();
        return PageResponse.of(items, query, runStore.countByBatch(batchId));
    }

    public EvaluationBatchDtos.BatchView toView(EvaluationBatch batch) {
        return new EvaluationBatchDtos.BatchView(batch.getId(), batch.getExperimentId(),
                batch.getEvaluationPlanVersionId(),
                batch.getBatchNo() == null ? 0 : batch.getBatchNo(),
                batch.getTriggerType() == null ? null : batch.getTriggerType().name(),
                batch.getParentBatchId(), batch.getStatus(),
                batch.getExpectedJobCount() == null ? 0 : batch.getExpectedJobCount(),
                jobMapper.countBatchRuns(batch.getId()),
                EvaluationBatchDtos.JobCounts.of(jobMapper.countByStatus(batch.getId())),
                batch.getSelectionJson(), batch.getCreatedAt(), batch.getStartedAt(),
                batch.getFinishedAt());
    }

    // ------------------------------------------------------------------ 取消

    /**
     * 取消批次。
     *
     * <p>待执行 Job → SKIPPED；运行中 Job → FAILED(error_code=CANCELLED)；
     * 运行中 Attempt → CANCELLED。终态批次重复取消返回当前状态（幂等）。
     */
    public EvaluationBatchDtos.BatchView cancel(String batchId, String reason) {
        EvaluationBatch batch = require(batchId);
        if (batch.getTriggerType() == BatchTriggerType.INITIAL) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "初始批次不能单独取消，请取消实验（会同时结束 Run 与所有批次）");
        }
        Instant now = Instant.now();
        Boolean changed = tx.execute(status -> {
            int cancelled = batchMapper.cancel(batchId, now);
            if (cancelled != 1) {
                return false;
            }
            attemptMapper.cancelRunningByBatch(batchId, now);
            jobMapper.failRunningByBatch(batchId, now);
            jobMapper.skipPendingByBatch(batchId, now);
            return true;
        });
        if (Boolean.TRUE.equals(changed)) {
            log.info("评测批次 {} 已取消（原因: {}）", batchId, reason == null ? "未提供" : reason);
        }
        return toView(require(batchId));
    }

    private static BatchStatus parseBatchStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return BatchStatus.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("status", "不支持的 status: " + value);
        }
    }

    private static RunStatus parseRunStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return RunStatus.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("filter.status", "不支持的 Run status: " + value);
        }
    }

    private static TaskOutcome parseTaskOutcome(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TaskOutcome.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("filter.taskOutcome", "不支持的 taskOutcome: " + value);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
