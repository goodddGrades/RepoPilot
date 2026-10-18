package com.repopilot.common.state;

/**
 * {@code evaluation_results.generated_by} 的取值集合。
 *
 * <p>必须与数据库 CHECK 完全一致：RULE, TEST, LLM, MOCK。
 * 集成测试读取 information_schema 中的 CHECK 定义逐值比对，
 * 改这里而不改字典会立刻失败，反之亦然。
 */
@DictionaryCheck(table = "evaluation_results", column = "generated_by")
public enum GeneratedBy {

    RULE,
    TEST,
    LLM,
    MOCK;
}
