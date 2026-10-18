# TASK-002 数据库领域模型、Flyway 与 API 公共基础

## 0. 前置状态（先读，避免重复劳动）

- TASK-001 已**有条件验收通过**：三端可构建、公共组件可用、readiness 语义已修正。
- 未验证项（真实 MySQL / RabbitMQ / MinIO / Flyway 实跑）**不在本任务重复手工验证**，转由本任务的 CI 集成测试承担。
- TASK-001 已实现的公共组件**必须复用，禁止重写第二套**（清单见 §3）。
- 本任务只做数据库结构与公共基础，**不实现任何业务 API、业务页面、Runner 或 Judge**。

## 正式执行指令

先 `git status --short` 并确认分支，阅读 AGENTS.md、TASK-001 最终验收报告、本文与全部必读契约，然后仅完成本任务。没有真实仓库对齐报告时先补核对，不盲目按示例包名建工程。

## 必读

- `docs/architecture/01-system-overview.md`
- `docs/architecture/02-domain-model.md`（含「评测批次与重评」）
- `docs/architecture/03-database-design.md`
- `docs/contracts/database-schema.json`（**字段唯一来源**）
- `docs/architecture/04-api-contract.md`（含已冻结的信封与错误码）
- `docs/contracts/openapi-common.yaml`
- `docs/architecture/05-event-contract.md`
- `docs/architecture/06-reliability-design.md`
- `docs/modules/evaluation-engine.md`（批次语义）
- `docs/reviews/task-002-revision.md`（本次修订的理由与裁决记录）
- `docs/reviews/task-001-final-acceptance.md`
- `docs/development-guide.md`、`AGENTS.md`

## 1. 范围

实现 **27 张表**、全部主外键/索引/CHECK、持久化 Entity 与基础 Mapper、ULID、JSON/UTC TypeHandler、状态枚举与转换表；扩展（不是重写）公共 API 组件；接入 OpenAPI 公共契约；补齐 **Surefire/Failsafe 分层**与 **GitHub Actions 集成验收**；同步文档。

表名以 `database-schema.json` 为唯一来源（27 张）：

```
agents, agent_versions, datasets, dataset_versions, dataset_cases,
evaluator_definitions, evaluation_plans, evaluation_plan_versions, evaluation_plan_items,
experiments, experiment_targets, evaluation_runs, run_attempts, artifacts, trace_events,
evaluation_batches, evaluation_jobs, evaluation_job_attempts, evaluation_results,
annotation_tasks, annotation_records, import_batches, import_records, import_errors,
outbox_events, inbox_events, api_idempotency_keys
```

仅建立可靠性辅助表与实体，**不在本任务实现 Outbox 发布器、Inbox 消费者、调度器或 API 幂等中间件**。状态转换为纯规则组件，业务动作由后续 TASK 实现。不要把字段表复制到第二个独立版本。

## 2. 本任务必须落实的四个领域决定

| 决定 | 实现要求 |
| --- | --- |
| Agent 执行与评测解耦 | Run / RunAttempt 只描述执行；评测一律经 `evaluation_batches → evaluation_jobs`。禁止 Job 直接挂 Run 而无批次 |
| 一次 Run 可多次执行 | 失败重试 = 同一 Run 新增 RunAttempt；`accepted_attempt_id` 锁定评测输入；不新增 Run 槽位 |
| 历史 Run 可重评 | 新建 `EvaluationBatch`（`trigger_type=REEVALUATION`，`parent_batch_id` 指向原批次），复用已封口 Run 与 Trace，**不得重跑 Agent**，旧批次 Job/Result 只读 |
| 在线离线共用引擎 | 两种模式共用同一 Batch/Job/JobAttempt/Result 表与服务；离线只是没有 RunAttempt，**禁止**为离线建第二套评测表或第二套算子结果结构 |

结果版本由批次承载：**不新增 `evaluation_results.version` 列**。

## 3. 公共组件：复用清单与允许的扩展

TASK-001 已存在，直接复用，不得重建：

| 组件 | 位置 | 本任务动作 |
| --- | --- | --- |
| `ApiResponse` | `common/api` | 不改字段与 JSON 形状（含 `errors`、`timestamp`） |
| `ErrorCode` | `common/api` | **只改枚举值**：`NOT_FOUND→RESOURCE_NOT_FOUND`、`DEPENDENCY_UNAVAILABLE→SERVICE_UNAVAILABLE`、删除泛化 `BUSINESS_ERROR`，按契约补 409 细分码 |
| `FieldViolation` | `common/api` | 复用 |
| `RequestIdHolder` / `RequestIdFilter` | `common/api`、`common/web` | 复用；生成器改接 ULID，保留合法入参透传与 MDC 清理 |
| `GlobalExceptionHandler` | `common/web` | 扩展映射，不重写处理链；保持不泄露堆栈与 SQL |
| `HealthController/Service/Probe` | `common/health` | 不改语义；新增探针仅在确有必要时 |

