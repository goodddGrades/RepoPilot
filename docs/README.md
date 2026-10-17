# 文档索引

本目录存放 RepoPilot 的设计与实施文档。总纲为仓库根目录的 `project_design.md`
（《RepoPilot 开发总手册 V1.0》），本目录按主题拆分细化。

## 已完成

| 文件 | 内容 | 产出任务 |
| --- | --- | --- |
| [`01-local-development.md`](./01-local-development.md) | 本地开发环境搭建、启动顺序、排障 | TASK-001 |
| [`README.md`](./README.md) | 文档索引与规划（本文件） | TASK-001 |

## 规划中（按总手册第 15 章，随对应 TASK 落地）

| 文件 | 内容 | 计划任务 |
| --- | --- | --- |
| `00-project-overview.md` | 项目概览与产品边界 | TASK-002 |
| `01-product-requirements.md` | 产品需求 | TASK-002 |
| `02-system-architecture.md` | 系统架构与模块边界 | TASK-002 |
| `03-domain-model.md` | 领域模型（Agent/Dataset/Experiment/Run/Trace…） | TASK-002 |
| `04-database-design.md` | 表结构与约束 | TASK-002 |
| `05-api-contract.md` | REST 契约与分页/错误码规范 | TASK-002 |
| `06-event-contract.md` | RabbitMQ 事件与消息 Schema | TASK-004 / TASK-011 |
| `07-online-evaluation.md` | 在线评测流程与 Mock Runner 规范 | TASK-004 |
| `08-offline-evaluation.md` | 离线导入与标准化 | TASK-005 |
| `09-trace-pipeline.md` | TraceEvent 规范、存储与对比 | TASK-006 |
| `10-evaluation-engine.md` | 算子协议与执行引擎 | TASK-007 |
| `11-annotation-workflow.md` | 标注流转与归因 | TASK-009 |
| `12-experiment-analysis.md` | 指标口径与版本回归 | TASK-010 |
| `13-frontend-specification.md` | 前端页面与交互规范 | TASK-003 起逐步补充 |
| `14-deployment-guide.md` | 部署与发布 | TASK-013 |
| `15-testing-strategy.md` | 测试分层与验收用例 | TASK-013 |

## 约定

- 文档与代码必须同步更新：修改公共 API / 消息格式 / 表结构时，同一次提交内更新对应文档。
- 未实现的能力不要在文档中描述为「已支持」；规划中的内容统一标注所属 TASK。
