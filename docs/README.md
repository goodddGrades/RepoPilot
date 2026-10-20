# 文档索引

本目录是 **RepoPilot 唯一的设计基线**（来自设计包 V1.0，2026-10-17 并入）。
根目录 `project_design.md`（《开发总手册 V1.0》）**已降级为历史草案**，仅作追溯；与本文档集冲突时以本目录为准。

## 阅读顺序

1. [`00-product-scope.md`](./00-product-scope.md) — 产品范围、V1 边界、交付阶段
2. [`architecture/01-system-overview.md`](./architecture/01-system-overview.md) — 组件职责与调度分期
3. [`reviews/task-013-delivery.md`](./reviews/task-013-delivery.md) — **TASK-013 交付报告：真实 RabbitMQ 链路闭环、三档退避、DLQ、受控重放、保留策略与迁移工具（最新）**
4. [`reviews/task-013-amqp-facts.md`](./reviews/task-013-amqp-facts.md) — **TASK-013 实测的 28 条 AMQP/Spring 事实**（含`x-match=all` 忽略 `x-` 前缀、探测性 declare 会破坏队列等坑）
5. [`reviews/task-012-delivery.md`](./reviews/task-012-delivery.md) — TASK-012 交付报告：压测装置、两个真实并发缺陷根因、修复前后对照、慢查询与索引结论
6. [`reviews/task-011-delivery.md`](./reviews/task-011-delivery.md) — TASK-011 交付报告：Outbox/RabbitMQ、inbox 去重、13 个故障演练用例与已知限制
7. [`reviews/task-010-delivery.md`](./reviews/task-010-delivery.md) — TASK-010 交付报告：实验报告、配对回归、质量门禁
8. [`reviews/task-009-delivery.md`](./reviews/task-009-delivery.md) — TASK-009 交付报告：人工标注工作台、失败归因、乐观锁与追加历史
9. [`reviews/task-008-delivery.md`](./reviews/task-008-delivery.md) — TASK-008 交付报告：Judge 算子、测试命令算子、真实 CLI Runner 适配
10. [`reviews/task-007-delivery.md`](./reviews/task-007-delivery.md) — TASK-007 交付报告：统一评测引擎与结果闭环
11. [`reviews/task-006-delivery.md`](./reviews/task-006-delivery.md) — TASK-006 交付报告：统一 Trace 查询与可视化回放
12. [`reviews/task-005-delivery.md`](./reviews/task-005-delivery.md) — TASK-005 交付报告：规范 JSONL 离线导入
13. [`reviews/task-004-delivery.md`](./reviews/task-004-delivery.md) — TASK-004 交付报告：在线 Mock 执行与 Worker 基础
14. [`reviews/task-003-delivery.md`](./reviews/task-003-delivery.md) — TASK-003 交付报告：Agent/数据集/评测方案 API、JSONL 整批导入、前端页面与并发验收
15. [`reviews/task-003-contract-revision.md`](./reviews/task-003-contract-revision.md) — TASK-003 契约修订（2026-10-18 裁决）：JSONL 整批原子、重复 caseKey 报错、`DRAFT→ARCHIVED`
16. [`reviews/task-002-implementation.md`](./reviews/task-002-implementation.md) — TASK-002 交付报告：迁移、实体、测试与偏差
17. [`reviews/rabbitmq-local-verification.md`](./reviews/rabbitmq-local-verification.md) — 本机 RabbitMQ 3.13.7 实跑核查与收发闭环（readiness 全 UP 已在本机执行）
18. [`reviews/task-002-actual-ddl.sql`](./reviews/task-002-actual-ddl.sql) — TASK-002 实际 DDL（空库 Flyway V1..V7 后由 mysqldump 导出）
19. [`reviews/task-002-ddl-indexes.md`](./reviews/task-002-ddl-indexes.md) — TASK-002 实际 DDL 索引清单（由 information_schema 导出）
20. [`reviews/task-002-v121-delta.md`](./reviews/task-002-v121-delta.md) — TASK-002 设计 V1.2.1：INITIAL 批次唯一、LATEST 语义、离线单次上传
21. [`reviews/task-002-freeze.md`](./reviews/task-002-freeze.md) — TASK-002 设计冻结 V1.2：批次 Run 集合、跨实验约束、报告默认语义、索引审计
22. [`reviews/task-002-revision.md`](./reviews/task-002-revision.md) — TASK-002 修订：评测批次与契约收敛
23. [`reviews/task-002-delta.md`](./reviews/task-002-delta.md) — 相对早期草案的修订（为什么这样做）
24. [`reviews/repository-alignment.md`](./reviews/repository-alignment.md) — 本仓库核对结果、实际版本与偏差
25. [`reviews/design-merge-report.md`](./reviews/design-merge-report.md) — 设计包合并时的冲突清单与处置

