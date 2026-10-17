# TASK-008 LLM Judge、测试与真实Runner适配

前置：TASK-007。

## 必读

- docs/modules/evaluation-engine.md
- docs/modules/online-evaluation.md
- docs/modules/trace-pipeline.md

同时读取README、AGENTS.md、tasks/README.md及前置任务实际交付报告。数据库字段仍以database-schema.json为准。

## 实施内容

1. 实现固定rubric的Judge、结构化输出校验、超时、有限重试、证据与全尝试成本。
2. 以本地实际可用Codex接口实现注册适配器，记录版本与代码提交，不假定不存在的SDK。
3. 可信测试命令算子实现退出/断言失败与技术超时分离；清理进程组和工作区。
4. 真实调用配置为可选，CI使用确定性供应商桩；用一小批人工标注样本检查Judge分歧。

## 验收

- 非法JSON/限流为技术错误，证据不足为INDETERMINATE，不适用为NOT_APPLICABLE。
- 含提示注入的被测输出不能改变Judge规则，所有请求和响应脱敏。
- 真实Runner冒烟有命令/版本/输出证据；缺访问条件时列为未验收，Mock仍可闭环。
- 成本覆盖所有Judge重试，未知费用不当零。

## 开发边界

不强制引入Ragas/Langfuse；不声称可信代码隔离已经等于生产安全沙箱。

## 交付

按tasks/README.md模板报告实际文件、接口/迁移差异、命令和测试数量。补齐对应OpenAPI和前端类型；保持前置测试通过。环境阻塞标为未验收，不虚构通过。仅提交本地commit，不push。任务完成即停。
