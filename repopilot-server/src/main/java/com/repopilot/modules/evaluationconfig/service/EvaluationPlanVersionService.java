package com.repopilot.modules.evaluationconfig.service;

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
import com.repopilot.common.state.Transitions;
import com.repopilot.common.state.VersionStatus;
import com.repopilot.common.validation.Inputs;
import com.repopilot.common.web.RequestBodies;
import com.repopilot.modules.dataset.service.DatasetContentHasher;
import com.repopilot.modules.evaluationconfig.dto.EvaluationDtos;
import com.repopilot.modules.evaluationconfig.entity.EvaluationPlan;
import com.repopilot.modules.evaluationconfig.entity.EvaluationPlanItem;
import com.repopilot.modules.evaluationconfig.entity.EvaluationPlanVersion;
import com.repopilot.modules.evaluationconfig.entity.EvaluatorDefinition;
import com.repopilot.modules.evaluationconfig.mapper.EvaluationPlanItemMapper;
import com.repopilot.modules.evaluationconfig.mapper.EvaluationPlanMapper;
import com.repopilot.modules.evaluationconfig.mapper.EvaluationPlanVersionMapper;
import com.repopilot.modules.evaluationconfig.mapper.EvaluatorDefinitionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 方案版本：分配版本号、草稿整体替换、发布与归档。
 *
 * <p>发布时做两件事：确认所有引用的算子都已发布，然后把 {@code {config, items}} 的规范化摘要
 * 写进 {@code content_hash}。这个摘要是批次可比性的基础——不同 hash 的两个方案版本，
 * 其评测结果不能直接判定回归。
 *
 * <p>之所以"发布"才写摘要：草稿的 items 还在变，提前算出来的值只会误导人。
 */
@Service
public class EvaluationPlanVersionService {

