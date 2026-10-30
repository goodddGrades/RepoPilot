# TASK-005 实施交付报告

> 范围：规范 JSONL 的离线轨迹导入与标准化——原文件 Artifact、导入批次、逐行校验与事务、
> 幂等与恢复、离线 Run/Trace 落库、冻结启动后进入统一 Job、前端导入页与缺失槽位展示。
> **未实现**：真实 LiteLLM/公司网关接入、Codex/Claude Code/WorkBuddy 执行器、Judge 与规则算子评分
> （TASK-007）、独立离线评测引擎、TASK-006 的完整检索回放、RabbitMQ Outbox（TASK-011）、Redis 限流。
> 设计依据：`docs/modules/offline-evaluation.md`、`docs/architecture/02-domain-model.md`、
> `04-api-contract.md`、`06-reliability-design.md`、`docs/contracts/offline-run.schema.json`。
> **本轮没有新增或修改数据库迁移**（`db/migration/` 0 个文件），未改动任何已冻结的跨模块契约；
> `tools/gen_java_from_dict.py --check` 与 `tools/gen_ddl_from_dict.py --check` 均通过。

## 1. 结论

| 验收项（`tasks/TASK-005.md`） | 结果 | 依据 |
| --- | --- | --- |
| READY 文件上传、批次创建、流式 JSONL 校验与逐行事务 | 通过 | `ArtifactController`/`ArtifactService`/`ImportService`；`OfflineImportIT.validJsonlShouldImportRunsAndKeepMissingOutputEmpty` |
| 非空行数统计与错误行定位到物理行号 | 通过 | `malformedLinesShouldReportEachProblemAndWriteNothing`（5 行各自成错，行号 exactly 1..5） |
| `import_records`/`import_errors` 回执与计数可重算 | 通过 | 收尾用 `import_records` 聚合覆盖增量；`resumeAfterCrashBeforeFinalizeShouldNotDuplicateRuns` |
| 同批次重放不重复计数 | 通过 | 同 key 同正文返回原批次（202）；异正文 409 `IDEMPOTENCY_CONFLICT` |
| 跨批次相同来源同正文复用 Run | 通过 | `replayingSameFileInAnotherBatchShouldReuseRuns`（Run 数不变、`duplicate_count=2`、`import_batch_id` 保持首次） |
| 相同来源异正文 409 | 通过 | `sameSourceWithDifferentBodyShouldConflict`（`SOURCE_RECORD_CONFLICT`，原轨迹 `output_json` 未被覆盖） |
| 同一槽位被另一来源占用 | 通过 | `sameSlotFromDifferentSourceShouldConflict`（`SLOT_CONFLICT`） |
| 缺 Case 明确报错、只拒该行 | 通过 | `unknownCaseKeyShouldRejectOnlyThatLine`（成功 1 / 失败 1，已是 `PARTIAL_FAILED`） |
| 半途崩溃恢复 | 通过 | `resumeAfterCrashBeforeFinalizeShouldNotDuplicateRuns`（把批次改回 RUNNING 再处理，Run 仍为 2） |
| 取消竞争 | 通过 | `cancelBeforeProcessingShouldKeepBatchCancelledAndWriteNothing`（取消后处理不写任何行） |
| 开始后禁止导入 | 通过 | `importAfterStartShouldBeRejected`（409 `INVALID_STATE_TRANSITION`，未新建批次） |
| 离线 Run 可无 AgentVersion/Attempt | 通过 | `evaluation_runs.mode=OFFLINE`、`attempt_count=0`、`accepted_attempt_id` 缺省、`run_attempts` 0 行 |
| 未知成本/输出保持缺失 | 通过 | `record-002` 行 `output_json IS NULL`；`usage.costKnown=false` 原样保留，不写 0 成本 |
| 轨迹完整度标记保守 | 通过 | `observationMetadataShouldBeDerivedConservativelyFromTheLine`：未声明按"是否显式给 output + 是否有事件"推导（PARTIAL/NONE），自报 `COMPLETE` 因无经验证适配器降级为 `PARTIAL` |
| 单 LLM 调用不伪装 Agent 轨迹 | 通过 | 事件 `run_attempt_id` 为 NULL，不建 Attempt，不写评分 |
| 界面展示处理数/错误行/来源/缺失槽位 | 通过 | 「离线轨迹导入」页（已处理行数、成功/失败/重复、错误明细）+ 实验详情（已导入/缺失）+ Run 详情「来源」 |
| 全量测试 | 通过 | 单元 **75** / 集成 **124** / **0 skipped** / BUILD SUCCESS，见 §5 |
| Worker 测试 / 前端构建 / 设计自检 | 通过 | pytest **28 passed**；`npm run build` 无错误；`verify_design.py` PASS（1254 项） |

