# TASK-003 开发手册：Agent、数据集、评测方案与版本管理

> 前置：TASK-002 已通过本地验收并在 CI 全绿（run `39116300359` / `39116707563`）。
> 本文件是**可直接执行的开发手册**，不是提纲。发生冲突时按此顺序裁决：
> `docs/contracts/database-schema.json` > `docs/architecture/*` > 本手册。
> 本手册与冻结契约的差异只有 §0.3 列出的两条，其余一律按现成契约实现。

---

## 0. 范围与前置

### 0.1 本任务做什么

四个子模块，共 12 张已存在的表，**不新增表**：

| 子模块 | 内容 | 主要表 |
| --- | --- | --- |
| A. Agent 管理 | Agent CRUD、配置版本、发布与归档 | `agents`、`agent_versions` |
| B. 数据集管理 | 数据集 CRUD、版本、样本编辑、JSONL 导入 | `datasets`、`dataset_versions`、`dataset_cases`、`import_batches`、`import_records`、`import_errors`、`artifacts` |
| C. 评测方案与算子 | 方案版本草稿、整体替换、发布校验、算子只读清单 | `evaluation_plans`、`evaluation_plan_versions`、`evaluation_plan_items`、`evaluator_definitions` |
| D. 版本控制与并发 | 版本号分配、发布锁、不可变、幂等回执、状态机 | `api_idempotency_keys`、`outbox_events` |

> 关于子模块 C：负责人在需求里概括为"三个部分"，但 `docs/architecture/04-api-contract.md`
> §版本管理接口（TASK-003）已把评测方案与算子清单划进本任务。V1 后续所有评测都要求
> "批次固定一个**已发布**的 EvaluationPlanVersion"，若本任务不做，TASK-004 无法开工。
> 因此**按契约保留**，除非负责人明确要求拆到别的任务。

### 0.2 明确不做

- 不执行 Agent，不实现 Run / Attempt / Trace 写入。
- 不实现在线评测、Mock Runner、Judge、评测引擎、批次与 Job。
- 不实现离线 Run 记录导入（`import_type=OFFLINE_RUNS`）——那是 TASK-005。
- 不做 MinIO 之外的对象存储，不做文件预览与下载签名。
- 不引入 Spring Security 之外的认证体系（V1 无登录，`created_by` 先写固定系统标识）。

### 0.3 负责人裁决（2026-10-18，已批准，按此实施）

TASK-002 正式验收通过；以下六项已获批准，**不需要再确认**：

1. **新增 `POST /api/v1/dataset-versions/{versionId}/cases:import`**，用于向草稿版本批量导入 JSONL 样本。
   已同步写入 `04-api-contract.md` §批量导入数据集样本（限制、校验顺序、错误行响应、幂等边界都在那里）。
2. **批量导入整批原子**：任一错误行则整批不写入——不写样本、不建批次、不存原文件。
3. **重复 `caseKey` 明确报错**：文件内重复 400、与库内已有样本冲突 409；不覆盖、不合并、不跳过。
4. **版本状态机增加 `DRAFT → ARCHIVED`**：丢弃建错的草稿；归档后不可修改、不可发布、
   不可被新实验引用，历史记录保留。
5. **保留四个子模块**：Agent 管理、数据集管理、版本控制、评测算子与方案配置管理。
6. **明确不做**：Runner、Judge 执行、在线/离线评测调度、RabbitMQ 业务消费、Outbox 发布器。

契约修订记录见 [`docs/reviews/task-003-contract-revision.md`](../docs/reviews/task-003-contract-revision.md)。
**已冻结的 TASK-002 历史文档不改写**；本次只更新当前有效契约（`04-api-contract.md`、`06-reliability-design.md`、
`03-database-design.md` 的说明段）与本手册。

`config_hash` / `content_hash` 的规范化算法**保持不变**——§3.4 是现状描述，不是待议项，实现时照抄，不要"顺手优化"。

### 0.4 开工前必读

1. `AGENTS.md`（尤其 §7「数据库变更流程」——V2–V7 已冻结）
2. `docs/architecture/02-domain-model.md` §核心定义、§数据库与服务层约束分工
3. `docs/architecture/04-api-contract.md` §公共约定、§版本管理接口
4. `docs/contracts/dataset-case.schema.json`（JSONL 单行结构）
5. `docs/modules/frontend-design.md`
6. TASK-002 交付报告 `docs/reviews/task-002-implementation.md`

---

## 1. 业务需求

### 1.1 角色与场景

只有一个角色：**平台使用者**（V1 无权限模型）。他需要先把"要测什么、用什么配置跑、怎么判分"
准备好，才能在 TASK-004 发起实验。本任务就是把这套准备动作做成可操作、可保存、可演示的流程。

### 1.2 用户故事（每条都带验收点）

