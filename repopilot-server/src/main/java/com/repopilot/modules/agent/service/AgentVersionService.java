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
import com.repopilot.common.state.LifecycleStatus;
import com.repopilot.common.state.RunnerType;
import com.repopilot.common.state.Transitions;
import com.repopilot.common.state.VersionStatus;
import com.repopilot.common.validation.Inputs;
import com.repopilot.common.web.RequestBodies;
import com.repopilot.modules.agent.dto.AgentDtos;
import com.repopilot.modules.agent.entity.Agent;
import com.repopilot.modules.agent.entity.AgentVersion;
import com.repopilot.modules.agent.mapper.AgentMapper;
import com.repopilot.modules.agent.mapper.AgentVersionMapper;
import com.repopilot.modules.dataset.service.DatasetContentHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Agent 配置版本：分配版本号、草稿编辑、发布与归档。
 *
 * <p>加锁顺序统一为 <b>Agent 行 → 版本行</b>。版本编辑若反过来先锁版本再锁 Agent，
 * 与"创建版本"（先锁 Agent）构成相反顺序，并发时有死锁风险。
 */
@Service
public class AgentVersionService {

    private final AgentMapper agentMapper;
    private final AgentVersionMapper versionMapper;
    private final IdempotencyService idempotency;
    private final DatasetContentHasher hasher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public AgentVersionService(AgentMapper agentMapper, AgentVersionMapper versionMapper,
                               IdempotencyService idempotency, DatasetContentHasher hasher,
                               ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.agentMapper = agentMapper;
        this.versionMapper = versionMapper;
        this.idempotency = idempotency;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public IdempotentResult<AgentDtos.VersionView> create(String agentId, JsonNode body, String idemKey) {
        String path = "/api/v1/agents/" + agentId + "/versions";
        String scope = "POST " + path;
        String hash = IdempotencyDigest.of("POST", path, body);
        Optional<IdempotentResult<AgentDtos.VersionView>> cached =
                idempotency.replay(scope, idemKey, hash, AgentDtos.VersionView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        AgentDtos.CreateVersionRequest request =
                RequestBodies.convert(objectMapper, body, AgentDtos.CreateVersionRequest.class);
        RunnerType runnerType = parseRunnerType(request.runnerType());
        JsonNode config = Inputs.object("config", request.config());

        return idempotency.execute(scope, idemKey, hash, AgentDtos.VersionView.class, () ->
                tx.execute(status -> {
                    // 锁父 Agent 行：版本号分配与"父是否 ACTIVE"的判断必须串行
                    Agent agent = agentMapper.findByIdForUpdate(agentId)
                            .orElseThrow(() -> AgentService.notFound(agentId));
                    requireActive(agent);

                    int version = versionMapper.nextVersion(agentId);
                    Instant now = Instant.now();

                    AgentVersion entity = new AgentVersion();
                    entity.setId(Ulid.newId());
                    entity.setAgentId(agentId);
                    entity.setVersion(version);
                    entity.setRunnerType(runnerType);
                    entity.setConfigJson(config);
                    entity.setConfigHash(hasher.hashConfig(config));
                    entity.setStatus(VersionStatus.DRAFT);
                    entity.setPublishedAt(null);
                    entity.setCreatedAt(now);
                    entity.setUpdatedAt(now);
                    versionMapper.insert(entity);

                    AgentDtos.VersionView view = AgentDtos.VersionView.from(entity);
                    idempotency.record(scope, idemKey, hash, "agent_version", entity.getId(), 201, view);
                    return IdempotentResult.created(view);
                }));
    }

    public PageResponse<AgentDtos.VersionView> list(String agentId, Integer page, Integer pageSize) {
        if (agentMapper.findById(agentId).isEmpty()) {
            throw AgentService.notFound(agentId);
        }
        PageQuery query = PageQuery.of(page, pageSize);
        List<AgentDtos.VersionView> items = versionMapper
                .listByAgent(agentId, query.pageSize(), query.offset())
                .stream().map(AgentDtos.VersionView::from).toList();
        return PageResponse.of(items, query, versionMapper.countByAgent(agentId));
    }

    public AgentDtos.VersionView get(String versionId) {
        return AgentDtos.VersionView.from(requireVersion(versionId));
    }

    public IdempotentResult<AgentDtos.VersionView> update(String versionId, JsonNode body, String idemKey) {
        String scope = "PUT /api/v1/agent-versions/" + versionId;
        String hash = IdempotencyDigest.of("PUT", "/api/v1/agent-versions/" + versionId, body);
        Optional<IdempotentResult<AgentDtos.VersionView>> cached =
                idempotency.replay(scope, idemKey, hash, AgentDtos.VersionView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        AgentDtos.CreateVersionRequest request =
                RequestBodies.convert(objectMapper, body, AgentDtos.CreateVersionRequest.class);
        RunnerType runnerType = parseRunnerType(request.runnerType());
        JsonNode config = Inputs.object("config", request.config());
        String configHash = hasher.hashConfig(config);

        return idempotency.execute(scope, idemKey, hash, AgentDtos.VersionView.class, () ->
                tx.execute(status -> {
                    AgentVersion current = lockVersionWithAgent(versionId);
                    if (current.getStatus() != VersionStatus.DRAFT) {
                        throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                                "版本已发布或已归档，不能修改");
                    }

                    Instant now = Instant.now();
                    versionMapper.updateConfig(versionId, config, configHash, runnerType, now);

                    AgentDtos.VersionView view = new AgentDtos.VersionView(versionId, current.getAgentId(),
                            current.getVersion(), runnerType, config, current.getStatus(),
                            current.getPublishedAt(), current.getCreatedAt(), now);
                    idempotency.record(scope, idemKey, hash, "agent_version", versionId, 200, view);
                    return IdempotentResult.ok(view);
                }));
    }

    public IdempotentResult<AgentDtos.VersionView> publish(String versionId, String idemKey) {
        String scope = "POST /api/v1/agent-versions/" + versionId + "/publish";
        String hash = IdempotencyDigest.of("POST", "/api/v1/agent-versions/" + versionId + "/publish", null);
        Optional<IdempotentResult<AgentDtos.VersionView>> cached =
                idempotency.replay(scope, idemKey, hash, AgentDtos.VersionView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        return idempotency.execute(scope, idemKey, hash, AgentDtos.VersionView.class, () ->
                tx.execute(status -> {
                    AgentVersion current = lockVersionWithAgent(versionId);
                    Transitions.require(current.getStatus(), VersionStatus.PUBLISHED);

                    Instant now = Instant.now();
                    versionMapper.updateStatus(versionId, VersionStatus.PUBLISHED, now, now);
                    AgentDtos.VersionView view = copyWithStatus(current, VersionStatus.PUBLISHED, now, now);
                    idempotency.record(scope, idemKey, hash, "agent_version", versionId, 200, view);
                    return IdempotentResult.ok(view);
                }));
    }

    public IdempotentResult<AgentDtos.VersionView> archive(String versionId, String idemKey) {
        String scope = "POST /api/v1/agent-versions/" + versionId + "/archive";
        String hash = IdempotencyDigest.of("POST", "/api/v1/agent-versions/" + versionId + "/archive", null);
        Optional<IdempotentResult<AgentDtos.VersionView>> cached =
                idempotency.replay(scope, idemKey, hash, AgentDtos.VersionView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        return idempotency.execute(scope, idemKey, hash, AgentDtos.VersionView.class, () ->
                tx.execute(status -> {
                    AgentVersion current = lockVersionWithAgent(versionId);
                    // 允许 DRAFT 与 PUBLISHED：草稿可以丢弃，已发布版本归档保留历史。
                    // published_at 保持原值，归档不是"重新发布"。
                    Transitions.require(current.getStatus(), VersionStatus.ARCHIVED);

                    Instant now = Instant.now();
                    versionMapper.updateStatus(versionId, VersionStatus.ARCHIVED,
                            current.getPublishedAt(), now);
                    AgentDtos.VersionView view = copyWithStatus(current, VersionStatus.ARCHIVED,
                            current.getPublishedAt(), now);
                    idempotency.record(scope, idemKey, hash, "agent_version", versionId, 200, view);
                    return IdempotentResult.ok(view);
                }));
    }

    /**
     * 统一加锁顺序：先读版本拿 agentId（快照读，不加锁），再锁 Agent，最后锁版本。
     *
     * <p>不能直接先锁版本：创建版本时先锁的是 Agent 行，两条路径的加锁顺序相反就会死锁。
     */
    private AgentVersion lockVersionWithAgent(String versionId) {
        AgentVersion snapshot = requireVersion(versionId);
        Agent agent = agentMapper.findByIdForUpdate(snapshot.getAgentId())
                .orElseThrow(() -> AgentService.notFound(snapshot.getAgentId()));
        // 归档的 Agent 不允许再改/发布其版本；读取历史不受影响
        if (agent.getStatus() != LifecycleStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Agent 已归档，不能再修改或发布其版本");
        }
        return versionMapper.findByIdForUpdate(versionId)
                .orElseThrow(() -> notFound(versionId));
    }

    /** 读取任意状态的配置版本；TASK-004 起也供实验模块读取（不跨模块用 Mapper）。 */
    public AgentVersion requireVersion(String id) {
        return versionMapper.findById(id).orElseThrow(() -> notFound(id));
    }

    private void requireActive(Agent agent) {
        if (agent.getStatus() != LifecycleStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Agent 已归档，不能创建新版本");
        }
    }

    private AgentDtos.VersionView copyWithStatus(AgentVersion current, VersionStatus status,
                                                 Instant publishedAt, Instant updatedAt) {
        return new AgentDtos.VersionView(current.getId(), current.getAgentId(), current.getVersion(),
                current.getRunnerType(), current.getConfigJson(), status, publishedAt,
                current.getCreatedAt(), updatedAt);
    }

    private RunnerType parseRunnerType(String value) {
        if (value == null || value.isBlank()) {
            throw BusinessException.field("runnerType", "runnerType 不能为空");
        }
        try {
            return RunnerType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.field("runnerType",
                    "不支持的 runnerType: " + value + "，可选值: MOCK, CODEX, CUSTOM");
        }
    }

    static BusinessException notFound(String id) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "AgentVersion 不存在: " + id);
    }

    // ---- TASK-004：供实验模块读取（不跨模块直接用 Mapper） ----

    public AgentVersion requirePublishedVersion(String id) {
        AgentVersion version = requireVersion(id);
        if (version.getStatus() != VersionStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "AgentVersion 不是已发布状态（当前 " + version.getStatus() + "）：" + id);
        }
        return version;
    }
}
