# 数据库详细设计

唯一字段来源为 [database-schema.json](../contracts/database-schema.json)。本文件由它生成，用于审阅；修改时同步两者。共 28 张表（其中 `evaluation_batch_runs` 是纯关联表，用业务键作复合主键）。没有在云端生成或执行真实 Flyway migration；本地 TASK-002 需按已有版本号实施。

## 统一规范

MySQL 8，InnoDB，utf8mb4_bin；ULID列用CHAR(26)/ascii_bin，统一大写且应用层校验；SHA256为64位小写十六进制；OTel ID为小写十六进制、非全零。业务键使用大小写敏感比较，不静默trim后合并不同来源。所有时间DATETIME(3)，Java Instant，经显式UTC TypeHandler映射；数据库会话设UTC。created_at/updated_at由应用显式写入，领取与过期判断使用数据库UTC_TIMESTAMP(3)。版本与状态均结构化；扩展信息用原生JSON。`required`建DDL时加反引号避免关键字风险。

字段默认值表示插入默认；“-”表示无默认，调用者必须明确提供或允许NULL。SQL NULL与JSON null不混用，缺失的可选JSON存SQL NULL。一般扩展配置空值用JSON对象。布尔值映射BOOLEAN，状态不使用MySQL ENUM。

所有外键RESTRICT，无级联删除。普通多态资源引用不能伪造外键。在线/离线模式、跨版本归属、Artifact属于同Run等跨行规则由Java事务校验，见领域文档。

主键约定：业务表一律 `id CHAR(26)`（ULID）。唯一的例外是纯关联表 `evaluation_batch_runs`，它没有独立生命周期，使用 `(batch_id, run_id)` 复合主键；若加 ULID 代理键只会多一份宽索引而不增加约束力。

## 建表与迁移顺序

先建身份/版本/方案，再建Experiment/Target，接着创建Run、Attempt、Artifact、Trace、评测批次、批次Run登记、Job及其Attempt、Result、Annotation、Import、Outbox/Inbox/API幂等表。Run与Attempt互引、Run与Artifact及Import互引，Job与JobAttempt互引；先创建所有表，再用ALTER TABLE添加循环外键；不能禁用foreign_key_checks掩盖依赖。复合外键的被引用列均有对应UNIQUE（含 `evaluation_batches(id, experiment_id)`、`evaluation_runs(id, experiment_id)`、`evaluation_plan_items(plan_version_id, id)`）。

评测批次必须早于 Job 创建：Job 的 `(batch_id, evaluation_plan_version_id)` 指向批次，`(evaluation_plan_version_id, evaluation_plan_item_id)` 指向方案项，因此在数据库层就拒绝"Job 使用不属于本批次方案版本的算子项"。

`evaluation_batch_runs` 必须早于 Job：Job 的 `(batch_id, run_id)` 指向这张登记表，因此"Job 评测了一个没被登记进本批次的 Run"在数据库层就被拒绝。批次创建与 Run 登记在同一事务提交，之后不得增删——否则历史批次的样本范围会漂移。

按仓库已有Flyway最大版本连续编号，推荐拆分身份配置、实验执行、轨迹与评测批次、人工与导入、可靠性辅助五组；最后一组补循环外键。新项目可把全部建表和最后补外键放在同一个按依赖排序的迁移集合。每个阶段完成后保证Flyway状态可验证，不能改写已执行迁移。

## 字段、约束与索引

以下PK均为id（`evaluation_batch_runs` 除外，见上）。索引分三类，声明时必须能说明属于哪一类：

1. **约束键**：主键与 UNIQUE，表达业务唯一性（如 `(experiment_id, batch_no)`、`(batch_id, run_id, evaluation_plan_item_id)`）；复合外键的被引用列必须有对应 UNIQUE。
2. **外键前缀索引**：每条外键都要有以约束列序列为左前缀的索引。本字典已逐条显式声明，**不依赖 InnoDB 隐式建索引**；TASK-002 必须用真实 DDL 验证"设计声明的索引 == 实际创建的索引"，不允许出现无法从字典追溯的索引。
3. **查询索引**：按已知读取路径追加（如 `(status, next_retry_at, created_at, id)`）。

三条去重规则：已由 UNIQUE/PK 以左前缀覆盖的列组合不再重复建索引；同一列组合不得既声明为 UNIQUE 又声明为普通索引；两条复合外键若索引列相同则合并为一条。当前字典中仅有 7 处索引与主键/唯一键同前缀（如 `evaluation_runs(id, active_attempt_id)`），都是复合外键无法并入主键导致的，已在各表说明原因。明确索引不等于已验证性能，TASK-012依据执行计划调整。

## agents

Agent身份，不持有运行密钥。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| code | VARCHAR(64) | 否 | - | 唯一业务标识，大小写敏感 |
| name | VARCHAR(128) | 否 | - | 名称 |
| description | TEXT | 是 | - | 说明 |
| agent_type | VARCHAR(32) | 否 | - | MOCK或CODING等注册类型，服务层白名单 |
| status | VARCHAR(32) | 否 | ACTIVE | 生命周期 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：(code)。

查询索引：无额外查询索引。

CHECK：`status IN ('ACTIVE', 'ARCHIVED')`。

## agent_versions

