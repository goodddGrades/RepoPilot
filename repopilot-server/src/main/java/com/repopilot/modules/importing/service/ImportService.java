package com.repopilot.modules.importing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.api.PageQuery;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.hash.Sha256;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.idempotency.IdempotencyDigest;
import com.repopilot.common.idempotency.IdempotencyService;
import com.repopilot.common.idempotency.IdempotentResult;
import com.repopilot.common.json.CanonicalJson;
import com.repopilot.common.jsonl.JsonlParseException;
import com.repopilot.common.jsonl.JsonlParser;
import com.repopilot.common.state.ArtifactStatus;
import com.repopilot.common.state.BatchStatus;
import com.repopilot.common.state.ImportRecordStatus;
import com.repopilot.common.state.ImportType;
import com.repopilot.common.state.RunMode;
import com.repopilot.common.state.RunStatus;
import com.repopilot.common.state.TaskOutcome;
import com.repopilot.common.storage.ArtifactStorage;
import com.repopilot.common.storage.StorageException;
import com.repopilot.common.web.RequestBodies;
import com.repopilot.modules.dataset.entity.DatasetCase;
import com.repopilot.modules.dataset.service.DatasetVersionService;
import com.repopilot.modules.experiment.entity.Experiment;
import com.repopilot.modules.experiment.entity.ExperimentTarget;
import com.repopilot.modules.experiment.mapper.ExperimentMapper;
import com.repopilot.modules.experiment.mapper.ExperimentTargetMapper;
import com.repopilot.modules.importing.dto.ImportCountsRow;
import com.repopilot.modules.importing.dto.ImportDtos;
import com.repopilot.modules.importing.entity.ImportBatch;
import com.repopilot.modules.importing.entity.ImportError;
import com.repopilot.modules.importing.entity.ImportRecord;
import com.repopilot.modules.importing.mapper.ImportBatchMapper;
import com.repopilot.modules.importing.mapper.ImportErrorMapper;
import com.repopilot.modules.importing.mapper.ImportRecordMapper;
import com.repopilot.modules.trace.entity.Artifact;
import com.repopilot.modules.trace.entity.EvaluationRun;
import com.repopilot.modules.trace.entity.TraceEvent;
import com.repopilot.modules.trace.service.ArtifactService;
import com.repopilot.modules.trace.service.RunStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 离线轨迹导入（{@code OFFLINE_RUNS}）。
 *
 * <p><b>写在前面的边界</b>：本服务只实现 {@code OFFLINE_RUNS}。{@code DATASET_CASES} 仍是
 * TASK-003 的整批原子接口（{@code POST /dataset-versions/{id}/cases:import}）——可靠性设计明确
 * "DATASET_CASES 只可能产生 COMPLETED 或 FAILED，PARTIAL_FAILED 仅保留给 OFFLINE_RUNS"，
 * 在这里再实现一套逐行写入会给出两份语义冲突的样本导入。传入 {@code DATASET_CASES} 会得到
 * 明确的 400 与替代入口，而不是静默按另一种语义执行。
 *
 * <p><b>逐行事务</b>：每一行是一个独立事务，失败的行只回滚自己，批次继续。成功时
 * Run + Trace 事件 + 回执 + 计数同事务提交；失败时业务写入回滚，随后在另一个短事务里
 * 幂等写 REJECTED 回执与 import_error。
 *
 * <p><b>计数不信任本机</b>：进度由 {@code import_records} 聚合重算，进程崩溃、重放、
 * 多实例读取都不会让计数漂移。恢复时从已处理的最大行号之后继续，但每一行仍先查回执。
 *
 * <p><b>取消与写入同一把锁</b>：每行开始先锁批次行；取消也锁同一行。因此"取消已返回、
 * 下一行仍提交成功"不可能发生——两者在数据库上排队。
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private static final int MAX_ERRORS_PAGE = 100;

    private final ImportBatchMapper batchMapper;
    private final ImportRecordMapper recordMapper;
    private final ImportErrorMapper errorMapper;
    private final ExperimentMapper experimentMapper;
    private final ExperimentTargetMapper targetMapper;
    private final DatasetVersionService datasetVersionService;
    private final ArtifactService artifactService;
    private final ArtifactStorage storage;
    private final RunStore runStore;
    private final OfflineRunValidator validator;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    /**
     * 是否在创建后立即排队处理。
     *
     * <p>生产默认为 true。集成测试关掉它，由用例显式调用 {@link #resume(String)} 驱动，
     * 这样"处理到一半"的状态可以被稳定地构造出来，而不是靠 sleep 撞时间窗口。
     */
    private final boolean autoProcess;

    /**
     * 单线程执行器：V1 是"单批次串行读取"。多线程只会让同一批次的行互相争锁，
     * 并不会更快——瓶颈是逐行事务本身。
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "offline-import");
        thread.setDaemon(true);
        return thread;
    });

    public ImportService(ImportBatchMapper batchMapper, ImportRecordMapper recordMapper,
                         ImportErrorMapper errorMapper, ExperimentMapper experimentMapper,
                         ExperimentTargetMapper targetMapper,
                         DatasetVersionService datasetVersionService,
                         ArtifactService artifactService, ArtifactStorage storage,
                         RunStore runStore, OfflineRunValidator validator,
                         IdempotencyService idempotency, ObjectMapper objectMapper,
                         PlatformTransactionManager transactionManager,
                         @org.springframework.beans.factory.annotation.Value(
                                 "${repopilot.import.auto-process:true}") boolean autoProcess) {
        this.batchMapper = batchMapper;
        this.recordMapper = recordMapper;
        this.errorMapper = errorMapper;
        this.experimentMapper = experimentMapper;
        this.targetMapper = targetMapper;
        this.datasetVersionService = datasetVersionService;
        this.artifactService = artifactService;
        this.storage = storage;
        this.runStore = runStore;
        this.validator = validator;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.autoProcess = autoProcess;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ 创建

    public IdempotentResult<ImportDtos.BatchView> create(JsonNode body, String idemKey) {
        String path = "/api/v1/imports";
        String scope = "POST " + path;
        String hash = IdempotencyDigest.of("POST", path, body);
        Optional<IdempotentResult<ImportDtos.BatchView>> cached =
                idempotency.replay(scope, idemKey, hash, ImportDtos.BatchView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        ImportDtos.CreateRequest request =
                RequestBodies.convert(objectMapper, body, ImportDtos.CreateRequest.class);
        ImportType importType = parseType(request.importType());
        String datasetVersionId = requireUlid("datasetVersionId", request.datasetVersionId());
        String experimentId = requireUlid("experimentId", request.experimentId());
        String artifactId = requireUlid("fileArtifactId", request.fileArtifactId());

        // 文件级校验前置到创建阶段：坏文件根本不应该产生批次，而不是先建一个注定失败的批次
        Artifact artifact = artifactService.require(artifactId);
        byte[] bytes = readArtifact(artifact);
        JsonlParser.Result parsed = parseOrReject(bytes);
        if (parsed.lines().isEmpty()) {
            throw BusinessException.field("file", "文件没有任何非空行");
        }

        IdempotentResult<ImportDtos.BatchView> result = idempotency.execute(scope, idemKey, hash,
                ImportDtos.BatchView.class, () -> tx.execute(status -> {
                    Experiment experiment = experimentMapper.findByIdForUpdate(experimentId)
                            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                                    "实验不存在: " + experimentId));
                    if (experiment.getStatus() != com.repopilot.common.state.ExperimentStatus.DRAFT) {
                        throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                                "实验已开始或已取消，不能再导入（当前 " + experiment.getStatus() + "）");
                    }
                    if (experiment.getMode() != RunMode.OFFLINE) {
                        throw BusinessException.field("experimentId",
                                "只有 OFFLINE 实验支持离线轨迹导入（当前 " + experiment.getMode() + "）");
                    }
                    if (!experiment.getDatasetVersionId().equals(datasetVersionId)) {
                        throw BusinessException.field("datasetVersionId",
                                "datasetVersionId 必须与实验使用的数据集版本一致");
                    }
                    if (datasetVersionService.requirePublishedVersion(datasetVersionId) == null) {
                        throw BusinessException.field("datasetVersionId", "数据集版本必须已发布");
                    }
                    Instant now = Instant.now();
                    ImportBatch batch = new ImportBatch();
                    batch.setId(Ulid.newId());
                    batch.setImportType(importType);
                    batch.setDatasetVersionId(datasetVersionId);
                    batch.setExperimentId(experimentId);
                    batch.setFileArtifactId(artifactId);
                    batch.setStatus(BatchStatus.PENDING);
                    batch.setTotalCount(0L);
                    batch.setSuccessCount(0L);
                    batch.setFailedCount(0L);
                    batch.setDuplicateCount(0L);
                    batch.setLastLineNo(0L);
                    batch.setStartedAt(null);
                    batch.setFinishedAt(null);
                    batch.setCreatedAt(now);
                    batch.setUpdatedAt(now);
                    batchMapper.insert(batch);

                    ImportDtos.BatchView view = ImportDtos.BatchView.from(batch);
                    idempotency.record(scope, idemKey, hash, "import_batch", batch.getId(), 202, view);
                    return new IdempotentResult<>(view, 202, false);
                }));

        if (!result.replayed() && autoProcess) {
            submit(result.data().id());
        }
        return result;
    }

    /** 异步处理；失败只记录日志，批次停在 RUNNING 等恢复扫描接手。 */
    private void submit(String batchId) {
        worker.submit(() -> {
            try {
                process(batchId);
            } catch (RuntimeException ex) {
                log.error("导入批次处理异常，保持 RUNNING 等待恢复: {}", batchId, ex);
            }
        });
    }

    // ------------------------------------------------------------------ 查询与取消

    public ImportDtos.BatchView get(String id) {
        return ImportDtos.BatchView.from(batchMapper.findById(id)
                .orElseThrow(() -> notFound(id)));
    }

    public PageResponse<ImportDtos.BatchView> list(Integer page, Integer pageSize, String importType,
                                                   String status, String experimentId,
                                                   String datasetVersionId) {
        PageQuery query = PageQuery.of(page, pageSize);
        ImportType parsedType = importType == null || importType.isBlank()
                ? null : parseType(importType);
        BatchStatus parsedStatus = status == null || status.isBlank() ? null : parseStatus(status);
        String exp = blankToNull(experimentId);
        String ver = blankToNull(datasetVersionId);
        List<ImportDtos.BatchView> items = batchMapper
                .search(parsedType, parsedStatus, exp, ver, query.pageSize(), query.offset())
                .stream().map(ImportDtos.BatchView::from).toList();
        long total = batchMapper.count(parsedType, parsedStatus, exp, ver);
        return PageResponse.of(items, query, total);
    }

    public PageResponse<ImportDtos.ErrorView> errors(String id, Integer page, Integer pageSize) {
        requireBatch(id);
        PageQuery query = PageQuery.of(page, pageSize);
        int size = Math.min(query.pageSize(), MAX_ERRORS_PAGE);
        List<ImportDtos.ErrorView> items = errorMapper
                .listByBatch(id, size, query.offset())
                .stream().map(ImportDtos.ErrorView::from).toList();
        return PageResponse.of(items, query, errorMapper.countByBatch(id));
    }

    public ImportDtos.BatchView cancel(String id) {
        return tx.execute(status -> {
            ImportBatch batch = lockBatch(id);
            if (batch.getStatus() != BatchStatus.PENDING && batch.getStatus() != BatchStatus.RUNNING) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "只有 PENDING/RUNNING 的导入可以取消（当前 " + batch.getStatus() + "）");
            }
            Instant now = Instant.now();
            if (batchMapper.cancel(id, now, now) != 1) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "导入状态已变化: " + id);
            }
            // 计数仍以回执为准：取消前已提交的行必须留在统计里
            recount(id, batch.getTotalCount() == null ? 0 : batch.getTotalCount(), now);
            return ImportDtos.BatchView.from(batchMapper.findById(id).orElseThrow(() -> notFound(id)));
        });
    }

    /**
     * 恢复一个未完成批次。
     *
     * <p>用于进程崩溃后的重启扫描，也用于测试直接驱动。已提交的行会被回执检查跳过，
     * 因此重复调用是安全的。
     */
    public void resume(String batchId) {
        process(batchId);
    }

    /** 重启后把停在 PENDING/RUNNING 的批次重新排队。 */
    public void resumeUnfinished(int limit) {
        for (String id : batchMapper.findUnfinished(limit)) {
            submit(id);
        }
    }

    // ------------------------------------------------------------------ 处理主循环

    void process(String batchId) {
        ImportBatch batch = batchMapper.findById(batchId).orElse(null);
        if (batch == null) {
            log.warn("导入批次不存在: {}", batchId);
            return;
        }
        if (batch.getStatus() == BatchStatus.CANCELLED) {
            return;
        }
        if (batch.getStatus() == BatchStatus.COMPLETED || batch.getStatus() == BatchStatus.PARTIAL_FAILED
                || batch.getStatus() == BatchStatus.FAILED) {
            return;
        }

        Instant now = Instant.now();
        batchMapper.markRunning(batchId, now, now);

        Artifact artifact = artifactService.require(batch.getFileArtifactId());
        byte[] bytes = readArtifact(artifact);
        JsonlParser.Result parsed;
        try {
            parsed = JsonlParser.parse(bytes);
        } catch (JsonlParseException ex) {
            // 创建阶段已校验过；文件在存储层被替换才会走到这里，按批次失败处理
            failWholeBatch(batchId, ex.getMessage());
            return;
        }

        Map<String, ExperimentTarget> targets = new LinkedHashMap<>();
        for (ExperimentTarget target : targetMapper.listByExperiment(batch.getExperimentId())) {
            targets.put(target.getTargetKey(), target);
        }
        Map<String, DatasetCase> cases = new LinkedHashMap<>();
        for (DatasetCase datasetCase : datasetVersionService.listCases(batch.getDatasetVersionId())) {
            cases.put(datasetCase.getCaseKey(), datasetCase);
        }

        boolean aborted = false;
        for (JsonlParser.Line line : parsed.lines()) {
            if (aborted) {
                break;
            }
            OfflineRunValidator.Outcome outcome = validator.validate(line.lineNo(), line.text());
            if (outcome.error() != null) {
                recordRejection(batchId, line, outcome.error().errorCode(), outcome.error().message());
                continue;
            }
            try {
                writeLine(batchId, batch, targets, cases, outcome.value());
            } catch (LineRejected rejected) {
                recordRejection(batchId, line, rejected.code, rejected.getMessage());
                aborted = rejected.abort;
            }
        }

        finish(batchId, parsed.nonBlankCount());
    }

    /** 一行的成功写入（独立事务）。 */
    private void writeLine(String batchId, ImportBatch batch,
                           Map<String, ExperimentTarget> targets,
                           Map<String, DatasetCase> cases,
                           OfflineRunValidator.Valid valid) {
        tx.execute(status -> {
            ImportBatch locked = lockBatch(batchId);
            if (locked.getStatus() == BatchStatus.CANCELLED) {
                throw new LineRejected("IMPORT_CANCELLED", "导入已取消", true);
            }
            if (locked.getStatus() != BatchStatus.RUNNING) {
                throw new LineRejected("IMPORT_CLOSED", "导入批次已结束", true);
            }
            // 1) 先看回执：崩溃恢复时这一步让已提交行变成无操作
            if (recordMapper.findByBatchAndLine(batchId, valid.lineNo()).isPresent()) {
                return null;
            }
            // 2) 父实验必须仍是 DRAFT：与 start 锁同一行，避免"导入与开始交错"
            Experiment experiment = experimentMapper.findByIdForUpdate(locked.getExperimentId())
                    .orElseThrow(() -> new LineRejected("EXPERIMENT_NOT_FOUND", "实验不存在", true));
            if (experiment.getStatus() != com.repopilot.common.state.ExperimentStatus.DRAFT) {
                throw new LineRejected("IMPORT_CLOSED",
                        "实验已开始或已取消，导入已停止（当前 " + experiment.getStatus() + "）", true);
            }

            ExperimentTarget target = targets.get(valid.targetKey());
            if (target == null) {
                throw new LineRejected("TARGET_NOT_FOUND",
                        "targetKey 不在本实验的 Target 中: " + valid.targetKey(), false);
            }
            if (valid.repeatIndex() > target.getRepeatCount()) {
                throw new LineRejected("REPEAT_INDEX_OUT_OF_RANGE",
                        "repeatIndex 超出 Target 的 repeatCount（1.." + target.getRepeatCount() + "）", false);
            }
            DatasetCase datasetCase = cases.get(valid.caseKey());
            if (datasetCase == null) {
                throw new LineRejected("CASE_NOT_FOUND",
                        "caseKey 不在已发布的数据集版本中: " + valid.caseKey(), false);
            }

            Instant now = Instant.now();
            Optional<EvaluationRun> sameIdentity = runStore.findBySourceIdentity(
                    locked.getExperimentId(), target.getId(),
                    valid.sourceSystem(), valid.sourceRecordId());
            if (sameIdentity.isPresent()) {
                EvaluationRun existing = sameIdentity.get();
                if (valid.businessHash().equals(existing.getSourceRecordHash())) {
                    insertRecord(batchId, valid, ImportRecordStatus.REUSED,
                            datasetCase.getId(), existing.getId(), now);
                    batchMapper.incrementSuccess(batchId, (long) valid.lineNo(), 1, now);
                    return null;
                }
                throw new LineRejected("SOURCE_RECORD_CONFLICT",
                        "同一来源记录已存在且正文不同（sourceRecordId=" + valid.sourceRecordId() + "）", false);
            }

            Optional<EvaluationRun> sameSlot = runStore.findBySlot(
                    target.getId(), datasetCase.getId(), valid.repeatIndex());
            if (sameSlot.isPresent()) {
                throw new LineRejected("SLOT_CONFLICT",
                        "该 Target 的样本 " + valid.caseKey() + " 第 " + valid.repeatIndex()
                                + " 次重复已被另一条来源记录占用", false);
            }

            EvaluationRun run = newRun(locked, target, datasetCase, valid, now);
            try {
                runStore.insertRun(run);
                runStore.insertEvents(buildEvents(run, valid, now));
            } catch (org.springframework.dao.DuplicateKeyException ex) {
                // 并发导入同一行/同一身份：对方先提交了，按回执语义收敛
                Optional<EvaluationRun> concurrent = runStore.findBySourceIdentity(
                        locked.getExperimentId(), target.getId(),
                        valid.sourceSystem(), valid.sourceRecordId());
                if (concurrent.isPresent()
                        && valid.businessHash().equals(concurrent.get().getSourceRecordHash())) {
                    insertRecord(batchId, valid, ImportRecordStatus.REUSED,
                            datasetCase.getId(), concurrent.get().getId(), now);
                    batchMapper.incrementSuccess(batchId, (long) valid.lineNo(), 1, now);
                    return null;
                }
                throw new LineRejected("SLOT_CONFLICT", "并发导入冲突：该槽位已被占用", false);
            }

            insertRecord(batchId, valid, ImportRecordStatus.IMPORTED,
                    datasetCase.getId(), run.getId(), now);
            batchMapper.incrementSuccess(batchId, (long) valid.lineNo(), 0, now);
            return null;
        });
    }

    private EvaluationRun newRun(ImportBatch batch, ExperimentTarget target, DatasetCase datasetCase,
                                 OfflineRunValidator.Valid valid, Instant now) {
        EvaluationRun run = new EvaluationRun();
        run.setId(Ulid.newId());
        run.setExperimentId(batch.getExperimentId());
        run.setExperimentTargetId(target.getId());
        run.setDatasetCaseId(datasetCase.getId());
        run.setRepeatIndex(valid.repeatIndex());
        run.setImportBatchId(batch.getId());
        run.setMode(RunMode.OFFLINE);
        run.setStatus(RunStatus.PENDING);
        run.setTaskOutcome(valid.taskOutcome() == null ? TaskOutcome.UNKNOWN : valid.taskOutcome());
        run.setSourceSystem(valid.sourceSystem());
        run.setSourceRecordId(valid.sourceRecordId());
        run.setSourceRecordHash(valid.businessHash());
        run.setTraceId(valid.traceId());
        run.setExternalTraceRef(valid.externalTraceRef());
        // 缺失就是缺失：没有 output 不写空对象，避免下游把"空答案"当成"有答案"
        run.setOutputJson(valid.output() == null || valid.output().isNull() ? null : valid.output());
        run.setOutputArtifactId(null);
        run.setUsageJson(valid.usage());
        run.setObservationMetadataJson(observationMetadata(valid));
        // 离线 Run 没有执行尝试，attemptCount 保持 0，也不伪造 accepted Attempt
        run.setAttemptCount(0);
        run.setSealedAt(now);
        run.setStartedAt(valid.startedAt() == null ? null : valid.startedAt().toInstant());
        run.setFinishedAt(valid.finishedAt() == null ? null : valid.finishedAt().toInstant());
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        return run;
    }

    /**
     * 观测完整性快照。
     *
     * <p>{@code docs/modules/trace-pipeline.md} 对此有明确规则：离线记录未声明时保守置
     * {@code outputKnown=是否显式提供 output}、{@code traceCoverage=PARTIAL（有事件）或 NONE（无事件）}；
     * 并且只有**经验证的适配器**才能声明 {@code COMPLETE}。
     *
     * <p>V1 的 JSONL 导入没有可核验的适配器，行的自称不能当事实，因此这里做两处收敛：
     * 声明 {@code COMPLETE} 但没有适配器凭据时降级为 {@code PARTIAL}；没有任何事件时一律 {@code NONE}。
     * 只改写这份派生元数据，原始行仍完整保存在 Artifact 与 {@code record_hash} 里。
     */
    private JsonNode observationMetadata(OfflineRunValidator.Valid valid) {
        JsonNode declared = valid.observationMetadata();
        boolean hasOutput = valid.output() != null && !valid.output().isNull();
        boolean hasEvents = !valid.events().isEmpty();
        if (declared == null || declared.isNull()) {
            ObjectNode derived = objectMapper.createObjectNode();
            derived.put("outputKnown", hasOutput);
            derived.put("traceCoverage", hasEvents ? "PARTIAL" : "NONE");
            return derived;
        }
        ObjectNode node = ((ObjectNode) declared).deepCopy();
        JsonNode declaredOutput = declared.get("outputKnown");
        boolean outputKnown = hasOutput
                || (declaredOutput != null && declaredOutput.isBoolean() && declaredOutput.asBoolean());
        node.put("outputKnown", outputKnown);
        // traceCoverage 只有 NONE/PARTIAL/COMPLETE 三个取值：无事件即 NONE，有事件最高只到 PARTIAL
        node.put("traceCoverage", hasEvents ? "PARTIAL" : "NONE");
        return node;
    }

    private List<TraceEvent> buildEvents(EvaluationRun run, OfflineRunValidator.Valid valid, Instant now) {
        List<TraceEvent> events = new ArrayList<>(valid.events().size());
        for (OfflineRunValidator.Event source : valid.events()) {
            ObjectNode content = objectMapper.createObjectNode();
            content.put("eventType", source.eventType());
            content.put("occurredAt", source.occurredAt().withOffsetSameInstant(ZoneOffset.UTC).toString());
            if (source.sequenceNo() != null) {
                content.put("sequenceNo", source.sequenceNo());
            }
            content.set("payload", source.payload());

            TraceEvent event = new TraceEvent();
            event.setId(Ulid.newId());
            // event_id 是全局唯一键：每次导入重新生成，重复导入靠来源身份复用而不是靠它
            event.setEventId(Ulid.newId());
            event.setRunId(run.getId());
            // 离线没有执行尝试：这一列保持 null，不伪造一条 Attempt
            event.setRunAttemptId(null);
            event.setSourceEventId(source.sourceEventId());
            event.setEventHash(Sha256.ofUtf8(CanonicalJson.canonicalize(content)));
            event.setTraceId(source.traceId());
            event.setSpanId(source.spanId());
            event.setParentSpanId(source.parentSpanId());
            event.setSequenceNo(source.sequenceNo());
            event.setEventType(source.eventType());
            event.setOccurredAt(source.occurredAt().toInstant());
            event.setPayloadJson(source.payload());
            event.setArtifactId(null);
            event.setCreatedAt(now);
            events.add(event);
        }
        return events;
    }

    private void insertRecord(String batchId, OfflineRunValidator.Valid valid,
                              ImportRecordStatus status, String caseId, String runId, Instant now) {
        ImportRecord record = new ImportRecord();
        record.setId(Ulid.newId());
        record.setBatchId(batchId);
        record.setLineNo((long) valid.lineNo());
        record.setRecordHash(valid.lineHash());
        record.setStatus(status);
        record.setDatasetCaseId(caseId);
        record.setRunId(runId);
        record.setCreatedAt(now);
        recordMapper.insert(record);
    }

    // ------------------------------------------------------------------ 失败与收尾

    /** 失败行：业务写入已回滚，这里用独立短事务幂等写回执与错误。 */
    private void recordRejection(String batchId, JsonlParser.Line line, String code, String message) {
        try {
            tx.execute(status -> {
                ImportBatch locked = lockBatch(batchId);
                if (locked.getStatus() != BatchStatus.RUNNING) {
                    return null;
                }
                if (recordMapper.findByBatchAndLine(batchId, line.lineNo()).isPresent()) {
                    return null;
                }
                Instant now = Instant.now();
                ImportRecord record = new ImportRecord();
                record.setId(Ulid.newId());
                record.setBatchId(batchId);
                record.setLineNo((long) line.lineNo());
                record.setRecordHash(Sha256.ofUtf8(line.text()));
                record.setStatus(ImportRecordStatus.REJECTED);
                record.setDatasetCaseId(null);
                record.setRunId(null);
                record.setCreatedAt(now);
                recordMapper.insert(record);

                ImportError error = new ImportError();
                error.setId(Ulid.newId());
                error.setBatchId(batchId);
                error.setLineNo((long) line.lineNo());
                error.setErrorCode(code);
                // 只保留"哪条规则失败"，不回显原始行内容
                error.setErrorMessage(truncate(message, 1024));
                error.setRecordHash(record.getRecordHash());
                error.setCreatedAt(now);
                errorMapper.insertIgnore(error);

                batchMapper.incrementFailed(batchId, (long) line.lineNo(), now);
                return null;
            });
        } catch (RuntimeException ex) {
            log.error("写导入失败回执时出错: batch={}, line={}", batchId, line.lineNo(), ex);
        }
    }

    private void finish(String batchId, long totalCount) {
        tx.execute(status -> {
            ImportBatch locked = lockBatch(batchId);
            if (locked.getStatus() == BatchStatus.CANCELLED) {
                // 取消是终态：保留已提交行，不再把状态改成失败
                recount(batchId, locked.getTotalCount() == null ? 0 : locked.getTotalCount(),
                        Instant.now());
                return null;
            }
            if (locked.getStatus() != BatchStatus.RUNNING) {
                return null;
            }
            ImportCountsRow counts = recordMapper.countsByStatus(batchId);
            long lastLineNo = recordMapper.maxLineNo(batchId);
            BatchStatus target = counts.failed() == 0
                    ? BatchStatus.COMPLETED
                    : counts.success() > 0 ? BatchStatus.PARTIAL_FAILED : BatchStatus.FAILED;
            Instant now = Instant.now();
            if (batchMapper.finish(batchId, BatchStatus.RUNNING, target,
                    totalCount, counts.success(), counts.failed(), counts.duplicate(),
                    lastLineNo, now, now) != 1) {
                log.warn("导入批次收尾时状态已变化: {}", batchId);
            }
            return null;
        });
    }

    private void failWholeBatch(String batchId, String message) {
        tx.execute(status -> {
            ImportBatch locked = lockBatch(batchId);
            if (locked.getStatus() != BatchStatus.RUNNING) {
                return null;
            }
            Instant now = Instant.now();
            batchMapper.finish(batchId, BatchStatus.RUNNING, BatchStatus.FAILED,
                    0, 0, 0, 0, 0, now, now);
            ImportError error = new ImportError();
            error.setId(Ulid.newId());
            error.setBatchId(batchId);
            error.setLineNo(0L);
            error.setErrorCode("FILE_UNREADABLE");
            error.setErrorMessage(truncate(message, 1024));
            error.setRecordHash(Sha256.ofUtf8(message == null ? "" : message));
            error.setCreatedAt(now);
            errorMapper.insertIgnore(error);
            return null;
        });
    }

    /** 计数重算：只写 import_records 聚合结果，不累加内存值。 */
    private void recount(String batchId, long totalCount, Instant now) {
        ImportCountsRow counts = recordMapper.countsByStatus(batchId);
        batchMapper.updateCounts(batchId, totalCount, counts.success(), counts.failed(),
                counts.duplicate(), recordMapper.maxLineNo(batchId), now);
    }

    // ------------------------------------------------------------------ 基础设施

    private ImportBatch lockBatch(String id) {
        return batchMapper.findByIdForUpdate(id)
                .orElseThrow(() -> notFound(id));
    }

    private ImportBatch requireBatch(String id) {
        return batchMapper.findById(id).orElseThrow(() -> notFound(id));
    }

    private byte[] readArtifact(Artifact artifact) {
        if (artifact.getStatus() != ArtifactStatus.READY) {
            throw BusinessException.field("fileArtifactId", "原始文件 Artifact 不是 READY 状态");
        }
        byte[] bytes;
        try {
            bytes = storage.get(artifact.getObjectKey());
        } catch (StorageException ex) {
            log.error("读取导入原文件失败: artifact={}", artifact.getId(), ex);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "原始文件不可读，请重新上传");
        }
        // 元数据里的 sha256 在创建批次时已固定；内容被替换时必须拒绝，否则回执与文件对不上
        if (!Sha256.ofBytes(bytes).equals(artifact.getSha256())) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                    "原始文件内容与其摘要不一致，请重新上传: " + artifact.getId());
        }
        return bytes;
    }

    private JsonlParser.Result parseOrReject(byte[] bytes) {
        try {
            return JsonlParser.parse(bytes);
        } catch (JsonlParseException ex) {
            throw new BusinessException(ex.getErrorCode(), ex.getMessage());
        }
    }

    private ImportType parseType(String value) {
        if (value == null || value.isBlank()) {
            throw BusinessException.field("importType", "importType 不能为空");
        }
        if ("DATASET_CASES".equals(value.trim())) {
            throw BusinessException.field("importType",
                    "DATASET_CASES 请使用整批原子接口 POST /dataset-versions/{id}/cases:import，"
                            + "本接口只接受 OFFLINE_RUNS");
        }
        if (!"OFFLINE_RUNS".equals(value.trim())) {
            throw BusinessException.field("importType", "不支持的 importType: " + value);
        }
        return ImportType.OFFLINE_RUNS;
    }

    private BatchStatus parseStatus(String value) {
        try {
            return BatchStatus.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("status", "不支持的 status: " + value);
        }
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static BusinessException notFound(String id) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "导入批次不存在: " + id);
    }

    /**
     * 行级业务拒绝。
     *
     * <p>{@code abort=true} 表示"后续行也不必再试"（实验已开始、批次已取消），
     * 其余情况只拒绝当前行，批次继续处理。
     */
    private static final class LineRejected extends RuntimeException {

        private final String code;
        private final boolean abort;

        private LineRejected(String code, String message, boolean abort) {
            super(message);
            this.code = code;
            this.abort = abort;
        }
    }
}
