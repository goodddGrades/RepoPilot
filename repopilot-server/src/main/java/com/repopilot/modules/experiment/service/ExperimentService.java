package com.repopilot.modules.experiment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.api.PageQuery;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.idempotency.IdempotencyDigest;
import com.repopilot.common.idempotency.IdempotencyService;
import com.repopilot.common.idempotency.IdempotentResult;
import com.repopilot.common.registry.RunnerRegistry;
import com.repopilot.common.state.ExperimentStatus;
import com.repopilot.common.state.RunMode;
import com.repopilot.common.state.RunStatus;
import com.repopilot.common.state.TaskOutcome;
import com.repopilot.common.state.VersionStatus;
import com.repopilot.common.validation.Inputs;
import com.repopilot.common.web.RequestBodies;
import com.repopilot.modules.agent.entity.AgentVersion;
import com.repopilot.modules.agent.service.AgentVersionService;
import com.repopilot.modules.dataset.entity.DatasetCase;
import com.repopilot.modules.dataset.entity.DatasetVersion;
import com.repopilot.modules.dataset.service.DatasetVersionService;
import com.repopilot.modules.evaluation.entity.EvaluationBatch;
import com.repopilot.modules.evaluation.service.EvaluationBatchService;
import com.repopilot.modules.evaluation.service.EvaluationJobService;
import com.repopilot.modules.evaluationconfig.entity.EvaluationPlanItem;
import com.repopilot.modules.evaluationconfig.entity.EvaluationPlanVersion;
import com.repopilot.modules.evaluationconfig.service.EvaluationPlanVersionService;
import com.repopilot.modules.experiment.dto.ExperimentDtos;
import com.repopilot.modules.experiment.entity.Experiment;
import com.repopilot.modules.experiment.entity.ExperimentTarget;
import com.repopilot.modules.experiment.mapper.ExperimentMapper;
import com.repopilot.modules.experiment.mapper.ExperimentTargetMapper;
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
 * 实验：草稿、提交（生成 Run 槽位）与取消。
 *
 * <p>提交是一个**单事务**：锁实验行 → 校验版本与数量 → 实验转 QUEUED → 插入全部 Run →
 * 创建 INITIAL 批次与 Run 登记 → 写幂等回执。任何一步失败都整体回滚，不存在
 * "实验已开始但只生成了一半 Run"的中间态。10000 个 Run 的上限正是为了让这件事在单事务里可控。
 *
 * <p>事务提交后另起一次"推进 PENDING→QUEUED"的扫描：它失败也不影响正确性，
 * 后台扫描会重做（数据库是唯一真相，内存里的动作不是）。
 */
@Service
public class ExperimentService {

    private static final Logger log = LoggerFactory.getLogger(ExperimentService.class);

    private static final int MAX_RUNS = 10000;
    private static final int MAX_REPEAT = 20;
    /** V1 无登录体系，批次记录使用固定系统身份；接入认证后由可信上下文注入。 */
    private static final String SYSTEM_ACTOR = "system";

