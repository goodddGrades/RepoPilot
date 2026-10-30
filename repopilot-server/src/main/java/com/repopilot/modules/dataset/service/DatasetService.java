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
import com.repopilot.common.validation.Inputs;
import com.repopilot.common.web.RequestBodies;
import com.repopilot.modules.dataset.dto.DatasetDtos;
import com.repopilot.modules.dataset.entity.Dataset;
import com.repopilot.modules.dataset.mapper.DatasetMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 数据集身份管理：创建、查询、部分更新。
 *
 * <p>与 Agent 一致地使用显式 {@link TransactionTemplate}：幂等回执与业务写入必须在同一事务，
 * 且"唯一键冲突后另开事务读回执"这一步用注解会因自调用失效。
 */
@Service
public class DatasetService {

    private static final String SCOPE_CREATE = "POST /api/v1/datasets";

    private final DatasetMapper mapper;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public DatasetService(DatasetMapper mapper, IdempotencyService idempotency,
                          ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public IdempotentResult<DatasetDtos.DatasetView> create(JsonNode body, String idemKey) {
        String hash = IdempotencyDigest.of("POST", "/api/v1/datasets", body);
        Optional<IdempotentResult<DatasetDtos.DatasetView>> cached =
                idempotency.replay(SCOPE_CREATE, idemKey, hash, DatasetDtos.DatasetView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        DatasetDtos.CreateRequest request = RequestBodies.convert(objectMapper, body, DatasetDtos.CreateRequest.class);
        String code = Inputs.code("code", request.code());
        String name = Inputs.required("name", request.name(), 128);
        String description = Inputs.optional("description", request.description(), 4000);

        return idempotency.execute(SCOPE_CREATE, idemKey, hash, DatasetDtos.DatasetView.class, () ->
                tx.execute(status -> {
                    Instant now = Instant.now();
                    Dataset entity = new Dataset();
                    entity.setId(Ulid.newId());
                    entity.setCode(code);
                    entity.setName(name);
                    entity.setDescription(description);
                    entity.setStatus(LifecycleStatus.ACTIVE);
                    entity.setCreatedAt(now);
                    entity.setUpdatedAt(now);
                    // 不先查 code：并发下必然有窗口。唯一键 + 幂等重放才是正确路径。
                    mapper.insert(entity);

                    DatasetDtos.DatasetView view = DatasetDtos.DatasetView.from(entity);
                    idempotency.record(SCOPE_CREATE, idemKey, hash, "dataset", entity.getId(), 201, view);
                    return IdempotentResult.created(view);
                }));
    }

    public DatasetDtos.DatasetView get(String id) {
        return DatasetDtos.DatasetView.from(requireDataset(id));
    }

    public PageResponse<DatasetDtos.DatasetView> list(Integer page, Integer pageSize,
                                                      String status, String keyword) {
        PageQuery query = PageQuery.of(page, pageSize);
        LifecycleStatus parsed = status == null || status.isBlank() ? null : parseStatus(status);
        String like = Inputs.likeKeyword(keyword, 64);

        List<DatasetDtos.DatasetView> items = mapper.search(parsed, like, query.pageSize(), query.offset())
                .stream().map(DatasetDtos.DatasetView::from).toList();
        return PageResponse.of(items, query, mapper.count(parsed, like));
    }

    /** 部分更新：只改请求里出现的字段，{@code code} 创建后不可改。 */
    public IdempotentResult<DatasetDtos.DatasetView> patch(String id, JsonNode body, String idemKey) {
        // scope 必须带资源 id：否则两个数据集用同一 key 会互相复用回执
        String scope = "PATCH /api/v1/datasets/" + id;
        String hash = IdempotencyDigest.of("PATCH", "/api/v1/datasets/" + id, body);
        Optional<IdempotentResult<DatasetDtos.DatasetView>> cached =
                idempotency.replay(scope, idemKey, hash, DatasetDtos.DatasetView.class);
        if (cached.isPresent()) {
            return cached.get();
        }
        if (body != null && body.has("code")) {
            throw BusinessException.field("code", "code 创建后不可修改");
        }

        return idempotency.execute(scope, idemKey, hash, DatasetDtos.DatasetView.class, () ->
                tx.execute(status -> {
                    Dataset current = mapper.findByIdForUpdate(id)
                            .orElseThrow(() -> notFound(id));

                    String name = body != null && body.has("name")
                            ? Inputs.required("name", body.get("name").asText(null), 128)
                            : current.getName();
                    String description = body != null && body.has("description")
                            ? Inputs.optional("description", body.get("description").asText(null), 4000)
                            : current.getDescription();

                    LifecycleStatus target = current.getStatus();
                    if (body != null && body.has("status")) {
                        LifecycleStatus requested = parseStatus(body.get("status").asText(null));
                        if (requested != current.getStatus()) {
                            // ARCHIVED→ACTIVE 在这里被拒（409）
                            Transitions.require(current.getStatus(), requested);
                            target = requested;
                        }
                    }

                    Instant now = Instant.now();
                    mapper.update(id, name, description, target, now);

                    Dataset updated = new Dataset();
                    updated.setId(current.getId());
                    updated.setCode(current.getCode());
                    updated.setName(name);
                    updated.setDescription(description);
                    updated.setStatus(target);
                    updated.setCreatedAt(current.getCreatedAt());
                    updated.setUpdatedAt(now);

                    DatasetDtos.DatasetView view = DatasetDtos.DatasetView.from(updated);
                    idempotency.record(scope, idemKey, hash, "dataset", id, 200, view);
                    return IdempotentResult.ok(view);
                }));
    }

    Dataset requireDataset(String id) {
        return mapper.findById(id).orElseThrow(() -> notFound(id));
    }

    static BusinessException notFound(String id) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "数据集不存在: " + id);
    }

    private LifecycleStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            throw BusinessException.field("status", "status 不能为空");
        }
        try {
            return LifecycleStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("status", "不支持的 status: " + value);
        }
    }
}
