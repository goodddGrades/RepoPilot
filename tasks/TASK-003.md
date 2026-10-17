# TASK-003 Agent、数据集、评测方案与版本管理

前置：TASK-002。

## 必读

- docs/architecture/02-domain-model.md
- docs/architecture/04-api-contract.md
- docs/modules/frontend-design.md

同时读取README、AGENTS.md、tasks/README.md及前置任务实际交付报告。数据库字段仍以database-schema.json为准。

## 实施内容

1. 实现Agent/Dataset身份与版本管理、样本编辑、Plan/Item草稿编辑与发布；算子定义按固定注册清单种子导入，先提供Mock/规则算子元信息。
2. 发布锁版本行并计算内容摘要，草稿子项修改也锁父版本；发布后只归档或复制新版本。版本序号由后端并发安全分配。
3. 实现API幂等回执，创建与publish同事务保存成功响应；同key异正文409。
4. Vue列表/详情/草稿表单/发布展示真实API，版本不可变提示、加载与错误态完整。

## 验收

- 并发发布与编辑不能出现已发布内容被修改；并发创建版本不重复。
- 重复创建/发布不会重复资源；同key异body报409。
- 线上Target所需配置均可发布，发布空数据集/缺少算子方案拒绝。
- Java/前端联调完成，现有002测试仍通过。

## 开发边界

不执行Agent，不实现Run、导入、Judge和消息调度。

## 交付

按tasks/README.md模板报告实际文件、接口/迁移差异、命令和测试数量。补齐对应OpenAPI和前端类型；保持前置测试通过。环境阻塞标为未验收，不虚构通过。仅提交本地commit，不push。任务完成即停。