允许新增：`IdGenerator`（ULID）、`PageQuery`/`PageResponse`、`JsonTypeHandler`、`InstantUtcTypeHandler`、状态枚举与转换表、实体与 Mapper。

**契约冻结**：成功/错误信封固定为 `{code,message,data,errors,requestId,timestamp}`；错误响应的 `data` 为 `null`。不得新增 `details` 别名、不得删除 `errors` 或 `timestamp`。前端 `repopilot-web/src/types/api.ts` 已依赖此结构。

错误码改名属于枚举值收敛，HTTP 状态与信封不变；同步修改 TASK-001 的测试断言，不保留旧名别名。

## 4. 实施顺序

1. 核对现有 migration、MySQL 版本、Entity 与公共组件；列出可复用项与差异，不无理由重写。
2. 逐表核对关系、复合外键的被引用 UNIQUE 与循环依赖；整理预期 DDL 清单（先表后外键）。
3. **在 V1 之后新增迁移**：从 `V2` 起连续编号，推荐 `V2` 身份与配置、`V3` 实验与执行、`V4` 评测批次与结果、`V5` 人工与导入、`V6` 可靠性辅助、`V7` 补循环外键。不修改 V1，不关闭 `foreign_key_checks` 掩盖依赖，不用 `repair` 掩盖半完成变更。
4. 建持久化 Entity 与 Mapper：JSON 用 `JsonNode`，UTC 用 `Instant`，CHAR(26) 用 ULID TypeHandler；不返回 Entity 作为 API DTO；Mapper 只开放本任务所需基础读写。
5. 领域枚举：生命周期、模式、Runner、技术状态、业务结果、Verdict、来源、导入/标注动作，以及 **BatchStatus、BatchTriggerType**；状态转换按 06 文档实现为可测试纯规则。
6. 扩展公共组件（§3），补 `PageResponse` 与 OpenAPI 公共 Schema。
7. 配置 Maven 测试分层（§6）与 CI（§7）。
8. 运行真实 MySQL 空库迁移与约束测试、公共组件测试、现有回归；同步文档并交付本地 commit。

## 5. 数据库测试矩阵

| 场景 | 预期 |
| --- | --- |
| 空 MySQL 完整迁移（V1→V7）、已有版本增量迁移 | 全部成功；历史 checksum 不变 |
| 同 Agent 重复 version；同 DatasetVersion 重复 caseKey | UNIQUE 拒绝 |
| 同 Target/Case/repeatIndex 重复 Run | UNIQUE 拒绝 |
| 同 Case 不同 Target 或 repeatIndex | 允许多个 Run |
| repeatIndex=0、repeatCount=0、越界 score | CHECK 拒绝 |
| 同 Run 重复 attemptNo；同 Job 重复 attemptNo | UNIQUE 拒绝 |
| 重复 eventId；同 Job 两个最终 Result | UNIQUE 拒绝 |
| 同批次同 Run 同 planItem 重复 Job | UNIQUE 拒绝 |
| 同 Run 在**不同批次**各建一个 Job | 允许 |
| Job 引用不属于本批次方案版本的 planItem | 复合外键拒绝 |
| `parent_batch_id` 指向其他实验的批次 | 服务层拒绝（数据库仅保证存在） |
| Trace 引用不存在 Run 或其他 Run 的 Attempt | 外键拒绝 |
| Run 引用其他 Experiment 的 Target | 复合外键拒绝 |
| Result 引用不存在 Job 或其他 Job 的 Attempt | 外键拒绝 |
| Annotation 引用其他 Run 的 Job | 外键拒绝 |
| 离线 Target 无 AgentVersion；原文件 Artifact 无 runId | 允许 |
| JSON 嵌套对象/数组/null 与中文 | 无损往返，非法 JSON 明确失败 |
| 非 UTC JVM/连接配置下毫秒 Instant 往返 | UTC 业务时刻保持，API 为 Z 后缀 |
| ULID 生成并存取 | 26 位大写合法、主键唯一 |
| 任意未定义 status | CHECK 及反序列化拒绝 |
| 循环引用删除 | RESTRICT 阻止误删，不级联历史 |

Case 是否属于实验 DatasetVersion、在线 AgentVersion 必填、版本不可变属于服务层规则，本任务不提供相关业务 API；文档已标明边界，不能声称这些已被单列外键强制保障。

## 6. 测试分层（必须真正执行）

当前 `*IT` 不被 Surefire 默认选中，常规 `mvn test` 里连"跳过"都不算。本任务必须：

