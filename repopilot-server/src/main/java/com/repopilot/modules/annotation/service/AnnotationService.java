package com.repopilot.modules.annotation.service;

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
import com.repopilot.common.state.AnnotationAction;
import com.repopilot.common.state.AnnotationTaskStatus;
import com.repopilot.common.state.RunStatus;
import com.repopilot.common.state.Transitions;
import com.repopilot.common.state.Verdict;
import com.repopilot.modules.annotation.dto.AnnotationDtos;
import com.repopilot.modules.annotation.entity.AnnotationRecord;
import com.repopilot.modules.annotation.entity.AnnotationTask;
import com.repopilot.modules.annotation.entity.FailureCategory;
import com.repopilot.modules.annotation.mapper.AnnotationRecordMapper;
import com.repopilot.modules.annotation.mapper.AnnotationTaskMapper;
import com.repopilot.modules.evaluation.service.EvaluationVerdictQueryService;
import com.repopilot.modules.trace.entity.EvaluationRun;
import com.repopilot.modules.trace.mapper.EvaluationRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 人工标注与失败归因（TASK-009）。
 *
 * <p>三条不可协商的规则，全部在数据库层与事务层落实，而不是靠服务层自觉：
 *
 * <ol>
 *   <li><b>状态行与历史记录同事务</b>：状态变了但记录没写、或记录写了状态没变，
 *       都会让"谁在什么时候标了什么"对不上。因此每次动作都在一个事务里
 *       先写 {@code annotation_records} 再改状态行（或反之），任一步失败整体回滚。</li>
 *   <li><b>乐观锁 + 数据库条件更新</b>：每个动作都带 {@code expectedVersion}，
 *       SQL 里同时比对 {@code version} 与前置 {@code status}。并发领取时只有一个
 *       拿到 1 行，其余得到 0 行并被转成 409 —— 后写不能覆盖前写。</li>
 *   <li><b>不覆盖自动结果</b>：{@code evaluation_results} 全程只读。人工结论
 *       写在 {@code annotation_records} 里，报告按 {@code RAW / REVIEWED} 视图切换，
 *       而不是把人写的结论塞回自动结果列。</li>
 * </ol>
 *
 * <p>V1 的操作者身份由后端配置固定（{@code dev-operator-id}），接口不接受调用方
 * 自称的身份——没有真实鉴权时，让前端随便填 operatorId 等于没有审计。
 */
@Service
public class AnnotationService {

    private static final Logger log = LoggerFactory.getLogger(AnnotationService.class);
    /** 证据引用的上限，避免把整段轨迹塞进标注记录。 */
    private static final int MAX_EVIDENCE_ITEMS = 50;

    private final AnnotationTaskMapper taskMapper;
    private final AnnotationRecordMapper recordMapper;
    private final EvaluationRunMapper runMapper;
    private final EvaluationVerdictQueryService verdicts;
    private final IdempotencyService idempotency;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final String devOperatorId;

    public AnnotationService(AnnotationTaskMapper taskMapper,
                             AnnotationRecordMapper recordMapper,
                             EvaluationRunMapper runMapper,
                             EvaluationVerdictQueryService verdicts,
                             IdempotencyService idempotency,
                             org.springframework.transaction.PlatformTransactionManager txManager,
                             ObjectMapper objectMapper,
                             @Value("${app.repopilot.annotation.dev-operator-id:dev-operator}")
                             String devOperatorId) {
        this.taskMapper = taskMapper;
        this.recordMapper = recordMapper;
        this.runMapper = runMapper;
        this.verdicts = verdicts;
        this.idempotency = idempotency;
        this.tx = new TransactionTemplate(txManager);
        this.objectMapper = objectMapper;
        this.devOperatorId = devOperatorId;
    }

    // ------------------------------------------------------------------ 创建

