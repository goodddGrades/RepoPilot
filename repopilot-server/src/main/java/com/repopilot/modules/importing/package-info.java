/**
 * 离线导入模块（TASK-002 仅落地持久化模型）。
 *
 * <p>职责边界：ImportBatch / ImportRecord / ImportError 的记账，两类导入
 * （`DATASET_CASES` 写草稿数据集、`OFFLINE_RUNS` 写离线 Run）共用同一套批次与行级记录。
 *
 * <p>导入必须幂等：同一文件同一行重放返回 REUSED 而不是重复创建。
 * 导入器可以按规则生成 DatasetCase，但不得伪造标准答案、工具轨迹或任务成功状态。
 */
package com.repopilot.modules.importing;
