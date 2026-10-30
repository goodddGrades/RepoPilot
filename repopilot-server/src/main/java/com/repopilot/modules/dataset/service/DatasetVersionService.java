package com.repopilot.modules.dataset.service;

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
import com.repopilot.common.state.LifecycleStatus;
import com.repopilot.common.state.Transitions;
import com.repopilot.common.state.VersionStatus;
import com.repopilot.common.validation.Inputs;
import com.repopilot.common.web.RequestBodies;
import com.repopilot.modules.dataset.dto.DatasetDtos;
import com.repopilot.modules.dataset.entity.Dataset;
import com.repopilot.modules.dataset.entity.DatasetCase;
import com.repopilot.modules.dataset.entity.DatasetVersion;
import com.repopilot.modules.dataset.mapper.DatasetCaseMapper;
import com.repopilot.modules.dataset.mapper.DatasetMapper;
import com.repopilot.modules.dataset.mapper.DatasetVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 数据集版本：分配版本号、可选克隆、发布与归档。
 *
 * <p>发布是<b>唯一</b>写入 {@code content_hash} 的时机。草稿没有摘要，
 * 因为草稿的内容还在变，提前算出来的摘要只会让人误以为"这版已经定了"。
 *
 * <p>发布必须校验非空样本：空版本发布后，实验跑出来的"成功率"是分数的分母为 0，
 * 这种数据比报错更难发现。
 */
@Service
public class DatasetVersionService {