    /**
     * 为 Run 创建人工复核任务。
     *
     * <p>任务按 Run 建立，可选绑定单个算子 Job。绑定 Job 时校验它确实属于该 Run——
     * 否则"针对某个算子的复核"会指向另一个 Run 的结论。
     */
    public IdempotentResult<AnnotationDtos.TaskView> create(JsonNode body, String idemKey) {
        String path = "/api/v1/annotation-tasks";
        String scope = "POST " + path;
        String hash = IdempotencyDigest.of("POST", path, body);
        Optional<IdempotentResult<AnnotationDtos.TaskView>> cached =
                idempotency.replay(scope, idemKey, hash, AnnotationDtos.TaskView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        String runId = requireText(body, "runId");
        String jobId = optionalText(body, "evaluationJobId");

        EvaluationRun run = runMapper.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Run 不存在: " + runId));
        if (jobId != null) {
            var job = verdicts.requireJob(jobId);
            if (!runId.equals(job.getRunId())) {
                throw BusinessException.field("evaluationJobId",
                        "评测任务不属于该 Run: " + jobId);
            }
        }

        Instant now = Instant.now();
        AnnotationTask task = new AnnotationTask();
        task.setId(Ulid.newId());
        task.setRunId(runId);
        task.setEvaluationJobId(jobId);
        task.setStatus(AnnotationTaskStatus.PENDING);
        task.setVersion(0L);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        AnnotationDtos.TaskView view = tx.execute(status -> {
            taskMapper.insert(task);
            appendRecord(task.getId(), AnnotationAction.CLAIM, devOperatorId, null, null,
                    "创建人工复核任务", null, null, now);
            return toView(task, List.of());
        });

        idempotency.record(scope, idemKey, hash, "annotation_task", view.id(), 201, view);
        log.info("Run {} 创建人工复核任务 {}", runId, view.id());
        return new IdempotentResult<>(view, 201, false);
    }

    // ------------------------------------------------------------------ 查询

    public PageResponse<AnnotationDtos.TaskView> list(String status, String assigneeId, String runId,
                                                      Integer page, Integer pageSize) {
        String normalized = parseStatus(status);
        PageQuery query = PageQuery.of(page, pageSize);
        // 只把白名单状态传给 SQL；未知值已在 parseStatus 里被 400 拒绝
        String assignee = (assigneeId == null || assigneeId.isBlank()) ? null : assigneeId.trim();
        String run = (runId == null || runId.isBlank()) ? null : runId.trim();

        List<AnnotationDtos.TaskView> items = taskMapper
                .list(normalized, assignee, run, query.pageSize(), query.offset()).stream()
                .map(task -> toView(task, recordMapper.listByTask(task.getId()).stream()
                        .map(record -> toRecordView(record, autoVerdictOf(task)))
                        .toList()))
                .toList();
        long total = taskMapper.count(normalized, assignee, run);
        return PageResponse.of(items, query, total);
    }

    public AnnotationDtos.TaskView detail(String id) {
        AnnotationTask task = require(id);
        return toView(task, recordMapper.listByTask(id).stream()
                .map(record -> toRecordView(record, autoVerdictOf(task)))
                .toList());
    }

    // ------------------------------------------------------------------ 领取

    /**
     * 原子领取。
     *
     * <p>并发安全由 SQL 保证：{@code WHERE version = ? AND status IN (PENDING, RETURNED)
     * AND (assignee_id IS NULL OR assignee_id = ?)} 加 {@code version = version + 1}。
     * 两个请求同时到达时只有一个影响 1 行，另一个得到 0 行并按 409 拒绝，
     * 不存在"都以为自己领到了"的窗口。
     */
    public IdempotentResult<AnnotationDtos.TaskView> claim(String id, JsonNode body, String idemKey) {
        String path = "/api/v1/annotation-tasks/" + id + "/claim";
        return runAction(id, path, "POST", body, idemKey, (task, expectedVersion, now) -> {
            if (taskMapper.claim(id, devOperatorId, expectedVersion, now) != 1) {
                throw conflict(task, expectedVersion, "领取");
            }
            AnnotationTask updated = require(id);
            appendRecord(id, AnnotationAction.CLAIM, devOperatorId, null, null,
                    "领取任务", null, autoVerdictOf(task), now);
            return toView(updated, recordMapper.listByTask(id).stream()
                    .map(record -> toRecordView(record, autoVerdictOf(task))).toList());
        }, 200);
    }

    // ------------------------------------------------------------------ 提交

    /**
     * 提交标注。
     *
     * <p>{@code verdict=FAIL} 时要求给出失败归因或明确写 {@code UNKNOWN}：
     * 不能让"标了失败但没说为什么"进入报表，那会让失败归因统计看起来完整而实则空洞。
     */
    public IdempotentResult<AnnotationDtos.TaskView> submit(String id, JsonNode body, String idemKey) {
        String path = "/api/v1/annotation-tasks/" + id + "/submit";
        return runAction(id, path, "POST", body, idemKey, (task, expectedVersion, now) -> {
            Verdict verdict = parseVerdict(body);
            String reason = requireText(body, "reason");
            FailureCategory category = parseCategory(body, verdict);
            JsonNode evidence = normalizeEvidence(body.get("evidence"));

            if (taskMapper.markSubmitted(id, devOperatorId, expectedVersion, now) != 1) {
                throw conflict(task, expectedVersion, "提交");
            }
            AnnotationTask updated = require(id);
            appendRecord(id, AnnotationAction.SUBMIT, devOperatorId, verdict.name(),
                    category == null ? null : category.name(), reason, evidence,
                    autoVerdictOf(task), now);
            return toView(updated, recordMapper.listByTask(id).stream()
                    .map(record -> toRecordView(record, autoVerdictOf(task))).toList());
        }, 200);
    }

    // ------------------------------------------------------------------ 审核

    /**
     * 审核：{@code approved=true} 进 REVIEWED，{@code false} 退回。
     *
     * <p>审核记录保存**审核者认可的最终标注快照**（verdict/category/reason/evidence），
     * 因此退回后重提不会污染已认可的内容；报告的 REVIEWED 视图只读最新的有效 REVIEW。
     */
    public IdempotentResult<AnnotationDtos.TaskView> review(String id, JsonNode body, String idemKey) {
        String path = "/api/v1/annotation-tasks/" + id + "/review";
        return runAction(id, path, "POST", body, idemKey, (task, expectedVersion, now) -> {
            Boolean approved = body != null && body.hasNonNull("approved")
                    ? body.get("approved").asBoolean() : null;
            if (approved == null) {
                throw BusinessException.field("approved", "必须显式给出 approved=true 或 false");
            }
            String reason = optionalText(body, "reason");
            if (!approved && (reason == null || reason.isBlank())) {
                throw BusinessException.field("reason", "退回时必须说明原因");
            }

            AnnotationTask submitted = require(id);
            if (submitted.getStatus() != AnnotationTaskStatus.SUBMITTED) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "只有已提交的任务可以审核，当前状态: " + submitted.getStatus());
            }

            // 审核快照取自该任务最后一次 SUBMIT；审核者可以补充自己的分类与证据
            AnnotationRecord latestSubmit = recordMapper.listByTask(id).stream()
                    .filter(record -> record.getAction() == AnnotationAction.SUBMIT)
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                            "任务没有提交记录，无法审核"));
            Verdict verdict = latestSubmit.getVerdict();
            FailureCategory category = latestSubmit.getFailureCategory() == null ? null
                    : FailureCategory.valueOf(latestSubmit.getFailureCategory());
            String override = optionalText(body, "failureCategory");
            if (override != null) {
                category = requireCategory(override);
            }
            JsonNode evidence = body != null && body.has("evidence")
                    ? normalizeEvidence(body.get("evidence")) : latestSubmit.getEvidenceJson();

