# TASK-002 修订说明（V1.1）

日期：2026-10-18
范围：**只修订设计与任务文本，不实现任何业务功能。**
基线：TASK-001 有条件验收通过；设计包 V1.0。

## 1. 为什么必须修订

原 TASK-002 的数据库字典在"重评历史 Run"这个核心场景上不可实现：

```text
evaluation_jobs  UNIQUE (run_id, evaluation_plan_item_id)
```

`evaluation_plan_item` 绑定的是一个**固定版本的算子**（`evaluator_definitions`）。升级 Judge 后必须使用新的 PlanVersion，也就是新的 plan item。而同一个 Run 在同一 plan item 下已有 Job，唯一键直接拒绝；想换方案又只能新建 Experiment，新 Experiment 又要新建 Run，而 Run 绑定了原 Experiment——**结果是：想重用原始 Trace 重评，只能重跑 Agent 或伪造新 Run**。这与"重新评测不应重新执行 Agent"直接冲突。

TASK-001 的实现没有涉及这部分，因此修订成本在文档与 TASK-002 内，不涉及回滚代码。

## 2. 设计变更：引入评测批次

新增第 27 张表 `evaluation_batches`：

| 字段 | 作用 |
| --- | --- |
| `experiment_id` | 批次归属实验（Run 仍属于原实验） |
| `evaluation_plan_version_id` | 本批次固定使用的**已发布**方案版本，可不同于实验初始版本 |
| `batch_no` | 实验内递增，`UNIQUE (experiment_id, batch_no)` |
| `trigger_type` | `INITIAL` / `REEVALUATION` / `MANUAL` |
| `parent_batch_id` | 重评来源批次，自引用，首次为空 |
| `selection_json` | Run 选择范围快照（显式 runIds 或过滤条件），创建后不可改 |
| `expected_job_count` | 建 Job 前冻结的应有 Job 数，用于批次完整性判断 |
| `status` | `PENDING/RUNNING/COMPLETED/PARTIAL_FAILED/FAILED/CANCELLED` |

`evaluation_jobs` 增加 `batch_id`、`evaluation_plan_version_id`，唯一键改为
`(batch_id, run_id, evaluation_plan_item_id)`，并增加两条复合外键：

- `(batch_id, evaluation_plan_version_id) → evaluation_batches(id, evaluation_plan_version_id)`
- `(evaluation_plan_version_id, evaluation_plan_item_id) → evaluation_plan_items(plan_version_id, id)`

因此数据库层直接拒绝"Job 用了不属于本批次方案版本的算子项"，不依赖服务层自觉。
`evaluation_plan_items` 为此补 `UNIQUE (plan_version_id, id)`。

### 索引补强

`evaluation_batches` 与 `evaluation_jobs` 新增的四条外键需要以约束列序列为前缀的索引，否则 InnoDB 会隐式创建、无法从设计文档追溯。已在字典中显式声明：

- `evaluation_batches`：`(evaluation_plan_version_id)`、`(parent_batch_id)`
- `evaluation_jobs`：`(batch_id, evaluation_plan_version_id)`、`(evaluation_plan_version_id, evaluation_plan_item_id)`

顺带核对出：**全库另有 18 处外键没有显式前缀索引**（例如 `experiments.dataset_version_id`、`evaluation_runs.output_artifact_id`），属设计包原有风格，依赖 InnoDB 自动建索引。不改这 18 处，但 TASK-002 交付时必须把实际 DDL 中的自动索引列入报告，不能声称"设计已声明全部索引"。

### 语义后果（已写入 02/06/modules）

1. 重评 = 新批次，复用已封口 Run 与 accepted Attempt，不新增 RunAttempt，不重跑 Agent。
2. 旧批次与旧 Result 只读；结果版本由批次承载，不新增 `result.version`。
3. `Experiment.status` 只由初始批次与 Run 执行决定；重评失败不回写历史实验状态。
4. 报告/对比必须显式带 `batchId` 并回显，跨批次默认"不可比=方案版本不同"。
5. 在线与离线共用同一条 Batch → Job → Attempt → Result 链路。