## 2. 功能与 API

### 2.1 新增接口（与 `docs/contracts/openapi-import.yaml` 一致）

| 方法与路径 | 行为 |
| --- | --- |
| `POST /api/v1/artifacts` | multipart 上传（`artifactType` 默认 `IMPORT_FILE`，`runId` 可空）；先写存储再写元数据，元数据失败清理已落盘对象；201 |
| `GET /api/v1/artifacts/{id}` | 元数据；**不返回 `objectKey`**，存储路径不外泄 |
| `GET /api/v1/artifacts/{id}/download` | 原始字节流 + `X-Content-Sha256`；异常返回公共错误 JSON |
| `POST /api/v1/imports` | 整文件校验后建 `PENDING` 批次，202；`importType` 本轮只接受 `OFFLINE_RUNS` |
| `GET /api/v1/imports` | 分页，支持 `importType/status/experimentId/datasetVersionId` |
| `GET /api/v1/imports/{id}` | 进度与计数；`totalKnown` 区分"0 行"与"还不知道总行数" |
| `GET /api/v1/imports/{id}/errors` | 行级错误摘要，只有行号/错误码/说明，**不回显原始行内容** |
| `POST /api/v1/imports/{id}/cancel` | 202；已提交行保留，批次终态 `CANCELLED` |

`GET /runs/{id}` 的响应新增 `sourceSystem`、`sourceRecordId`（只有离线 Run 有值），
用于追溯"这条轨迹从哪来"。这是对既有 `RunDetail` 的**追加字段**，老调用方不受影响；
`docs/contracts/openapi-execution.yaml` 已同步。

### 2.2 行级错误码

`INVALID_JSON`、`SCHEMA_VIOLATION`、`UNSUPPORTED_SCHEMA_VERSION`、`INVALID_TIMESTAMP`、
`INVALID_TIME_RANGE`、`CASE_NOT_FOUND`、`TARGET_NOT_FOUND`、`REPEAT_INDEX_OUT_OF_RANGE`、
`SOURCE_RECORD_CONFLICT`、`SLOT_CONFLICT`、`EXPERIMENT_NOT_FOUND`、`IMPORT_CLOSED`、`IMPORT_CANCELLED`。

错误码与行号一起写 `import_errors`；同一 `(batch_id, line_no)` 唯一，重复写入用
`ON DUPLICATE KEY UPDATE id = id` 幂等收敛。

### 2.3 前端

- 新增「离线轨迹导入」页（`/imports`，导航第 7 项）：选择草稿态离线实验 → 上传 JSONL（进度条）→
  创建批次 → 轮询至终态 → 成功/失败/重复统计 → 错误明细对话框（行号可直接跳编辑器）；支持取消。
- 实验详情页：离线实验显示「已导入 N / 计划 M，缺 K 个槽位」，草稿态给出「去导入轨迹」入口。
- Run 详情页：离线 Run 显示「来源」（`sourceSystem · sourceRecordId`）。
- 新增 `src/api/imports.ts`、`ImportBatchView`/`ImportErrorView`/`ArtifactView` 类型；
  `ExperimentView` 补 `importedRunCount`/`missingRunCount`。

## 3. 数据流与状态转换

```
上传      POST /artifacts            → artifacts(artifact_type=IMPORT_FILE, run_id=NULL, status=READY)
受理      POST /imports              → 校验 Artifact 可读、sha256 与元数据一致、文件非空、
                                       行数与单行长度未超限 → import_batches(PENDING) → 202
处理      后台单线程 resume(batchId) → PENDING→RUNNING，逐行：
            校验  OfflineRunValidator（schema/时间/枚举，首个错误即定位该行）
            事务  锁批次（CANCELLED 即停）→ 查回执跳过 → 锁实验行校验 DRAFT
                  → Target/caseKey/repeatIndex 校验 → 来源身份去重 → 槽位去重
                  → 写 Run + Trace 事件 + IMPORTED 回执 + 增量计数
            拒绝  独立短事务写 REJECTED 回执 + import_error
收尾      按 import_records 聚合重算计数 → COMPLETED / PARTIAL_FAILED / FAILED（取消则保持 CANCELLED）
冻结启动  POST /experiments/{id}/start → 已导入 Run：PENDING→EVALUATING，写 sealed_at，
                                       建 INITIAL 批次 + PENDING Job；不建 Attempt、不写评分
```

