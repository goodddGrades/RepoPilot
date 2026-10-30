package com.repopilot.modules.evaluationconfig.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.api.PageQuery;
import com.repopilot.common.api.PageResponse;
import com.repopilot.common.id.Ulid;
import com.repopilot.common.idempotency.IdempotencyDigest;
import com.repopilot.common.idempotency.IdempotencyService;
import com.repopilot.common.idempotency.IdempotentResult;
import com.repopilot.common.validation.Inputs;
import com.repopilot.common.web.RequestBodies;
import com.repopilot.modules.evaluationconfig.dto.EvaluationDtos;
import com.repopilot.modules.evaluationconfig.entity.EvaluationPlan;
import com.repopilot.modules.evaluationconfig.mapper.EvaluationPlanMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 评测方案身份管理。
 *
 * <p>方案没有状态列，也没有 PATCH：停用发生在"版本"层。
 * 这样历史实验引用的方案身份始终可读，不会因为某次"归档方案"而查不到名字。
 */
@Service
public class EvaluationPlanService {

    private static final String SCOPE_CREATE = "POST /api/v1/evaluation-plans";

    private final EvaluationPlanMapper mapper;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public EvaluationPlanService(EvaluationPlanMapper mapper, IdempotencyService idempotency,
                                 ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public IdempotentResult<EvaluationDtos.PlanView> create(JsonNode body, String idemKey) {
        String hash = IdempotencyDigest.of("POST", "/api/v1/evaluation-plans", body);
        Optional<IdempotentResult<EvaluationDtos.PlanView>> cached =
                idempotency.replay(SCOPE_CREATE, idemKey, hash, EvaluationDtos.PlanView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        EvaluationDtos.CreatePlanRequest request =
                RequestBodies.convert(objectMapper, body, EvaluationDtos.CreatePlanRequest.class);
        String code = Inputs.code("code", request.code());
        String name = Inputs.required("name", request.name(), 128);
        String description = Inputs.optional("description", request.description(), 4000);

        return idempotency.execute(SCOPE_CREATE, idemKey, hash, EvaluationDtos.PlanView.class, () ->
                tx.execute(status -> {
                    Instant now = Instant.now();
                    EvaluationPlan entity = new EvaluationPlan();
                    entity.setId(Ulid.newId());
                    entity.setCode(code);
                    entity.setName(name);
                    entity.setDescription(description);
                    entity.setCreatedAt(now);
                    entity.setUpdatedAt(now);
                    mapper.insert(entity);

                    EvaluationDtos.PlanView view = EvaluationDtos.PlanView.from(entity);
                    idempotency.record(SCOPE_CREATE, idemKey, hash, "evaluation_plan", entity.getId(), 201, view);
                    return IdempotentResult.created(view);
                }));
    }

    public EvaluationDtos.PlanView get(String id) {
        return EvaluationDtos.PlanView.from(mapper.findById(id)
                .orElseThrow(() -> EvaluationPlanGuard.planNotFound(id)));
    }

    public PageResponse<EvaluationDtos.PlanView> list(Integer page, Integer pageSize, String keyword) {
        PageQuery query = PageQuery.of(page, pageSize);
        String like = Inputs.likeKeyword(keyword, 64);
        List<EvaluationDtos.PlanView> items = mapper.search(like, query.pageSize(), query.offset())
                .stream().map(EvaluationDtos.PlanView::from).toList();
        return PageResponse.of(items, query, mapper.count(like));
    }
}