发布后不可变的执行配置。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| agent_id | CHAR(26) | 否 | - | 所属Agent |
| version | INT | 否 | - | 从1递增 |
| runner_type | VARCHAR(32) | 否 | - | Runner种类 |
| config_json | JSON | 否 | - | 完整配置快照，仅含secretRef |
| config_hash | CHAR(64) | 否 | - | 规范化配置SHA256 |
| status | VARCHAR(32) | 否 | DRAFT | 版本状态 |
| published_at | DATETIME(3) | 是 | - | UTC发布时间 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：(agent_id, version)。

查询索引：无额外查询索引。

CHECK：`version >= 1`；`runner_type IN ('MOCK', 'CODEX', 'CUSTOM')`；`status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')`。

外键：(agent_id) → agents(id)；RESTRICT。

## datasets

数据集身份。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| code | VARCHAR(64) | 否 | - | 唯一业务标识 |
| name | VARCHAR(128) | 否 | - | 名称 |
| description | TEXT | 是 | - | 说明 |
| status | VARCHAR(32) | 否 | ACTIVE | 生命周期 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：(code)。

查询索引：无额外查询索引。

CHECK：`status IN ('ACTIVE', 'ARCHIVED')`。

## dataset_versions

发布后固定样本集合。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| dataset_id | CHAR(26) | 否 | - | 所属数据集 |
| version | INT | 否 | - | 版本序号 |
| status | VARCHAR(32) | 否 | DRAFT | 状态 |
| description | TEXT | 是 | - | 说明 |
| content_hash | CHAR(64) | 是 | - | 发布时生成，排序case_key后对完整样本集合求摘要 |
| published_at | DATETIME(3) | 是 | - | 发布时间 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：(dataset_id, version)。

查询索引：无额外查询索引。

CHECK：`version >= 1`；`status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')`。

外键：(dataset_id) → datasets(id)；RESTRICT。

## dataset_cases

版本内样本身份。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| dataset_version_id | CHAR(26) | 否 | - | 所属版本 |
| case_key | VARCHAR(128) | 否 | - | 版本内稳定key，大小写敏感 |
| task_type | VARCHAR(32) | 否 | - | 任务类型，由注册表校验 |
| input_json | JSON | 否 | - | 规范化任务输入 |
| expected_json | JSON | 是 | - | 期望结果，未知时NULL |
| metadata_json | JSON | 是 | - | 标签与扩展信息，不存密钥 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：(dataset_version_id, case_key)。

查询索引：无额外查询索引。

CHECK：无额外CHECK。

外键：(dataset_version_id) → dataset_versions(id)；RESTRICT。

## evaluator_definitions

算子实现与输入输出契约版本。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| evaluator_key | VARCHAR(64) | 否 | - | 算子稳定标识 |
| version | INT | 否 | - | 版本号 |
| display_name | VARCHAR(128) | 否 | - | 名称 |
| description | TEXT | 是 | - | 说明 |
| evaluator_type | VARCHAR(32) | 否 | - | 算子分类 |
| implementation_ref | VARCHAR(256) | 否 | - | 注册的实现及代码或镜像版本 |
| input_schema_json | JSON | 否 | - | 输入JSON Schema |
| output_schema_json | JSON | 否 | - | 输出JSON Schema |
| status | VARCHAR(32) | 否 | DRAFT | 版本状态 |
| published_at | DATETIME(3) | 是 | - | 发布时间 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：(evaluator_key, version)。

查询索引：无额外查询索引。

CHECK：`version >= 1`；`evaluator_type IN ('RULE', 'TRACE', 'COST', 'TEST', 'LLM_JUDGE')`；`status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')`。

## evaluation_plans

评测方案身份。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| code | VARCHAR(64) | 否 | - | 唯一业务标识 |
| name | VARCHAR(128) | 否 | - | 名称 |
| description | TEXT | 是 | - | 说明 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：(code)。

查询索引：无额外查询索引。

CHECK：无额外CHECK。

## evaluation_plan_versions

方案配置快照。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| plan_id | CHAR(26) | 否 | - | 所属方案 |
| version | INT | 否 | - | 版本号 |
| status | VARCHAR(32) | 否 | DRAFT | 版本状态 |
| config_json | JSON | 否 | - | 任务结果来源、门禁规则、重试与超时预算 |
| content_hash | CHAR(64) | 是 | - | 发布时完整方案摘要 |
| published_at | DATETIME(3) | 是 | - | 发布时间 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：(plan_id, version)。

查询索引：无额外查询索引。

CHECK：`version >= 1`；`status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')`。

外键：(plan_id) → evaluation_plans(id)；RESTRICT。

## evaluation_plan_items

顺序执行的固定算子配置。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| plan_version_id | CHAR(26) | 否 | - | 所属方案版本 |
| evaluator_definition_id | CHAR(26) | 否 | - | 固定算子版本 |
| execution_order | INT | 否 | - | 从1递增且版本内唯一 |
| required | BOOLEAN | 否 | true | 影响技术完成，不等同于质量通过 |
| config_json | JSON | 否 | - | 算子参数与阈值，空配置用对象 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：(plan_version_id, id)（供 Job 复合外键引用）；(plan_version_id, evaluator_definition_id)；(plan_version_id, execution_order)。

查询索引：(evaluator_definition_id)（外键前缀索引）。

CHECK：`execution_order >= 1`。

外键：(plan_version_id) → evaluation_plan_versions(id)；RESTRICT。

外键：(evaluator_definition_id) → evaluator_definitions(id)；RESTRICT。

## experiments

