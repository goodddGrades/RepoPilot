package com.repopilot.common.state;

/**
 * {@code annotation_tasks.status} 的取值集合。
 *
 * <p>必须与数据库 CHECK 完全一致：PENDING, CLAIMED, SUBMITTED, REVIEWED, RETURNED, CANCELLED。
 * 集成测试读取 information_schema 中的 CHECK 定义逐值比对，
 * 改这里而不改字典会立刻失败，反之亦然。
 */
@DictionaryCheck(table = "annotation_tasks", column = "status")
public enum AnnotationTaskStatus {

    PENDING,
    CLAIMED,
    SUBMITTED,
    REVIEWED,
    RETURNED,
    CANCELLED;
}
