package com.repopilot.common.state;

/**
 * {@code outbox_events.status} 的取值集合。
 *
 * <p>必须与数据库 CHECK 完全一致：PENDING, PUBLISHED, FAILED。
 * 集成测试读取 information_schema 中的 CHECK 定义逐值比对，
 * 改这里而不改字典会立刻失败，反之亦然。
 */
@DictionaryCheck(table = "outbox_events", column = "status")
public enum OutboxStatus {

    PENDING,
    PUBLISHED,
    FAILED;
}
