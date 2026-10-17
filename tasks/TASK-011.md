# TASK-011 Outbox、RabbitMQ与系统可靠性增强

前置：TASK-010。

## 必读

- docs/architecture/05-event-contract.md
- docs/architecture/06-reliability-design.md
- docs/architecture/01-system-overview.md

同时读取README、AGENTS.md、tasks/README.md及前置任务实际交付报告。数据库字段仍以database-schema.json为准。

## 实施内容

1. 在业务事务中写稳定Outbox信封，实现短租约领取、确认/路由检查、重试/失败告警。
2. 建立持久化队列、消息、manual ACK、inbox去重和不支持版本隔离；只唤醒同一数据库领取协议。
3. 保留数据库扫描恢复通道，实现消息重复、崩溃、租约、取消和导入恢复故障演练。
4. 记录指标、恢复操作手册与Outbox/Inbox保留策略，UI只展示用户可理解的等待/失败。

## 验收

- 发布成功确认丢失可重发，消费者无重复业务效果。
- DB提交后ACK前崩溃可重投，唯一结果与状态仍正确。
- MQ不可用时数据库轮询能继续，恢复后重复消息不会重跑已完成Run。
- 旧Attempt、失效发布器、毒消息有限重试、取消竞争均有确定性测试。

## 开发边界

不宣称恰好一次，不把publisher confirm等同Agent完成，不用消息代替DB状态。

## 交付

按tasks/README.md模板报告实际文件、接口/迁移差异、命令和测试数量。补齐对应OpenAPI和前端类型；保持前置测试通过。环境阻塞标为未验收，不虚构通过。仅提交本地commit，不push。任务完成即停。