| # | 故事 | 验收点 |
| --- | --- | --- |
| U1 | 我新建一个 Agent，用 `code` 唯一标识它 | 重复 `code` 返回 409 `RESOURCE_CONFLICT`，不返回数据库原始错误 |
| U2 | 我给 Agent 加一个配置版本，后端自动给版本号 | 并发创建 10 个版本，版本号是 1..10 的连续整数，无重复、无空洞 |
| U3 | 我改草稿版本的配置，改完再改一次 | 只有 `DRAFT` 能改；`PUBLISHED` 改 → 409 `INVALID_STATE_TRANSITION` |
| U4 | 我把版本发布，之后它就不能再变 | 发布后 `PUT` 一律 409；`config_hash` 与发布时一致 |
| U5 | 我建一个数据集，往里放样本 | 样本 `caseKey` 在版本内唯一；跨版本可重复 |
| U6 | 我用 JSONL 一次导入几百条样本 | 逐行校验，错误带物理行号；部分失败时其余行照常入库 |
| U7 | 我发布数据集版本，之后样本不能再改 | 发布前校验样本非空；发布后 `POST/PUT/DELETE case` 全部 409 |
| U8 | 我基于已发布版本复制出一个新草稿继续改 | 新版本内容与源版本一致（`content_hash` 可断言相等），源版本不受影响 |
| U9 | 我配置评测方案：选算子、定顺序、标必填 | 同一版本内 `executionOrder` 唯一且从 1 连续；算子必须已发布才能发布方案 |
| U10 | 我重复点"发布"或重复提交同一个请求 | 相同 `Idempotency-Key` 返回原结果，不产生第二个资源 |

### 1.3 三个技术难点（评分重点在这里，别用"能跑"糊过去）

**难点一：版本不可变。** 不是"代码里判断一下 status"，而是
"编辑与发布锁同一行 + 所有子项写入都检查父版本状态 + 已发布后任何写路径都拿不到锁"。
风险点：`dataset_cases` 的修改只看 `dataset_version_id`，若不检查父版本状态，
发布后仍能改样本 —— 这是最容易漏的一条。

**难点二：并发一致性。** 版本号分配和发布是两条独立的竞态：
- 两个请求同时创建版本 → 版本号重复（由 `uk_agent_versions_agent_id_version` 兜底报 1062，
  但正确做法是先锁父行，让第二个请求等，而不是撞唯一键再重试）。
- 一个请求在改草稿、另一个在发布 → 必须在同一行锁上串行，否则出现"发布出去的配置不是最终保存的配置"。

**难点三：批量导入。** 要同时做到：逐行定位错误、重复样本可解释、计数可对账、重放不重复入库。
"用 `List<Case>` 一次性 insert 然后抛异常"不满足要求。

---

## 2. API 契约

### 2.1 公共约定（沿用 TASK-001/002，不要另起一套）

- 前缀 `/api/v1`，JSON camelCase，成功信封 `{code:"OK",message,data,errors:[],requestId,timestamp}`。
- 创建 201；同步修改/publish/archive 200；本任务**没有** 202 与 204。
- 分页：`page≥1`、`pageSize` 默认 20 上限 100，`data={items,page,pageSize,total}`，
  默认 `createdAt DESC, id DESC`；非法分页 → 400 `VALIDATION_ERROR`。
- 时间 ISO8601 UTC 毫秒；ID 为 26 位大写 ULID。
- 请求体上限 1MiB。超限 → 413 `PAYLOAD_TOO_LARGE`。
- `Idempotency-Key` 必填：所有 `POST` 创建、`publish`、`archive`、`import`。
  同 key 同摘要 → 复用原 `data` 与原状态码；同 key 异摘要 → 409 `IDEMPOTENCY_CONFLICT`。
- 错误码只用 `ErrorCode` 里已有的值，不要新造。

### 2.2 Agent

| 方法与路径 | 请求体 | 成功 | 关键校验 |
| --- | --- | --- | --- |
| `POST /agents` | `{code,name,description?,agentType}` | 201 Agent | `code` 1~64 `[A-Za-z0-9._-]`，全局唯一；`agentType` 走注册表；`name` 1~128 |
| `GET /agents` | `page,pageSize,status?,keyword?` | 200 页 | `keyword` 匹配 `code`/`name`，≤64 字符 |
| `GET /agents/{id}` | — | 200 Agent | 不存在 → 404 |
| `PATCH /agents/{id}` | `{name?,description?,status?}` | 200 Agent | **不能改 `code`**（出现即 400）；`status` 只允许 `ACTIVE→ARCHIVED` |
| `POST /agents/{id}/versions` | `{config,runnerType}` | 201 AgentVersion | 父 Agent 必须 `ACTIVE`；`runnerType ∈ {MOCK,CODEX,CUSTOM}`；`config` 必须对象 |
| `GET /agents/{id}/versions` | `page,pageSize` | 200 页 | 按 `version DESC` |
| `GET /agent-versions/{id}` | — | 200 AgentVersion | — |
| `PUT /agent-versions/{id}` | `{config,runnerType}` | 200 AgentVersion | 只允许 `DRAFT`；不允许改 `version`/`agentId` |
| `POST /agent-versions/{id}/publish` | 空体 | 200 AgentVersion | 只允许 `DRAFT`；同事务写幂等回执 |
| `POST /agent-versions/{id}/archive` | 空体 | 200 AgentVersion | 只允许 `PUBLISHED`；只改状态与 `updated_at` |

`Agent` 响应：`id,code,name,description,agentType,status,createdAt,updatedAt`。
`AgentVersion` 响应：`id,agentId,version,runnerType,config,status,publishedAt,createdAt,updatedAt`。
`config` 原样返回，**不解析 `secretRef`**，日志里也不打印 `config`。

### 2.3 Dataset 与 DatasetCase

