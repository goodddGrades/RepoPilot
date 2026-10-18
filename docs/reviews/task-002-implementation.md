# TASK-002 实施交付报告

> 范围：数据库领域模型、Flyway 迁移、持久化基础、公共组件扩展、测试分层与 CI。
> **未实现**任何业务 API、页面、Runner、Judge、Outbox 发布器或幂等中间件（TASK-003 起）。
> 设计依据：`docs/reviews/task-002-freeze.md`（V1.2）与 `task-002-v121-delta.md`（V1.2.1 三项约束）。

## 1. 结论

| 验收项 | 结果 |
| --- | --- |
| 28 张表与全部主外键/索引/CHECK | 落实，真实 MySQL 空库迁移通过 |
| 字典 ↔ 实际 DDL 逐项一致 | 自动化比对通过（列、类型、可空、主键/唯一/索引、外键、CHECK） |
| 每条外键有显式左前缀索引、无隐式索引 | 106 个索引名 = 28 PRIMARY + 32 UNIQUE + 46 二级，与字典完全相等 |
| JSON / UTC / ULID / 枚举映射 | 集成测试通过（含非 UTC JVM 场景） |
| 公共组件复用、契约未改 | 复用；仅错误码枚举值收敛 + 映射扩展 |
| Surefire / Failsafe 分层 | 落实，`mvn verify` 真正执行 `*IT` |
| GitHub Actions | **已编写，未实际运行**（本轮无 push 授权） |
| 真实库验收 | 在本地隔离 MySQL 8.0.22 实例完成 |

## 2. 交付物

### 迁移（`repopilot-server/src/main/resources/db/migration/`）

| 文件 | 内容 | 表数 |
| --- | --- | --- |
| `V1__baseline.sql` | 未改动（空基线） | 0 |
| `V2__identity_and_configuration.sql` | Agent、数据集、算子、方案与版本项 | 9 |
| `V3__experiment_and_execution.sql` | 实验、Target、Run、Attempt、Artifact、TraceEvent | 6 |
| `V4__evaluation_batch_and_result.sql` | 批次、批次 Run 登记、Job、JobAttempt、Result | 5 |
| `V5__annotation_and_import.sql` | 标注与导入记账 | 5 |
| `V6__reliability_helpers.sql` | Outbox / Inbox / API 幂等 | 3 |
| `V7__deferred_foreign_keys.sql` | 7 条延后外键（循环引用与父表后建） | 0 |

V7 处理的具体外键：`evaluation_runs` 的 `active_attempt_id` / `accepted_attempt_id` /
`output_artifact_id` / `import_batch_id`，`run_attempts.output_artifact_id`，
`evaluation_batches.parent_batch_id+experiment_id`，`evaluation_jobs.active_attempt_id`。
**全程没有关闭 `foreign_key_checks`**，也没有用 `repair` 掩盖半完成状态。

### 实际 DDL 导出

`docs/reviews/task-002-actual-ddl.sql`（42 KB，599 行）不是手写文档，而是从真实库导出的对象定义：

```
空库 repopilot_ddl_probe（utf8mb4 / utf8mb4_0900_ai_ci）
  → SchemaMigrationIT 触发 Flyway V1..V7
  → mysqldump --no-data
  → 剔除 flyway_schema_history 块
```

导出后再次用 `information_schema` 独立计数复核，与字典完全相等（不含 Flyway 元数据表）：

| 项 | 实际值 |
| --- | --- |
| 业务表 | 28 |
| 主键 | 28 |
| 唯一键 | 32 |
| 二级索引 | 46 |
| 外键 | 47 |
| CHECK 约束 | 60 |
| 索引名合计 | 106（28 + 32 + 46，无隐式索引） |
| `flyway_schema_history` 最高版本 | 7 |

### 代码

| 类别 | 位置 | 数量 |
| --- | --- | --- |
| 生成器（字典 → DDL / Java） | `tools/gen_ddl_from_dict.py`、`tools/gen_java_from_dict.py` | 2 |
| 迁移 SQL | `db/migration` | 6 |
| 持久化实体 | `modules/*/entity`、`common/reliability/entity` | 28 |
| 基础 Mapper | `modules/*/mapper`、`common/reliability/mapper` | 28 |
| 状态枚举（带 `@DictionaryCheck`） | `common/state` | 20 |
| 手写公共组件 | `common/id`、`common/persistence`、`common/state`、`common/api` | 7 |

