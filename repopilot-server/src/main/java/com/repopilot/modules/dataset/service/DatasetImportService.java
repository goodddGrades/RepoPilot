package com.repopilot.modules.dataset.service;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.api.FieldViolation;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.hash.Sha256;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.idempotency.IdempotencyDigest;
import com.repopilot.common.idempotency.IdempotencyService;
import com.repopilot.common.idempotency.IdempotentResult;
import com.repopilot.common.jsonl.JsonlParser;
import com.repopilot.common.state.ArtifactStatus;
import com.repopilot.common.state.BatchStatus;
import com.repopilot.common.state.ImportType;
import com.repopilot.common.storage.ArtifactStorage;
import com.repopilot.common.storage.StorageException;
import com.repopilot.modules.dataset.dto.DatasetDtos;
import com.repopilot.modules.dataset.entity.DatasetCase;
import com.repopilot.modules.dataset.mapper.DatasetCaseMapper;
import com.repopilot.modules.dataset.mapper.DatasetVersionMapper;
import com.repopilot.modules.importing.entity.ImportBatch;
import com.repopilot.modules.importing.mapper.ImportBatchMapper;
import com.repopilot.modules.trace.entity.Artifact;
import com.repopilot.modules.trace.mapper.ArtifactMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * JSONL 样本整批导入。
 *
 * <p><b>原子语义</b>：整批要么全部写入，要么一条都不写。为此校验全部发生在写库之前，
 * 且校验失败时**不创建批次、不写样本、不保存原文件**——用户拿到的 400 就是"库里什么都没有"。
 *
 * <p><b>为什么文件写入也放进事务里</b>：数据库事务回滚不了文件系统。文件在事务内写入，
 * 一旦事务失败（唯一键冲突、幂等并发、任何异常导致回滚），catch 分支删除刚写入的对象，
 * 避免出现"有文件、无记录"的孤儿 Artifact。反过来，先写文件再校验则会留下一堆无主文件。
 *
 * <p><b>顺序</b>（与契约一致，不打乱）：文件级 → 行级 schema → 业务规则 → 文件内重复 → 与库内冲突。
 * 前四步完全不碰数据库，因此坏文件在最坏情况下也只消耗一次 HTTP 请求。
 */
@Service
public class DatasetImportService {

    private static final Logger log = LoggerFactory.getLogger(DatasetImportService.class);

    /** 一次最多返回 100 条行级错误；信封已冻结，不新增"是否截断"字段，只用 message 说明。 */
    private static final int MAX_ERRORS = 100;

    private static final String ARTIFACT_TYPE = "IMPORT_FILE";
    private static final String CONTENT_TYPE = "application/x-ndjson";
    private static final String STORAGE_PREFIX = "imports";

    private final DatasetVersionGuard guard;
    private final DatasetCaseValidator validator;
    private final DatasetCaseBatchWriter caseWriter;
    private final DatasetCaseMapper caseMapper;
    private final DatasetVersionMapper versionMapper;
    private final ArtifactMapper artifactMapper;
    private final ImportBatchMapper importBatchMapper;
    private final ArtifactStorage storage;
    private final IdempotencyService idempotency;
    private final TransactionTemplate tx;

