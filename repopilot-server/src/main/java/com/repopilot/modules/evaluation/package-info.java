/**
 * 评测模块（V1 未实现）。
 *
 * <p>职责边界（后续任务实现）：EvaluatorDefinition 注册、EvaluationPlan 配置、
 * EvaluationJob 调度与 EvaluationResult 落库。
 *
 * <p>必须严格区分「执行状态」（PENDING/RUNNING/COMPLETED/FAILED/SKIPPED）与
 * 「检测结论」（PASS/FAIL/INDETERMINATE/NOT_APPLICABLE）。
 */
package com.repopilot.modules.evaluation;