为什么用生成器：字段、类型、可空性、枚举取值都在字典里，手抄 28 张表必然漂移，而漂移只能靠人眼发现。
生成后 CI 用 `--check` 保证"有人改了字典却没重新生成"会立刻失败。

### 测试

| 层 | 文件 | 数量 |
| --- | --- | --- |
| 单元 | `UlidTest`(6)、`TransitionsTest`(9)、`PageQueryTest`(4)、`ApiResponseTest`(4)、`GlobalExceptionHandlerTest`(5)、`RequestIdFilterTest`(4)、`HealthServiceTest`(4)、`HealthControllerTest`(2) | **38** |
| 集成 | `SchemaMigrationIT`(4)、`SchemaDictionaryIT`(5)、`EnumDictionaryIT`(1)、`ConstraintIT`(16)、`PersistenceMappingIT`(8)、`ApiHealthIT`(4，其中 1 条 CI 专用) | **38** |

`ConstraintIT` 覆盖的拒绝场景（断言 MySQL 错误码而不是"抛异常"）：同实验第二个 INITIAL 批次、
INITIAL 带父批次、批次自引用、跨实验父批次、同批次重复登记 Run、跨实验登记 Run、
Job 的 Run 未登记、Job 引用其他方案版本的 planItem、同批同 Run 同 planItem 重复 Job、
重复 attemptNo、同 Job 第二个最终结果、score 越界、未知状态、删除被引用 Run、
重复 eventId。同时断言"同 Run 进第二个批次是允许的"——只测拒绝不测放行，会把模型收紧成不能用。

`SchemaDictionaryIT` 是本任务最有价值的一条：它把 `information_schema` 与字典逐项比对，
包括"每张表索引数量必须等于设计数量"，因此任何漏建或 InnoDB 隐式建索引都会失败。

## 3. 实际执行记录

环境：Windows + 本地隔离 MySQL 实例（`mysqld --no-defaults` 独立数据目录，端口 13306，MySQL **8.0.22**），
JDK 21.0.10（`~/.jdks/ms-21.0.10`）。没有使用 Docker（本机 `wsl.exe` 被安全策略拦截），
也没有用 H2/SQLite 替代。

| 命令 | 目录 | 结果 |
| --- | --- | --- |
| `./mvnw -o test` | `repopilot-server/` | 38 通过，0 失败，0 跳过，退出码 0 |
| `./mvnw -o verify` | `repopilot-server/` | 单测 38 + 集成 38（1 跳过），BUILD SUCCESS |
| `python tools/verify_design.py` | 仓库根 | PASS，1278 项，28 表 |
| `python tools/gen_ddl_from_dict.py --check` | 仓库根 | 6 个迁移与字典一致 |
| `python tools/gen_java_from_dict.py --check` | 仓库根 | 76 个 Java 文件与字典一致 |
| `python -m pytest -q` | `repopilot-worker/` | 14 通过 |
| `npm run build` | `repopilot-web/` | 构建成功（仅有 chunk 体积提示） |

集成测试连接参数：`REPOPILOT_IT_DB_HOST=127.0.0.1`、`PORT=13306`、`NAME=repopilot_it`、`USER=root`、空密码。
测试库在运行前为空库，Flyway 从 V1 建到 V7。

**唯一跳过项**：`ApiHealthIT.readinessShouldBeUpWhenEveryDependencyIsAvailable`，
它需要 RabbitMQ，本机没有；该用例由 `REPOPILOT_IT_REQUIRE_ALL_UP=true` 开启，CI 中会执行。
除它之外没有被 `@Disabled` 或 assumption 静默跳过的测试。

### 3.1 空库复现（第二轮独立验证）

首次验收跑在 `repopilot_it` 上，为了排除"历史残留库恰好通过"，交付前又新建了一个从未被迁移过的空库重跑：

| 命令 | 结果 |
| --- | --- |
| `CREATE DATABASE repopilot_ddl_probe`（全新空库） | — |
| `./mvnw -o failsafe:integration-test -Dit.test=SchemaMigrationIT`（`REPOPILOT_IT_DB_NAME=repopilot_ddl_probe`） | Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 |
| `information_schema` 独立复核 | 28 表 / 32 唯一键 / 46 二级索引 / 47 外键 / 60 CHECK |
| `mysqldump --no-data` | 生成 `task-002-actual-ddl.sql` |

