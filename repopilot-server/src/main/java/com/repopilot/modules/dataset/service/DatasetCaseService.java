package com.repopilot.modules.dataset.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.api.FieldViolation;
import com.repopilot.common.api.PageQuery;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.exception.BusinessException;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.idempotency.IdempotencyDigest;
import com.repopilot.common.idempotency.IdempotencyService;
import com.repopilot.common.idempotency.IdempotentResult;
import com.repopilot.common.validation.Inputs;
import com.repopilot.common.web.RequestBodies;
import com.repopilot.modules.dataset.dto.DatasetDtos;
import com.repopilot.modules.dataset.entity.DatasetCase;
import com.repopilot.modules.dataset.entity.DatasetVersion;
import com.repopilot.modules.dataset.mapper.DatasetCaseMapper;
import com.repopilot.modules.dataset.mapper.DatasetVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 单条样本的增删改查。
 *
 * <p>写操作一律先锁父版本并确认仍是草稿：已发布版本的样本集合是实验结果的输入，
 * 改一条样本就等于改了历史结论的依据。
 *
 * <p>加锁顺序沿用 {@link DatasetVersionGuard}（数据集行 → 版本行）。
 * 版本行锁同时把"同一版本内的 caseKey 唯一性检查"变成安全的检查——
 * 检查与插入之间不会插进另一个写请求。
 */
@Service
public class DatasetCaseService {