## 全局架构

| 文件 | 内容 |
| --- | --- |
| [`architecture/01-system-overview.md`](./architecture/01-system-overview.md) | 技术边界、组件职责、工程组织、数据流、运维可见性 |
| [`architecture/02-domain-model.md`](./architecture/02-domain-model.md) | 领域约束、版本不可变、样本身份、Attempt 归属 |
| [`architecture/03-database-design.md`](./architecture/03-database-design.md) | 28 张表的生成视图（**唯一来源是 `contracts/database-schema.json`**） |
| [`architecture/04-api-contract.md`](./architecture/04-api-contract.md) | `/api/v1`、`/internal/v1`、统一响应、错误码、分页、幂等、Worker 协议 |
| [`architecture/05-event-contract.md`](./architecture/05-event-contract.md) | 事件信封与轨迹结构语义 |
| [`architecture/06-reliability-design.md`](./architecture/06-reliability-design.md) | 状态机、租约、重试、封口、恢复 |

## 模块规范

| 文件 | 覆盖任务 |
| --- | --- |
| [`modules/online-evaluation.md`](./modules/online-evaluation.md) | TASK-004 |
| [`modules/offline-evaluation.md`](./modules/offline-evaluation.md) | TASK-005 |
| [`modules/trace-pipeline.md`](./modules/trace-pipeline.md) | TASK-006 |
| [`modules/evaluation-engine.md`](./modules/evaluation-engine.md) | TASK-007 起 |
| [`modules/annotation-system.md`](./modules/annotation-system.md) | TASK-009 |
| [`modules/experiment-analysis.md`](./modules/experiment-analysis.md) | TASK-010 |
| [`modules/messaging-reliability.md`](./modules/messaging-reliability.md) | TASK-011（**运维手册**：Outbox/Inbox 排查、手动重投递、指标、保留策略） |
| [`modules/frontend-design.md`](./modules/frontend-design.md) | TASK-003 起逐步落地 |

## 机器可读契约

| 文件 | 用途 |
| --- | --- |
| [`contracts/database-schema.json`](./contracts/database-schema.json) | 数据库字段/索引/外键/CHECK 的**唯一来源**，先改此文件再生成迁移与文档 |
| [`contracts/migrations.lock.json`](./contracts/migrations.lock.json) | 已发布迁移的 sha256 冻结记录：冻结后只新增迁移，不改历史 |
| [`contracts/openapi-common.yaml`](./contracts/openapi-common.yaml) | 公共响应、错误、分页、ULID 组件（只含公共部分） |
| [`contracts/openapi-execution.yaml`](./contracts/openapi-execution.yaml) | 执行链路（实验/Run/Worker 协议/附件）路由与 Schema |
| [`contracts/openapi-import.yaml`](./contracts/openapi-import.yaml) | 离线轨迹导入（批次/进度/行级错误）路由与 Schema |
| [`contracts/openapi-trace.yaml`](./contracts/openapi-trace.yaml) | 统一 Trace 查询（`GET /runs/{id}/events`、签名游标分页）路由与 Schema |
| [`contracts/openapi-evaluation.yaml`](./contracts/openapi-evaluation.yaml) | 评测执行（重评批次/取消、评测 Attempt 心跳与结论提交）路由与 Schema |
| [`contracts/openapi-annotation.yaml`](./contracts/openapi-annotation.yaml) | 人工标注与失败归因（复核任务创建/原子领取/提交/审核/取消、派单建议）路由与 Schema |
| [`contracts/openapi-reporting.yaml`](./contracts/openapi-reporting.yaml) | 实验报告（`GET /experiments/{id}/report`：槽位级指标/配对回归/门禁）路由与 Schema |
| [`contracts/openapi-messaging.yaml`](./contracts/openapi-messaging.yaml) | 消息可靠性（`POST /internal/v1/notifications/accept`：唤醒登记/inbox 去重/隔离）路由与 Schema |
| [`contracts/event-envelope.schema.json`](./contracts/event-envelope.schema.json) | 事件信封 |
| [`contracts/trace-event.schema.json`](./contracts/trace-event.schema.json) | 轨迹事件 |
| [`contracts/dataset-case.schema.json`](./contracts/dataset-case.schema.json) | 数据集样本 |
| [`contracts/offline-run.schema.json`](./contracts/offline-run.schema.json) | 离线导入记录 |