| 方法与路径 | 请求体 | 成功 | 关键校验 |
| --- | --- | --- | --- |
| `POST /datasets` | `{code,name,description?}` | 201 Dataset | `code` 唯一，规则同 Agent |
| `GET /datasets`；`GET /datasets/{id}` | `page,pageSize,status?,keyword?` | 200 页 / Dataset | — |
| `PATCH /datasets/{id}` | `{name?,description?,status?}` | 200 Dataset | 不能改 `code`；`ACTIVE→ARCHIVED` 单向 |
| `POST /datasets/{id}/versions` | `{description?,cloneFromVersionId?}` | 201 DatasetVersion | 父 Dataset 必须 `ACTIVE`；`cloneFromVersionId` 必须属于同一 Dataset |
| `GET /datasets/{id}/versions` | `page,pageSize` | 200 页 | `version DESC` |
| `GET /dataset-versions/{id}` | — | 200 DatasetVersion | 含 `caseCount`（现算，不落库） |
| `POST /dataset-versions/{id}/publish` | 空体 | 200 DatasetVersion | 只允许 `DRAFT`；样本数必须 > 0，否则 400 `VALIDATION_ERROR`(field=`cases`)；发布时写 `content_hash` 与 `published_at` |
| `POST /dataset-versions/{id}/archive` | 空体 | 200 DatasetVersion | 允许 `DRAFT`（丢弃草稿）与 `PUBLISHED`；`ARCHIVED` 为终态 |
| `POST /dataset-versions/{id}/cases` | `{caseKey,taskType,input,expected?,metadata?}` | 201 DatasetCase | 父版本 `DRAFT`；`caseKey` 1~128 版本内唯一；`taskType` 走注册表；`input` 必须是对象 |
| `GET /dataset-versions/{id}/cases` | `page,pageSize,keyword?` | 200 页 | `keyword` 匹配 `caseKey`；默认 `caseKey ASC` |
| `PUT /dataset-cases/{id}` | 同创建（`caseKey` 可改） | 200 DatasetCase | 父版本 `DRAFT`；改 `caseKey` 撞已存在 → 409 |
| `DELETE /dataset-cases/{id}` | — | 200 `{id,deleted:true}` | 父版本 `DRAFT` |
| `POST /dataset-versions/{id}/cases:import` | `multipart/form-data`：`file` | 201 导入回执 | 整批原子；见 §2.5 |

`DatasetVersion` 响应：`id,datasetId,version,status,description,contentHash,publishedAt,caseCount,createdAt,updatedAt`。
`DatasetCase` 响应：`id,datasetVersionId,caseKey,taskType,input,expected,metadata,createdAt,updatedAt`。

### 2.4 EvaluationPlan 与 Evaluator

| 方法与路径 | 请求体 | 成功 | 关键校验 |
| --- | --- | --- | --- |
| `POST /evaluation-plans` | `{code,name,description?}` | 201 EvaluationPlan | `code` 唯一 |
| `GET /evaluation-plans`；`GET /evaluation-plans/{id}` | `page,pageSize,keyword?` | 200 页 / Plan | Plan 无状态列，**不提供 PATCH** |
| `POST /evaluation-plans/{id}/versions` | `{config,items:[{evaluatorDefinitionId,executionOrder,required,config}]}` | 201 PlanVersion | `items` 至少 1 条；`executionOrder` 版本内唯一且从 1 连续；算子必须存在；同一版本不能重复引用同一算子 |
| `GET /evaluation-plans/{id}/versions` | `page,pageSize` | 200 页 | `version DESC` |
| `GET /evaluation-plan-versions/{id}` | — | 200 PlanVersion | 含 `items`（按 `executionOrder ASC`） |
| `PUT /evaluation-plan-versions/{id}` | `{config,items[]}` | 200 PlanVersion | 只允许 `DRAFT`；**整体替换** `items`（先删后插，同事务） |
| `POST /evaluation-plan-versions/{id}/publish` | 空体 | 200 PlanVersion | 只允许 `DRAFT`；所有引用的算子必须 `PUBLISHED`，否则 400 `VALIDATION_ERROR`(field=`items[i].evaluatorDefinitionId`)；写 `content_hash` |
| `POST /evaluation-plan-versions/{id}/archive` | 空体 | 200 PlanVersion | 只允许 `PUBLISHED` |
| `GET /evaluators`；`GET /evaluators/{id}` | `page,pageSize,key?,type?,status?` | 200 页 / EvaluatorDefinition | **只读**，无写接口 |

算子清单通过**版本控制的注册清单 + 启动种子导入**维护，不提供上传代码接口。
种子文件放 `repopilot-server/src/main/resources/seed/evaluators.json`，
启动时按 `(evaluator_key, version)` 幂等 upsert（已存在则跳过，不改内容）。

### 2.5 JSONL 批量导入（已批准新增接口）

```
POST /api/v1/dataset-versions/{id}/cases:import
Header: Idempotency-Key（必填）
Body: multipart/form-data；file=<*.jsonl>
```

限制：文件 **20 MiB**、非空行 **10000**、单行 **64 KiB**；超出 → 413，且**不写入任何数据**。

**整批原子**（把"不存在什么"也写清楚，避免实现走偏）：

| 情形 | 结果 |
| --- | --- |
| 全部行合法 | 单事务：存原文件 → 插 `artifacts` → 插 `import_batches` → 批量插 `dataset_cases` → 提交，201 |
| 任意一行失败 | 整批拒绝：不写样本、不建批次、不存原文件，400/409 + 错误行列表 |

不允许"成功行先提交"，也不允许"失败行记 `import_errors` 后继续"。**不存在部分成功。**

**校验顺序**（一次收集全部错误，最多返回 100 条）：

1. 文件级：字节数、UTF-8 严格解码、非空行数；
2. 行级 schema：`dataset-case.schema.json`（`additionalProperties=false`），`schemaVersion` 必须为 `1`；
3. 业务：`taskType` 在注册表、`caseKey` 1~128 且字符合规、`input` 必须是对象；
4. 文件内 `caseKey` 重复；
5. 与目标版本已有 `caseKey` 冲突。