    private final DatasetCaseMapper caseMapper;
    private final DatasetVersionMapper versionMapper;
    private final DatasetVersionGuard guard;
    private final DatasetCaseValidator validator;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public DatasetCaseService(DatasetCaseMapper caseMapper, DatasetVersionMapper versionMapper,
                              DatasetVersionGuard guard, DatasetCaseValidator validator,
                              IdempotencyService idempotency, ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager) {
        this.caseMapper = caseMapper;
        this.versionMapper = versionMapper;
        this.guard = guard;
        this.validator = validator;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public IdempotentResult<DatasetDtos.CaseView> create(String versionId, JsonNode body, String idemKey) {
        String path = "/api/v1/dataset-versions/" + versionId + "/cases";
        String scope = "POST " + path;
        String hash = IdempotencyDigest.of("POST", path, body);
        Optional<IdempotentResult<DatasetDtos.CaseView>> cached =
                idempotency.replay(scope, idemKey, hash, DatasetDtos.CaseView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        DatasetDtos.CaseUpsertRequest request =
                RequestBodies.convert(objectMapper, body, DatasetDtos.CaseUpsertRequest.class);
        DatasetCaseValidator.Valid valid = validator.requireCase("body", request.caseKey(),
                request.taskType(), request.input(), request.expected(), request.metadata());

        return idempotency.execute(scope, idemKey, hash, DatasetDtos.CaseView.class, () ->
                tx.execute(status -> {
                    DatasetVersion version = guard.lockEditable(versionId);
                    requireNoKeyConflict(versionId, valid.caseKey(), null);

                    Instant now = Instant.now();
                    DatasetCase entity = newEntity(version.getId(), valid, now);
                    caseMapper.insert(entity);
                    versionMapper.touch(versionId, now);

                    DatasetDtos.CaseView view = DatasetDtos.CaseView.from(entity);
                    idempotency.record(scope, idemKey, hash, "dataset_case", entity.getId(), 201, view);
                    return IdempotentResult.created(view);
                }));
    }

    public PageResponse<DatasetDtos.CaseView> list(String versionId, Integer page, Integer pageSize,
                                                    String keyword) {
        // 版本存不存在要单独判断，否则"不存在的版本"会返回空页，看起来像"这版没有样本"
        guard.requireVersion(versionId);
        PageQuery query = PageQuery.of(page, pageSize);
        String like = Inputs.likeKeyword(keyword, 128);

        List<DatasetDtos.CaseView> items = caseMapper
                .listByVersion(versionId, like, query.pageSize(), query.offset())
                .stream().map(DatasetDtos.CaseView::from).toList();
        return PageResponse.of(items, query, caseMapper.countByVersion(versionId, like));
    }

    public IdempotentResult<DatasetDtos.CaseView> update(String caseId, JsonNode body, String idemKey) {
        String scope = "PUT /api/v1/dataset-cases/" + caseId;
        String hash = IdempotencyDigest.of("PUT", "/api/v1/dataset-cases/" + caseId, body);
        Optional<IdempotentResult<DatasetDtos.CaseView>> cached =
                idempotency.replay(scope, idemKey, hash, DatasetDtos.CaseView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        DatasetDtos.CaseUpsertRequest request =
                RequestBodies.convert(objectMapper, body, DatasetDtos.CaseUpsertRequest.class);
        DatasetCaseValidator.Valid valid = validator.requireCase("body", request.caseKey(),
                request.taskType(), request.input(), request.expected(), request.metadata());

        return idempotency.execute(scope, idemKey, hash, DatasetDtos.CaseView.class, () ->
                tx.execute(status -> {
                    DatasetCase snapshot = requireCase(caseId);
                    guard.lockEditable(snapshot.getDatasetVersionId());
                    // 版本行锁之后重读：此前的快照可能已被并发写改过
                    DatasetCase current = requireCase(caseId);
                    requireNoKeyConflict(current.getDatasetVersionId(), valid.caseKey(), caseId);

                    Instant now = Instant.now();
                    caseMapper.update(caseId, valid.caseKey(), valid.taskType(), valid.input(),
                            valid.expected(), valid.metadata(), now);
                    versionMapper.touch(current.getDatasetVersionId(), now);

                    DatasetDtos.CaseView view = new DatasetDtos.CaseView(caseId, current.getDatasetVersionId(),
                            valid.caseKey(), valid.taskType(), valid.input(), valid.expected(),
                            valid.metadata(), current.getCreatedAt(), now);
                    idempotency.record(scope, idemKey, hash, "dataset_case", caseId, 200, view);
                    return IdempotentResult.ok(view);
                }));
    }

    public IdempotentResult<DatasetDtos.DeleteResult> delete(String caseId, String idemKey) {
        String scope = "DELETE /api/v1/dataset-cases/" + caseId;
        String hash = IdempotencyDigest.of("DELETE", "/api/v1/dataset-cases/" + caseId, null);
        Optional<IdempotentResult<DatasetDtos.DeleteResult>> cached =
                idempotency.replay(scope, idemKey, hash, DatasetDtos.DeleteResult.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        return idempotency.execute(scope, idemKey, hash, DatasetDtos.DeleteResult.class, () ->
                tx.execute(status -> {
                    DatasetCase snapshot = requireCase(caseId);
                    guard.lockEditable(snapshot.getDatasetVersionId());

                    Instant now = Instant.now();
                    caseMapper.delete(caseId);
                    versionMapper.touch(snapshot.getDatasetVersionId(), now);

                    DatasetDtos.DeleteResult result = new DatasetDtos.DeleteResult(caseId, true);
                    idempotency.record(scope, idemKey, hash, "dataset_case", caseId, 200, result);
                    return IdempotentResult.ok(result);
                }));
    }

    /**
     * 版本内 caseKey 唯一性检查。
     *
     * <p>{@code selfId} 非空表示"修改自身"：key 没变时必须放行，否则用户改个 input 也会被
     * 自己挡住。当前持有版本行锁，检查与写入之间不会有别的写请求插进来。
     */
    private void requireNoKeyConflict(String versionId, String caseKey, String selfId) {
        caseMapper.findIdByVersionAndKey(versionId, caseKey)
                .filter(id -> !id.equals(selfId))
                .ifPresent(id -> {
                    throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                            "caseKey 在该版本中已存在: " + caseKey,
                            List.of(new FieldViolation("caseKey",
                                    "CASE_KEY_CONFLICT 该 caseKey 在该版本中已存在")));
                });
    }

    private DatasetCase newEntity(String versionId, DatasetCaseValidator.Valid valid, Instant now) {
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
        return entity;
    }

    private DatasetCase requireCase(String caseId) {
        return caseMapper.findById(caseId).orElseThrow(() -> new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND, "样本不存在: " + caseId));
    }
}
