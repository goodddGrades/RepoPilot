# TASK-009 人工标注与失败归因

前置：TASK-007；顺序交付在008后。

## 必读

- docs/modules/annotation-system.md
- docs/architecture/04-api-contract.md
- docs/modules/frontend-design.md

同时读取README、AGENTS.md、tasks/README.md及前置任务实际交付报告。数据库字段仍以database-schema.json为准。

## 实施内容

1. 实现创建、领取、提交、审核、退回和取消，所有动作带expectedVersion与幂等键。
2. 状态更新和追加AnnotationRecord同事务，审核记录固定认可的提交内容。
3. 失败类别注册、证据校验、开发测试身份显示，Run详情和工作台联调。

## 验收

- 并发领取仅一个成功；版本冲突409，原输入可保留。
- 重复提交不重复历史，退回重提保留全部记录。
- 原EvaluationResult保持不变；REVIEWED视图只取有效审核记录。
- 无真实鉴权时页面明确开发模式，不允许随意提交operatorId冒充身份。

## 开发边界

不开发完整组织权限和生产SSO，不覆盖自动结果。

## 交付

按tasks/README.md模板报告实际文件、接口/迁移差异、命令和测试数量。补齐对应OpenAPI和前端类型；保持前置测试通过。环境阻塞标为未验收，不虚构通过。仅提交本地commit，不push。任务完成即停。
