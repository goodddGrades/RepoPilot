/**
 * 报告读取视图（TASK-010 实现，不属于独立领域模块）。
 *
 * <p>报告不是新的聚合：它只读取 `experiments`、`evaluation_batches`、`evaluation_jobs`、
 * `evaluation_results` 与标注结果，按批次聚合出指标。因此报告代码归属于 {@code experiment}
 * 模块的读侧，数据访问通过 experiment / evaluation 模块暴露的查询接口完成，
 * 不在这里另建一套 Mapper 直接跨表 join，也不为报告新建业务表。
 *
 * <p>本包在 TASK-002 只保留边界说明，不放入任何实现；等 TASK-010 需要时再落地。
 * 保留说明而不是删除空目录，是为了让"报告不属于独立模块"这个决定有据可查。
 */
package com.repopilot.modules.report;