结论：迁移链在全新空库上一次性建到 V7，不存在"依赖二次执行才成立"的对象。

### 3.2 提交

| 项 | 值 |
| --- | --- |
| 分支 | `develop` |
| Commit | `5299e46f5372499601dc1227577506a01584769b` |
| 短哈希 | `5299e46` |
| 变更规模 | 133 files changed, +9386 / −124 |
| 工作区 | clean（`git status --short` 无输出） |
| 远程状态 | **未 Push**（本轮无授权） |

## 4. 公共组件的复用与改动

| 组件 | 动作 |
| --- | --- |
| `ApiResponse` / `FieldViolation` / `RequestIdHolder` | **未改动**（信封字段与 JSON 形状保持冻结） |
| `ErrorCode` | 按契约收敛枚举值：`NOT_FOUND→RESOURCE_NOT_FOUND`、`DEPENDENCY_UNAVAILABLE→SERVICE_UNAVAILABLE`、删除兜底 `BUSINESS_ERROR`，新增 400/401/403/409/413/429 细分码 |
| `GlobalExceptionHandler` | 在原有链路上扩展：`DuplicateKeyException→409 RESOURCE_CONFLICT`、`DataIntegrityViolationException→400`，日志只记根因、响应不含 SQL 与约束名 |
| `RequestIdFilter` | 生成器由 UUID 改为 ULID，其余规则（合法透传、MDC、finally 清理）不变 |
| `BusinessException` | 删除"默认 409"的便捷构造器，强制调用方选具体码 |
| 健康检查 | **未改动**语义；`ApiHealthIT` 补充验证 liveness 不依赖外部系统 |

新增：`Ulid`、`UlidTypeHandler`、`JsonNodeTypeHandler`、`InstantUtcTypeHandler`、`PageQuery`、
`PageResponse`、`DictionaryCheck(s)`、`Transitions`、20 个状态枚举。

两个故意的设计选择：

- **`UlidTypeHandler` 不做全局注册**。库里还有 `CHAR(64)` 的哈希列，如果按"String + CHAR"全局注册，
  哈希值会被当成 ULID 校验而拒绝合法数据。因此它只在 Mapper 的 id 参数上显式引用。
- **`InstantUtcTypeHandler` 显式传 UTC Calendar**，不依赖 JVM 默认时区或会话时区。
  `PersistenceMappingIT` 把 JVM 时区切到 `Asia/Shanghai` 后断言库里存的是 UTC 字符串。

## 5. 与设计的偏差（3 处，均已记录理由）

1. **一条 CHECK 改成 MySQL 标准形**：`NOT (output_json IS NOT NULL AND output_artifact_id IS NOT NULL)`
   → `output_json IS NULL OR output_artifact_id IS NULL`。MySQL 解析时本就做同样的 De Morgan 重写，
   不改则"字典 ↔ 库"永远逻辑相等、文本不等，逐项比对只能放宽。语义未变。详见 `task-002-v121-delta.md` §6。
2. **`initial_experiment_id` 生成列**：V1.2.1 授权项，实现为 `STORED` 生成列 + 唯一键，
   并在真实库验证"第二个 INITIAL 批次被 1062 拒绝、多个 REEVALUATION 正常"。
3. **`modules/report` 包**：保留 `package-info.java`，内容改为"报告是 experiment 的读取视图，
   TASK-010 需要时再建"，不再是空壳。

## 6. 未完成与受限

| 项 | 状态 | 说明 |
| --- | --- | --- |
| GitHub Actions 实际运行 | **未运行** | 本轮无 push 授权；workflow 已写好，需 push 后跑 |
| RabbitMQ 相关断言 | 未在本机执行 | 本机无 RabbitMQ；readiness 全 UP 用例在 CI 执行 |
| MySQL 版本覆盖 | 仅 8.0.22 | CI 用 `mysql:8.0` 最新补丁版，版本差异由 CI 覆盖 |
| 业务功能 | 未实现 | 按范围禁止：无 API、无页面、无 Runner/Judge |

## 7. 下一步（等负责人授权）

1. Push 到 `develop` 并跑 CI，用真实 Run ID 补齐"数据库集成验收"的最后一环。
2. 授权后进入 TASK-003（Agent 与数据集管理），复用本任务的实体、Mapper 与状态机。
3. 若 CI 在 MySQL 8.0 最新版上出现差异（例如 `information_schema` 文本格式变化），
   先修比对逻辑而不是放宽断言。
