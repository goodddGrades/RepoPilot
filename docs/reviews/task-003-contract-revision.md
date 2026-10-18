# TASK-003 契约修订记录（2026-10-18 裁决）

> 本文件**只记录本次修订**，不覆盖任何已冻结的历史文档。
> 已冻结的 `task-002-freeze.md`、`task-002-v121-delta.md`、`task-002-implementation.md` 保持原样，
> 它们的结论不被追溯改写；本文件说明"从哪一版起契约变成了什么样"。
>
> 修订触发：负责人在批准 TASK-002 正式验收通过的同时，对 TASK-003 的六项契约问题作出裁决。

## 1. 决裁事项与落地位置

| # | 裁决 | 落地位置 |
| --- | --- | --- |
| 1 | 批准新增 `POST /api/v1/dataset-versions/{versionId}/cases:import`，向草稿版本批量导入 JSONL | `04-api-contract.md` §版本管理接口（表格行 + 新小节）、`tasks/TASK-003.md` §2.5 |
| 2 | 批量导入**整批原子**：任一行错则一条都不写 | `04-api-contract.md` §批量导入数据集样本、`tasks/TASK-003.md` §2.5/§3.6 |
| 3 | 重复 `caseKey` **明确报错**，不静默覆盖 | 同上（文件内 400、与库内冲突 409） |
| 4 | 版本状态机增加 `DRAFT → ARCHIVED` | `06-reliability-design.md` §允许的转换、`tasks/TASK-003.md` §4.1、`Transitions.java` |
| 5 | 保留四个子模块：Agent、数据集、版本控制、算子与方案配置 | `tasks/TASK-003.md` §0.3 |
| 6 | 不实现 Runner / Judge 执行 / 在线离线调度 / RabbitMQ 业务消费 / Outbox 发布器 | `tasks/TASK-003.md` §0.2 |

## 2. 被替换的旧方案（写清楚，避免实现时回退）

TASK-003 手册初稿（未实施）曾设计"部分成功 + 静默复用"，本次**全部作废**：

| 旧设计 | 现契约 | 理由 |
| --- | --- | --- |
| 导入有失败行 → 批次 `PARTIAL_FAILED`，成功行照常提交 | **整批拒绝，零写入** | 用户拿到"318/320 成功"会误以为数据完整；原子语义让"成功"这件事没有歧义 |
| 文件内 `caseKey` 重复 → 第一条入库，后续 `REJECTED` | **整批 400** | 静默取舍会让用户不知道哪条进了库 |
| 与库内同 `caseKey` 且内容相同 → `REUSED` | **409 明确报错** | "内容相同就复用"是隐式覆盖的前身；重传同一文件应由幂等键处理 |
| 失败行写 `import_errors` | **不落库，只回 HTTP 错误行** | 整批原子下没有"部分成功的批次"可挂载错误行；V1 不保留失败批次 |
| 写 `import_records` 行级回执 | **V1 不写** | 整批原子 + 幂等键已保证不重复计数；行级回执留给 TASK-005 的 `OFFLINE_RUNS` |

`import_batches` 的 `PARTIAL_FAILED` 状态值**保留**（CHECK 未改），只保留给后续 `OFFLINE_RUNS` 场景，
不再用于 `DATASET_CASES`。

## 3. 数据库结构：零变更

本次修订**没有**新增表、列、索引或约束：

- `dataset_cases` 已有唯一键 `(dataset_version_id, case_key)`，正好承载"重复即报错"。
- `import_batches` 已有 `import_type=DATASET_CASES`、`file_artifact_id NOT NULL`、`status` 全集。
- `artifacts` 已有 `run_id` 可空（导入原文件无 Run）与 `status=READY`。
- 三类版本表的 `status` CHECK 已含 `ARCHIVED`，`DRAFT→ARCHIVED` 不需要改 CHECK。

因此 `migrations.lock.json` 不变，V2–V7 一行不动，也不产生 V8。
归档时刻用 `updated_at` 记录；若将来要独立的 `archived_at` / `archive_reason`，按 `AGENTS.md` §7 走 V8。

## 4. 未改动的内容（明确声明）

- `config_hash` / `content_hash` 的规范化算法与既有约束**完全不变**，包括键序、数字标度、非 ASCII 不转义。
- 统一响应信封（`code/message/data/errors/requestId/timestamp`）不变，本次**没有**新增 `errorsTruncated`
  之类的字段——错误条数用 `message` 说明。
- 错误码枚举不变：仍然使用 `VALIDATION_ERROR` / `RESOURCE_CONFLICT` / `INVALID_STATE_TRANSITION` /
  `PAYLOAD_TOO_LARGE` / `IDEMPOTENCY_CONFLICT`，没有为导入新增顶层错误码。
- 分页、幂等、时间格式等公共约定不变。

## 5. 一致性与验证

| 检查 | 方式 |
| --- | --- |
| 契约与手册一致 | `04-api-contract.md` 的导入小节与 `TASK-003.md` §2.5/§3.6 逐条对应（限制、错误码、原子语义、幂等） |
| 状态机一致 | `06-reliability-design.md` 的转换表 == `Transitions.VERSION` 的允许边 == `TASK-003.md` §4.1 |
| 文档未覆盖冻结历史 | `git diff` 不触碰 `task-002-freeze.md` / `task-002-v121-delta.md` / `task-002-implementation.md` |
| 字典与迁移未变 | `gen_ddl_from_dict.py --check` 仍通过，`migrations.lock.json` 无差异 |
| 静态设计检查 | `verify_design.py` 新增两条断言：契约必须含 `cases:import`；状态机必须含 `DRAFT→ARCHIVED` |

## 6. 对后续 TASK 的影响

- **TASK-004（在线评测）**：创建 Experiment 时必须校验 `dataset_version.status=PUBLISHED`；
  归档版本不得被新实验引用（I24）。
- **TASK-005（离线评测）**：`OFFLINE_RUNS` 导入仍可采用逐行部分成功 + 行级回执，
  `import_records` / `import_errors` 与 `PARTIAL_FAILED` 是给它的，不要因为 TASK-003 不用就删。
- **TASK-010（报告）**：归档版本的历史报告仍需可读，归档不等于删除。