要点：

- **逐行事务**：一行失败只回滚这一行，已提交行不受影响；`cancel` 在下一行的写入事务里被感知，
  不存在"检查通过后仍写入"的窗口。
- **计数可信**：过程中用增量让页面有进度，收尾一律从 `import_records` 聚合重算，
  崩溃重放或多实例都不会漂移。
- **两种摘要**：`import_records.record_hash` 取原始行字节（去行末 CR/LF）SHA-256，用于错误追溯；
  `evaluation_runs.source_record_hash` 取规范化业务 JSON 摘要，用于跨批次语义幂等。
  换行符或字段顺序变化不会造成"假的来源冲突"。
- **不伪造**：离线 Run 的 `attempt_count=0`、无 `run_attempts`、事件 `run_attempt_id` 为 NULL；
  缺失 `output` 就保持 `NULL`，`costKnown=false` 原样保存。
- **完整度如实标注**：`observation_metadata_json` 是 NOT NULL 列，缺省时不再写空对象，而是按
  `docs/modules/trace-pipeline.md` 保守推导——`outputKnown` 只看是否显式给出 `output`，
  `traceCoverage` 有事件为 `PARTIAL`、无事件为 `NONE`；JSONL 导入没有可核验的适配器，
  因此行内自报 `COMPLETE` 也降级为 `PARTIAL`。原始行不受影响（Artifact 与 `record_hash` 完整保留），
  改写的只是这份派生元数据。

## 4. 演示步骤（实际执行）

```bash
# 后端（dev profile；默认 repopilot.import.auto-process=true，批次由后台线程推进）
cd repopilot-server
SERVER_PORT=18080 REPOPILOT_WORKER_TOKEN=local-demo-token ./mvnw spring-boot:run

# 端到端演示（10 步，见 tools/demo-task-005.sh）
BASE=http://127.0.0.1:18080/api/v1 bash tools/demo-task-005.sh
```

实测输出（本机，2026-10-19）：

```
第 3 步  实验草稿        status=DRAFT expectedRunCount=2 importedRunCount=0 missingRunCount=2
第 4 步  上传原文件       sizeBytes=400 sha256=3f2c4c7b… status=READY（响应无 objectKey）
第 5 步  创建批次        status=PENDING importType=OFFLINE_RUNS
第 6 步  轮询至终态      COMPLETED totalCount=1 totalKnown=true successCount=1 failedCount=0 lastLineNo=1，errors total=0
第 7 步  实验详情        importedRunCount=1 expectedRunCount=2 missingRunCount=1
第 8 步  重复导入        COMPLETED successCount=1 duplicateCount=1，实验内 Run 总数仍为 1
第 9 步  启动实验        status=QUEUED；Run mode=OFFLINE status=EVALUATING attemptCount=0
                        traceEventCount=1 taskOutcome=UNKNOWN；Job total=1 全部 PENDING
第 10 步 开始后再导入    HTTP 409
```

`examples/dataset-cases.jsonl` + `examples/offline-runs.jsonl` 按 `examples/README.md` 的说明
进入了统一 Run/Job，`caseKey`/`targetKey`/`repeatIndex` 全部命中。

## 5. 实际执行的命令与结果

### 5.1 后端全量

```bash
export REPOPILOT_IT_DB_HOST=127.0.0.1 REPOPILOT_IT_DB_PORT=13306 REPOPILOT_IT_DB_NAME=repopilot_it \
       REPOPILOT_IT_DB_USER=repopilot REPOPILOT_IT_DB_PASSWORD=repopilot_it_only \
       REPOPILOT_IT_REQUIRE_ALL_UP=true \
       SPRING_RABBITMQ_HOST=127.0.0.1 SPRING_RABBITMQ_PORT=5672 \
       SPRING_RABBITMQ_USERNAME=repopilot SPRING_RABBITMQ_PASSWORD=repopilot_dev_pwd
./mvnw -B verify
```

