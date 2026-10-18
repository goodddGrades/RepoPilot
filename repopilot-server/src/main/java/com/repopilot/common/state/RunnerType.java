package com.repopilot.common.state;

/**
 * {@code agent_versions.runner_type} 的取值集合。
 *
 * <p>必须与数据库 CHECK 完全一致：MOCK, CODEX, CUSTOM。
 * 集成测试读取 information_schema 中的 CHECK 定义逐值比对，
 * 改这里而不改字典会立刻失败，反之亦然。
 */
@DictionaryCheck(table = "agent_versions", column = "runner_type")
public enum RunnerType {

    MOCK,
    CODEX,
    CUSTOM;
}