固定版本的实验。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| name | VARCHAR(128) | 否 | - | 名称 |
| description | TEXT | 是 | - | 说明 |
| mode | VARCHAR(32) | 否 | - | ONLINE或OFFLINE |
| dataset_version_id | CHAR(26) | 否 | - | 固定已发布样本版本 |
| evaluation_plan_version_id | CHAR(26) | 否 | - | 固定已发布方案版本 |
| status | VARCHAR(32) | 否 | DRAFT | 技术状态 |
| config_json | JSON | 否 | - | 运行预算等不可变提交快照 |
| expected_run_count | BIGINT | 否 | 0 | 开始时冻结的槽位总数 |
| started_at | DATETIME(3) | 是 | - | 首次开始 |
| finished_at | DATETIME(3) | 是 | - | 终态时间 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：除PK外无。

查询索引：(status, created_at, id)；(dataset_version_id)；(evaluation_plan_version_id)。后两条为外键前缀索引，显式声明以替代 InnoDB 隐式建索引。

CHECK：`expected_run_count >= 0`；`mode IN ('ONLINE', 'OFFLINE')`；`status IN ('DRAFT', 'QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED', 'CANCELLED')`。

外键：(dataset_version_id) → dataset_versions(id)；RESTRICT。

外键：(evaluation_plan_version_id) → evaluation_plan_versions(id)；RESTRICT。

## experiment_targets

在线/离线比较对象。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| experiment_id | CHAR(26) | 否 | - | 所属实验 |
| target_key | VARCHAR(64) | 否 | - | 实验内稳定标识 |
| display_name | VARCHAR(128) | 否 | - | 显示名称 |
| agent_version_id | CHAR(26) | 是 | - | 离线可空；在线必须已发布 |
| repeat_count | INT | 否 | 1 | 每个样本的重复观察数 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：(experiment_id, target_key)；(experiment_id, id)。

查询索引：(agent_version_id)。`experiment_id` 已由唯一键 `(experiment_id, target_key)` 覆盖，不再重复建索引。

CHECK：`repeat_count >= 1`。

外键：(experiment_id) → experiments(id)；RESTRICT。

外键：(agent_version_id) → agent_versions(id)；RESTRICT。

## evaluation_runs

逻辑观察与最终接受输出。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| experiment_id | CHAR(26) | 否 | - | 所属实验 |
| experiment_target_id | CHAR(26) | 否 | - | 所属对象 |
| dataset_case_id | CHAR(26) | 否 | - | 固定样本；服务层校验属于实验版本 |
| repeat_index | INT | 否 | 1 | 逻辑重复编号，与Attempt不同 |
| import_batch_id | CHAR(26) | 是 | - | 首次创建此Run的批次 |
| mode | VARCHAR(32) | 否 | - | 须与实验相同 |
| status | VARCHAR(32) | 否 | PENDING | 技术状态 |
| task_outcome | VARCHAR(32) | 否 | UNKNOWN | 业务任务结果 |
| source_system | VARCHAR(64) | 是 | - | 离线必填，在线可空 |
| source_record_id | VARCHAR(128) | 是 | - | 外部记录标识，成对出现 |
| source_record_hash | CHAR(64) | 是 | - | 原始规范化导入记录摘要 |
| trace_id | CHAR(32) | 是 | - | 合法OTel Trace ID；不能填ULID |
| external_trace_ref | VARCHAR(256) | 是 | - | 非OTel来源追踪引用 |
| output_json | JSON | 是 | - | 最终接受的规范化输出，小于等于64KiB |
| output_artifact_id | CHAR(26) | 是 | - | 较大输出引用，不与output_json同时有值 |
| usage_json | JSON | 是 | - | 最终观察用量、币种、来源与缺失标记 |
| observation_metadata_json | JSON | 否 | - | outputKnown、traceCoverage、outcomeSource与适配器版本等观测完整性 |
| attempt_count | INT | 否 | 0 | 已创建的执行尝试数 |
| active_attempt_id | CHAR(26) | 是 | - | 当前唯一执行尝试 |
| accepted_attempt_id | CHAR(26) | 是 | - | 最终被接受尝试；离线为空 |
| next_retry_at | DATETIME(3) | 是 | - | 再次可领取时间 |
| sealed_at | DATETIME(3) | 是 | - | 执行数据封口时间 |
| started_at | DATETIME(3) | 是 | - | 开始时间 |
| finished_at | DATETIME(3) | 是 | - | 技术终态时间 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：(experiment_target_id, dataset_case_id, repeat_index)；(experiment_id, experiment_target_id, source_system, source_record_id)；(id, experiment_id)（供批次登记表复合外键引用，用于强制 Run 与批次同实验）。

查询索引：(experiment_id, status)；(status, next_retry_at, created_at, id)；(dataset_case_id)；(created_at, id)；(trace_id)；(id, active_attempt_id)；(id, accepted_attempt_id)；(output_artifact_id)；(import_batch_id)。`(id, active_attempt_id)` 与 `(id, accepted_attempt_id)` 只是两条复合外键的前缀索引，无法并入主键，且各自守护不同不变量（当前尝试 / 已接受尝试），不合并。

CHECK：`repeat_index >= 1`；`attempt_count >= 0`；`(source_system IS NULL) = (source_record_id IS NULL)`；`output_json IS NULL OR output_artifact_id IS NULL`（输出要么内联、要么落 Artifact，不能同时给）；`mode IN ('ONLINE', 'OFFLINE')`；`task_outcome IN ('UNKNOWN', 'SUCCESS', 'FAILURE', 'NOT_APPLICABLE')`；`status IN ('PENDING', 'QUEUED', 'RUNNING', 'EVALUATING', 'COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED')`。

