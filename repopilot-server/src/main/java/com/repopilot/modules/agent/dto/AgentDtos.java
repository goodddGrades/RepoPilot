package com.repopilot.modules.agent.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.common.state.LifecycleStatus;
import com.repopilot.common.state.RunnerType;
import com.repopilot.common.state.VersionStatus;
import com.repopilot.modules.agent.entity.Agent;
import com.repopilot.modules.agent.entity.AgentVersion;

import java.time.Instant;

/**
 * Agent 模块的请求/响应形状。
 *
 * <p>响应字段与 {@code 04-api-contract.md} 逐字对应，不直接把实体返回：
 * 实体里有 {@code config_json} / {@code config_hash} 这类内部列名，
 * 直接暴露会让接口字段随数据库改名而变。
 */
public final class AgentDtos {

    private AgentDtos() {
    }

    /** 创建 Agent。{@code agentType} 走服务层白名单。 */
    public record CreateRequest(String code, String name, String description, String agentType) {
    }

    /**
     * PATCH 的部分更新。
     *
     * <p>用 {@link JsonNode} 而不是 record：需要区分"字段没传"与"显式传 null"。
     * 用 record 时两者都是 null，会把"只想改名字"变成"把描述清空"。
     */
    public record CreateVersionRequest(String runnerType, JsonNode config) {
    }

    public record AgentView(String id, String code, String name, String description,
                            String agentType, LifecycleStatus status,
                            Instant createdAt, Instant updatedAt) {

        public static AgentView from(Agent entity) {
            return new AgentView(entity.getId(), entity.getCode(), entity.getName(), entity.getDescription(),
                    entity.getAgentType(), entity.getStatus(), entity.getCreatedAt(), entity.getUpdatedAt());
        }
    }

    public record VersionView(String id, String agentId, Integer version, RunnerType runnerType,
                              JsonNode config, VersionStatus status, Instant publishedAt,
                              Instant createdAt, Instant updatedAt) {

        public static VersionView from(AgentVersion entity) {
            return new VersionView(entity.getId(), entity.getAgentId(), entity.getVersion(),
                    entity.getRunnerType(), entity.getConfigJson(), entity.getStatus(),
                    entity.getPublishedAt(), entity.getCreatedAt(), entity.getUpdatedAt());
        }
    }
}