    private final EvaluationPlanMapper planMapper;
    private final EvaluationPlanVersionMapper versionMapper;
    private final EvaluationPlanItemMapper itemMapper;
    private final EvaluatorDefinitionMapper evaluatorMapper;
    private final EvaluationPlanGuard guard;
    private final PlanItems planItems;
    private final DatasetContentHasher hasher;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public EvaluationPlanVersionService(EvaluationPlanMapper planMapper,
                                        EvaluationPlanVersionMapper versionMapper,
                                        EvaluationPlanItemMapper itemMapper,
                                        EvaluatorDefinitionMapper evaluatorMapper,
                                        EvaluationPlanGuard guard, PlanItems planItems,
                                        DatasetContentHasher hasher, IdempotencyService idempotency,
                                        ObjectMapper objectMapper,
                                        PlatformTransactionManager transactionManager) {
        this.planMapper = planMapper;
        this.versionMapper = versionMapper;
        this.itemMapper = itemMapper;
        this.evaluatorMapper = evaluatorMapper;
        this.guard = guard;
        this.planItems = planItems;
        this.hasher = hasher;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public IdempotentResult<EvaluationDtos.PlanVersionView> create(String planId, JsonNode body, String idemKey) {
        String path = "/api/v1/evaluation-plans/" + planId + "/versions";
        String scope = "POST " + path;
        String hash = IdempotencyDigest.of("POST", path, body);
        Optional<IdempotentResult<EvaluationDtos.PlanVersionView>> cached =
                idempotency.replay(scope, idemKey, hash, EvaluationDtos.PlanVersionView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        EvaluationDtos.PlanVersionRequest request =
                RequestBodies.convert(objectMapper, body, EvaluationDtos.PlanVersionRequest.class);
        JsonNode config = normalizeConfig(request.config());
        // 直接看原始 JSON：DTO 化之后"items 缺失"与"items 为空数组"就分不出来了
        List<PlanItems.Spec> specs = planItems.parse(body.get("items"));

        return idempotency.execute(scope, idemKey, hash, EvaluationDtos.PlanVersionView.class, () ->
                tx.execute(status -> {
                    // 锁方案行：版本号分配必须串行
                    EvaluationPlan plan = planMapper.findByIdForUpdate(planId)
                            .orElseThrow(() -> EvaluationPlanGuard.planNotFound(planId));

                    int version = versionMapper.nextVersion(planId);
                    Instant now = Instant.now();

                    EvaluationPlanVersion entity = new EvaluationPlanVersion();
                    entity.setId(Ulid.newId());
                    entity.setPlanId(plan.getId());
                    entity.setVersion(version);
                    entity.setStatus(VersionStatus.DRAFT);
                    entity.setConfigJson(config);
                    entity.setContentHash(null);
                    entity.setPublishedAt(null);
                    entity.setCreatedAt(now);
                    entity.setUpdatedAt(now);
                    versionMapper.insert(entity);

                    List<EvaluationPlanItem> items = insertItems(entity.getId(), specs, now);
                    EvaluationDtos.PlanVersionView view = toView(entity, items);

                    idempotency.record(scope, idemKey, hash, "evaluation_plan_version", entity.getId(), 201, view);
                    return IdempotentResult.created(view);
                }));
    }

    public PageResponse<EvaluationDtos.PlanVersionView> list(String planId, Integer page, Integer pageSize) {
        guard.requirePlan(planId);
        PageQuery query = PageQuery.of(page, pageSize);
        List<EvaluationDtos.PlanVersionView> items = versionMapper
                .listByPlan(planId, query.pageSize(), query.offset())
                .stream()
                .map(v -> EvaluationDtos.PlanVersionView.from(v, toItemViews(itemMapper.listByVersion(v.getId()))))
                .toList();
        return PageResponse.of(items, query, versionMapper.countByPlan(planId));
    }

    public EvaluationDtos.PlanVersionView get(String versionId) {
        EvaluationPlanVersion version = guard.requireVersion(versionId);
        return EvaluationDtos.PlanVersionView.from(version,
                toItemViews(itemMapper.listByVersion(versionId)));
    }

    /** 整体替换草稿：先删后插，不做逐项 diff（方案项数量很小，diff 反而引入中间态）。 */
    public IdempotentResult<EvaluationDtos.PlanVersionView> replace(String versionId, JsonNode body, String idemKey) {
        String scope = "PUT /api/v1/evaluation-plan-versions/" + versionId;
        String hash = IdempotencyDigest.of("PUT", "/api/v1/evaluation-plan-versions/" + versionId, body);
        Optional<IdempotentResult<EvaluationDtos.PlanVersionView>> cached =
                idempotency.replay(scope, idemKey, hash, EvaluationDtos.PlanVersionView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        EvaluationDtos.PlanVersionRequest request =
                RequestBodies.convert(objectMapper, body, EvaluationDtos.PlanVersionRequest.class);
        JsonNode config = normalizeConfig(request.config());
        List<PlanItems.Spec> specs = planItems.parse(body.get("items"));

        return idempotency.execute(scope, idemKey, hash, EvaluationDtos.PlanVersionView.class, () ->
                tx.execute(status -> {
                    EvaluationPlanVersion current = guard.lockEditable(versionId);

                    Instant now = Instant.now();
                    versionMapper.updateConfig(versionId, config, now);
                    itemMapper.deleteByVersion(versionId);
                    List<EvaluationPlanItem> items = insertItems(versionId, specs, now);

                    EvaluationPlanVersion updated = new EvaluationPlanVersion();
                    updated.setId(current.getId());
                    updated.setPlanId(current.getPlanId());
                    updated.setVersion(current.getVersion());
                    updated.setStatus(current.getStatus());
                    updated.setConfigJson(config);
                    updated.setContentHash(current.getContentHash());
                    updated.setPublishedAt(current.getPublishedAt());
                    updated.setCreatedAt(current.getCreatedAt());
                    updated.setUpdatedAt(now);

                    EvaluationDtos.PlanVersionView view = toView(updated, items);
                    idempotency.record(scope, idemKey, hash, "evaluation_plan_version", versionId, 200, view);
                    return IdempotentResult.ok(view);
                }));
    }

    public IdempotentResult<EvaluationDtos.PlanVersionView> publish(String versionId, String idemKey) {
        String scope = "POST /api/v1/evaluation-plan-versions/" + versionId + "/publish";
        String hash = IdempotencyDigest.of("POST", "/api/v1/evaluation-plan-versions/" + versionId + "/publish", null);
        Optional<IdempotentResult<EvaluationDtos.PlanVersionView>> cached =
                idempotency.replay(scope, idemKey, hash, EvaluationDtos.PlanVersionView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        return idempotency.execute(scope, idemKey, hash, EvaluationDtos.PlanVersionView.class, () ->
                tx.execute(status -> {
                    EvaluationPlanVersion current = guard.lock(versionId);
                    Transitions.require(current.getStatus(), VersionStatus.PUBLISHED);

                    List<EvaluationPlanItem> items = itemMapper.listByVersion(versionId);
                    requireEvaluatorsPublished(items);

                    String contentHash = hasher.hashPlan(current.getConfigJson(), toPlanItemViews(items));
                    Instant now = Instant.now();
                    versionMapper.updateStatus(versionId, VersionStatus.PUBLISHED, contentHash, now, now);

                    EvaluationPlanVersion published = new EvaluationPlanVersion();
                    published.setId(current.getId());
                    published.setPlanId(current.getPlanId());
                    published.setVersion(current.getVersion());
                    published.setStatus(VersionStatus.PUBLISHED);
                    published.setConfigJson(current.getConfigJson());
                    published.setContentHash(contentHash);
                    published.setPublishedAt(now);
                    published.setCreatedAt(current.getCreatedAt());
                    published.setUpdatedAt(now);

                    EvaluationDtos.PlanVersionView view = toView(published, items);
                    idempotency.record(scope, idemKey, hash, "evaluation_plan_version", versionId, 200, view);
                    return IdempotentResult.ok(view);
                }));
    }

    public IdempotentResult<EvaluationDtos.PlanVersionView> archive(String versionId, String idemKey) {
        String scope = "POST /api/v1/evaluation-plan-versions/" + versionId + "/archive";
        String hash = IdempotencyDigest.of("POST", "/api/v1/evaluation-plan-versions/" + versionId + "/archive", null);
        Optional<IdempotentResult<EvaluationDtos.PlanVersionView>> cached =
                idempotency.replay(scope, idemKey, hash, EvaluationDtos.PlanVersionView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        return idempotency.execute(scope, idemKey, hash, EvaluationDtos.PlanVersionView.class, () ->
                tx.execute(status -> {
                    EvaluationPlanVersion current = guard.lock(versionId);
                    // 草稿可以丢弃，已发布版本归档保留历史；摘要与发布时间原样保留
                    Transitions.require(current.getStatus(), VersionStatus.ARCHIVED);

                    Instant now = Instant.now();
                    versionMapper.updateStatus(versionId, VersionStatus.ARCHIVED,
                            current.getContentHash(), current.getPublishedAt(), now);

                    EvaluationPlanVersion archived = new EvaluationPlanVersion();
                    archived.setId(current.getId());
                    archived.setPlanId(current.getPlanId());
                    archived.setVersion(current.getVersion());
                    archived.setStatus(VersionStatus.ARCHIVED);
                    archived.setConfigJson(current.getConfigJson());
                    archived.setContentHash(current.getContentHash());
                    archived.setPublishedAt(current.getPublishedAt());
                    archived.setCreatedAt(current.getCreatedAt());
                    archived.setUpdatedAt(now);

                    EvaluationDtos.PlanVersionView view = toView(archived, itemMapper.listByVersion(versionId));
                    idempotency.record(scope, idemKey, hash, "evaluation_plan_version", versionId, 200, view);
                    return IdempotentResult.ok(view);
                }));
    }

    /**
     * 发布前的算子状态校验。
     *
     * <p>一次批量查完再比对，而不是逐项查库：方案项最多 50 个，逐项查会变成 50 次往返，
     * 而且中间可能被别的请求改掉状态。
     */
    private void requireEvaluatorsPublished(List<EvaluationPlanItem> items) {
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "方案版本没有算子，不能发布",
                    List.of(new FieldViolation("items", "请先添加至少一个算子")));
        }
        List<String> ids = items.stream().map(EvaluationPlanItem::getEvaluatorDefinitionId).toList();
        Map<String, EvaluatorDefinition> byId = evaluatorMapper.findByIds(ids).stream()
                .collect(Collectors.toMap(EvaluatorDefinition::getId, Function.identity()));

        List<FieldViolation> violations = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            EvaluationPlanItem item = items.get(i);
            EvaluatorDefinition definition = byId.get(item.getEvaluatorDefinitionId());
            if (definition == null) {
                violations.add(new FieldViolation("items[" + i + "].evaluatorDefinitionId",
                        "算子定义不存在: " + item.getEvaluatorDefinitionId()));
            } else if (definition.getStatus() != VersionStatus.PUBLISHED) {
                violations.add(new FieldViolation("items[" + i + "].evaluatorDefinitionId",
                        "算子未发布（" + definition.getEvaluatorKey() + " v" + definition.getVersion()
                                + " 当前 " + definition.getStatus() + "），方案不能发布"));
            }
        }
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "方案引用的算子必须已发布", violations);
        }
    }