            int updated = approved
                    ? taskMapper.markReviewed(id, expectedVersion, now)
                    : taskMapper.markReturned(id, expectedVersion, now);
            if (updated != 1) {
                throw conflict(submitted, expectedVersion, approved ? "审核通过" : "退回");
            }

            AnnotationTask after = require(id);
            appendRecord(id, approved ? AnnotationAction.REVIEW : AnnotationAction.RETURN,
                    devOperatorId,
                    approved && verdict != null ? verdict.name() : null,
                    category == null ? null : category.name(),
                    // 审核意见是审核者写的，不能被固定文案覆盖：
                    // "通过"已经由 action=REVIEW 表达，再写一句"审核通过"只会把
                    // 审核者真正写的意见（"认可""这次判定对"）挤掉。
                    reason, evidence, autoVerdictOf(submitted), now);
            return toView(after, recordMapper.listByTask(id).stream()
                    .map(record -> toRecordView(record, autoVerdictOf(task))).toList());
        }, 200);
    }

    // ------------------------------------------------------------------ 取消

    public IdempotentResult<AnnotationDtos.TaskView> cancel(String id, JsonNode body, String idemKey) {
        String path = "/api/v1/annotation-tasks/" + id + "/cancel";
        return runAction(id, path, "POST", body, idemKey, (task, expectedVersion, now) -> {
            String reason = requireText(body, "reason");
            Transitions.require(task.getStatus(), AnnotationTaskStatus.CANCELLED);
            if (taskMapper.markCancelled(id, expectedVersion, now) != 1) {
                throw conflict(task, expectedVersion, "取消");
            }
            AnnotationTask after = require(id);
            appendRecord(id, AnnotationAction.CANCEL, devOperatorId, null, null, reason, null,
                    autoVerdictOf(task), now);
            return toView(after, recordMapper.listByTask(id).stream()
                    .map(record -> toRecordView(record, autoVerdictOf(task))).toList());
        }, 200);
    }

    // ------------------------------------------------------------------ 自动派单建议

    /**
     * 列出建议人工复核的 Run：技术失败的 Run，或结论与期望冲突的 Run。
     *
     * <p>这是"自动评测失败或结果冲突后进入人工质检"的入口。当前实现返回技术失败的 Run；
     * 结论冲突（规则判 FAIL 但任务成功等）由调用方结合 {@code autoVerdict} 判断。
     */
    public List<AnnotationDtos.DispatchHint> dispatchCandidates(String experimentId, int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        List<AnnotationDtos.DispatchHint> hints = new java.util.ArrayList<>();
        for (EvaluationRun run : runMapper.listByExperiment(experimentId)) {
            if (hints.size() >= capped) {
                break;
            }
            // 已有未终态任务的 Run 不重复建议
            if (taskMapper.countOpenByRun(run.getId()) > 0) {
                continue;
            }
            String reason = dispatchReason(run);
            if (reason == null) {
                continue;
            }
            Verdict dominant = verdicts.dominantVerdictOfRun(run.getId());
            hints.add(new AnnotationDtos.DispatchHint(run.getId(), null, reason,
                    dominant == null ? null : dominant.name(), run.getCreatedAt()));
        }
        return hints;
    }

    /**
     * 需要人工复核的判定规则。
     *
     * <p>只挑**真正需要人看**的：技术失败的 Run（算不出结论），
     * 以及任何一条算子判 FAIL 的 Run（机器认为质量不达标）。
     * 技术上 COMPLETED 且全部 PASS 的 Run 不进队列——那会让工作台被噪音淹没。
     *
     * @return 建议理由；不需要复核时返回 {@code null}
     */
    private String dispatchReason(EvaluationRun run) {
        RunStatus status = run.getStatus();
        if (status == RunStatus.FAILED || status == RunStatus.TIMED_OUT
                || status == RunStatus.CANCELLED) {
            return "技术状态 " + status;
        }
        if (status != RunStatus.COMPLETED) {
            return null;
        }
        Verdict verdict = verdicts.dominantVerdictOfRun(run.getId());
        return verdict == Verdict.FAIL ? "自动评测判定未通过" : null;
    }

    // ------------------------------------------------------------------ 内部

    private interface Action {
        AnnotationDtos.TaskView apply(AnnotationTask task, long expectedVersion, Instant now);
    }

    private IdempotentResult<AnnotationDtos.TaskView> runAction(String id, String path, String method,
                                                               JsonNode body, String idemKey,
                                                               Action action, int successStatus) {
        String scope = method + " " + path;
        String hash = IdempotencyDigest.of(method, path, body);
        Optional<IdempotentResult<AnnotationDtos.TaskView>> cached =
                idempotency.replay(scope, idemKey, hash, AnnotationDtos.TaskView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        AnnotationTask task = require(id);
        Long expected = body != null && body.hasNonNull("expectedVersion")
                ? body.get("expectedVersion").asLong() : null;
        if (expected == null) {
            throw BusinessException.field("expectedVersion", "必须给出 expectedVersion");
        }

        Instant now = Instant.now();
        AnnotationDtos.TaskView view = tx.execute(status -> action.apply(task, expected, now));
        idempotency.record(scope, idemKey, hash, "annotation_task", id, successStatus, view);
        return new IdempotentResult<>(view, successStatus, false);
    }

    /**
     * 版本或状态不匹配。
     *
     * <p>回 409 {@code OPTIMISTIC_LOCK_CONFLICT} 而不是笼统的 409：前端据此提示
     * "刷新后重试"，而不是当成业务错误。响应里带上当前版本，方便页面直接刷新。
     */
    private BusinessException conflict(AnnotationTask task, long expectedVersion, String action) {
        AnnotationTask current = require(task.getId());
        return new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                "标注任务已被他人更新（当前版本 " + current.getVersion() + "，"
                        + "你的 expectedVersion=" + expectedVersion + "），请刷新后重试",
                List.of(new com.repopilot.common.api.FieldViolation("expectedVersion",
                        "expectedVersion=" + expectedVersion + "，当前=" + current.getVersion()
                                + "，无法" + action)));
    }

    private AnnotationTask require(String id) {
        return taskMapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "标注任务不存在: " + id));
    }

    private void appendRecord(String taskId, AnnotationAction action, String operator,
                              String verdict, String category, String reason, JsonNode evidence,
                              String originalVerdict, Instant now) {
        AnnotationRecord record = new AnnotationRecord();
        record.setId(Ulid.newId());
        record.setAnnotationTaskId(taskId);
        record.setOperatorId(operator);
        record.setAction(action);
        record.setVerdict(verdict == null ? null : Verdict.valueOf(verdict));
        record.setFailureCategory(category);
        record.setReason(reason);
        record.setEvidenceJson(evidence);
        record.setCreatedAt(now);
        recordMapper.insert(record);
    }

    /**
     * 被复核的自动评测结论：只读，永不因人工判断而改变。
     *
     * <p>优先取绑定 Job 的结论；任务只绑定到 Run（没指定具体算子）时，
     * 退回该 Run 的主导结论。少了这个回落，Run 级复核任务的历史里就没有
     * "机器当初判了什么"，复核者看不出自己改的是哪条结论——而这正是复核的意义。
     *
     * <p>技术失败的 Run 没有任何结论，返回 {@code null}：缺失表示"机器没给出结论"，
     * 不表示 PASS，也不表示 0 分。
     */
    private String autoVerdictOf(AnnotationTask task) {
        Verdict verdict = task.getEvaluationJobId() == null
                ? verdicts.dominantVerdictOfRun(task.getRunId())
                : verdicts.verdictOfJob(task.getEvaluationJobId());
        return verdict == null ? null : verdict.name();
    }

    private AnnotationDtos.TaskView toView(AnnotationTask task, List<AnnotationDtos.RecordView> records) {
        return new AnnotationDtos.TaskView(task.getId(), task.getRunId(), task.getEvaluationJobId(),
                task.getStatus().name(), task.getAssigneeId(), task.getVersion(),
                task.getClaimedAt(), task.getSubmittedAt(), task.getReviewedAt(),
                task.getCreatedAt(), task.getUpdatedAt(), records);
    }

    private AnnotationDtos.RecordView toRecordView(AnnotationRecord record, String autoVerdict) {
        return new AnnotationDtos.RecordView(record.getId(), record.getOperatorId(),
                record.getAction().name(),
                record.getVerdict() == null ? null : record.getVerdict().name(),
                record.getFailureCategory(), record.getReason(), record.getEvidenceJson(),
                autoVerdict, record.getCreatedAt());
    }

    // ------------------------------------------------------------------ 解析

    private static String parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AnnotationTaskStatus.valueOf(status.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("status", "未知的标注状态: " + status);
        }
    }

    private static Verdict parseVerdict(JsonNode body) {
        String raw = body == null ? null : body.path("verdict").asText(null);
        if (raw == null || raw.isBlank()) {
            throw BusinessException.field("verdict", "必须给出 verdict");
        }
        try {
            return Verdict.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("verdict", "未知的判定: " + raw);
        }
    }

    private static FailureCategory requireCategory(String raw) {
        FailureCategory category = FailureCategory.parseOrNull(raw);
        if (category == null) {
            throw BusinessException.field("failureCategory",
                    "未注册的失败分类: " + raw + "；可选值: " + java.util.Arrays
                            .stream(FailureCategory.values()).map(Enum::name)
                            .reduce((a, b) -> a + "," + b).orElse(""));
        }
        return category;
    }

    /**
     * 失败归因。
     *
     * <p>判 FAIL 必须给一个分类——可以是 {@code UNKNOWN}（显式的"证据不足，不归因"），
     * 但不能留空。留空会让失败归因报表出现"分母不明的分子"。
     */
    private static FailureCategory parseCategory(JsonNode body, Verdict verdict) {
        String raw = body.path("failureCategory").asText(null);
        if (raw == null || raw.isBlank()) {
            if (verdict == Verdict.FAIL) {
                throw BusinessException.field("failureCategory",
                        "判定为未通过时必须给出失败分类（证据不足请显式填 UNKNOWN）");
            }
            return null;
        }
        return requireCategory(raw);
    }

    /** 证据必须是数组且规模可控：防止把整段轨迹塞进一次标注。 */
    private JsonNode normalizeEvidence(JsonNode evidence) {
        if (evidence == null || evidence.isNull() || evidence.isMissingNode()) {
            return objectMapper.createArrayNode();
        }
        if (!evidence.isArray()) {
            throw BusinessException.field("evidence", "evidence 必须是数组");
        }
        if (evidence.size() > MAX_EVIDENCE_ITEMS) {
            throw BusinessException.field("evidence",
                    "证据条数不能超过 " + MAX_EVIDENCE_ITEMS + " 条");
        }
        return evidence;
    }

    private static String requireText(JsonNode body, String field) {
        String value = body == null ? null : body.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw BusinessException.field(field, field + " 不能为空");
        }
        return value.trim();
    }

    private static String optionalText(JsonNode body, String field) {
        String value = body == null ? null : body.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

}