    public DatasetImportService(DatasetVersionGuard guard, DatasetCaseValidator validator,
                                DatasetCaseBatchWriter caseWriter, DatasetCaseMapper caseMapper,
                                DatasetVersionMapper versionMapper, ArtifactMapper artifactMapper,
                                ImportBatchMapper importBatchMapper, ArtifactStorage storage,
                                IdempotencyService idempotency,
                                PlatformTransactionManager transactionManager) {
        this.guard = guard;
        this.validator = validator;
        this.caseWriter = caseWriter;
        this.caseMapper = caseMapper;
        this.versionMapper = versionMapper;
        this.artifactMapper = artifactMapper;
        this.importBatchMapper = importBatchMapper;
        this.storage = storage;
        this.idempotency = idempotency;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public IdempotentResult<DatasetDtos.ImportReceipt> importCases(String versionId, MultipartFile file,
                                                                    String idemKey) {
        byte[] bytes = readBytes(file);
        String sha256 = Sha256.ofBytes(bytes);

        // 1) 文件级：大小、行数、单行长度、严格 UTF-8（失败即 413/400，零写入）
        JsonlParser.Result parsed = JsonlParser.parse(bytes);
        if (parsed.lines().isEmpty()) {
            throw BusinessException.field("file", "文件没有任何非空行");
        }

        // 2)~4) 行级 schema、业务规则、文件内重复；一次收集全部错误行
        List<DatasetCaseValidator.Valid> valids = new ArrayList<>(parsed.nonBlankCount());
        Set<Integer> badLines = new LinkedHashSet<>();
        List<FieldViolation> violations = new ArrayList<>();
        Map<String, Integer> firstLineByKey = new LinkedHashMap<>();

        for (JsonlParser.Line line : parsed.lines()) {
            DatasetCaseValidator.Outcome outcome = validator.validate(line.lineNo(), line.text());
            if (outcome.error() != null) {
                badLines.add(line.lineNo());
                addViolation(violations, outcome.error().field(), outcome.error().message());
                continue;
            }
            DatasetCaseValidator.Valid valid = outcome.value();
            Integer previous = firstLineByKey.putIfAbsent(valid.caseKey(), valid.lineNo());
            if (previous != null) {
                badLines.add(valid.lineNo());
                addViolation(violations, "lines[" + valid.lineNo() + "].caseKey",
                        "DUPLICATE_CASE_KEY_IN_FILE 与第 " + previous + " 行重复");
                continue;
            }
            valids.add(valid);
        }

        if (!badLines.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    describe(badLines, violations.size() < badLines.size()), violations);
        }

        // 幂等摘要含文件 sha256：同 key 换文件必须冲突，而不是静默复用旧回执
        String path = "/api/v1/dataset-versions/" + versionId + "/cases:import";
        String scope = "POST " + path;
        String hash = IdempotencyDigest.of("POST", path, null, sha256);
        Optional<IdempotentResult<DatasetDtos.ImportReceipt>> cached =
                idempotency.replay(scope, idemKey, hash, DatasetDtos.ImportReceipt.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        return idempotency.execute(scope, idemKey, hash, DatasetDtos.ImportReceipt.class, () -> {
            // 事务回滚不会撤销文件写入，因此把"已写入的对象键"带出事务，失败时显式清理
            AtomicReference<String> writtenObjectKey = new AtomicReference<>();
            try {
                DatasetDtos.ImportReceipt receipt = tx.execute(status -> doImport(
                        versionId, bytes, sha256, valids, scope, idemKey, hash, writtenObjectKey));
                return IdempotentResult.created(receipt);
            } catch (RuntimeException ex) {
                deleteQuietly(writtenObjectKey.get());
                throw ex;
            }
        });
    }

    /** 真正落库：锁版本 → 库内冲突 → 落文件 → artifacts → import_batches → dataset_cases。 */
    private DatasetDtos.ImportReceipt doImport(String versionId, byte[] bytes, String sha256,
                                                List<DatasetCaseValidator.Valid> valids,
                                                String scope, String idemKey, String hash,
                                                AtomicReference<String> writtenObjectKey) {
        guard.lockEditable(versionId);

        // 5) 与库内已有样本冲突：整批拒绝，不覆盖、不合并、不跳过
        List<String> keys = valids.stream().map(DatasetCaseValidator.Valid::caseKey).toList();
        Set<String> existing = new HashSet<>(caseMapper.findExistingKeys(versionId, keys));
        if (!existing.isEmpty()) {
            List<FieldViolation> conflicts = valids.stream()
                    .filter(v -> existing.contains(v.caseKey()))
                    .limit(MAX_ERRORS)
                    .map(v -> new FieldViolation("lines[" + v.lineNo() + "].caseKey",
                            "CASE_KEY_CONFLICT 该 caseKey 在目标版本中已存在"))
                    .toList();
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                    "导入文件中有 " + existing.size() + " 个 caseKey 与目标版本已有样本重复",
                    conflicts);
        }

        // 校验全部通过后才落盘：此刻的任何失败都已不会留下"半份数据"
        ArtifactStorage.StoredObject stored;
        try {
            stored = storage.put(STORAGE_PREFIX, bytes, CONTENT_TYPE);
        } catch (StorageException ex) {
            log.error("导入原文件写入失败: versionId={}, size={}", versionId, bytes.length, ex);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "导入原文件保存失败，请稍后重试");
        }
        writtenObjectKey.set(stored.objectKey());