**重复 caseKey 一律报错**：

| 场景 | HTTP | `errors[].field` | 错误码（写在 `message` 首词） |
| --- | --- | --- | --- |
| 文件内重复 | 400 | `lines[n].caseKey` | `DUPLICATE_CASE_KEY_IN_FILE` |
| 与库内冲突 | 409 | `lines[n].caseKey` | `CASE_KEY_CONFLICT` |

**不要**实现"内容相同就 `REUSED`"的静默复用——旧方案已被裁决替换；重传靠 `Idempotency-Key` 拿回执。

行级错误码（枚举，用于 `message` 首词与日志；V1 不落 `import_errors`）：

| 错误码 | 含义 |
| --- | --- |
| `INVALID_JSON` | 该行不是合法 JSON |
| `SCHEMA_VIOLATION` | 缺必填 / 类型不符 / 未知字段 |
| `UNSUPPORTED_SCHEMA_VERSION` | `schemaVersion ≠ 1` |
| `UNKNOWN_TASK_TYPE` | `taskType` 不在注册表 |
| `INVALID_CASE_KEY` | 长度或字符不合规 |
| `DUPLICATE_CASE_KEY_IN_FILE` | 文件内重复 |
| `CASE_KEY_CONFLICT` | 与版本内已有样本冲突 |
| `LINE_TOO_LONG` | 单行超 64 KiB（文件级可提前判 413） |

成功响应 `data`：

```json
{"importBatchId":"01J...","versionId":"01J...","status":"COMPLETED",
 "importedCount":320,"totalCount":320,"sizeBytes":183421,"sha256":"<64 hex>"}
```

**两条路径必须同 hash**：同一组样本，逐条 `POST /cases` 与一次导入，写入行逐字段相同；发布后
`content_hash` 必须完全相同。归一化与摘要只允许一份实现（`DatasetContentHasher`），两条路径共用。
集成用例 I18 拿这个当断言。

### 2.6 注册表（本任务新增的唯一"字典"）

```java
// 服务层白名单，与数据库 CHECK 保持一致
AgentType        : MOCK, CODING
TaskType         : AGENT_TASK, LLM_CALL
RunnerType       : MOCK, CODEX, CUSTOM      // 已有 CHECK，直接复用枚举
EvaluatorType    : RULE, TRACE, COST, TEST, LLM_JUDGE
```

`agent_type` 与 `task_type` 在库里**没有 CHECK**（字典注明"服务层白名单"），
因此这是唯一靠代码守住的约束——必须有单元测试覆盖"未知值被拒绝"。

---

## 3. 数据库操作

### 3.1 事务边界（逐接口，照这个写）

| 接口 | 事务内容 |
| --- | --- |
| `POST /agents` | 插入 `agents` + 插入幂等回执，同一事务 |
| `POST /agents/{id}/versions` | `SELECT ... FROM agents WHERE id=? FOR UPDATE` → 校验 `ACTIVE` → `SELECT COALESCE(MAX(version),0)` → 插入版本 → 幂等回执 |
| `PUT /agent-versions/{id}` | `SELECT ... FROM agent_versions WHERE id=? FOR UPDATE` → 校验 `DRAFT` 且父 Agent `ACTIVE` → 更新 `config_json,config_hash,updated_at` |
| `publish` / `archive` | 锁版本行 → `Transitions.require` → 更新状态与时间戳 → 幂等回执 |
| `POST /datasets/{id}/versions` | 锁 `datasets` 行 → 校验 `ACTIVE` → 分配版本号 → 若 `cloneFromVersionId` 则同事务复制样本 |
| `POST .../cases`、`PUT`、`DELETE` | 锁父 `dataset_versions` 行 → 校验 `DRAFT` → 写 `dataset_cases` |
| `POST .../publish` | 锁版本行 → 校验 `DRAFT` → `COUNT(*)` 样本 > 0 → 计算 `content_hash` → 更新状态 |
| `PUT /evaluation-plan-versions/{id}` | 锁版本行 → 校验 `DRAFT` → `DELETE FROM evaluation_plan_items WHERE plan_version_id=?` → 批量插入新 items → 更新 `config_json` |
| `import` | 锁版本行 → 校验 `DRAFT` → 建 `artifacts`（`IMPORT_FILE`）→ 建 `import_batches` → 逐行插入 `dataset_cases` + `import_records` / `import_errors` → 更新计数与状态 |

所有事务用 `@Transactional`，**不要在 Controller 里写业务判断**；
状态校验与写入必须在同一事务、同一行锁内完成。

### 3.2 版本号分配（不要用 `MAX+1` 裸查）

```sql
-- 同一事务内，先锁父身份行，把并发创建串行化
SELECT id, status FROM agents WHERE id = ? FOR UPDATE;
SELECT COALESCE(MAX(version), 0) + 1 AS next FROM agent_versions WHERE agent_id = ?;
INSERT INTO agent_versions (...) VALUES (...);
```

`uk_agent_versions_agent_id_version` 仍然保留作为最后一道防线。
**不要**写"catch 1062 → 重试 N 次"作为主路径：那是把并发问题交给数据库报错，
重试次数用完还会给用户一个 500。

### 3.3 发布锁与不可变

- 编辑与发布锁**同一行**（`FOR UPDATE`），保证"先检查草稿、随后并发发布"的窗口不可达。
- 已发布后，任何写路径都必须先拿到 `DRAFT` 才继续；拿不到就 409 `INVALID_STATE_TRANSITION`。
- 父版本发布后，`dataset_cases` / `evaluation_plan_items` 的写操作同样被拦住
  （**这是最易漏的一条，必须写集成测试**）。
