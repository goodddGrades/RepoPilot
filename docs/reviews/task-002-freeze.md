# TASK-002 设计冻结（V1.2）

日期：2026-10-18
范围：**只修订设计与任务文本，不实现任何业务功能、不建表、不 push。**
前置：[task-002-revision.md](./task-002-revision.md)（V1.1，引入 `evaluation_batches`）
结论：评审裁决"评测批次方向正确，需补数据一致性约束"**全部落实**；表数 27 → 28；V1 契约冻结。

## 1. 裁决与落实位置

| 裁决（负责人） | 落实 |
| --- | --- |
| ① V1.0 所有 Run 必须关联 DatasetCase，不支持游离 Run；导入可自动生成样本，但不得伪造 Ground Truth / 工具结果 / 成功状态 | `02-domain-model.md` 新增「离线导入与样本归属」；`modules/offline-evaluation.md` 同步；`tasks/TASK-002.md §2` 增行。`evaluation_runs.dataset_case_id` 保持 NOT NULL，**不改表结构** |
| ② Git 推送需先核对仓库、远程与敏感文件，并取得明确授权；只推独立开发分支 | 见本文 §7。**尚未推送**，等负责人口头授权 |
| ③ 保留 `evaluation_batches`，增加 `evaluation_batch_runs` 固定 Run 集合 | 新增第 28 张表（§2） |
| ④ 批次与 Run 同实验；Job 属于批次、PlanItem 属于方案版本；优先数据库约束 | 两条复合外键 + 父批次复合外键 + Job 指向登记表的复合外键（§2、§3） |
| ⑤ 重评只新增 Batch/Job/Result；明确批次状态机与报告默认语义 | `06-reliability-design.md` 批次转换补不可变项；`04-api-contract.md` 报告默认初始批次、`batchScope`、跨批次比较需双 batchId |
| ⑥ 检查全部复合外键、父表复合唯一键、NOT NULL 与必要索引，避免重复索引；真实 MySQL 验证 | 18 处缺失外键索引全部补齐（§4）；字典新增 `optional` 标记；TASK-002 验收改为"设计索引 == 实际索引" |
| ⑦ 同步字典、领域模型、API 契约、状态机与 TASK-002，更新自检规则 | 全部已改；`verify_design.py` 新增 5 类规则，1247 项通过 |

## 2. 新增表：evaluation_batch_runs

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `batch_id` | CHAR(26) | 否 | 评测批次，与 `run_id` 组成主键 |
| `run_id` | CHAR(26) | 否 | 参与该批次的 Run |
| `experiment_id` | CHAR(26) | 否 | 从批次与 Run 复制的快照列，供两条复合外键共用 |
| `created_at` | DATETIME(3) | 否 | 加入时间 |

主键 `(batch_id, run_id)`：这是全库唯一的非 ULID 主键。理由是关联表没有独立生命周期，加代理键只会多一份宽索引，不增加任何约束力；`verify_design.py` 为此表保留一条显式例外，其余 27 张表仍强制 `PK = id CHAR(26)`。

外键：

- `(batch_id, experiment_id) → evaluation_batches(id, experiment_id)`
- `(run_id, experiment_id) → evaluation_runs(id, experiment_id)`

两条外键共用 `experiment_id`，因此"批次属于实验 A、Run 属于实验 B"无法同时满足；`experiment_id` 不再单独建指向 `experiments` 的外键。

配套的父表复合唯一键：`evaluation_batches(id, experiment_id)`、`evaluation_runs(id, experiment_id)`。`evaluation_jobs` 增加 `(batch_id, run_id) → evaluation_batch_runs(batch_id, run_id)`，由唯一键 `(batch_id, run_id, evaluation_plan_item_id)` 作左前缀索引，不新增索引。

`parent_batch_id` 从单列外键改为 `(parent_batch_id, experiment_id) → evaluation_batches(id, experiment_id)`：跨实验的"重评来源"在数据库层即被拒绝，不再依赖服务层自觉（V1.1 写作"服务层拒绝"，已更正）。

## 3. 关于原论证的更正

V1.1 报告说"唯一键 `(run_id, evaluation_plan_item_id)` 直接阻止重评"，评审指出这不准确：换一版 Judge 会产生新的 PlanItem，ID 不同，唯一键并不会拒绝。准确的表述是：

> 旧模型下同一 Run 的所有 Job 混在一起，没有"批次"这个分组维度，无法表达**同一方案版本的独立重评**、无法标注**某条结果的批次归属**，也无法在保留旧结果的同时生成新结果；`parent_batch_id` 更是无从挂载。

批次模型的结论不变，但它解决的是**批次归属与历史分组**，不是"绕过唯一键"。

## 4. 外键与索引审计

原设计有 18 处外键没有显式左前缀索引，依赖 InnoDB 自动创建。本轮全部补齐，并把规则变成可检查项：