外键：(experiment_id) → experiments(id)；RESTRICT。

外键：(experiment_id, experiment_target_id) → experiment_targets(experiment_id, id)；RESTRICT。

外键：(dataset_case_id) → dataset_cases(id)；RESTRICT。

外键：(id, active_attempt_id) → run_attempts(run_id, id)；RESTRICT。可空列，勾选 optional：NULL 表示当前没有进行中的尝试，非空时必须属于本 Run。

外键：(id, accepted_attempt_id) → run_attempts(run_id, id)；RESTRICT。可空列，勾选 optional：离线 Run 与未封口 Run 没有接受尝试。

外键：(output_artifact_id) → artifacts(id)；RESTRICT。

外键：(import_batch_id) → import_batches(id)；RESTRICT。

## run_attempts

每次执行尝试；失败重试保留历史。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| run_id | CHAR(26) | 否 | - | 逻辑Run |
| attempt_no | INT | 否 | - | 单调递增 |
| worker_id | VARCHAR(128) | 否 | - | Worker实例标识 |
| status | VARCHAR(32) | 否 | RUNNING | 尝试状态 |
| lease_token_hash | CHAR(64) | 否 | - | 随机Token摘要，不存明文 |
| lease_until | DATETIME(3) | 否 | - | 数据库UTC租约截止 |
| deadline_at | DATETIME(3) | 否 | - | 硬截止，续租不得越过 |
| completion_hash | CHAR(64) | 是 | - | 完成回调的规范化摘要，支持重放 |
| output_json | JSON | 是 | - | 尝试输出，失败亦可留存 |
| output_artifact_id | CHAR(26) | 是 | - | 大输出附件 |
| usage_json | JSON | 是 | - | 本尝试用量，包括重试成本 |
| started_at | DATETIME(3) | 否 | - | 领取时间 |
| finished_at | DATETIME(3) | 是 | - | 结束时间 |
| error_code | VARCHAR(64) | 是 | - | 结构化错误码 |
| error_message | VARCHAR(1024) | 是 | - | 已脱敏错误摘要 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：(run_id, attempt_no)；(run_id, id)。

查询索引：(status, lease_until)；(output_artifact_id)（外键前缀索引）。

CHECK：`attempt_no >= 1`；`status IN ('RUNNING', 'COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED')`。

外键：(run_id) → evaluation_runs(id)；RESTRICT。

外键：(output_artifact_id) → artifacts(id)；RESTRICT。

## artifacts

对象存储元数据，可先上传后关联Run。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| run_id | CHAR(26) | 是 | - | 导入原文件可为空 |
| artifact_type | VARCHAR(32) | 否 | - | IMPORT_FILE/OUTPUT/STDOUT/EVIDENCE等注册类型 |
| storage_provider | VARCHAR(32) | 否 | - | 对象存储注册名 |
| object_key | VARCHAR(512) | 否 | - | 内部对象路径，不含签名URL |
| content_type | VARCHAR(128) | 否 | - | MIME类型 |
| size_bytes | BIGINT | 否 | - | 服务端校验长度 |
| sha256 | CHAR(64) | 否 | - | 服务端校验SHA256 |
| status | VARCHAR(32) | 否 | READY | 只在字节检查通过后创建READY记录 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |

唯一约束：除PK外无。

查询索引：(run_id)。

CHECK：`size_bytes >= 0`；`status IN ('READY', 'DELETED')`。

外键：(run_id) → evaluation_runs(id)；RESTRICT。

## trace_events

规范化、追加式轨迹事件。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| event_id | CHAR(26) | 否 | - | 全局幂等ULID；消息重放必须稳定 |
| run_id | CHAR(26) | 否 | - | 所属Run |
| run_attempt_id | CHAR(26) | 是 | - | 在线必须指向其执行尝试，离线可空 |
| source_event_id | VARCHAR(128) | 是 | - | 上游稳定标识 |
| event_hash | CHAR(64) | 否 | - | 事件规范化SHA256，用于同ID异载荷冲突 |
| trace_id | CHAR(32) | 是 | - | 合法32位小写十六进制非零 |
| span_id | CHAR(16) | 是 | - | 合法16位小写十六进制非零 |
| parent_span_id | CHAR(16) | 是 | - | 父span，可缺失 |
| sequence_no | BIGINT | 是 | - | 来源顺序，可重复，不作唯一键 |
| event_type | VARCHAR(64) | 否 | - | 见事件契约注册表 |
| occurred_at | DATETIME(3) | 否 | - | UTC事件发生时间 |
| payload_json | JSON | 否 | - | 脱敏载荷，单事件上限64KiB |
| artifact_id | CHAR(26) | 是 | - | 大对象引用 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |

唯一约束：(event_id)。

查询索引：(run_id, occurred_at, id)；(trace_id)；(run_id, event_type)；(run_attempt_id, sequence_no, id)；(run_id, run_attempt_id)；(artifact_id)。第四条按尝试读轨迹；`(run_id, run_attempt_id)` 与 `(artifact_id)` 是外键前缀索引。

CHECK：`sequence_no IS NULL OR sequence_no >= 0`。

外键：(run_id) → evaluation_runs(id)；RESTRICT。

