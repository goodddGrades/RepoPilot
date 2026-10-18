# TASK-002 设计 V1.2.1：三项实施约束补齐

> 2026-10-18，负责人批准 V1.2 并授权开工，同时给出三项实施前必须闭合的约束。
> 本文只记录**这三项**的落地方式与副作用，不重复 V1.2 的架构论证（见 [task-002-freeze.md](./task-002-freeze.md)）。

## 背景

V1.2 冻结后仍有三个问题留给了"实现时再说"，而它们都属于**数据正确性**，不能等实现时随手决定：

1. 默认报告取 `INITIAL` 批次，但没有任何约束保证一个实验只有一个 `INITIAL` 批次；
2. `batchScope=LATEST` 的时间语义写成"最新一个已完成批次"，等于隐式跳过未完成与失败批次；
3. 离线导入的"草稿样本 → 发布 → 建 Run"是两阶段数据处理，容易被实现成"让用户上传两次文件"。

三项都不改领域模型，只把语义写死并加一条数据库约束，因此记为 **V1.2.1**，不叫 V1.3。

## 一、每实验最多一个 INITIAL 批次

### 为什么不能用应用层判断

"创建批次前先查该实验是否已有 INITIAL 批次"在并发下必然失效：两个请求同时查到"没有"，然后同时插入。加乐观锁或分布式锁能挡住，但那是把数据完整性交给调用路径，任何绕过 Service 的脚本、数据修复或重放都会破掉它。

### 采用方案：生成列 + 唯一键

```sql
initial_experiment_id CHAR(26)
  GENERATED ALWAYS AS (IF(trigger_type = 'INITIAL', experiment_id, NULL)) STORED,
UNIQUE KEY uk_evaluation_batches_initial (initial_experiment_id)
```

- `INITIAL` 批次：生成列取 `experiment_id`，同一实验第二次插入即唯一键冲突；
- `REEVALUATION` / `MANUAL` 批次：生成列为 `NULL`，MySQL 唯一索引允许多行 `NULL`，不受限制；
- 并发安全由 InnoDB 唯一索引保证，后到者拿到 `Duplicate entry`，服务层翻译成 409；
- 没有新增表、没有新增业务列，应用侧只多一个"永不写入"的列（存储型生成列，插入时必须显式排除）。

选择 `STORED` 而不是 `VIRTUAL`：两者都能建二级索引，但 `STORED` 让索引值与行一致落盘，`information_schema` 与 DDL 比对也更直观。

### 同时补的两条 CHECK

| CHECK | 原因 |
| --- | --- |
| `trigger_type <> 'INITIAL' OR parent_batch_id IS NULL` | 初始批次不该有"重评来源"；否则批次树出现语义矛盾 |
| `parent_batch_id IS NULL OR parent_batch_id <> id` | 挡住一步自环。更长的环（A→B→A）无法用 CHECK 表达，由服务层在建批次时拒绝，属已声明的边界 |

## 二、LATEST 的时间语义

V1.2 写的是"最新一个已完成批次（`COMPLETED` 优先，其次 `PARTIAL_FAILED`）"，这有两个问题：

- **隐藏状态**：最新批次失败时，报告悄悄返回上一次成功结果，用户看到的是"过期但好看"的数字；
- **不可解释**：同一个 URL 在重评失败前后返回不同内容，且没有字段说明为什么。

V1.2.1 改为：

| 场景 | 语义 |
| --- | --- |
| 不传批次参数 | 初始批次（`trigger_type=INITIAL`） |
| `batchScope=LATEST` | 该实验**最新创建**的批次，按 `(created_at, id)` 倒序取第一条，**不跳过任何状态** |
| 该批次未完成或失败 | 照常返回批次对象，`batchStatus` 为真实状态，指标标记不可用/部分可用，**不回退** |
| `batchScope=SPECIFIED` | 指定 `batchId`，缺参 400 |
| 将来需要"最近一次跑完的" | 新增 `LATEST_COMPLETED`，**不改 LATEST** |

响应必须同时回显 `batchId`、`batchScope`、`batchStatus`，前端据此显示"该批次仍在运行/已失败"，而不是拿旧报告冒充新结论。