- `archive` 只允许改 `status` 与 `updated_at`，不允许动 `config_json` / `content_hash`。

### 3.4 规范化与摘要算法（`config_hash` / `content_hash`）

必须是**确定性**的，否则同一份配置两次发布得到两个 hash，历史对比就废了。

规范化 JSON 规则：

1. 对象键按 Unicode 码点升序；数组保持原顺序。
2. 无多余空白：`,` 与 `:` 后不加空格。
3. 布尔 `true/false`，`null` 保留为 `null`。
4. 数字保留原始标度：反序列化用 `USE_BIG_DECIMAL_FOR_FLOATS`，输出用
   `BigDecimal.toPlainString()`。禁止 `1.0` 变 `1`、`0.10` 变 `0.1`。
5. 字符串最小转义（只转 `"`、`\` 与控制字符），非 ASCII 原样输出 UTF-8，不转 `\uXXXX`。
6. 输出 UTF-8 字节，`sha256` 取**小写**十六进制，长度 64。

`agent_versions.config_hash = sha256(规范化(config))`。

`dataset_versions.content_hash`：

```
cases = SELECT case_key, task_type, input_json, expected_json, metadata_json
          FROM dataset_cases WHERE dataset_version_id = ? ORDER BY case_key
payload = 规范化( [ {caseKey, taskType, input, expected, metadata}, ... ] )
          // expected / metadata 缺失时显式写 null，不要省略键
content_hash = sha256(payload)
```

`evaluation_plan_versions.content_hash`：

```
payload = 规范化( {config, items: [ {evaluatorDefinitionId, executionOrder, required, config}, ... ]} )
          // items 按 executionOrder 升序
```

> 必须写"固定输入的 hash 快照测试"：给定一份 fixture，断言 hash 等于写死的值。
> 否则以后有人改了序列化实现，历史版本的 hash 无法复现，且没人会发现。

### 3.5 幂等回执

- 表：`api_idempotency_keys`（`scope` + `key` 唯一）。
- 摘要 = `sha256(method + path + 规范化(body) + 关键路径参数)`；导入额外拼文件 `sha256`。
- 回执与资源变更**同一事务**提交；失败请求不写回执。
- 同 key 异摘要 → 409 `IDEMPOTENCY_CONFLICT`，不执行业务。
- 并发同 key：唯一键冲突后读取已提交回执并返回，不要返回 500。

### 3.6 导入的写入顺序（整批原子，单事务）

```
1) 文件级校验：字节 ≤20MiB、UTF-8 严格解码、非空行 ≤10000
2) 逐行解析 + 校验 + 收集错误（不写库，最多收集 100 条展示用）
   → 有任何错误：抛 ImportValidationException(errors)，事务回滚，库内零写入
3) 文件内 caseKey 重复检查；与库内冲突检查（SELECT ... WHERE dataset_version_id=? AND case_key IN (...)）
4) 锁版本行并确认 DRAFT：SELECT ... FOR UPDATE
5) 存原文件：ArtifactStorage.put → 得到 sha256/sizeBytes；INSERT artifacts(IMPORT_FILE, run_id=NULL, READY)
6) INSERT import_batches(DATASET_CASES, dataset_version_id, file_artifact_id, status=COMPLETED,
                         total_count=success_count=n, failed_count=0, duplicate_count=0,
                         last_line_no=最大物理行号, started_at, finished_at)