外键：(run_id, run_attempt_id) → run_attempts(run_id, id)；RESTRICT。可空列，勾选 optional：离线轨迹没有执行尝试。

外键：(artifact_id) → artifacts(id)；RESTRICT。

## evaluation_batches

一次对待评测 Run 集合按固定方案版本的批量评测；Run 集合由 `evaluation_batch_runs` 固定，重评创建新批次。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| experiment_id | CHAR(26) | 否 | - | 所属实验 |
| evaluation_plan_version_id | CHAR(26) | 否 | - | 本批次使用的已发布方案版本，可不同于实验初始版本 |
| batch_no | INT | 否 | - | 实验内递增，从1开始 |
| trigger_type | VARCHAR(32) | 否 | - | INITIAL/REEVALUATION/MANUAL |
| parent_batch_id | CHAR(26) | 是 | - | 重评来源批次，首次批次为空 |
| initial_experiment_id | CHAR(26) | 是 | 生成列 | `IF(trigger_type = 'INITIAL', experiment_id, NULL)`，STORED；只为 INITIAL 批次产出实验 ID |
| selection_json | JSON | 否 | - | Run选择范围快照：显式runIds或过滤条件与排除原因 |
| status | VARCHAR(32) | 否 | PENDING | 技术状态 |
| expected_job_count | BIGINT | 否 | 0 | 创建Job前冻结的应有Job数 |
| created_by | VARCHAR(128) | 否 | - | 发起人或调度器标识，不做虚假用户外键 |
| started_at | DATETIME(3) | 是 | - | 首次开始时间 |
| finished_at | DATETIME(3) | 是 | - | 终态时间 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：(experiment_id, batch_no)；(id, evaluation_plan_version_id)（供 Job 复合外键引用）；(id, experiment_id)（供批次登记表与父批次复合外键引用，用于强制同实验）；(initial_experiment_id)（保证每个实验最多一个 INITIAL 批次）。

`initial_experiment_id` 是唯一按下述方式"条件唯一"的列：生成表达式只在 `trigger_type='INITIAL'` 时写入 `experiment_id`，其余批次为 `NULL`，而 MySQL 唯一索引允许任意多行 `NULL`，因此每个实验最多一个 INITIAL 批次在**数据库层**成立，不依赖 Controller 判断或事务时序。选择生成列而不是"应用层先查再插"，是因为并发启动两个初始批次时应用层检查会双双通过；生成列 + 唯一键让后到者必然拿到重复键错误。

查询索引：(experiment_id, status, created_at)；(status, created_at, id)；(evaluation_plan_version_id)；(parent_batch_id, experiment_id)。后两条为外键前缀索引，显式声明，不依赖 InnoDB 隐式创建；`experiment_id` 已由唯一键覆盖，不再重复建索引。

CHECK：`batch_no >= 1`；`expected_job_count >= 0`；`trigger_type IN ('INITIAL', 'REEVALUATION', 'MANUAL')`；`status IN ('PENDING', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED', 'CANCELLED')`；`trigger_type <> 'INITIAL' OR parent_batch_id IS NULL`（初始批次不得有父批次）；`parent_batch_id IS NULL OR parent_batch_id <> id`（禁止自引用成环的单步情形，更长的环由服务层拒绝）。

外键：(experiment_id) → experiments(id)；RESTRICT。

外键：(evaluation_plan_version_id) → evaluation_plan_versions(id)；RESTRICT。

外键：(parent_batch_id, experiment_id) → evaluation_batches(id, experiment_id)；RESTRICT。可空列，勾选 optional：初始批次没有父批次；非空时父批次必须属于同一实验，跨实验的重评来源在数据库层被拒绝。

## evaluation_batch_runs

一个批次固定登记的 Run 集合；批次的样本范围在写入时就落库，而不是查询时按条件动态选择。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| batch_id | CHAR(26) | 否 | - | 评测批次（与 run_id 组成主键） |
| run_id | CHAR(26) | 否 | - | 参与该批次的 Run |
| experiment_id | CHAR(26) | 否 | - | 冗余自批次与 Run，供两条复合外键强制同实验 |
| created_at | DATETIME(3) | 否 | - | 加入时间 |

主键：(batch_id, run_id)。关联表没有独立生命周期，使用业务键作复合主键；ULID 代理键只会多一份宽索引，不引入。同一批次重复登记同一 Run 由主键拒绝。

唯一约束：除PK外无。

查询索引：(batch_id, experiment_id)；(run_id, experiment_id)。前者是"批次内 Run 列表"的读取路径并支撑指向批次的外键；后者反查"某 Run 参与过哪些批次"并支撑指向 Run 的外键。

CHECK：无额外CHECK。

外键：(batch_id, experiment_id) → evaluation_batches(id, experiment_id)；RESTRICT。

外键：(run_id, experiment_id) → evaluation_runs(id, experiment_id)；RESTRICT。

两条复合外键共用 `experiment_id` 列，因此"批次属于实验 A、Run 属于实验 B"的组合无法成立：要同时满足两条外键，`experiment_id` 必须既等于批次的实验又等于 Run 的实验。`experiment_id` 本身不再单独建指向 `experiments` 的外键，其一致性由两条父外键传递保证。

## evaluation_jobs