## 3. 评审四点意见的落实

| 评审意见 | 落实 |
| --- | --- |
| 离线 Run 是否必须关联 DatasetCase：不强制 | **未采纳为 V1 变更**。当前 V1 明确"不支持游离 Run"（`docs/modules/offline-evaluation.md`），且 Run 唯一键与报告配对依赖它。放宽需要 `case_ref` + 生成列唯一键，属跨模块变更。已列为 TASK-002 开工前唯一待确认项，禁止实施方自行放宽 |
| 一次 Run 能否多次执行：支持，RunAttempt 记录 | 已在设计基线中，本任务补测试矩阵：重试只增 Attempt、不增 Run 槽位 |
| 重评历史 Run：保留原 Run，新建 EvaluationJob 与结果 | **已采纳并落地为批次模型**（§2） |
| 在线离线共用评测引擎 | 已在基线中；TASK-002 明确禁止为离线建第二套评测表/结果结构 |

## 4. API 契约收敛（按负责人裁决）

| 项 | TASK-001 现状 | 最终契约 |
| --- | --- | --- |
| 成功/错误信封 | `code/message/data/errors/requestId/timestamp` | **保持不变**（`errors` 不叫 `details`，保留 `timestamp`） |
| `message` | `success`（已在上一轮修正） | 保持一致 |
| 404 | `NOT_FOUND` | `RESOURCE_NOT_FOUND`（枚举值改名，HTTP 与结构不变） |
| 通用 409 | `BUSINESS_ERROR` | 拆为 `RESOURCE_CONFLICT` / `IDEMPOTENCY_CONFLICT` / `INVALID_STATE_TRANSITION` / `STALE_ATTEMPT` / `OPTIMISTIC_LOCK_CONFLICT` |
| 503 | `DEPENDENCY_UNAVAILABLE` | `SERVICE_UNAVAILABLE` |
| `requestId` | UUID v4 | ULID（不透明值，客户端不解析，不构成破坏性变更） |

`docs/contracts/openapi-common.yaml` 的 `Success`/`Error` 已同步为六字段，且 `data` 在错误时为 `null`。

## 5. 测试与 CI

- Surefire 只跑 `*Test`；Failsafe 在 `verify` 阶段跑 `*IT`；`mvn verify` 作为后端唯一完整验收命令。
- 集成测试必须连真实 MySQL，禁止 H2 替代，禁止 `@Disabled` 后宣称通过。
- GitHub Actions 四个 job：`design` / `backend`（MySQL 8.0 + RabbitMQ 3.13 service）/ `worker` / `web`。
- CI 未实际运行必须在交付报告里写明，不能用"配置已写"代替"We're green"。

## 6. 对 TASK-001 代码的影响

只改三处，均为契约收敛，不涉及架构：

1. `ErrorCode` 枚举值改名并补 409 细分码；`GlobalExceptionHandler` 的映射同步。
2. `RequestIdFilter` 的生成器由 UUID 改为 ULID（透传规则与 MDC 清理不变）。
3. 相关单测断言（404 code、requestId 格式）。

TASK-001 的 19 个单测在改动后必须全部重新通过。

## 7. 未做的事

- 没有建表、没有写 Entity/Mapper、没有新增业务接口。
- 没有实现 Outbox/Inbox、调度器、幂等中间件。
- 没有触发 GitHub Actions（仓库尚未 push，无法取得 Run ID）。

## 8. 待负责人确认

1. 是否允许"无样本归属的历史日志"导入（见 §3 第一行）。默认按 V1 现状强制执行必绑。
2. 是否现在 push 到 GitHub：**CI 验收依赖 push**，不 push 则 TASK-002 的数据库验收只能标记为"未完成"。