        Instant now = Instant.now();
        Artifact artifact = new Artifact();
        artifact.setId(Ulid.newId());
        artifact.setRunId(null);
        artifact.setArtifactType(ARTIFACT_TYPE);
        artifact.setStorageProvider(stored.provider());
        artifact.setObjectKey(stored.objectKey());
        artifact.setContentType(CONTENT_TYPE);
        artifact.setSizeBytes(stored.sizeBytes());
        artifact.setSha256(stored.sha256());
        artifact.setStatus(ArtifactStatus.READY);
        artifact.setCreatedAt(now);
        artifactMapper.insert(artifact);

        int importedCount = valids.size();
        int lastLineNo = valids.get(importedCount - 1).lineNo();

        ImportBatch batch = new ImportBatch();
        batch.setId(Ulid.newId());
        batch.setImportType(ImportType.DATASET_CASES);
        batch.setDatasetVersionId(versionId);
        batch.setExperimentId(null);
        batch.setFileArtifactId(artifact.getId());
        batch.setStatus(BatchStatus.COMPLETED);
        batch.setTotalCount((long) importedCount);
        batch.setSuccessCount((long) importedCount);
        batch.setFailedCount(0L);
        batch.setDuplicateCount(0L);
        batch.setLastLineNo((long) lastLineNo);
        batch.setStartedAt(now);
        batch.setFinishedAt(now);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        importBatchMapper.insert(batch);

        List<DatasetCase> entities = new ArrayList<>(importedCount);
        for (DatasetCaseValidator.Valid valid : valids) {
            DatasetCase entity = new DatasetCase();
            entity.setId(Ulid.newId());
            entity.setDatasetVersionId(versionId);
            entity.setCaseKey(valid.caseKey());
            entity.setTaskType(valid.taskType());
            entity.setInputJson(valid.input());
            entity.setExpectedJson(valid.expected());
            entity.setMetadataJson(valid.metadata());
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            entities.add(entity);
        }
        caseWriter.insertAll(entities);
        versionMapper.touch(versionId, now);

        DatasetDtos.ImportReceipt receipt = new DatasetDtos.ImportReceipt(batch.getId(), versionId,
                BatchStatus.COMPLETED.name(), importedCount, importedCount,
                stored.sizeBytes(), stored.sha256());
        idempotency.record(scope, idemKey, hash, "import_batch", batch.getId(), 201, receipt);
        return receipt;
    }

    private byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.field("file", "file 不能为空");
        }
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无法读取上传文件");
        }
    }

    private void addViolation(List<FieldViolation> violations, String field, String message) {
        if (violations.size() < MAX_ERRORS) {
            violations.add(new FieldViolation(field, message));
        }
    }

    /**
     * 组装 message：列出前 5 个出错行号，便于用户直接跳到编辑器对应位置。
     *
     * <p>截断时在 message 里说明"共 N 行，仅显示前 100 条"——契约已冻结信封，
     * 不新增字段表达截断状态。
     */
    private String describe(Set<Integer> badLines, boolean truncated) {
        String head = badLines.stream().limit(5).map(String::valueOf).collect(Collectors.joining("、"));
        StringBuilder sb = new StringBuilder("第 ").append(head).append(" 行不合法");
        if (badLines.size() > 5) {
            sb.append(" 等 ").append(badLines.size()).append(" 行");
        }
        if (truncated) {
            sb.append("（共 ").append(badLines.size()).append(" 行不合法，仅显示前 ")
                    .append(MAX_ERRORS).append(" 条）");
        }
        return sb.toString();
    }

    private void deleteQuietly(String objectKey) {
        if (objectKey == null) {
            return;
        }
        try {
            storage.delete(objectKey);
            log.info("事务回滚，已清理导入原文件: {}", objectKey);
        } catch (RuntimeException ex) {
            // 清理失败只记录：此时正在向上抛原始异常，覆盖它会让真正的失败原因消失
            log.error("清理孤儿导入文件失败，需人工处理: {}", objectKey, ex);
        }
    }
}
