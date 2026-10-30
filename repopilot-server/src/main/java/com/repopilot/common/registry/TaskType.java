package com.repopilot.common.registry;

import com.repopilot.common.exception.BusinessException;

/**
 * 数据集样本的任务类型白名单。
 *
 * <p>{@code dataset_cases.task_type} 同样是 {@code VARCHAR(32)} 且没有 CHECK，
 * 校验只在服务层。样本类型决定了后续用哪个 Runner 执行，写错会等到 TASK-004
 * 真正跑评测时才暴露，所以必须在这里拦住。
 */
public enum TaskType {

    /** 交给 Agent 完成的多步任务。 */
    AGENT_TASK,
    /** 单次大模型调用。 */
    LLM_CALL;

    public static TaskType require(String value) {
        if (value == null || value.isBlank()) {
            throw BusinessException.field("taskType", "taskType 不能为空");
        }
        for (TaskType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        throw BusinessException.field("taskType", "不支持的 taskType: " + value
                + "，可选值: AGENT_TASK, LLM_CALL");
    }
}