批次内Run×方案项的逻辑评测任务。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| run_id | CHAR(26) | 否 | - | 所属Run |
| batch_id | CHAR(26) | 否 | - | 所属评测批次 |
| evaluation_plan_version_id | CHAR(26) | 否 | - | 冗余自批次，用于复合外键锁定方案项归属 |
| evaluation_plan_item_id | CHAR(26) | 否 | - | 固定方案项，服务层检查属于实验方案 |
| status | VARCHAR(32) | 否 | PENDING | 技术状态 |
| attempt_count | INT | 否 | 0 | 已创建尝试数 |
| active_attempt_id | CHAR(26) | 是 | - | 当前尝试 |
| next_retry_at | DATETIME(3) | 是 | - | 退避时间 |
| input_hash | CHAR(64) | 否 | - | 封口Run及固定算子输入摘要 |
| started_at | DATETIME(3) | 是 | - | 首次领取 |
| finished_at | DATETIME(3) | 是 | - | 终态时间 |
| error_code | VARCHAR(64) | 是 | - | 最终技术失败原因 |
| error_message | VARCHAR(1024) | 是 | - | 脱敏错误摘要 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：(batch_id, run_id, evaluation_plan_item_id)；(run_id, id)。

查询索引：(status, next_retry_at, created_at, id)；(batch_id, evaluation_plan_version_id)；(evaluation_plan_version_id, evaluation_plan_item_id)；(id, active_attempt_id)。中间两条分别是复合外键所需的前缀索引；`(batch_id, run_id)` 无需再建索引，已由唯一键 `(batch_id, run_id, evaluation_plan_item_id)` 以左前缀覆盖。

CHECK：`attempt_count >= 0`；`status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'SKIPPED')`。

外键：(run_id) → evaluation_runs(id)；RESTRICT。单列外键只证明 Run 存在。

外键：(batch_id) → evaluation_batches(id)；RESTRICT。单列外键只证明批次存在。

外键：(batch_id, run_id) → evaluation_batch_runs(batch_id, run_id)；RESTRICT。这条才是语义约束：Job 的 Run 必须已被登记进本批次。它与上面两条单列外键不重复——两条单列外键各自成立时，Run 仍可能属于别的批次。

外键：(batch_id, evaluation_plan_version_id) → evaluation_batches(id, evaluation_plan_version_id)；RESTRICT。

外键：(evaluation_plan_version_id, evaluation_plan_item_id) → evaluation_plan_items(plan_version_id, id)；RESTRICT。

外键：(id, active_attempt_id) → evaluation_job_attempts(job_id, id)；RESTRICT。可空列，勾选 optional：Job 空闲时没有当前尝试。

## evaluation_job_attempts

算子重试历史与租约。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| job_id | CHAR(26) | 否 | - | 所属Job |
| attempt_no | INT | 否 | - | 单调递增 |
| worker_id | VARCHAR(128) | 否 | - | Worker实例 |
| status | VARCHAR(32) | 否 | RUNNING | 尝试状态 |
| lease_token_hash | CHAR(64) | 否 | - | Token摘要 |
| lease_until | DATETIME(3) | 否 | - | 租约截止 |
| deadline_at | DATETIME(3) | 否 | - | 硬截止 |
| completion_hash | CHAR(64) | 是 | - | 回调摘要 |
| result_json | JSON | 是 | - | 本次尝试结果，仅最终合法尝试晋升Result |
| usage_json | JSON | 是 | - | Judge等实际成本 |
| error_code | VARCHAR(64) | 是 | - | 技术错误码 |
| error_message | VARCHAR(1024) | 是 | - | 脱敏错误摘要 |
| started_at | DATETIME(3) | 否 | - | 开始时间 |
| finished_at | DATETIME(3) | 是 | - | 结束时间 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：(job_id, attempt_no)；(job_id, id)。

查询索引：(status, lease_until)。

CHECK：`attempt_no >= 1`；`status IN ('RUNNING', 'COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED')`。

外键：(job_id) → evaluation_jobs(id)；RESTRICT。

## evaluation_results

每个Job唯一最终自动结论。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| job_id | CHAR(26) | 否 | - | 所属Job |
| job_attempt_id | CHAR(26) | 否 | - | 产生结果的合法算子尝试 |
| verdict | VARCHAR(32) | 否 | - | 质量结论 |
| score | DECIMAL(6,5) | 是 | - | 0到1，可空 |
| reason | TEXT | 是 | - | 简要解释 |
| evidence_json | JSON | 是 | - | 事件或附件引用 |
| result_json | JSON | 否 | - | 完整结果，含缺失原因和实现信息 |
| generated_by | VARCHAR(32) | 否 | - | 生成来源 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |

唯一约束：(job_id)。

查询索引：(job_id, job_attempt_id)。唯一键 `(job_id)` 约束"每 Job 一条最终结果"，这条前缀索引支撑"结果必须来自本 Job 的尝试"的复合外键，两者职责不同，都保留。

CHECK：`score IS NULL OR (score >= 0 AND score <= 1)`；`verdict IN ('PASS', 'FAIL', 'INDETERMINATE', 'NOT_APPLICABLE')`；`generated_by IN ('RULE', 'TEST', 'LLM', 'MOCK')`。

外键：(job_id) → evaluation_jobs(id)；RESTRICT。

外键：(job_id, job_attempt_id) → evaluation_job_attempts(job_id, id)；RESTRICT。

## annotation_tasks