| 项 | 结果 |
| --- | --- |
| 单元测试（Surefire） | **75** 通过 / 0 失败 / 0 跳过 |
| 集成测试（Failsafe，真实 MySQL 8.0.22 + 真实 RabbitMQ） | **124** 通过 / 0 失败 / **0 跳过** |
| 其中 `OfflineImportIT` | **18** 通过 |
| 构建 | BUILD SUCCESS |

### 5.2 其他检查

| 命令 | 结果 |
| --- | --- |
| `pytest -q`（`repopilot-worker/.venv`） | **28 passed**（1 条 starlette 弃用告警） |
| `npm run type-check` | 通过（无输出即无错误） |
| `npm run build`（`vue-tsc --noEmit && vite build`） | 通过，1728 模块，`ImportListView` chunk 11.9 kB |
| `python tools/verify_design.py` | PASS（1254 项检查 / 28 表 / 4 JSON Schema） |
| `python tools/gen_java_from_dict.py --check` | OK：76 个 Java 文件与字典一致（28 Mapper） |
| `python tools/gen_ddl_from_dict.py --check` | OK：6 个迁移文件与字典一致，冻结项未被改动 |
| OpenAPI 引用完整性（本地脚本校验） | `openapi-import.yaml` 7 条路径，172 处 `$ref` 全部可解析 |

## 6. 设计差异与已知限制

1. **`importType=DATASET_CASES` 不在本接口受理**。可靠性设计规定 `DATASET_CASES` 只可能
   `COMPLETED/FAILED`，`PARTIAL_FAILED` 是留给离线轨迹行级失败的状态；TASK-003 已有整批原子入口
   `POST /dataset-versions/{id}/cases:import`。因此 `/imports` 收到 `DATASET_CASES` 返回 400 并在
   字段错误里指向该入口，避免出现"两个入口写同一张表、语义却不同"。
2. **`GET /runs/{id}/events` 明确属于 TASK-006，不是 TASK-005 的缺口**。依据：`tasks/TASK-005.md`
   的实施内容只有「上传 / 批次 / 校验与逐行事务 / 离线 Run 绑定 / 界面」，没有事件读取；
   `tasks/TASK-006.md` 实施内容第 1 条即「Run/Attempt 事件筛选与签名游标，按 occurredAt,id 稳定排序」，
   并通过验收项约束「游标过滤变化不能复用、封口轨迹无重复遗漏」。该端点在本轮只交付了
   `RunDetail.traceEventCount` 与库内证据，读取接口留给 TASK-006，避免交付半成品。
3. **修正了 `examples/dataset-cases.jsonl` 的 `taskType`**：原值 `QA` 不在白名单
   （`AGENT_TASK`/`LLM_CALL`），按 README 步骤导入会被 `DatasetCaseValidator` 拒绝，示例无法自洽。
   已改为 `AGENT_TASK`，未改任何 Schema。
4. **`missingRunCount` 的计算口径**：离线实验在 DRAFT 阶段 `expected_run_count` 仍为 0，
   详情接口按"已发布样本数 × repeatCount"实时计算完整槽位（启动时冻结写的也是同一个数），
   因此导入过程中页面就能回答"还差多少条"。在线实验该字段恒为 0。
5. **单进程串行处理**：导入用单线程执行器 + 批次行锁。多实例部署时同一批次只会有一个实例推进
   （`markRunning` 条件更新），但吞吐上限是单批次串行；这是 V1 的有意取舍，压测与并行化留给 TASK-012。
6. **未做真实外部格式适配**：只接受 `offline-run.schema.json` 定义的规范 JSONL，
   不解析 LiteLLM 原始导出格式（`docs/modules/offline-evaluation.md` 明确不在本任务范围内）。

### 6.1 两项针对性契约核查（推送前复检）

**核查一：离线 Run 的 `attemptCount=0` 与 `traceEventCount=1` 是否符合冻结契约。**