## 开发与运行

| 文件 | 性质 |
| --- | --- |
| [`development-guide.md`](./development-guide.md) | **通用规范**：测试层级、CI、配置清单、备份恢复、性能方法 |
| [`01-local-development.md`](./01-local-development.md) | **本仓库 Runbook**：真实端口、启动顺序、实测命令、排障 |
| [`reviews/design-validation.md`](./reviews/design-validation.md) | 设计包自检报告 |
| [`reviews/perf/`](./reviews/perf/) | TASK-012 压测原始结果 JSON 与 InnoDB 死锁图节选（修复前后对照） |

## 任务与样例

- 任务范围与交付格式：`tasks/README.md`（12 个 TASK 的唯一任务表）
- 不可运行样例：`examples/`（导入记录、事件信封、轨迹事件，均不含真实密钥）
- 设计自检工具：`tools/verify_design.py`

## 已废止的旧规划

合并前 `docs/README.md` 曾在根目录规划一套 `03~15` 编号文档，与设计包路径重复。现统一按下表归位，**不再新建同名文件**：

| 旧规划文件 | 现行位置 |
| --- | --- |
| `00-project-overview.md` / `01-product-requirements.md` | `00-product-scope.md` + `architecture/01-system-overview.md` |
| `02-system-architecture.md` | `architecture/01-system-overview.md` |
| `03-domain-model.md` | `architecture/02-domain-model.md` |
| `04-database-design.md` | `architecture/03-database-design.md` + `contracts/database-schema.json` |
| `05-api-contract.md` | `architecture/04-api-contract.md` |
| `06-event-contract.md` | `architecture/05-event-contract.md` + `contracts/*.schema.json` |
| `07-online-evaluation.md` | `modules/online-evaluation.md` |
| `08-offline-evaluation.md` | `modules/offline-evaluation.md` |
| `09-trace-pipeline.md` | `modules/trace-pipeline.md` |
| `10-evaluation-engine.md` | `modules/evaluation-engine.md` |
| `11-annotation-workflow.md` | `modules/annotation-system.md` |
| `12-experiment-analysis.md` | `modules/experiment-analysis.md` |
| `13-frontend-specification.md` | `modules/frontend-design.md` |
| `14-deployment-guide.md` | `development-guide.md` §本地部署 + `01-local-development.md` |
| `15-testing-strategy.md` | `development-guide.md` §测试层级 + §CI |

## 约定

1. **唯一来源**：字段改 `contracts/database-schema.json`；路由与响应改 `architecture/04-api-contract.md`；任务范围改 `tasks/`。其余文档只引用，不复制字段表。
2. **文档与代码同步**：改公共 API / 事件格式 / 表结构时，同一次提交内更新对应文档与 Schema。
3. **不把设计写成成果**：未实现的能力标注所属 TASK，不写"已支持"。
4. 设计自检：`python tools/verify_design.py`（需 `tools/requirements-design.txt` 依赖），检查表数、Schema、样例与文档链接。