人工复核当前状态。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| run_id | CHAR(26) | 否 | - | 复核对象 |
| evaluation_job_id | CHAR(26) | 是 | - | 可针对单算子 |
| status | VARCHAR(32) | 否 | PENDING | 标注状态 |
| assignee_id | VARCHAR(128) | 是 | - | 测试身份或未来用户ID，不建虚假users外键 |
| claimed_at | DATETIME(3) | 是 | - | 领取时间 |
| submitted_at | DATETIME(3) | 是 | - | 提交时间 |
| reviewed_at | DATETIME(3) | 是 | - | 审核时间 |
| version | BIGINT | 否 | 0 | 乐观锁版本 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：除PK外无。

查询索引：(status, created_at, id)；(run_id, evaluation_job_id)。`(run_id)` 不再单独声明：它是 `(run_id, evaluation_job_id)` 的左前缀，已被覆盖。

CHECK：`version >= 0`；`status IN ('PENDING', 'CLAIMED', 'SUBMITTED', 'REVIEWED', 'RETURNED', 'CANCELLED')`。

外键：(run_id) → evaluation_runs(id)；RESTRICT。

外键：(run_id, evaluation_job_id) → evaluation_jobs(run_id, id)；RESTRICT。可空列，勾选 optional：Run 级标注不绑定具体 Job。

## annotation_records

追加式人工操作历史。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| annotation_task_id | CHAR(26) | 否 | - | 所属任务 |
| operator_id | VARCHAR(128) | 否 | - | 操作者 |
| action | VARCHAR(32) | 否 | - | CLAIM/SUBMIT/REVIEW/RETURN/CANCEL |
| verdict | VARCHAR(32) | 是 | - | 提交或审核的质量判断 |
| failure_category | VARCHAR(64) | 是 | - | 归因注册分类，允许OTHER |
| reason | TEXT | 是 | - | 解释 |
| evidence_json | JSON | 是 | - | 事件、附件及标注证据 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |

唯一约束：除PK外无。

查询索引：(annotation_task_id, created_at, id)。

CHECK：`action IN ('CLAIM', 'SUBMIT', 'REVIEW', 'RETURN', 'CANCEL')`；`verdict IN ('PASS', 'FAIL', 'INDETERMINATE', 'NOT_APPLICABLE')`。

外键：(annotation_task_id) → annotation_tasks(id)；RESTRICT。

## import_batches

导入文件与进度，恢复时锁批次行。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| import_type | VARCHAR(32) | 否 | - | DATASET_CASES或OFFLINE_RUNS |
| dataset_version_id | CHAR(26) | 否 | - | 样本导入为草稿；轨迹导入为已发布 |
| experiment_id | CHAR(26) | 是 | - | OFFLINE_RUNS必须有OFFLINE草稿实验 |
| file_artifact_id | CHAR(26) | 否 | - | 校验过的原文件 |
| status | VARCHAR(32) | 否 | PENDING | 批次技术状态 |
| total_count | BIGINT | 否 | 0 | 完成扫描后确定非空物理行数 |
| success_count | BIGINT | 否 | 0 | 含幂等复用行 |
| failed_count | BIGINT | 否 | 0 | 错误行数 |
| duplicate_count | BIGINT | 否 | 0 | 成功行中的幂等复用子集 |
| last_line_no | BIGINT | 否 | 0 | 最后提交的连续物理行号 |
| started_at | DATETIME(3) | 是 | - | 开始时间 |
| finished_at | DATETIME(3) | 是 | - | 结束时间 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：除PK外无。

查询索引：(experiment_id, status)；(status, created_at, id)；(dataset_version_id)；(file_artifact_id)。后两条为外键前缀索引。

CHECK：`total_count >= 0`；`success_count >= 0`；`failed_count >= 0`；`duplicate_count >= 0`；`duplicate_count <= success_count`；`last_line_no >= 0`；`import_type IN ('DATASET_CASES', 'OFFLINE_RUNS')`；`status IN ('PENDING', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED', 'CANCELLED')`。

外键：(dataset_version_id) → dataset_versions(id)；RESTRICT。

外键：(experiment_id) → experiments(id)；RESTRICT。

外键：(file_artifact_id) → artifacts(id)；RESTRICT。

## import_records

每行导入回执，确保重放不重复计数。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| batch_id | CHAR(26) | 否 | - | 批次 |
| line_no | BIGINT | 否 | - | 从1开始的物理行号；空行不建回执 |
| record_hash | CHAR(64) | 否 | - | 原始行字节SHA256，不持有原文 |
| status | VARCHAR(32) | 否 | - | IMPORTED/REUSED/REJECTED |
| dataset_case_id | CHAR(26) | 是 | - | 成功时对应样本 |
| run_id | CHAR(26) | 是 | - | 历史运行导入时对应Run |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |

唯一约束：(batch_id, line_no)。

查询索引：(dataset_case_id)；(run_id)。均为外键前缀索引；`batch_id` 已由唯一键 `(batch_id, line_no)` 覆盖。

CHECK：`line_no >= 1`；`status IN ('IMPORTED', 'REUSED', 'REJECTED')`。

外键：(batch_id) → import_batches(id)；RESTRICT。

外键：(dataset_case_id) → dataset_cases(id)；RESTRICT。

外键：(run_id) → evaluation_runs(id)；RESTRICT。

## import_errors

脱敏错误定位，不存原始敏感行。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| batch_id | CHAR(26) | 否 | - | 所属批次 |
| line_no | BIGINT | 否 | - | 物理行号 |
| error_code | VARCHAR(64) | 否 | - | 结构化原因 |
| error_message | VARCHAR(1024) | 否 | - | 脱敏字段级摘要 |
| record_hash | CHAR(64) | 否 | - | 原始行SHA256 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |

唯一约束：(batch_id, line_no)。

查询索引：无额外查询索引。

CHECK：`line_no >= 1`。

外键：(batch_id) → import_batches(id)；RESTRICT。

## outbox_events

事务消息，TASK-002建表，TASK-011实现发布。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| aggregate_type | VARCHAR(64) | 否 | - | 聚合类型 |
| aggregate_id | CHAR(26) | 否 | - | 多态聚合ID，服务层校验，无虚假多态外键 |
| event_type | VARCHAR(64) | 否 | - | 事件类型 |
| event_version | INT | 否 | 1 | 信封版本 |
| payload_json | JSON | 否 | - | 完整稳定信封，不含正文与密钥 |
| status | VARCHAR(32) | 否 | PENDING | 发布状态 |
| retry_count | INT | 否 | 0 | 发布重试次数 |
| next_retry_at | DATETIME(3) | 是 | - | 下次发布时间 |
| publisher_id | VARCHAR(128) | 是 | - | 当前发布器 |
| lease_until | DATETIME(3) | 是 | - | 发布领取租约 |
| claim_token_hash | CHAR(64) | 是 | - | 发布领取隔离令牌摘要 |
| published_at | DATETIME(3) | 是 | - | 确认发布时间 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：除PK外无。

查询索引：(status, next_retry_at, created_at, id)。

CHECK：`event_version >= 1`；`retry_count >= 0`；`status IN ('PENDING', 'PUBLISHED', 'FAILED')`。

## inbox_events

通知去重记录，TASK-011启用。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| consumer_name | VARCHAR(64) | 否 | - | 消费者逻辑名称 |
| event_id | CHAR(26) | 否 | - | 消息信封eventId，区别于TraceEvent |
| payload_hash | CHAR(64) | 否 | - | 重复信封内容一致性检查 |
| processed_at | DATETIME(3) | 否 | - | 短事务处理提交时间 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |

唯一约束：(consumer_name, event_id)。

查询索引：无额外查询索引。

CHECK：无额外CHECK。

## api_idempotency_keys

创建与状态动作的请求幂等。

| 字段 | 类型 | 可空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | CHAR(26) | 否 | - | 应用层生成ULID，ascii_bin |
| scope | VARCHAR(160) | 否 | - | 身份+操作+资源范围的规范化摘要标识 |
| idempotency_key | VARCHAR(128) | 否 | - | 调用者提供的请求标识 |
| request_hash | CHAR(64) | 否 | - | 规范化正文摘要 |
| resource_type | VARCHAR(64) | 否 | - | 已创建或已操作资源类型 |
| resource_id | CHAR(26) | 否 | - | 结果资源ID，多态无直接外键 |
| http_status | INT | 否 | - | 原始成功响应状态 |
| response_json | JSON | 否 | - | 原始成功响应data，返回时使用当前requestId |
| expires_at | DATETIME(3) | 否 | - | 至少7天，业务唯一键永久生效 |
| created_at | DATETIME(3) | 否 | - | UTC创建时间 |
| updated_at | DATETIME(3) | 否 | - | UTC更新时间 |

唯一约束：(scope, idempotency_key)。

查询索引：(expires_at)。

CHECK：无额外CHECK。


## 实体与映射

每表一个持久化Entity，JSON使用Jackson JsonNode及统一TypeHandler；解析失败抛异常。CHAR去空格规则必须明确，ULID始终26位。不要让数据库排序规则自动更改业务标识。时间精度按毫秒截断，测试非UTC JVM/连接配置，禁止偷偷转为服务器本地时间。

`evaluation_jobs` 的 `batch_id` 与 `evaluation_plan_version_id` 是业务写入时由服务层从批次复制的一次性快照，不允许后续更新；数据库中通过两条复合外键保证三者一致。

`evaluation_batch_runs.experiment_id` 同理是从批次复制的快照列，存在的唯一目的是把"批次与 Run 同实验"变成两条单条外键即可检查的条件；它不单独建指向 `experiments` 的外键，避免多一条同义索引。

Mapper只提供当前任务所需的基础读写，不一次性开放任意状态更新。后续领取和完成必须采用条件更新并检查影响行数，或锁行后变更。Entity不作为API响应。

## 必须在真实MySQL验证

空库迁移、重复版本/样本/Run槽位/Attempt/eventId/Job最终结果、非法外键、非法score与状态、nullable离线AgentVersion、同Case不同Target/重复编号多个Run、UTC与JSON往返。评测批次另外验证：同批次同 Run 同方案项重复 Job 被拒绝；同 Run 在不同批次可各有一个 Job；Job 引用不属于本批次方案版本的 planItem 被复合外键拒绝；`parent_batch_id` 指向其他实验的批次被复合外键拒绝；跨实验 Run 登记进批次被复合外键拒绝；Job 引用未登记在本批次的 Run 被复合外键拒绝；同批次重复登记同一 Run 被主键拒绝；批次方案版本与选择范围不可更新。索引验收：导出 `information_schema.STATISTICS` 与设计字典逐表比对，确认每条外键都有显式左前缀索引、无多余隐式索引。检查所有复合外键真实生效。服务层测试另外覆盖在线AgentVersion必填、跨实验Case归属和发布不可变；不能把服务规则说成数据库CHECK已完成。