7) 批量 INSERT dataset_cases（JDBC batch，rewriteBatchedStatements=true）
8) UPDATE dataset_versions SET updated_at=? （不写 content_hash——草稿没有摘要）
提交
```

要点：

- **校验全部在写库之前完成**：先收集、再落库。"整批原子"不靠回滚掩盖半成品，而是本来就没写过。
- 第 5 步存储不可用 → 503 `SERVICE_UNAVAILABLE`，事务回滚，同样零写入。
- 第 7 步撞唯一键 `(dataset_version_id, case_key)`（并发下被别人抢先写入）→ 409 `RESOURCE_CONFLICT`，
  整批回滚；**不要让 `DuplicateKeyException` 冒到 500**。
- `import_records` V1 不写：整批原子 + 幂等键已保证不重复计数，行级回执留给 TASK-005 的 `OFFLINE_RUNS`。
- `failed_count=0`、`duplicate_count=0` 是"整批原子"的必然结果，不要为它们编造统计逻辑。

### 3.7 本任务不需要新迁移

12 张表全部已在 V2/V3/V5/V6 建好，`artifacts` 的 `run_id` 可空、`import_batches` 的
`DATASET_CASES` 枚举值都已就位。**因此不要新增迁移，也不要改 V2–V7**
（`docs/contracts/migrations.lock.json` 会拦住改动）。

如果实现中确实发现缺列或缺索引：按 `AGENTS.md` §7「数据库变更流程」新增 `V8__*.sql`，
同步字典，跑 `SchemaDictionaryIT`，再 `python tools/gen_ddl_from_dict.py --freeze`。
**不允许**为了让代码少写点而改历史迁移。

---

## 4. 状态转换

### 4.1 状态机（全部复用 `Transitions`，不要新写一份）

**Agent.status / Dataset.status（`LifecycleStatus`）**

| 从 | 到 | 触发 |
| --- | --- | --- |
| `ACTIVE` | `ARCHIVED` | `PATCH ...{status:"ARCHIVED"}` |

单向。归档后：仍可读、仍可被历史引用；不能再创建新版本；不能再发布其下版本。

**版本（`VersionStatus`，AgentVersion / DatasetVersion / PlanVersion 共用）**

| 从 | 到 | 触发 |
| --- | --- | --- |
| `DRAFT` | `PUBLISHED` | `POST .../publish` |
| `DRAFT` | `ARCHIVED` | `POST .../archive`（丢弃建错的草稿） |
| `PUBLISHED` | `ARCHIVED` | `POST .../archive` |

`ARCHIVED` 是终态：不可修改、不可发布、不可被新实验引用（新建 Experiment 时校验
`dataset_version.status=PUBLISHED`，引用校验放在提交事务内），历史记录保留。

**EvaluatorDefinition.status**：只读，由种子导入决定（`PUBLISHED`）。不提供转换接口。

### 4.2 非法转换

一律 409 `INVALID_STATE_TRANSITION`，`message` 写清"当前状态 + 目标动作"，
例如 `版本已发布，不能修改`。不要返回 400，也不要复用 `RESOURCE_CONFLICT`——
409 的细分码就是给这类场景准备的。

### 4.3 实现方式

```java
Transitions.require(current, VersionStatus.PUBLISHED);   // 非法则抛 INVALID_STATE_TRANSITION
```

`Transitions` 缺少 `require(LifecycleStatus)` 重载，可以新增这个重载，
**但不允许改已有的转换表**（`VERSION` / `LIFECYCLE` 的允许边）。

---

## 5. 前端交互

### 5.1 路由与导航

| 路径 | 视图 | 说明 |
| --- | --- | --- |
| `/agents` | `AgentListView` | 列表 + 新建 |
| `/agents/:id` | `AgentDetailView` | 基本信息 + 版本列表 + 草稿编辑 |
| `/datasets` | `DatasetListView` | 列表 + 新建 |
| `/datasets/:id` | `DatasetDetailView` | 版本列表 + 样本表格 + JSONL 导入 |
| `/evaluation-plans` | `PlanListView` | 列表 + 新建 |
| `/evaluation-plans/:id` | `PlanDetailView` | 版本 + 草稿编排（算子拖序/必填） |

`config/navigation.ts` 增加四个入口；`router/index.ts` 挂到 `DefaultLayout` 下。

### 5.2 页面与组件

新增组件（`src/components/`）：

| 组件 | 职责 |
| --- | --- |
| `VersionStatusTag.vue` | `DRAFT/PUBLISHED/ARCHIVED` 三色标签，色值走主题变量 |
| `ImmutableBanner.vue` | 已发布/已归档时的只读提示条，含"复制为新版本"入口 |
| `ConfirmActionDialog.vue` | 发布/归档二次确认，展示将要发生什么（不是"确定吗？"） |
| `JsonlImportDialog.vue` | 选文件 → 上传进度 → 结果摘要（成功/失败/重复/错误行表） |
| `CaseEditorDrawer.vue` | 样本 JSON 编辑，前后端同时做结构校验 |
| `AsyncState.vue` | 统一 loading / error / empty 三态，避免每个页面各写一套 |

### 5.3 状态与错误呈现（V1 无登录，仍然要有完整态）

- API 层：`src/api/agents.ts`、`datasets.ts`、`plans.ts`、`evaluators.ts`，全部经 `http.ts`。
- Store：`stores/agent.ts`、`stores/dataset.ts`、`stores/plan.ts`；
  列表状态（page/total/filters）放 store，表单临时态放组件。
- 类型：`src/types/agent.ts` 等，字段与 §2 响应一致；**不要用 `any` 兜住**。
- 错误：读 `errors[].field` 定位到表单项；无字段级信息时用页面级 `ElMessage`。
  `INVALID_STATE_TRANSITION` 要显示成人话（"该版本已发布，不能再修改"），
  不要直接把后端 message 弹给用户。
- 加载：列表用骨架屏；详情用区块级 loading；按钮提交期间禁用并显示进行中。

### 5.4 不可变提示与归档

发布后详情页出现 `ImmutableBanner`：说明"内容已冻结，如需修改请复制为新版本"，
并把编辑表单整块置灰（不是只禁用保存按钮——用户会以为还能填）。

归档规则（`DRAFT` 与 `PUBLISHED` 都可归档）：

- 草稿的归档按钮文案是"丢弃草稿"（二次确认写清"样本与配置将不可再用，但记录保留"），
  不是"归档"——用户不会把"丢弃"理解成"存档"。
- 归档后详情页显示"已归档"横幅；编辑、发布、导入三个入口全部不渲染（不是置灰）。
- 列表默认隐藏 `ARCHIVED`，筛选器可显式查看。

---

## 6. 异常处理

### 6.1 场景 → 错误码 → HTTP

| 场景 | 错误码 | HTTP | errors[].field |
| --- | --- | --- | --- |
| 字段缺失/格式错 | `VALIDATION_ERROR` | 400 | 字段名 |
| `code` 重复 | `RESOURCE_CONFLICT` | 409 | `code` |
| 资源不存在 | `RESOURCE_NOT_FOUND` | 404 | — |
| 非草稿版本被修改 | `INVALID_STATE_TRANSITION` | 409 | — |
| 发布空数据集 | `VALIDATION_ERROR` | 400 | `cases` |
| 发布方案时算子未发布 | `VALIDATION_ERROR` | 400 | `items[2].evaluatorDefinitionId` |
| 同一方案版本重复算子 | `VALIDATION_ERROR` | 400 | `items` |
| `executionOrder` 不连续/重复 | `VALIDATION_ERROR` | 400 | `items[i].executionOrder` |
| 幂等 key 异摘要 | `IDEMPOTENCY_CONFLICT` | 409 | — |
| 文件超限 | `PAYLOAD_TOO_LARGE` | 413 | `file` |
| 对象存储不可用 | `SERVICE_UNAVAILABLE` | 503 | — |

### 6.2 明确禁止

- 不把 `SQLException` / 约束名 / 堆栈放进响应（`GlobalExceptionHandler` 已处理，别绕过它）。
- 不用 500 表达业务冲突；不用 400 表达状态冲突。
- 不吞掉幂等的唯一键冲突后返回成功却没落库。

### 6.3 日志

- 记录 `requestId`、资源 id、动作、结果码。
- **不记录** `config` 内容、`secretRef` 解析结果、导入的原始行内容。

---

## 7. 测试用例

### 7.1 单元测试（不依赖数据库）

| 用例 | 断言 |
| --- | --- |
| `AgentTypeRegistryTest` | 未知 `agentType` 被拒；大小写敏感 |
| `TaskTypeRegistryTest` | 未知 `taskType` 被拒 |
| `CanonicalJsonTest` | 键序无关；`1.0` 不改写成 `1`；非 ASCII 不转义；嵌套对象稳定 |
| `ContentHashSnapshotTest` | 固定 fixture → 写死的 hash 值（发布算法不许漂） |
| `JsonlParserTest` | BOM、CRLF、空行占行号、末尾无换行、单行超长、非法 UTF-8 |
| `DatasetCaseValidatorTest` | 缺 `input`、`schemaVersion=2`、未知字段、`caseKey` 超长逐一报对应码 |
| `VersionNumberingTest` | 空历史 → 1；有 3 条 → 4（用 mock Mapper） |
| `TransitionsRequireLifecycleTest` | `ARCHIVED→ACTIVE` 被拒 |
| `TransitionsVersionArchiveTest` | `DRAFT→ARCHIVED` 允许；`PUBLISHED→ARCHIVED` 允许；`ARCHIVED→PUBLISHED` / `ARCHIVED→DRAFT` 被拒 |
| `IdempotencyDigestTest` | 键序不同但语义相同 → 同摘要；body 改动 → 摘要变 |

### 7.2 集成测试（真实 MySQL，`*IT`，由 Failsafe 执行）

命名 `AgentApiIT`、`DatasetApiIT`、`DatasetImportIT`、`PlanApiIT`。
造数用 `Fixtures`（原生 SQL），不依赖被测 Mapper。

| # | 场景 | 断言 |
| --- | --- | --- |
| I1 | 创建 Agent 重复 `code` | 409 `RESOURCE_CONFLICT`，库里仍只有 1 行 |
| I2 | 创建版本 3 次 | `version` = 1,2,3；`created_at` 递增 |
| I3 | 改已发布版本 | 409 `INVALID_STATE_TRANSITION`；`config_hash` 未变 |
| I4 | 发布已发布版本 | 409；`published_at` 不变 |
| I5 | 归档 `ACTIVE` Agent 后再建版本 | 409 / 400，且不产生版本行 |
| I6 | 发布空数据集版本 | 400，`field=cases`；版本仍 `DRAFT` |
| I7 | 发布后新增/修改/删除样本 | 三条路径全部 409；样本数不变 |
| I8 | 版本内重复 `caseKey` | 409；版本内仍 1 条 |
| I9 | 跨版本相同 `caseKey` | 允许，两版本各自 1 条 |
| I10 | 克隆已发布版本 | 新版本 `DRAFT`、样本数相同、`content_hash` 计算值与源版本相等 |
| I11 | 方案含未发布算子 | 400，`field=items[i].evaluatorDefinitionId` |
| I12 | `executionOrder` = [1,3] | 400，拒绝不连续 |
| I13 | 幂等：同 key 同 body 两次 | 第二次返回同一 `id`，库里 1 行 |
| I14 | 幂等：同 key 异 body | 409 `IDEMPOTENCY_CONFLICT` |
| I15 | 导入 3 行合法 + 1 行非法 | **整批拒绝**：`dataset_cases` 零新增、`import_batches` 零新增、`artifacts` 零新增；400/409 且错误行号正确 |
| I16 | 文件内重复 `caseKey` | 400 `VALIDATION_ERROR`，`field=lines[n].caseKey`，message 首词 `DUPLICATE_CASE_KEY_IN_FILE`；零写入 |
| I17 | 文件的 `caseKey` 与库内已有样本冲突 | 409 `RESOURCE_CONFLICT`，`CASE_KEY_CONFLICT`；零写入，原样本内容未被覆盖 |
| I18 | 导入后 `content_hash` | 与"逐条 POST 同样样本再发布"得到的 hash 相等（两条入口必须一致） |
| I19 | 导入到已发布 / 已归档版本 | 409 `INVALID_STATE_TRANSITION`，零写入 |
| I20 | 超过 10000 非空行 / 文件 >20MiB / 单行 >64KiB | 413 `PAYLOAD_TOO_LARGE`，零写入 |
| I21 | CRLF、BOM、中间空行 | 导入成功；行数只计非空行；错误行号按**物理行**（含空行占号） |
| I22 | 同 `Idempotency-Key` 重放同一文件 | 201 且 `importBatchId` 相同；样本数不增；`import_batches` 仍 1 条 |
| I23 | 草稿归档 `DRAFT→ARCHIVED` | 200；再 `PUT` / `publish` / `import` 全部 409 |
| I24 | 归档版本被新实验引用 | 拒绝（400/409），不产生 Experiment 行 |
| I25 | 归档后读取 | 详情与样本列表仍可读（历史保留，不物理删除） |

### 7.3 并发测试（必须真跑，不靠"看起来没问题"）

| # | 场景 | 做法 | 断言 |
| --- | --- | --- | --- |
| C1 | 10 线程同时建版本 | `CountDownLatch` 同时放行 | 版本号为 1..10 连续，无重复；无 500 |
| C2 | 一边 `PUT` 草稿、一边 `publish` | 两线程交错，重复 20 轮 | 要么 `PUT` 成功且 `publish` 拿到最终配置，要么 `PUT` 409；**不允许**发布后仍能改 |
| C3 | 同幂等 key 并发 5 次 | 5 线程同 key 同 body | 只产生 1 个资源，5 个响应体一致 |
| C4 | 导入同一文件并发 2 次（不同幂等键） | 两线程同时提交 | 一方 201，另一方 409 `RESOURCE_CONFLICT`；样本数 = n，**不翻倍不重复**；无 500 |

> 并发测试用明确同步点（latch / barrier），**不要用 `Thread.sleep` 制造时序**。

### 7.4 前端

- `npm run build` 必须通过（类型检查即主要防线）。
- 手工联调清单（写进交付报告，逐条勾）：
  1. 新建 Agent → 加版本 → 改草稿 → 发布 → 详情页出现只读条
  2. 发布后编辑入口不可用（置灰 + 文案正确）
  3. 数据集导入 5 行文件（含 1 行坏行）→ **整批被拒**、错误行号准确定位、样本数仍为 0
  4. 数据集发布后再点导入 → 明确的"已发布不能导入"提示
  5. 方案编排：算子顺序调整 → 保存 → 重新打开顺序一致
  6. 断网/后端 500 → 页面显示错误态与"重试"，不是白屏

### 7.5 CI 门槛

`./mvnw verify`（单测 + Failsafe 集成）必须 0 失败；
新增 `*IT` 不允许 `@Disabled`；若因环境缺依赖跳过，必须在报告里逐条列出。
CI 会在 MySQL `8.0.36` 与 `8.0` 两个版本上各跑一遍，两边都要绿。

---

## 8. 验收标准

全部满足才算通过，逐条勾选后写进交付报告：

- [ ] 12 张表零新增迁移；`migrations.lock.json` 未变更；`gen_ddl_from_dict.py --check` 通过
- [ ] 四个子模块的全部接口按 §2 实现，路径/状态码/错误码与契约一致
- [ ] 版本不可变：已发布版本的配置、样本、方案项三条写路径全部 409（I3/I7 通过）
- [ ] 归档语义：`DRAFT→ARCHIVED`、`PUBLISHED→ARCHIVED` 均可；归档后修改/发布/导入/被新实验引用全部被拒，历史仍可读（I23/I24/I25）
- [ ] 并发：C1/C2/C3/C4 全绿，且**没有**用 sleep 制造时序
- [ ] 版本号连续无空洞（含并发场景）
- [ ] `content_hash` / `config_hash` 有快照测试，两次发布同一内容 hash 相同
- [ ] JSONL 导入：**整批原子**（任一行错 → 零写入）、错误行号可定位、重复 `caseKey` 明确报错、超限 413、幂等重放不重复入库（I15–I22）
- [ ] 单条创建与批量导入得到相同 `content_hash`（I18）
- [ ] 幂等：同 key 同 body 复用、异 body 409（I13/I14）
- [ ] 未知 `agentType` / `taskType` 被拒绝（服务层注册表，库无 CHECK）
- [ ] 前端 6 个页面可操作，`npm run build` 通过，联调清单 6 条全勾
- [ ] `./mvnw verify` 0 失败；`*IT` 无 `@Disabled`
- [ ] `04-api-contract.md` 已补 `cases:import` 一行，文档与代码一致
- [ ] 交付报告含：文件清单、接口差异、迁移差异（应为"无"）、命令与退出码、
      通过/失败/跳过数量、未验证项及原因、遗留问题

---

## 9. 交付物

| 类别 | 内容 |
| --- | --- |
| 代码 | `modules/agent`、`modules/dataset`、`modules/evaluationconfig`（补 controller/service）、`common/api`（分页复用）、`common/storage`（`ArtifactStorage` 抽象 + 本地文件系统实现）、`common/state`（补 `require(LifecycleStatus)` 与 `DRAFT→ARCHIVED`） |
| 种子 | `resources/seed/evaluators.json` + 启动导入器（幂等） |
| 前端 | 6 个视图、6 个组件、4 个 api 模块、3 个 store、类型定义 |
| 测试 | 单元 9 组、集成 I1–I25、并发 C1–C4 |
| 文档 | `04-api-contract.md` 已补 `cases:import`；`06-reliability-design.md` 已补归档边；`frontend-design.md` 补页面；本手册末尾追加"实施记录" |

报告模板沿用 `tasks/README.md`，并额外回答三个问题：

1. 版本不可变是怎么保证的？（指到具体行锁与状态校验代码）
2. 并发版本号分配的临界区在哪一行？
3. 归档规则落在哪几处？（`Transitions` 边、Service 校验、引用校验、前端置灰——四处都要指出来）

---

## 10. 边界提醒

- 不 push、不合并 `main`；只提交本地 commit，push 需逐次授权。
- 不改 V2–V7 迁移；需要新迁移时走冻结流程。
- 不为了让测试变绿而放宽断言或加 `@Disabled`。
- 发现契约冲突：**暂停受影响部分**，写清冲突与推荐方案，继续做不受影响的部分。
- TASK-003 完成即停，不进入 TASK-004。
