"""Evaluator 目录（V1 不实现真实 Judge）。

后续任务（TASK-007 / TASK-008）将在此注册规则算子、轨迹算子、成本算子、
测试算子与 LLM Judge，并统一输出：

    {"job_id": ..., "status": "COMPLETED", "verdict": "PASS", "score": 1.0, "reason": ...}

约束：
* 严格区分执行状态（PENDING/RUNNING/COMPLETED/FAILED/SKIPPED）与
  检测结论（PASS/FAIL/INDETERMINATE/NOT_APPLICABLE）；
* 缺少证据时必须返回 INDETERMINATE / NOT_APPLICABLE，不得判为失败；
* 未配置真实模型时，模拟评分必须明确标记，不能伪装成真实质量结论。
"""
