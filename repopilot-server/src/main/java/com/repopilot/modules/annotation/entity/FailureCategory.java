package com.repopilot.modules.annotation.entity;

/**
 * 失败归因的注册分类（TASK-009）。
 *
 * <p>只允许注册过的分类写入 {@code annotation_records.failure_category}。
 * {@code UNKNOWN} 是**显式的"不归因"**，不是可以随便填的兜底：
 * 证据不足时如实写 UNKNOWN，而不是被迫选一个假原因把数据污染进报表。
 *
 * <p>{@code EVALUATOR} 表示"问题出在评测本身"（算子配置错、判据不合理），
 * 与被测 Agent 的缺陷是两回事——报告里必须能区分。
 */
public enum FailureCategory {

    MODEL_REASONING("推理错误"),
    TOOL_USE("工具使用"),
    RETRIEVAL("检索"),
    CONTEXT("上下文"),
    ENVIRONMENT("环境"),
    DATA_QUALITY("数据质量"),
    EVALUATOR("评测问题"),
    OTHER("其他"),
    /** 证据不足，明确不归因；不是"随便选一个"。 */
    UNKNOWN("未归因");

    private final String displayName;

    FailureCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** 解析外部输入；未知值返回 {@code null} 由调用方给出可读错误，而不是静默降级。 */
    public static FailureCategory parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (FailureCategory category : values()) {
            if (category.name().equalsIgnoreCase(value.trim())) {
                return category;
            }
        }
        return null;
    }
}