    private final ExperimentMapper experimentMapper;
    private final ExperimentTargetMapper targetMapper;
    private final AgentVersionService agentVersionService;
    private final DatasetVersionService datasetVersionService;
    private final EvaluationPlanVersionService planVersionService;
    private final EvaluationBatchService batchService;
    private final EvaluationJobService jobService;
    private final RunStore runStore;
    private final RunnerRegistry runnerRegistry;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public ExperimentService(ExperimentMapper experimentMapper,
                             ExperimentTargetMapper targetMapper,
                             AgentVersionService agentVersionService,
                             DatasetVersionService datasetVersionService,
                             EvaluationPlanVersionService planVersionService,
                             EvaluationBatchService batchService,
                             EvaluationJobService jobService,
                             RunStore runStore,
                             RunnerRegistry runnerRegistry,
                             IdempotencyService idempotency,
                             ObjectMapper objectMapper,
                             PlatformTransactionManager transactionManager) {
        this.experimentMapper = experimentMapper;
        this.targetMapper = targetMapper;
        this.agentVersionService = agentVersionService;
        this.datasetVersionService = datasetVersionService;
        this.planVersionService = planVersionService;
        this.batchService = batchService;
        this.jobService = jobService;
        this.runStore = runStore;
        this.runnerRegistry = runnerRegistry;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ 创建

    public IdempotentResult<ExperimentDtos.ExperimentView> create(JsonNode body, String idemKey) {
        String path = "/api/v1/experiments";
        String scope = "POST " + path;
        String hash = IdempotencyDigest.of("POST", path, body);
        Optional<IdempotentResult<ExperimentDtos.ExperimentView>> cached =
                idempotency.replay(scope, idemKey, hash, ExperimentDtos.ExperimentView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        ExperimentDtos.CreateRequest request =
                RequestBodies.convert(objectMapper, body, ExperimentDtos.CreateRequest.class);
        String name = Inputs.required("name", request.name(), 128);
        String description = Inputs.optional("description", request.description(), 4000);
        RunMode mode = parseMode(request.mode());
        String datasetVersionId = requireUlid("datasetVersionId", request.datasetVersionId());
        String planVersionId = requireUlid("evaluationPlanVersionId", request.evaluationPlanVersionId());
        JsonNode config = request.config() == null || request.config().isNull()
                ? objectMapper.createObjectNode()
                : Inputs.object("config", request.config());
        validateBudget(config);

        List<ExperimentDtos.TargetRequest> targets = request.targets();
        if (targets == null || targets.isEmpty()) {
            throw BusinessException.field("targets", "至少需要一个 Target");
        }
        List<ExperimentTarget> targetEntities = new ArrayList<>(targets.size());
        Set<String> keys = new HashSet<>();
        Instant now = Instant.now();
        for (int i = 0; i < targets.size(); i++) {
            ExperimentDtos.TargetRequest target = targets.get(i);
            String prefix = "targets[" + i + "]";
            if (target == null) {
                throw BusinessException.field(prefix, "Target 不能为空");
            }
            String targetKey = Inputs.code(prefix + ".targetKey", target.targetKey());
            if (!keys.add(targetKey)) {
                throw BusinessException.field(prefix + ".targetKey",
                        "同一实验内 targetKey 不能重复: " + targetKey);
            }
            String displayName = Inputs.required(prefix + ".displayName", target.displayName(), 128);
            int repeat = target.repeatCount() == null ? 0 : target.repeatCount();
            if (repeat < 1 || repeat > MAX_REPEAT) {
                throw BusinessException.field(prefix + ".repeatCount",
                        "repeatCount 必须在 1~" + MAX_REPEAT + " 之间");
            }
            String agentVersionId = requireUlid(prefix + ".agentVersionId", target.agentVersionId());
            AgentVersion version = agentVersionService.requireVersion(agentVersionId);
            if (version.getStatus() == VersionStatus.ARCHIVED) {
                throw BusinessException.field(prefix + ".agentVersionId",
                        "已归档的 AgentVersion 不能用于新实验");
            }
            if (!runnerRegistry.isRegistered(version.getRunnerType())) {
                throw BusinessException.field(prefix + ".agentVersionId",
                        "Runner 未注册（V1 仅支持 MOCK）: " + version.getRunnerType());
            }

            ExperimentTarget entity = new ExperimentTarget();
            entity.setId(Ulid.newId());
            entity.setTargetKey(targetKey);
            entity.setDisplayName(displayName);
            entity.setAgentVersionId(agentVersionId);
            entity.setRepeatCount(repeat);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            targetEntities.add(entity);
        }

        DatasetVersion datasetVersion = datasetVersionService.requireVersion(datasetVersionId);
        if (datasetVersion.getStatus() == VersionStatus.ARCHIVED) {
            throw BusinessException.field("datasetVersionId", "已归档的数据集版本不能用于新实验");
        }
        EvaluationPlanVersion planVersion = planVersionService.requireVersion(planVersionId);
        if (planVersion.getStatus() == VersionStatus.ARCHIVED) {
            throw BusinessException.field("evaluationPlanVersionId", "已归档的方案版本不能用于新实验");
        }

        return idempotency.execute(scope, idemKey, hash, ExperimentDtos.ExperimentView.class, () ->
                tx.execute(status -> {
                    Experiment experiment = new Experiment();
                    experiment.setId(Ulid.newId());
                    experiment.setName(name);
                    experiment.setDescription(description);
                    experiment.setMode(mode);
                    experiment.setDatasetVersionId(datasetVersionId);
                    experiment.setEvaluationPlanVersionId(planVersionId);
                    experiment.setStatus(ExperimentStatus.DRAFT);
                    experiment.setConfigJson(config);
                    experiment.setExpectedRunCount(0L);
                    experiment.setCreatedAt(now);
                    experiment.setUpdatedAt(now);
                    experimentMapper.insert(experiment);

                    for (ExperimentTarget target : targetEntities) {
                        target.setExperimentId(experiment.getId());
                        targetMapper.insert(target);
                    }

                    ExperimentDtos.ExperimentView view =
                            ExperimentDtos.ExperimentView.from(experiment, targetEntities);
                    idempotency.record(scope, idemKey, hash, "experiment", experiment.getId(), 201, view);
                    return IdempotentResult.created(view);
                }));
    }

    // ------------------------------------------------------------------ 查询

    public ExperimentDtos.ExperimentView get(String id) {
        Experiment experiment = requireExperiment(id);
        return ExperimentDtos.ExperimentView.from(experiment, targetMapper.listByExperiment(id));
    }

    public PageResponse<ExperimentDtos.ExperimentView> list(Integer page, Integer pageSize,
                                                            String status, String mode, String keyword) {
        PageQuery query = PageQuery.of(page, pageSize);
        ExperimentStatus parsedStatus = status == null || status.isBlank() ? null : parseStatus(status);
        RunMode parsedMode = mode == null || mode.isBlank() ? null : parseMode(mode);
        String like = Inputs.likeKeyword(keyword, 64);
        List<ExperimentDtos.ExperimentView> items = experimentMapper
                .search(parsedStatus, parsedMode, like, query.pageSize(), query.offset())
                .stream()
                .map(e -> ExperimentDtos.ExperimentView.from(e, targetMapper.listByExperiment(e.getId())))
                .toList();
        long total = experimentMapper.count(parsedStatus, parsedMode, like);
        return PageResponse.of(items, query, total);
    }

    // ------------------------------------------------------------------ 启动

    /**
     * 提交实验。
     *
     * <p>所有校验在写任何槽位之前完成；校验失败保持 DRAFT（事务尚未改变任何状态）。
     * Run 槽位在同一事务内一次性生成——TASK-004 明确不允许"边生成边执行"。
     */
    public IdempotentResult<ExperimentDtos.StatusResult> start(String id, String idemKey) {
        String path = "/api/v1/experiments/" + id + "/start";
        String scope = "POST " + path;
        String hash = IdempotencyDigest.of("POST", path, (JsonNode) objectMapper.createObjectNode());
        Optional<IdempotentResult<ExperimentDtos.StatusResult>> cached =
                idempotency.replay(scope, idemKey, hash, ExperimentDtos.StatusResult.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        return idempotency.execute(scope, idemKey, hash, ExperimentDtos.StatusResult.class, () ->
                tx.execute(status -> {
                    Experiment experiment = experimentMapper.findByIdForUpdate(id)
                            .orElseThrow(() -> notFound(id));
                    if (experiment.getStatus() != ExperimentStatus.DRAFT) {
                        throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                                "只有 DRAFT 实验可以开始（当前 " + experiment.getStatus() + "）");
                    }
                    if (experiment.getMode() != RunMode.ONLINE) {
                        throw BusinessException.field("mode",
                                "TASK-004 只支持 ONLINE 实验；OFFLINE 导入在 TASK-005 实现");
                    }

                    DatasetVersion datasetVersion = datasetVersionService
                            .requireVersion(experiment.getDatasetVersionId());
                    if (datasetVersion.getStatus() != VersionStatus.PUBLISHED) {
                        throw BusinessException.field("datasetVersionId",
                                "数据集版本必须已发布（当前 " + datasetVersion.getStatus() + "）");
                    }
                    List<DatasetCase> cases = datasetVersionService.listCases(datasetVersion.getId());
                    if (cases.isEmpty()) {
                        throw BusinessException.field("datasetVersionId", "数据集版本不能为空");
                    }

                    EvaluationPlanVersion planVersion = planVersionService
                            .requireVersion(experiment.getEvaluationPlanVersionId());
                    if (planVersion.getStatus() != VersionStatus.PUBLISHED) {
                        throw BusinessException.field("evaluationPlanVersionId",
                                "方案版本必须已发布（当前 " + planVersion.getStatus() + "）");
                    }
                    List<EvaluationPlanItem> items = planVersionService.listItems(planVersion.getId());
                    if (items.isEmpty()) {
                        throw BusinessException.field("evaluationPlanVersionId", "方案版本不能为空");
                    }

                    List<ExperimentTarget> targets = targetMapper.listByExperiment(id);
                    if (targets.isEmpty()) {
                        throw BusinessException.field("targets", "实验没有 Target，不能开始");
                    }

                    long totalRuns = 0;
                    for (int i = 0; i < targets.size(); i++) {
                        ExperimentTarget target = targets.get(i);
                        String prefix = "targets[" + i + "]";
                        AgentVersion agentVersion = agentVersionService
                                .requireVersion(target.getAgentVersionId());
                        if (agentVersion.getStatus() != VersionStatus.PUBLISHED) {
                            throw BusinessException.field(prefix + ".agentVersionId",
                                    "AgentVersion 必须已发布（当前 " + agentVersion.getStatus() + "）");
                        }
                        if (!runnerRegistry.isRegistered(agentVersion.getRunnerType())) {
                            throw BusinessException.field(prefix + ".agentVersionId",
                                    "Runner 未注册（V1 仅支持 MOCK）: " + agentVersion.getRunnerType());
                        }
                        for (DatasetCase datasetCase : cases) {
                            com.repopilot.common.registry.TaskType taskType =
                                    com.repopilot.common.registry.TaskType.require(datasetCase.getTaskType());
                            if (!runnerRegistry.supports(agentVersion.getRunnerType(), taskType)) {
                                throw BusinessException.field("datasetVersionId",
                                        "样本 " + datasetCase.getCaseKey() + " 的 taskType "
                                                + datasetCase.getTaskType() + " 不被 Runner "
                                                + agentVersion.getRunnerType() + " 支持");
                            }
                        }
                        totalRuns += (long) cases.size() * target.getRepeatCount();
                    }
                    if (totalRuns > MAX_RUNS) {
                        throw BusinessException.field("targets",
                                "Run 总数 " + totalRuns + " 超过上限 " + MAX_RUNS);
                    }

                    Instant now = Instant.now();
                    if (experimentMapper.markQueued(id, totalRuns, now, now) != 1) {
                        throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                                "实验状态已变化，无法开始: " + id);
                    }

                    List<EvaluationRun> runs = new ArrayList<>((int) totalRuns);
                    for (ExperimentTarget target : targets) {
                        for (DatasetCase datasetCase : cases) {
                            for (int repeat = 1; repeat <= target.getRepeatCount(); repeat++) {
                                runs.add(newRun(experiment, target, datasetCase, repeat, now));
                            }
                        }
                    }
                    runStore.insertSlots(runs);

                    EvaluationBatch batch = batchService.createInitial(id, planVersion.getId(),
                            totalRuns, totalRuns * items.size(), SYSTEM_ACTOR, now);
                    batchService.registerRuns(batch.getId(), id,
                            runs.stream().map(EvaluationRun::getId).toList(), now);

                    ExperimentDtos.StatusResult result =
                            new ExperimentDtos.StatusResult(id, ExperimentStatus.QUEUED.name());
                    idempotency.record(scope, idemKey, hash, "experiment", id, 202, result);
                    return new IdempotentResult<>(result, 202, false);
                }));
    }

    /**
     * 提交后推进 PENDING→QUEUED。
     *
     * <p>放在事务外调用：推进失败不应回滚已经提交的实验；后台扫描会重做。
     */
    public int promoteAfterStart(String experimentId) {
        int promoted = runStore.promotePending(Instant.now());
        log.info("实验 {} 提交后推进 PENDING→QUEUED，影响 {} 个 Run", experimentId, promoted);
        return promoted;
    }

    // ------------------------------------------------------------------ 取消

    public IdempotentResult<ExperimentDtos.StatusResult> cancel(String id, JsonNode body, String idemKey) {
        String path = "/api/v1/experiments/" + id + "/cancel";
        String scope = "POST " + path;
        String hash = IdempotencyDigest.of("POST", path, body);
        Optional<IdempotentResult<ExperimentDtos.StatusResult>> cached =
                idempotency.replay(scope, idemKey, hash, ExperimentDtos.StatusResult.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        return idempotency.execute(scope, idemKey, hash, ExperimentDtos.StatusResult.class, () ->
                tx.execute(status -> {
                    Experiment experiment = experimentMapper.findByIdForUpdate(id)
                            .orElseThrow(() -> notFound(id));
                    if (experiment.getStatus() == ExperimentStatus.CANCELLED) {
                        throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                                "实验已取消: " + id);
                    }
                    com.repopilot.common.state.Transitions.require(
                            experiment.getStatus(), ExperimentStatus.CANCELLED);

                    Instant now = Instant.now();
                    int updated = experimentMapper.updateStatus(id, experiment.getStatus(),
                            ExperimentStatus.CANCELLED, now, now);
                    if (updated != 1) {
                        throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                                "实验状态已变化，取消失败: " + id);
                    }
                    // 顺序：先让 Run 终态，再取消 Attempt。反过来的话，Attempt 先变 CANCELLED、
                    // Run 还 RUNNING 的窗口里完成回调仍可能通过 Run 的状态检查。
                    runStore.cancelNonTerminal(id, now);
                    runStore.cancelRunningAttempts(id, now);
                    batchService.cancelNonTerminal(id, now);
                    jobService.skipPendingByExperiment(id);

                    ExperimentDtos.StatusResult result =
                            new ExperimentDtos.StatusResult(id, ExperimentStatus.CANCELLED.name());
                    idempotency.record(scope, idemKey, hash, "experiment", id, 202, result);
                    return new IdempotentResult<>(result, 202, false);
                }));
    }

