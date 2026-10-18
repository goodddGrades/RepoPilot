package com.repopilot.common.registry;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 这两个注册表是"数据库没有 CHECK 时唯一的防线"。
 *
 * <p>{@code agents.agent_type} 与 {@code dataset_cases.task_type} 都是 VARCHAR(32)、
 * 没有 CHECK，字典注明服务层白名单。如果这里放行未知值，脏数据会静默进库，
 * 等到 TASK-004 真正执行时才炸，届时还要回头清洗。
 */
class RegistryTest {

    @Test
    void knownAgentTypesShouldBeAccepted() {
        assertThat(AgentType.require("MOCK")).isEqualTo(AgentType.MOCK);
        assertThat(AgentType.require("CODING")).isEqualTo(AgentType.CODING);
    }

    @Test
    void unknownAgentTypeShouldBeRejected() {
        assertThatThrownBy(() -> AgentType.require("HUMAN"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR))
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrors().getFirst().field())
                        .isEqualTo("agentType"));
    }

    @Test
    void agentTypeShouldBeCaseSensitive() {
        // 库里存的是大写枚举名；接受 "mock" 会让同一类型出现两种写法
        assertThatThrownBy(() -> AgentType.require("mock")).isInstanceOf(BusinessException.class);
    }

    @Test
    void blankAgentTypeShouldBeRejected() {
        assertThatThrownBy(() -> AgentType.require("  ")).isInstanceOf(BusinessException.class);
    }

    @Test
    void knownTaskTypesShouldBeAccepted() {
        assertThat(TaskType.require("AGENT_TASK")).isEqualTo(TaskType.AGENT_TASK);
        assertThat(TaskType.require("LLM_CALL")).isEqualTo(TaskType.LLM_CALL);
    }

    @Test
    void unknownTaskTypeShouldBeRejected() {
        assertThatThrownBy(() -> TaskType.require("RAG"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("taskType");
    }
}
