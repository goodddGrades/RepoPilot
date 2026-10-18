package com.repopilot.common.state;

/**
 * {@code agents.status / datasets.status} 的取值集合。
 *
 * <p>必须与数据库 CHECK 完全一致：ACTIVE, ARCHIVED。
 * 集成测试读取 information_schema 中的 CHECK 定义逐值比对，
 * 改这里而不改字典会立刻失败，反之亦然。
 */
@DictionaryCheck(table = "agents", column = "status")
@DictionaryCheck(table = "datasets", column = "status")
public enum LifecycleStatus {

    ACTIVE,
    ARCHIVED;
}
