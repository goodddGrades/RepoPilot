# TASK-004 在线Mock执行与Worker基础协议

前置：TASK-003。

## 必读

- docs/architecture/04-api-contract.md
- docs/architecture/06-reliability-design.md
- docs/modules/online-evaluation.md
- docs/architecture/05-event-contract.md

同时读取README、AGENTS.md、tasks/README.md及前置任务实际交付报告。数据库字段仍以database-schema.json为准。

## 实施内容

1. 实现实验草稿、提交、取消与Run槽位生成，所有版本/数量校验齐全。
2. 实现数据库轮询领取、Attempt、Token摘要、心跳、硬截止、退避、回收；Python Worker通过HTTP交互。
3. Mock Runner覆盖成功、业务失败、技术异常、超时和可重试错误；事件接收与Artifact基础上传按完成清单封口。
4. 合法完成后EVALUATING并幂等生成PENDING Job，规则引擎留到007；前端如实展示待评测。

## 验收

- 2Target×3Case×2重复=12Run，基础设施重试不增加Run。
- 同一Run只能有一个有效Attempt；失效Worker不能续租、写事件、写成功。
- 重复start/complete无重复；完成丢响应可同正文重放。
- 数据库提交后服务崩溃重启，扫描能推进PENDING/QUEUED，取消与回调竞争不复活。

## 开发边界

不实现真实Codex参数假设，不用RabbitMQ，不给Job写假结果。

## 交付

按tasks/README.md模板报告实际文件、接口/迁移差异、命令和测试数量。补齐对应OpenAPI和前端类型；保持前置测试通过。环境阻塞标为未验收，不虚构通过。仅提交本地commit，不push。任务完成即停。
