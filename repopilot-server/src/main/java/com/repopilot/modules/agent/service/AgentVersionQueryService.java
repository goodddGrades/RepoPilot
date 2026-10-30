package com.repopilot.modules.agent.service;

import com.repopilot.modules.agent.entity.Agent;
import com.repopilot.modules.agent.entity.AgentVersion;
import com.repopilot.modules.agent.mapper.AgentMapper;
import com.repopilot.modules.agent.mapper.AgentVersionMapper;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Agent 版本信息的只读门面（TASK-010 新增）。
 *
 * <p>报告要显示"这个 Target 用的是哪个 Agent 版本"，但 Target 上的
 * {@code agent_version_id} 可以为空（离线导入的 Target 没有平台侧 Agent 版本）。
 * 报告必须把这种情况显示为"版本未知"并明确**不声称受控因果对比**，
 * 而不是显示一个空字符串或随便挑一个版本。
 */
@Service
public class AgentVersionQueryService {

    /** 版本的可读标签；{@code agentVersionId} 为空时标签也是空，由调用方显示"未知"。 */
    public record Label(String agentVersionId, String label) {
    }

    private final AgentVersionMapper versionMapper;
    private final AgentMapper agentMapper;

    public AgentVersionQueryService(AgentVersionMapper versionMapper, AgentMapper agentMapper) {
        this.versionMapper = versionMapper;
        this.agentMapper = agentMapper;
    }

    /**
     * 版本标签，形如 {@code my-agent v3}。
     *
     * <p>{@code agentVersionId} 为空时返回 {@link Label} 且两者都为 {@code null}——
     * "未知版本"必须能和"版本名为空字符串"区分开。
     */
    public Label labelOf(String agentVersionId) {
        if (agentVersionId == null || agentVersionId.isBlank()) {
            return new Label(null, null);
        }
        Optional<AgentVersion> version = versionMapper.findById(agentVersionId);
        if (version.isEmpty()) {
            // 引用了但查不到：数据不一致，如实标成未知而不是编一个标签出来
            return new Label(agentVersionId, null);
        }
        String code = agentMapper.findById(version.get().getAgentId())
                .map(Agent::getCode).orElse(null);
        Integer number = version.get().getVersion();
        String label = code == null ? "v" + number : code + " v" + number;
        return new Label(agentVersionId, label);
    }
}