1. 配置 **maven-surefire-plugin**：只跑 `*Test`，明确排除 `*IT`。
2. 配置 **maven-failsafe-plugin**：`integration-test` + `verify` 阶段执行 `*IT`，失败必须让 `mvn verify` 失败。
3. `mvn verify` = 单元测试 + 集成测试 + 打包；`mvn test` 保持不依赖基础设施，用于快速反馈。
4. 集成测试使用 `@SpringBootTest` + 环境变量连接真实 MySQL，**禁止用 H2 替代**，禁止给未执行测试标 `@Disabled` 后宣称通过。
5. 公共组件测试：MockMvc/测试 Controller 验证 200/201 成功序列化、400 字段校验、404、409 业务/状态错误、500 未知异常脱敏、分页默认与上限、`X-Request-Id` 生成/合法透传/非法替换、MDC 在异常与线程复用后清理。错误 HTTP 码必须真实返回，不能全 200 再写 code。

## 7. CI 集成验收（新增，本任务必须落地）

在 `.github/workflows/ci.yml` 建立流水线，至少四个 job：

| Job | 内容 |
| --- | --- |
| `design` | 安装 `tools/requirements-design.txt`，运行 `python tools/verify_design.py`，失败即红 |
| `backend` | `ubuntu-latest` + `services: mysql:8.0`（健康检查 `mysqladmin ping`）+ `rabbitmq:3.13`；执行 `./mvnw -B verify`；导出 OpenAPI 公共组件 |
| `worker` | Python 3.11，`pip install -r requirements-dev.txt`，`pytest` |
| `web` | Node 22，`npm ci`，`npm run build` |

要求：

1. MySQL service 使用独立测试库与**非生产测试密码**（写入 workflow 的环境变量，不使用仓库 Secrets 之外的敏感值）；健康检查通过后才开始迁移。
2. `backend` job 必须验证：空库执行 V1→V7、`flyway_schema_history` 中版本连续、约束测试通过、`/api/health` 返回 200。
3. 缓存 Maven（`~/.m2`）、npm、pip，缩短执行时间；缓存键包含锁文件哈希。
4. 允许矩阵或最小化配置，但**不得**为了绿而跳过数据库测试、注释断言、或在无 MySQL 时静默 `skip`。
5. 若仓库未启用 Actions 或无法 push，交付报告必须写"CI 未实际运行"，不得声称通过。

CI 通过只证明该提交在干净环境可迁移、可构建、可测；不代表业务功能完成。

## 8. 禁止内容

Agent/数据集/方案 CRUD、在线业务、导入业务、Runner/Judge、标注业务、RabbitMQ 消费者/发布器、Vue 业务页、新中间件、自动删库重建、H2 替代 MySQL、修改 V1 迁移、未经论证改 TASK-001 架构。

模块划分按 `docs/architecture/01-system-overview.md` 的 8 个模块：`agent`、`dataset`、`evaluationconfig`、`experiment`、`trace`、`evaluation`、`annotation`、`importing`。TASK-001 遗留的 `report` 包**不删除也不保留为空壳**：报告属于 `experiment` 的读取视图，在 TASK-010 需要时再建，本任务只调整包边界说明。

## 9. 验收

- 27 表及全部设计约束落实；迁移在空真实 MySQL 与 CI 中均可执行。
- 交付报告列出**实际 DDL 的全部索引**，包含 InnoDB 为 18 处外键自动创建的索引；不得声称设计文档已显式声明全部索引。
- `evaluation_batches` 与 Job 的复合外键真实生效；同 Run 跨批次重评路径在数据库层可表达。
- 持久化 JSON/UTC/ULID 映射通过。
- 公共组件复用而非重建；契约冻结项未被改动。
- `./mvnw verify` 真正执行 `*IT`；`pytest`、`npm run build`、`verify_design.py` 通过。
- CI 至少执行一次并给出运行链接或 Run ID。
- 文档与实际 DDL 一致。

若环境仍不能运行 Docker 且 CI 也未能触发，仍可完成静态代码与单测，但结论必须标 **"数据库验收未完成"**，不得用 H2 或 SQLite 替代。若已有业务表与设计冲突，先列最小前向迁移方案，重大跨模块变化等待负责人决定，同时继续不受影响的工作。

## 10. 待负责人确认（开工前一句话即可）

1. **离线 Run 是否允许不关联 DatasetCase**：当前设计为**必填**（`docs/modules/offline-evaluation.md` 明确 V1 不支持游离 Run），理由是 Run 唯一键与报告配对都依赖它。若要求支持"无样本归属的历史日志"，需先改 `database-schema.json`（增加 `case_ref` 与生成列唯一键），属跨模块变更，**不得由实施方自行放宽**。
2. 其余契约已冻结，无需再确认。

## 11. 交付报告

按 `tasks/README.md` 模板给出：分支与 Commit ID、文件列表、实际建表与迁移版本、主外键/索引/CHECK、公共组件复用与改动点、精确测试命令与数量（区分 unit / IT / 跳过）、CI Run ID、已知限制与设计差异。已完成/未完成逐项对应验收，完成即停止，不进入 TASK-003。