    private final DatasetMapper datasetMapper;
    private final DatasetVersionMapper versionMapper;
    private final DatasetCaseMapper caseMapper;
    private final DatasetCaseBatchWriter caseWriter;
    private final DatasetVersionGuard guard;
    private final DatasetContentHasher hasher;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public DatasetVersionService(DatasetMapper datasetMapper,
                                 DatasetVersionMapper versionMapper,
                                 DatasetCaseMapper caseMapper,
                                 DatasetCaseBatchWriter caseWriter,
                                 DatasetVersionGuard guard,
                                 DatasetContentHasher hasher,
                                 IdempotencyService idempotency,
                                 ObjectMapper objectMapper,
                                 PlatformTransactionManager transactionManager) {
        this.datasetMapper = datasetMapper;
        this.versionMapper = versionMapper;
        this.caseMapper = caseMapper;
        this.caseWriter = caseWriter;
        this.guard = guard;
        this.hasher = hasher;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public IdempotentResult<DatasetDtos.VersionView> create(String datasetId, JsonNode body, String idemKey) {
        String path = "/api/v1/datasets/" + datasetId + "/versions";
        String scope = "POST " + path;
        String hash = IdempotencyDigest.of("POST", path, body);
        Optional<IdempotentResult<DatasetDtos.VersionView>> cached =
                idempotency.replay(scope, idemKey, hash, DatasetDtos.VersionView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        DatasetDtos.CreateVersionRequest request =
                RequestBodies.convert(objectMapper, body, DatasetDtos.CreateVersionRequest.class);
        String description = Inputs.optional("description", request.description(), 4000);
        String cloneFrom = Inputs.optional("cloneFromVersionId", request.cloneFromVersionId(), 26);

        return idempotency.execute(scope, idemKey, hash, DatasetDtos.VersionView.class, () ->
                tx.execute(status -> {
                    // 锁数据集行：版本号分配与"父是否 ACTIVE"的判断必须串行
                    Dataset dataset = datasetMapper.findByIdForUpdate(datasetId)
                            .orElseThrow(() -> DatasetService.notFound(datasetId));
                    if (dataset.getStatus() != LifecycleStatus.ACTIVE) {
                        throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                                "数据集已归档，不能创建新版本");
                    }

                    List<DatasetCase> clones = cloneFrom == null
                            ? List.of() : readCloneSource(datasetId, cloneFrom);

                    int version = versionMapper.nextVersion(datasetId);
                    Instant now = Instant.now();

                    DatasetVersion entity = new DatasetVersion();
                    entity.setId(Ulid.newId());
                    entity.setDatasetId(datasetId);
                    entity.setVersion(version);
                    entity.setStatus(VersionStatus.DRAFT);
                    entity.setDescription(description);
                    entity.setContentHash(null);
                    entity.setPublishedAt(null);
                    entity.setCreatedAt(now);
                    entity.setUpdatedAt(now);
                    versionMapper.insert(entity);

                    if (!clones.isEmpty()) {
                        String newVersionId = entity.getId();
                        List<DatasetCase> copies = clones.stream()
                                .map(source -> copyFor(source, newVersionId, now))
                                .toList();
                        caseWriter.insertAll(copies);
                    }

                    DatasetDtos.VersionView view = DatasetDtos.VersionView.from(entity, clones.size());
                    idempotency.record(scope, idemKey, hash, "dataset_version", entity.getId(), 201, view);
                    return IdempotentResult.created(view);
                }));
    }

    public PageResponse<DatasetDtos.VersionView> list(String datasetId, Integer page, Integer pageSize) {
        if (datasetMapper.findById(datasetId).isEmpty()) {
            throw DatasetService.notFound(datasetId);
        }
        PageQuery query = PageQuery.of(page, pageSize);
        List<DatasetDtos.VersionView> items = versionMapper
                .listByDataset(datasetId, query.pageSize(), query.offset())
                .stream()
                .map(v -> DatasetDtos.VersionView.from(v, caseMapper.countByVersion(v.getId(), null)))
                .toList();
        return PageResponse.of(items, query, versionMapper.countByDataset(datasetId));
    }

    public DatasetDtos.VersionView get(String versionId) {
        DatasetVersion version = guard.requireVersion(versionId);
        return DatasetDtos.VersionView.from(version, caseMapper.countByVersion(versionId, null));
    }

    public IdempotentResult<DatasetDtos.VersionView> publish(String versionId, String idemKey) {
        String scope = "POST /api/v1/dataset-versions/" + versionId + "/publish";
        String hash = IdempotencyDigest.of("POST", "/api/v1/dataset-versions/" + versionId + "/publish", null);
        Optional<IdempotentResult<DatasetDtos.VersionView>> cached =
                idempotency.replay(scope, idemKey, hash, DatasetDtos.VersionView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        return idempotency.execute(scope, idemKey, hash, DatasetDtos.VersionView.class, () ->
                tx.execute(status -> {
                    DatasetVersion current = guard.lock(versionId);
                    Transitions.require(current.getStatus(), VersionStatus.PUBLISHED);

                    long caseCount = caseMapper.countByVersion(versionId, null);
                    if (caseCount == 0) {
                        throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                                "数据集版本没有样本，不能发布",
                                List.of(new com.repopilot.common.api.FieldViolation("cases",
                                        "请先添加或导入样本")));
                    }

                    // 一次性取全量样本再算摘要：边查边算会让两次查询看到不同快照
                    String contentHash = hasher.hashCases(caseMapper.findAllByVersion(versionId));
                    Instant now = Instant.now();
                    versionMapper.updateStatus(versionId, VersionStatus.PUBLISHED, contentHash, now, now);

                    DatasetDtos.VersionView view = new DatasetDtos.VersionView(versionId,
                            current.getDatasetId(), current.getVersion(), VersionStatus.PUBLISHED,
                            current.getDescription(), contentHash, now, caseCount,
                            current.getCreatedAt(), now);
                    idempotency.record(scope, idemKey, hash, "dataset_version", versionId, 200, view);
                    return IdempotentResult.ok(view);
                }));
    }

    public IdempotentResult<DatasetDtos.VersionView> archive(String versionId, String idemKey) {
        String scope = "POST /api/v1/dataset-versions/" + versionId + "/archive";
        String hash = IdempotencyDigest.of("POST", "/api/v1/dataset-versions/" + versionId + "/archive", null);
        Optional<IdempotentResult<DatasetDtos.VersionView>> cached =
                idempotency.replay(scope, idemKey, hash, DatasetDtos.VersionView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        return idempotency.execute(scope, idemKey, hash, DatasetDtos.VersionView.class, () ->
                tx.execute(status -> {
                    DatasetVersion current = guard.lock(versionId);
                    // DRAFT 与 PUBLISHED 都允许归档；ARCHIVED 为终态。
                    // content_hash 与 published_at 原样保留：归档不是"重新发布"，也不能抹掉历史摘要。
                    Transitions.require(current.getStatus(), VersionStatus.ARCHIVED);

                    Instant now = Instant.now();
                    versionMapper.updateStatus(versionId, VersionStatus.ARCHIVED,
                            current.getContentHash(), current.getPublishedAt(), now);

                    long caseCount = caseMapper.countByVersion(versionId, null);
                    DatasetDtos.VersionView view = new DatasetDtos.VersionView(versionId,
                            current.getDatasetId(), current.getVersion(), VersionStatus.ARCHIVED,
                            current.getDescription(), current.getContentHash(), current.getPublishedAt(),
                            caseCount, current.getCreatedAt(), now);
                    idempotency.record(scope, idemKey, hash, "dataset_version", versionId, 200, view);
                    return IdempotentResult.ok(view);
                }));
    }

    /**
     * 读取克隆源；源版本必须属于同一个数据集。
     *
     * <p>不允许跨数据集克隆：数据集是样本口径的边界，跨数据集复制会让"这个版本的样本从哪来"
     * 完全无法追溯，而 {@code content_hash} 相同的两个版本分属不同数据集又很容易被误当成同一批数据。
     */
    private List<DatasetCase> readCloneSource(String datasetId, String cloneFromVersionId) {
        DatasetVersion source = versionMapper.findById(cloneFromVersionId)
                .orElseThrow(() -> DatasetVersionGuard.notFound(cloneFromVersionId));
        if (!datasetId.equals(source.getDatasetId())) {
            throw BusinessException.field("cloneFromVersionId", "克隆源必须是同一数据集下的版本");
        }
        return caseMapper.findAllByVersion(cloneFromVersionId);
    }

    private DatasetCase copyFor(DatasetCase source, String newVersionId, Instant now) {
        DatasetCase copy = new DatasetCase();
        copy.setId(Ulid.newId());
        copy.setDatasetVersionId(newVersionId);
        copy.setCaseKey(source.getCaseKey());
        copy.setTaskType(source.getTaskType());
        copy.setInputJson(source.getInputJson());
        copy.setExpectedJson(source.getExpectedJson());
        copy.setMetadataJson(source.getMetadataJson());
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        return copy;
    }

    // ---- TASK-004：供实验模块读取（不跨模块直接用 Mapper） ----

    public DatasetVersion requireVersion(String id) {
        return versionMapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "DatasetVersion 不存在: " + id));
    }

    /**
     * 实验开始时的版本校验。
     *
     * <p>只接受 PUBLISHED：DRAFT 的内容随时会变，用它启动实验等于让"同一实验"在不同时间
     * 跑不同数据；ARCHIVED 是终态，也不允许新实验引用。
     */
    public DatasetVersion requirePublishedVersion(String id) {
        DatasetVersion version = requireVersion(id);
        if (version.getStatus() != VersionStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "DatasetVersion 不是已发布状态（当前 " + version.getStatus() + "）：" + id);
        }
        return version;
    }

    /** 固定版本下的全部样本，顺序稳定，用于生成 Run 槽位。 */
    public List<DatasetCase> listCases(String versionId) {
        return caseMapper.findAllByVersion(versionId);
    }

    /** 按主键读取样本；领取任务时用它组装 Worker 输入。 */
    public DatasetCase requireCase(String caseId) {
        return caseMapper.findById(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "DatasetCase 不存在: " + caseId));
    }
}
