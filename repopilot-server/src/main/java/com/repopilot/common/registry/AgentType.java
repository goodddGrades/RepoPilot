package com.repopilot.common.registry;

import com.repopilot.common.exception.BusinessException;

/**
 * Agent 类型白名单。
 *
 * <p>{@code agents.agent_type} 在数据库里是 {@code VARCHAR(32)} 且**没有 CHECK**，
 * 字典注明"服务层白名单"。也就是说：这是唯一挡住非法值的地方，
 * 必须有单元测试覆盖"未知值被拒绝"，否则脏值会静默进库。
 */
public enum AgentType {

    /** 对话/通用型 Agent。 */
    MOCK,
    /** 代码类 Agent。 */
    CODING;

    public static AgentType require(String value) {
        if (value == null || value.isBlank()) {
            throw BusinessException.field("agentType", "agentType 不能为空");
        }
        for (AgentType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        // 大小写敏感：数据库里存的是大写枚举名，接受 "mock" 会让同一个类型出现两种写法。
        throw BusinessException.field("agentType", "不支持的 agentType: " + value
                + "，可选值: MOCK, CODING");
    }
}
