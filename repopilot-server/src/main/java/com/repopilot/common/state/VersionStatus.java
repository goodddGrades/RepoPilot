package com.repopilot.common.state;

/**
 * {@code agent_versions.status / dataset_versions.status / evaluator_definitions.status / evaluation_plan_versions.status} 的取值集合。
 *
 * <p>必须与数据库 CHECK 完全一致：DRAFT, PUBLISHED, ARCHIVED。
 * 集成测试读取 information_schema 中的 CHECK 定义逐值比对，
 * 改这里而不改字典会立刻失败，反之亦然。
 */
@DictionaryCheck(table = "agent_versions", column = "status")
@DictionaryCheck(table = "dataset_versions", column = "status")
@DictionaryCheck(table = "evaluator_definitions", column = "status")
@DictionaryCheck(table = "evaluation_plan_versions", column = "status")
public enum VersionStatus {

    DRAFT,
    PUBLISHED,
    ARCHIVED;
}