    // ------------------------------------------------------------------ 内部

    private EvaluationRun newRun(Experiment experiment, ExperimentTarget target,
                                 DatasetCase datasetCase, int repeat, Instant now) {
        EvaluationRun run = new EvaluationRun();
        run.setId(Ulid.newId());
        run.setExperimentId(experiment.getId());
        run.setExperimentTargetId(target.getId());
        run.setDatasetCaseId(datasetCase.getId());
        run.setRepeatIndex(repeat);
        run.setMode(RunMode.ONLINE);
        run.setStatus(RunStatus.PENDING);
        run.setTaskOutcome(TaskOutcome.UNKNOWN);
        // 该列 NOT NULL：空观测元数据用空对象表示，而不是 null
        run.setObservationMetadataJson(objectMapper.createObjectNode());
        run.setAttemptCount(0);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        return run;
    }

    private Experiment requireExperiment(String id) {
        return experimentMapper.findById(id).orElseThrow(() -> notFound(id));
    }

    private static BusinessException notFound(String id) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "实验不存在: " + id);
    }

    private static String requireUlid(String field, String value) {
        if (value == null || value.isBlank()) {
            throw BusinessException.field(field, field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!Ulid.isValid(trimmed)) {
            throw BusinessException.field(field, field + " 必须是 26 位 ULID");
        }
        return trimmed;
    }

    private RunMode parseMode(String value) {
        if (value == null || value.isBlank()) {
            throw BusinessException.field("mode", "mode 不能为空");
        }
        try {
            return RunMode.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("mode", "不支持的 mode: " + value + "，可选值: ONLINE, OFFLINE");
        }
    }

    private ExperimentStatus parseStatus(String value) {
        try {
            return ExperimentStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("status", "不支持的 status: " + value);
        }
    }

    /**
     * 预算校验。
     *
     * <p>V1 只校验结构：负预算一定是配置错误，而"预算够不够"要等真实 Runner 产生用量才知道。
     * 这里不编造成本模型，只把非法值挡在启动之前。
     */
    private void validateBudget(JsonNode config) {
        JsonNode budget = config.get("budget");
        if (budget == null || budget.isNull()) {
            return;
        }
        if (!budget.isObject()) {
            throw BusinessException.field("config.budget", "budget 必须是 JSON 对象");
        }
        JsonNode maxCost = budget.get("maxCostUsd");
        if (maxCost != null && !maxCost.isNull()) {
            if (!maxCost.isNumber() || maxCost.decimalValue().signum() < 0) {
                throw BusinessException.field("config.budget.maxCostUsd",
                        "maxCostUsd 必须是非负数");
            }
        }
        JsonNode maxTokens = budget.get("maxTokens");
        if (maxTokens != null && !maxTokens.isNull()) {
            if (!maxTokens.isIntegralNumber() || maxTokens.bigIntegerValue().signum() < 0) {
                throw BusinessException.field("config.budget.maxTokens",
                        "maxTokens 必须是非负整数");
            }
        }
    }
}