| 类别 | 现状 |
| --- | --- |
| 主键 | 28（含 1 个复合主键） |
| 额外 UNIQUE | 31（本轮新增 2 处供跨实验复合外键引用：`evaluation_batches(id, experiment_id)`、`evaluation_runs(id, experiment_id)`） |
| 二级索引 | 46 |
| 外键 | 47 |
| 依赖 InnoDB 隐式索引的外键 | **0** |

三条去重规则写进 `03-database-design.md`：与 PK/UNIQUE 同左前缀的组合不再重复建索引；同一列组合不得既声明 UNIQUE 又声明普通索引；两条复合外键列相同则合并。

仍有 7 处索引与主键/唯一键同前缀，均为复合外键无法并入主键所致（如 `evaluation_runs(id, active_attempt_id)`、`evaluation_jobs(id, active_attempt_id)`、`evaluation_results(job_id, job_attempt_id)`），已在字典中逐条说明理由，没有为了"看起来干净"而删掉约束。

同时清理了两条真冗余：`annotation_tasks(run_id)`（被 `(run_id, evaluation_job_id)` 覆盖）、`evaluation_batches(parent_batch_id)`（被新的复合前缀索引覆盖）。

复合外键的可空列用 `optional` + `optional_reason` 显式登记，共 6 条：`active_attempt_id`（Run / Job）、`accepted_attempt_id`、`run_attempt_id`（离线轨迹）、`evaluation_job_id`（Run 级标注）、`parent_batch_id`（初始批次）。校验规则：复合外键只要含可空列就必须标记 `optional`，未标记即视为设计缺陷；标记后仍要求"非空时必须命中父行"。

## 5. 报告与批次历史语义

| 查询 | 语义 |
| --- | --- |
| 初始报告 | 不传批次参数（默认）= `trigger_type=INITIAL` 的批次 |
| 最新评测 | `batchScope=LATEST`，取最新已完成批次（`COMPLETED` 优先，其次 `PARTIAL_FAILED`） |
| 指定批次 | `batchScope=SPECIFIED` + `batchId` |
| 对比两版 Judge | `comparison` 必须同时给 `baselineBatchId` 与 `candidateBatchId`，只给一个返回 400 |

响应必须回显 `batchId` 与 `batchScope`。默认值刻意**不跟随最新批次**：同一 URL 在后台重评后不得返回不同历史结论。跨方案版本的跨批次比较只返回"不可比原因=方案版本不同"，不自动判定回归。

批次状态独立：`PENDING/RUNNING/COMPLETED/PARTIAL_FAILED/FAILED/CANCELLED`。初始批次早已完成时，新批次仍可 RUNNING 或 FAILED；`Experiment.status` 只由初始批次与 Run 执行决定。

## 6. 自检规则（1247 项通过）

新增：

1. 表数 28；关联表主键白名单，其余强制 `PK = id CHAR(26)`。
2. 每条外键必须有显式左前缀索引（`PK ∪ UNIQUE ∪ INDEX`），缺失即失败。
3. 复合外键含可空列必须带 `optional` 与 `optional_reason`；不可空列不得标 `optional`。
4. 被引用父键列不得可空（避免 NULL 参与父键匹配）。
5. 同一列组合不得重复声明；主键不得再声明为二级索引。
6. 批次语义断言：登记表列集合、两条复合外键都带 `experiment_id`、Job 必须指向登记表、`parent_batch_id` 复合外键存在、API 契约含 `batchScope` 与"默认返回初始批次"。

## 7. Git 与 CI 状态

核对结果：

- `origin` = `https://github.com/goodddGrades/RepoPilot.git`（个人仓库，非公司内网地址）。
- 工作区唯一未跟踪文件是 `git_address.txt`，本轮加入 `.gitignore`，不会进入提交。
- 追踪文件中无 `.env`、密钥、令牌；`deploy/.env.example` 只有占位符。
- 分支仍为 `main`，**未创建 develop、未 push、未触发 Actions**。

因此："CI 通过才算数据库集成验收"这条目前**未满足**，本轮的 1247 项只是静态设计检查，不能替代真实 MySQL 迁移与集成测试。

## 8. 未做的事

- 没有建表、没有写 Entity/Mapper、没有新增业务接口，也没有改动 TASK-001 的 Java 代码。
- 没有实现报告 API 或批次创建 API（属 TASK-007/TASK-010）。
- 没有 push，没有运行 CI。
- 未开始 TASK-002 编码，等待实施授权。

## 9. 需要负责人确认的一处解读

裁决写的是"导入时能否自动创建 DatasetCase：可以"。本轮把它落在 **`DATASET_CASES` 导入阶段、目标是草稿版本**；`OFFLINE_RUNS` 阶段仍要求 `caseKey` 命中已发布的既有样本，否则整行拒绝。

这样处理的原因：`evaluation_runs` 的 `dataset_case_id` 指向已发布版本的样本，而发布后的数据集版本不可追加样本（版本不可变原则）。如果希望"导入历史 Run 时顺带补样本"，就必须允许向已发布版本追加，等于打破版本不可变，需另行裁决——当前未这样做。