    private List<EvaluationPlanItem> insertItems(String versionId, List<PlanItems.Spec> specs, Instant now) {
        List<EvaluationPlanItem> entities = new ArrayList<>(specs.size());
        for (PlanItems.Spec spec : specs) {
            EvaluationPlanItem item = new EvaluationPlanItem();
            item.setId(Ulid.newId());
            item.setPlanVersionId(versionId);
            item.setEvaluatorDefinitionId(spec.evaluatorDefinitionId());
            item.setExecutionOrder(spec.executionOrder());
            item.setRequired(spec.required());
            item.setConfigJson(spec.config());
            item.setCreatedAt(now);
            item.setUpdatedAt(now);
            entities.add(item);
        }
        itemMapper.batchInsert(entities);
        return entities;
    }

    private JsonNode normalizeConfig(JsonNode config) {
        if (config == null || config.isNull()) {
            return objectMapper.createObjectNode();
        }
        return Inputs.object("config", config);
    }

    private EvaluationDtos.PlanVersionView toView(EvaluationPlanVersion entity, List<EvaluationPlanItem> items) {
        return EvaluationDtos.PlanVersionView.from(entity, toItemViews(items));
    }

    private List<EvaluationDtos.PlanItemView> toItemViews(List<EvaluationPlanItem> items) {
        return items.stream().map(EvaluationDtos.PlanItemView::from).toList();
    }

    private List<DatasetContentHasher.PlanItemView> toPlanItemViews(List<EvaluationPlanItem> items) {
        return items.stream()
                .map(i -> new DatasetContentHasher.PlanItemView(i.getEvaluatorDefinitionId(),
                        i.getExecutionOrder(), Boolean.TRUE.equals(i.getRequired()), i.getConfigJson()))
                .toList();
    }

    // ---- TASK-004：供实验模块读取（不跨模块直接用 Mapper） ----

    public EvaluationPlanVersion requireVersion(String id) {
        return versionMapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "EvaluationPlanVersion 不存在: " + id));
    }

    public EvaluationPlanVersion requirePublishedVersion(String id) {
        EvaluationPlanVersion version = requireVersion(id);
        if (version.getStatus() != VersionStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "EvaluationPlanVersion 不是已发布状态（当前 " + version.getStatus() + "）：" + id);
        }
        return version;
    }

    /** 方案项按执行顺序返回；启动实验时用它冻结 Job 数量。 */
    public List<EvaluationPlanItem> listItems(String planVersionId) {
        return itemMapper.listByVersion(planVersionId);
    }
}