## 三、离线导入：两阶段数据处理，一次上传

业务流程不变：

```text
解析文件 → 草稿 DatasetCase（+ 预览） → 人工确认发布 DatasetVersion → 创建 Offline Run
```

补充的约束是**操作层面只上传一次**：

- 同一个 Artifact 与哈希贯穿两个阶段，`import_batches` 可跨阶段，`import_records` 分阶段记账；
- 预览阶段返回样本条数、`caseKey` 冲突、缺失的证据类型，用户确认后直接在同一份文件上继续，不要求重新上传；
- V1 允许前端分两步点击（导入样本 / 导入 Run），这是界面取舍，不是数据模型要求；导入向导阶段合并成一个入口时**数据模型不变**。

## 四、对自检规则的补充

`tools/verify_design.py` 新增 6 项断言，防止后续有人"优化"掉这些约束：

| 断言 | 防止的退化 |
| --- | --- |
| `evaluation_batches` 存在 `initial_experiment_id` 且有生成表达式 | 删掉数据库级 INITIAL 唯一性，退回 Controller 判断 |
| 生成表达式含 `INITIAL` | 生成列被改成常量或其他条件 |
| 生成列必须可空 | 改成 `NOT NULL` 会让非 INITIAL 批次无法插入，或被迫造假值 |
| 存在 `['initial_experiment_id']` 唯一键 | 只加列不加约束 |
| 生成列不得进主键 | 生成列不适合做身份 |
| 契约含"最新创建""不得静默回退""LATEST_COMPLETED" | LATEST 被改回"最近完成" |

自检项数 1260 → 1270。

## 五、影响范围

| 对象 | 变更 |
| --- | --- |
| `docs/contracts/database-schema.json` | `evaluation_batches` 增 1 个生成列、1 个唯一键、2 条 CHECK |
| `docs/architecture/03-database-design.md` | 同上，并说明为何用生成列而非应用层判断 |
| `docs/architecture/02-domain-model.md` | 批次规则新增第 8 条（INITIAL 唯一）；第 5 条改为 LATEST 不回退 |
| `docs/architecture/04-api-contract.md` | LATEST / LATEST_COMPLETED 语义与回显字段 |
| `docs/modules/offline-evaluation.md` | 一次上传、两阶段处理 |
| `docs/modules/experiment-analysis.md` | 报告回显 `batchScope` / `batchStatus` |
| `tools/verify_design.py` | 新增 6 项断言 |
| `tasks/TASK-002.md` | 冻结版本号、测试矩阵、表规模数字、已裁决事项 |

**未变**：28 张表、47 条外键、跨实验复合外键、`evaluation_batch_runs`、离线必绑 DatasetCase、API 信封。V1.2.1 只是把三处"实现时再说"钉死。

## 六、实施期发现：一条 CHECK 的等价改写

实现时 `SchemaDictionaryIT` 报出字典与真实库不一致：

```
字典：NOT (output_json IS NOT NULL AND output_artifact_id IS NOT NULL)
库中：output_json IS NULL OR output_artifact_id IS NULL
```

这不是迁移写错，而是 MySQL 解析时对 `NOT (A AND B)` 做了 De Morgan 重写。两者语义相同，
但**文本不同**，会让"字典 ↔ DDL ↔ information_schema"三者永久对不上，进而让逐项比对
必须放宽成"看起来差不多"，那样检测能力就没了。

处理：把字典改成等价的标准形 `output_json IS NULL OR output_artifact_id IS NULL`，
并保留中文注释"输出要么内联、要么落 Artifact，不能同时给"。这是**表示法**调整，
没有改变约束含义，也没有改动 28 表结构与任何业务规则。类似风险只有这一处
（全库唯一一个 `NOT (... AND ...)` 形式的 CHECK）。

同时保留一条经验：CHECK 表达式应写成 MySQL 解析后不变的标准形（`A IS NULL OR B IS NULL`
而不是 `NOT (A IS NOT NULL AND B IS NOT NULL)`），否则字典与库永远是"逻辑相等、文本不等"。
