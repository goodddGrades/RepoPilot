package com.repopilot.modules.agent.service;

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
import com.repopilot.common.registry.AgentType;
import com.repopilot.common.state.LifecycleStatus;
import com.repopilot.common.state.Transitions;
import com.repopilot.common.validation.Inputs;
import com.repopilot.common.web.RequestBodies;
import com.repopilot.modules.agent.dto.AgentDtos;
import com.repopilot.modules.agent.entity.Agent;
import com.repopilot.modules.agent.mapper.AgentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Agent 身份管理。
 *
 * <p>事务用 {@link TransactionTemplate} 显式控制，而不是 {@code @Transactional}：
 * <ul>
 *   <li>业务方法内部需要"捕获唯一键冲突后另开新事务读幂等回执"，
 *       而自调用不会触发 Spring 代理，注解在这一步会静默失效；</li>
 *   <li>每处事务边界都能在代码里直接看到，评审时不必去推代理行为。</li>
 * </ul>
 * 事务仍然在服务层，Controller 不含任何业务判断。
 */
@Service
public class AgentService {

    private static final String SCOPE_CREATE = "POST /api/v1/agents";

    private final AgentMapper mapper;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public AgentService(AgentMapper mapper, IdempotencyService idempotency,
                        ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public IdempotentResult<AgentDtos.AgentView> create(JsonNode body, String idemKey) {
        String hash = IdempotencyDigest.of("POST", "/api/v1/agents", body);
        Optional<IdempotentResult<AgentDtos.AgentView>> cached =
                idempotency.replay(SCOPE_CREATE, idemKey, hash, AgentDtos.AgentView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        AgentDtos.CreateRequest request = RequestBodies.convert(objectMapper, body, AgentDtos.CreateRequest.class);
        String code = Inputs.code("code", request.code());
        String name = Inputs.required("name", request.name(), 128);
        String description = Inputs.optional("description", request.description(), 4000);
        String agentType = AgentType.require(request.agentType()).name();

        return idempotency.execute(SCOPE_CREATE, idemKey, hash, AgentDtos.AgentView.class, () ->
                tx.execute(status -> {
                    Instant now = Instant.now();
                    Agent entity = new Agent();
                    entity.setId(Ulid.newId());
                    entity.setCode(code);
                    entity.setName(name);
                    entity.setDescription(description);
                    entity.setAgentType(agentType);
                    entity.setStatus(LifecycleStatus.ACTIVE);
                    entity.setCreatedAt(now);
                    entity.setUpdatedAt(now);
                    // 不做"先查 code 是否存在"：并发下这个检查必然有窗口，
                    // 且会让"同幂等键并发"的请求拿不到对方的回执。唯一键 + 幂等重放才是正确路径。
                    mapper.insert(entity);

                    AgentDtos.AgentView view = AgentDtos.AgentView.from(entity);
                    idempotency.record(SCOPE_CREATE, idemKey, hash, "agent", entity.getId(), 201, view);
                    return IdempotentResult.created(view);
                }));
    }

    public AgentDtos.AgentView get(String id) {
        return AgentDtos.AgentView.from(requireAgent(id));
    }

    public PageResponse<AgentDtos.AgentView> list(Integer page, Integer pageSize,
                                                  String status, String keyword) {
        PageQuery query = PageQuery.of(page, pageSize);
        LifecycleStatus parsed = status == null || status.isBlank() ? null : parseStatus(status);
        String like = Inputs.likeKeyword(keyword, 64);

        List<AgentDtos.AgentView> items = mapper.search(parsed, like, query.pageSize(), query.offset())
                .stream().map(AgentDtos.AgentView::from).toList();
        long total = mapper.count(parsed, like);
        return PageResponse.of(items, query, total);
    }

    /**
     * 部分更新。
     *
     * <p>用 {@code has("字段")} 判断"是否要改"，而不是用 null 判断：
     * 否则"只想改名字"会被理解成"把描述清空"。
     */
    public IdempotentResult<AgentDtos.AgentView> patch(String id, JsonNode body, String idemKey) {
        // scope 必须带真实资源 id：否则两个不同 Agent 用同一个 key 会互相复用回执。
        String scope = "PATCH /api/v1/agents/" + id;
        String hash = IdempotencyDigest.of("PATCH", "/api/v1/agents/" + id, body);
        Optional<IdempotentResult<AgentDtos.AgentView>> cached =
                idempotency.replay(scope, idemKey, hash, AgentDtos.AgentView.class);
        if (cached.isPresent()) {
            return cached.get();
        }
        if (body != null && body.has("code")) {
            throw BusinessException.field("code", "code 创建后不可修改");
        }

        return idempotency.execute(scope, idemKey, hash, AgentDtos.AgentView.class, () ->
                tx.execute(status -> {
                    Agent current = mapper.findByIdForUpdate(id)
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
                            // ARCHIVED→ACTIVE 在这里被拒（409），不会走到 UPDATE
                            Transitions.require(current.getStatus(), requested);
                            target = requested;
                        }
                    }

                    Instant now = Instant.now();
                    mapper.update(id, name, description, target, now);

                    Agent updated = new Agent();
                    updated.setId(current.getId());
                    updated.setCode(current.getCode());
                    updated.setName(name);
                    updated.setDescription(description);
                    updated.setAgentType(current.getAgentType());
                    updated.setStatus(target);
                    updated.setCreatedAt(current.getCreatedAt());
                    updated.setUpdatedAt(now);

                    AgentDtos.AgentView view = AgentDtos.AgentView.from(updated);
                    idempotency.record(scope, idemKey, hash, "agent", id, 200, view);
                    return IdempotentResult.ok(view);
                }));
    }

    Agent requireAgent(String id) {
        return mapper.findById(id).orElseThrow(() -> notFound(id));
    }

    static BusinessException notFound(String id) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Agent 不存在: " + id);
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