- `attempt_count=0`：符合。`02-domain-model.md`「在线与离线」第 7 条明确「离线 Run 只是缺少 RunAttempt，
  不另建评测表」，`06-reliability-design.md` 的离线状态图为 `PENDING→EVALUATING→COMPLETED`，中间没有
  Attempt 阶段；`offline-run.schema.json` 也不接受任意 Attempt 字段。因此 `attempt_count=0`、
  `run_attempts` 0 行、`accepted_attempt_id` 为空是契约要求的形态，测试已逐项断言。
- `traceEventCount=1`：符合。它是 `RunDetail` 的既有必填字段（`openapi-execution.yaml`），
  统计 `trace_events` 行数，与是否有 Attempt 无关；离线事件的 `run_attempt_id` 按
  `docs/modules/trace-pipeline.md`「摄取」一节的明确要求置 NULL。
- **发现并修复的问题**：`traceCoverage` 标记原先缺省写空对象 `{}`，违反同一节「成本」段落的规则——
  离线未声明时应保守置 `outputKnown=是否显式提供 output`、`traceCoverage=PARTIAL（有事件）/NONE（无事件）`，
  且只有经验证的适配器才能声明 `COMPLETE`。已改为保守推导并对自报 `COMPLETE` 降级为 `PARTIAL`，
  原始行不受影响。新增用例 `observationMetadataShouldBeDerivedConservativelyFromTheLine`
  同时断言数据库 JSON 列与 `GET /runs/{id}` 响应。

**核查二：`GET /runs/{id}/events` 是否属于 TASK-005。**

不属于，归 TASK-006。`tasks/TASK-005.md` 的四条实施内容不含事件读取；`tasks/TASK-006.md` 的实施内容第 1 条
即「实现 Run/Attempt 事件筛选与签名游标，按 occurredAt,id 稳定排序」，验收项进一步要求
「游标过滤变化不能复用，封口轨迹无重复遗漏」。因此 TASK-005 没有遗漏必需接口：
本轮所需的事件证据以 `RunDetail.traceEventCount` + `trace_events` 行（类型、来源事件号、序号、
`occurred_at`、`payload`）交付，读取与分页留给 TASK-006。

## 7. 提交

本任务 4 个功能提交 + 1 个 CI 补记提交；前 4 个合计 38 个文件（4634+/73−），补记提交只改本文件：

| Commit | 内容 |
| --- | --- |
| `6d41cc3` | feat：后端导入与 Artifact 实现、Mapper 追加、Run 详情来源字段 |
| `96e2c24` | test：真实 MySQL 集成测试（基类 + 18 条用例）与测试配置开关 |
| `c71d7bb` | feat：前端导入页、实验详情缺失槽位、Run 详情来源 |
| docs 提交（即本文件所在提交，amend 后哈希会变化，故不写自身） | docs：OpenAPI、演示脚本、示例 taskType 修正、本报告 |

## 8. CI 实跑（GitHub Actions）

推送 `41fc2d5..7c0606c`（fast-forward，未 force）后，run **39227535989** 的 5 个 Job 全部 `success`：

| Job | 结果 | 实测数字 |
| --- | --- | --- |
| 后端（MySQL 8.0.36 + RabbitMQ） | success | 单元 **75** / 集成 **124** / **0 skipped** |
| 后端（MySQL 8.0 + RabbitMQ） | success | 单元 **75** / 集成 **124** / **0 skipped** |
| Web（构建） | success | 1728 modules transformed，`built in 5.09s` |
| 设计字典自检 | success | `OK：76 个 Java 文件与字典一致（28 个 Mapper）` |
| Worker（pytest） | success | **28 passed**, 1 warning（0.42s） |

两条 MySQL 腿与本地 `./mvnw -B verify` 的数字完全一致，说明本机隔离实例（8.0.22）与 CI 的
8.0 / 8.0.36 在本次改动范围内行为相同。未创建、未合并 PR。

以上结果对应代码提交 `7c0606c`；本报告随后仅追加该段，不含任何代码改动。

核对方式：`git log --oneline origin/develop..HEAD`、`git diff origin/develop..HEAD --stat`
（38 files changed, 4634 insertions, 73 deletions）；工作区干净（`git status --short` 无输出）。
敏感信息扫描：对新增行匹配私钥/AK/`ghp_`/`sk-`/`xox`/`password=`/`secret=`/长 `Bearer`，无命中；
未提交 `.env`、`node_modules/`、`target/`、`.venv/`、`dist/`、`tmp/`。
