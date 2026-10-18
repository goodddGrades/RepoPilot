# TASK-007 统一规则评测引擎

前置：TASK-006。

## 必读

- docs/modules/evaluation-engine.md
- docs/architecture/06-reliability-design.md
- docs/architecture/04-api-contract.md

同时读取README、AGENTS.md、tasks/README.md及前置任务实际交付报告。数据库字段仍以database-schema.json为准。

## 实施内容

1. 实现规则、Trace和成本算子，按注册key/version解析，不接受任意远程代码。
2. Job按executionOrder串行、Run间并行；实现JobAttempt领取/心跳/回收/重试与唯一最终Result。
3. 封口输入摘要、acceptedAttempt隔离、缺失/不适用分支和证据校验。
4. 实现Run与Experiment终态聚合，前端展示技术异常与质量失败的区别。

## 验收

- Agent业务失败可Run COMPLETED+taskOutcome FAILURE；算子FAIL可Job COMPLETED。
- 缺expected或成本不自动0分/PASS；required技术失败导致Run FAILED。
- 重复回调只一份Result，旧JobAttempt不能覆写；重试历史保留。
- 在线与离线相同规范输入产生同口径规则结果，覆盖率分母一致。

## 开发边界

不接LLM Judge，不覆盖人工标注，不引入通用DAG。

## 交付

按tasks/README.md模板报告实际文件、接口/迁移差异、命令和测试数量。补齐对应OpenAPI和前端类型；保持前置测试通过。环境阻塞标为未验收，不虚构通过。仅提交本地commit，不push。任务完成即停。
