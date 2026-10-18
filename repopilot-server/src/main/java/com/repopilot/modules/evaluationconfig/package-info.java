/**
 * 评测方案配置模块（TASK-002 仅落地持久化模型）。
 *
 * <p>职责边界：EvaluatorDefinition（算子定义）、EvaluationPlan 及其版本与版本项。
 * 方案版本发布后不可原地修改，重评必须新建版本并配合新批次。
 *
 * <p>本模块不执行算子、不调度 Job；执行与结果属于 {@code evaluation} 模块。
 */
package com.repopilot.modules.evaluationconfig;
