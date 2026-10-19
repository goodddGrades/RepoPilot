# TASK-004 实施交付报告

> 范围：在线 Mock 执行与 Worker 基础——实验草稿/提交/取消、Run 槽位、Worker 领取与租约、
> 事件与封口、待评测 Job、Python Mock Worker、前端实验与 Run 证据页。
> **未实现**：真实 Codex 适配（TASK-008）、规则评测引擎与评分（TASK-007）、离线 JSONL 导入（TASK-005）、
> Trace 检索与可视化（TASK-006）、RabbitMQ Outbox（TASK-011）。
> 设计依据：`docs/architecture/04-api-contract.md`、`05-event-contract.md`、`06-reliability-design.md`。
> **本轮没有新增或修改数据库迁移**（`db/migration/` 0 个文件），也未改动任何已冻结的跨模块契约。
>
> **最终验收补充（2026-10-19）**：本机 RabbitMQ 实际可用，故开启 `REPOPILOT_IT_REQUIRE_ALL_UP=true`
> 复跑全量，结果为单元 **75** / 集成 **106** / **0 skipped** / BUILD SUCCESS。§1 与 §5 已替换为本轮实测数字，
> §6.1 补充了敏感信息检查结论。代码未改动（除本报告），没有为消除跳过而删改任何测试。

## 1. 结论

| 验收项（`tasks/TASK-004.md`） | 结果 | 依据 |
| --- | --- | --- |
| 2 Target × 3 Case × 2 重复 = 12 Run，且只建一个 INITIAL 批次 | 通过 | `ExecutionFlowIT.startShouldGenerateTwelveRunsAndOneInitialBatch`；演示脚本第 5 步 `total:12` |
| 基础设施重试不增加 Run（只追加 Attempt） | 通过 | `ExecutionFlowIT.retryableFailureShouldAppendAttemptOnly`（Run 数不变、attemptNo=2） |
| 同一 Run 只能有一个有效 Attempt | 通过 | `ExecutionConcurrencyIT.concurrentClaimShouldGiveEachRunExactlyOneAttempt`（4 线程并发领取，`HAVING COUNT(*)>1` 为 0） |
| 失效 Worker 不能续租 / 写事件 / 写成功 | 通过 | `ExecutionFlowIT.expiredLeaseShouldRejectLateCompleteAndAllowNewAttempt`（三条路径都 409 `STALE_ATTEMPT`） |
| 重复 start / 重复 complete 不产生重复数据 | 通过 | `duplicateStartShouldBeIdempotent`、`completeShouldSealRunAndCreateOnePendingJob`（同正文重放回原回执，Job 仍 1 条） |
| 完成丢响应可同正文重放；异正文 409 | 通过 | 同上 + `completionHash` 比对（`WorkService`） |
| 提交后崩溃重启，扫描能推进 PENDING/QUEUED | 通过 | `RunMaintenanceScheduler` + `promotePending/promoteRetries`（用例中直接驱动 `workService.maintain()`，不依赖时序） |
| 取消与回调竞争不复活 | 通过 | `cancelRacingWithCompleteShouldNotResurrectRun`（最终必为 `CANCELLED`，无 PENDING Job 残留） |
| 合法完成后 EVALUATING 且幂等生成 PENDING Job | 通过 | `completeShouldSealRunAndCreateOnePendingJob`；真实 Worker 联调：4 个 Run 全部 `EVALUATING`、4 个 Job `PENDING` |
| 全量测试 | 通过 | `mvn verify`（`REPOPILOT_IT_REQUIRE_ALL_UP=true` + 真实 RabbitMQ）：单元 **75** / 集成 **106** / **0 skipped** / BUILD SUCCESS，见 §5.1 |
| Worker 单元测试 | 通过 | `pytest`：**28 passed** |
| 前端构建 | 通过 | `npm run build`（`vue-tsc --noEmit` + `vite build`）无错误 |

**按要求未做**：没有调用任何真实 Agent；Mock 输出固定带 `mock=true` 与 `runner=MOCK`；Job 一律 `PENDING`，
没有写入任何 `evaluation_results` 行——页面上"待评测"就是待评测，不是 0 分。

## 2. 实际跑通的业务流程（演示步骤）

```bash
# 1) 后端（dev profile + Worker 凭证；凭证为空时 /internal/v1 一律 401）
cd repopilot-server
SERVER_PORT=18080 REPOPILOT_WORKER_TOKEN=local-demo-token ./mvnw spring-boot:run

# 2) 一键复现主链路（12 步，含领取/事件/封口/重试/取消）
BASE=http://127.0.0.1:18080/api/v1 WORKER_TOKEN=local-demo-token bash tools/demo-task-004.sh

# 3) 真实 Python Worker 执行一轮（可选，验证跨语言协议）
cd repopilot-worker
REPOPILOT_WORKER_TOKEN=local-demo-token WORKER_SERVER_BASE_URL=http://127.0.0.1:18080 \
  WORKER_MAX_TASKS=10 ./.venv/Scripts/python.exe -m uvicorn app.main:app --port 8000
curl -X POST http://127.0.0.1:8000/worker/run-once

# 4) 前端
cd repopilot-web && VITE_API_PROXY_TARGET=http://127.0.0.1:18080 npm run dev
```

本次实际运行结果（原样摘录）：

- 提交：`"status":"QUEUED"`、`"total":12`（2 Target × 3 样本 × 2 重复）
- 领取：返回 `WorkItem{runId, attemptId, attemptNo:1, leaseToken, leaseUntil, deadlineAt, input{...}}`
- 事件：5 条 `ACCEPTED`，`manifestSha256=642ae1b3…381e`
- 完成：`"status":"COMPLETED"`；Run 详情 `"status":"EVALUATING"`、`"taskOutcome":"SUCCESS"`、
  `"attemptCount":1`、`"traceEventCount":5`；评测任务 `"status":"PENDING"`
- 重试：`fail(retryableHint=true)` → `"retryScheduled":true`，实验 Run 数仍为 12
- 取消：实验与 Run 均为 `CANCELLED`
- 真实 Worker：`{"claimed":4,"completed":4,"failed":0,"abandoned":0,"errors":[]}`，随后 4 个 Run 全部
  `EVALUATING`、实验 `RUNNING`、4 个 Job `PENDING`

## 3. 交付物

### 3.1 API（14 个端点，OpenAPI 见 `docs/contracts/openapi-execution.yaml`）

| 分组 | 端点 |
| --- | --- |
| 实验 | `POST /api/v1/experiments`、`GET /api/v1/experiments`、`GET /api/v1/experiments/{id}`、`POST /api/v1/experiments/{id}/start`、`POST /api/v1/experiments/{id}/cancel` |
| Run 读取 | `GET /api/v1/experiments/{id}/runs`、`GET /api/v1/runs/{id}`、`GET /api/v1/runs/{id}/attempts`、`GET /api/v1/runs/{id}/evaluation-jobs` |
| Worker 协议（Bearer） | `POST /internal/v1/work/claim`、`POST /internal/v1/run-attempts/{id}/heartbeat`、`.../events`、`.../complete`、`.../fail`、`POST /internal/v1/artifacts` |

前端类型同步补齐：`repopilot-web/src/types/domain.ts`（Experiment/Run/Attempt/Job 等 11 个类型）。

### 3.2 状态转换

- 实验：`DRAFT → QUEUED`（提交）→ `RUNNING`（第一个任务被领走）→ `COMPLETED / PARTIAL_FAILED / FAILED`；
  `DRAFT / QUEUED / RUNNING → CANCELLED`。`RUNNING` 之前的失败不会写成终态（`Transitions` 校验后聚合才落库）。
- Run（在线）：`PENDING → QUEUED → RUNNING → EVALUATING`；`RUNNING → FAILED / TIMED_OUT`；任意非终态 `→ CANCELLED`。
- Attempt：`RUNNING → COMPLETED / FAILED / TIMED_OUT / CANCELLED`。
- Job：只写 `PENDING`（成功封口时按方案项生成），取消时 `PENDING → SKIPPED`；`RUNNING/COMPLETED` 留给 TASK-007。

所有转换都以数据库条件更新 + 影响行数判定为准，内存里的 `Transitions` 只做"业务上是否允许"的规则校验。

### 3.3 数据库持久化（不新增表、不新增迁移）

| 表 | 写入内容 |
| --- | --- |
| `evaluation_runs` | 槽位（身份 `experiment_target_id + dataset_case_id + repeat_index`）、`status/task_outcome`、`attempt_count`、`active_attempt_id`、`accepted_attempt_id`、`next_retry_at`、`sealed_at`、`output_json`、`usage_json`、`observation_metadata_json` |
| `run_attempts` | 每次领取一行：`worker_id`、`attempt_no`、`lease_token_hash`（只存 SHA-256）、`lease_until`、`deadline_at`、`completion_hash`、错误码 |
| `trace_events` | `event_id`（Worker 生成、全局唯一）、`event_hash`、`sequence_no`、`occurred_at`、`payload_json` |
| `artifacts` | `run_id`、`object_key`、`sha256`、`size_bytes`、`status=READY` |
| `evaluation_batches` / `evaluation_batch_runs` | 一个 INITIAL 批次（`expected_job_count` 提交时冻结）、Run 与批次绑定 |
| `evaluation_jobs` | 每个方案项一行 `PENDING`，`input_hash` 由 runId + acceptedAttemptId + planItem + config 规范化摘要 |

### 3.4 Worker（Python）

- `app/runner/base.py`：`Runner` 协议（`run(request, emit)`）+ `RunnerResult/RunnerFailure/RunnerTimeout`
- `app/runner/mock.py`：5 个预设 `SUCCESS / BUSINESS_FAILURE / TECHNICAL_ERROR / TIMEOUT / RETRYABLE_ERROR`
- `app/client.py`：标准库 `urllib` 实现 claim/heartbeat/events/complete/fail/artifacts（含手写 multipart）
- `app/poller.py`：领取→执行→心跳线程→事件→完成/失败；409 不重放，5xx/超时用同正文重放一次；取消后不提交完成
- `app/runtime.py` + `app/api/routes/worker.py`：`GET /worker/status`、`POST /worker/run-once`

### 3.5 前端

- `/experiments`：列表（状态筛选、提交草稿）、新建实验对话框（只列已发布的数据集/方案/Agent 版本）
- `/experiments/:id`：实验概要与 Run 列表（状态过滤、状态分布、提交/取消）
- `/runs/:id`：执行证据——Attempt 明细、Trace 事件数、评测任务按批次分组；`EVALUATING + PENDING` 时显式提示
  "执行已结束，评测尚未开始"，不展示任何分数
- 导航「在线 / 离线评测」由占位改为可用

## 4. 本轮的关键实现说明（供 review 定位）

1. **领取的正确性与活性拆成两步**：`listClaimCandidates`（不加锁，只缩小范围）+
   `lockClaimCandidate`（`FOR UPDATE OF r SKIP LOCKED` 并重新校验全部条件）。
   最初写成"一条 `ORDER BY ... LIMIT ... FOR UPDATE`"，实测会让一个 Worker 把 filesort 阶段扫到的行
   全部锁住，并发时其他 Worker 全部跳过、领不到任务（4 个并发只返回 1 个任务）。范围查询不参与加锁后，
   并发领取每人拿到一个不同 Run，且"实验已被取消"仍在加锁那一刻被重新判定。
2. **`eventId` 必须由 Worker 生成**：后端代生成会让网络重发变成两条不同 eventId 的新事件，幂等契约失效；
   现在缺失直接 400，并在 `eventHash` 中计入 `occurredAt`（重发必须同正文）。
3. **`observationMetadata` 可选但列 NOT NULL**：缺省时后端补最小快照
   `{outputKnown, traceCoverage:"EVENT_MANIFEST_ONLY", outcomeSource:"RUNNER_REPORTED", adapterVersion:"worker-protocol/1"}`，
   只陈述已知事实，不声称上游 Trace 完整。
4. **取消顺序**：先 Run 终态、再取消 Attempt——反过来会留下"Attempt 已取消、Run 仍 RUNNING"的窗口，
   完成回调在那个窗口里仍能通过检查。
5. **软失败与硬失败**：`retryableHint` 只是建议；是否重试由后端按 `maxAttemptsPerRun` 与退避窗口决定。

## 5. 实际执行的检查

### 5.1 后端全量

两轮都以真实 MySQL（`127.0.0.1:13306/repopilot_it`）为底座，差别只在 RabbitMQ 断言是否开启：

| 轮次 | 命令 | 退出码 | 结果 |
| --- | --- | --- | --- |
| A（默认） | `./mvnw -B verify` | 0 | 单元 **75** / 集成 **106**，Skipped 1（readiness 用例按开关关闭） |
| B（零跳过，最终验收） | 见下方命令 | 0 | 单元 **75** / 集成 **106**，**Skipped 0**，BUILD SUCCESS（1 min） |

轮次 B 的环境变量（与 `.github/workflows/ci.yml` 的写法一致，只是换成本机容器凭据）：

```bash
REPOPILOT_IT_DB_HOST=127.0.0.1 REPOPILOT_IT_DB_PORT=13306 \
REPOPILOT_IT_DB_NAME=repopilot_it REPOPILOT_IT_DB_USER=repopilot \
REPOPILOT_IT_DB_PASSWORD=repopilot_it_only \
REPOPILOT_IT_REQUIRE_ALL_UP=true \
SPRING_RABBITMQ_HOST=127.0.0.1 SPRING_RABBITMQ_PORT=5672 \
SPRING_RABBITMQ_USERNAME=repopilot SPRING_RABBITMQ_PASSWORD=repopilot_dev_pwd \
./mvnw -B verify
```

零跳过的直接证据：

- `ApiHealthIT`：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`——`/actuator/health/readiness`
  在真实 Broker 下返回 200 且 `status: UP`（readiness 组含 `db` 与 `rabbit`）。
- 全量汇总行：`Tests run: 75 ... Skipped: 0` 与 `Tests run: 106 ... Skipped: 0`，
  日志中 `Skipped: 1` 出现次数为 **0**。
- RabbitMQ 容器：`rabbitmq:3.13.7-management`，`Up 16 hours (healthy)`，`rabbitmq-diagnostics -q ping` 成功。

之所以需要 `SPRING_RABBITMQ_*`：`application-it.yml` 不写 Broker 连接信息，Spring Boot 会退回
`localhost:5672` + `guest/guest`，而容器默认账号是 `repopilot`，认证失败会让 readiness 判为 DOWN。
这是**环境变量**层面的补齐，没有修改任何测试断言或跳过注解。

集成用例分布：`ExecutionFlowIT` 15、`ExecutionConcurrencyIT` 3，其余为 TASK-002/003 既有用例（88 条，全绿）。

### 5.2 并发与稳定性

`ExecutionConcurrencyIT` 单独连跑 3 次（含 `ExecutionFlowIT` 同批）：
18/18、18/18、18/18 全部通过，无偶发失败。

### 5.3 其他检查（本轮复跑）

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| Worker 单元测试 | `repopilot-worker/.venv/Scripts/python.exe -m pytest -q` | **28 passed**，1 warning（Starlette 对 `httpx` 的弃用提示，非本次代码） |
| 前端构建 | `rm -rf dist && npm run build` | 通过（`vue-tsc --noEmit` + `vite build`），仅剩 chunk > 500 kB 的既有体积提示 |
| 端到端演示 | `BASE=http://127.0.0.1:18080/api/v1 WORKER_TOKEN=local-demo-token bash tools/demo-task-004.sh` | 12 步全通：`QUEUED`/`total:12` → 领取 → 5 事件 + manifest → `EVALUATING` + `PENDING` → 重试不增 Run → `CANCELLED` 且迟到回调 409 |
| 真实 Worker 联调 | `POST http://127.0.0.1:8000/worker/run-once` | `{"claimed":4,"completed":4,"failed":0,"abandoned":0,"errors":[]}`；4 个 Run 全部 `EVALUATING`，Job `PENDING`，`evaluation_results` 仍为 **0 行** |

### 5.4 未执行项

| 项 | 状态 | 原因 |
| --- | --- | --- |
| 真实 Codex / 真实 Agent 执行 | 未实现 | TASK-008 范围；`RunnerRegistry` 对 `RUN:CODEX` 明确 400（fail closed），不假装支持 |
| 规则评测评分 | 未实现 | TASK-007 范围；Job 停留在 `PENDING` |
| 前端截图 | 未产出 | 本机无 playwright/puppeteer；前端以路由 + `npm run dev` 为演示入口，构建与类型检查已通过 |
| CI | 未运行 | 本轮只做本地提交，按 `AGENTS.md` 不 push，因此没有新的 CI run ID |

> 已无 JUnit 跳过用例。此前唯一 skip 的 readiness 用例本轮在真实 Broker 下执行并通过，断言未被删除或放宽。

## 6. Git

| 分支 | `develop`（与 TASK-001..003 同一分支） |
| --- | --- |

提交按逻辑拆分（详见 `git log --oneline`），`origin/develop` 之上共 **6** 个提交（推送前用
`git rev-list --left-right --count origin/develop...HEAD` 核对为 `0 6`，即落后 0、领先 6）：

| Commit | 内容 |
| --- | --- |
| `730c595` | `feat(task-004)`: 后端执行域——实验草稿/提交/取消、Run 槽位、领取与租约、事件与封口、待评测 Job |
| `0dc3e25` | `test(task-004)`: 主链路与并发集成测试（真实 HTTP + 真实 MySQL） |
| `98ee5df` | `feat(task-004)`: Python Worker——Runner 抽象、Mock Runner、轮询执行协议 |
| `7c90a37` | `feat(task-004)`: 前端实验列表/详情与 Run 执行证据页 |
| `246ab2b` | `docs(task-004)`: OpenAPI、演示脚本与本报告 |
| `HEAD` | `docs(task-004)`: 补记零跳过验收结果、敏感信息检查与提交范围核对（本行所在提交，ID 在推送时由 `git log -1 --format=%h` 确认） |

工作区状态：`git status --porcelain` 为空。

**推送方式**：`git push origin develop`，实际为 fast-forward（推送前 `0 6`、无分叉），未使用 `--force`
或任何强制参数（`43919fe..9014e7a`）。推送后的 CI 结果见 §8。

### 6.1 敏感信息检查

对 `origin/develop..HEAD` 的全部新增行做了模式扫描（私钥块、AWS AK、GitHub/OpenAI/Slack token、
形如 `password=`/`secret=` 的赋值、长 `Bearer` 串）：**无命中**。

- 提交范围内没有 `.env`、`node_modules/`、`target/`、`.venv/`、`dist/`。
- 出现的两个凭据样字符串均为测试专用：`it-worker-token`（`application-it.yml`，仅 it profile）
  与 `local-demo-token`（演示脚本默认值、命令行可覆盖），不是任何环境的真实凭据。
- 数据库与 Broker 的真实口令只存在于本机 `.env` / 容器环境变量，未进入版本库；
  `.env.example` 只放占位符与默认端口。

## 7. 已知问题与下一 TASK 前置

1. **领取候选窗口**：候选取 `maxTasks × 5`（上限 200）。极端突发下某个 Worker 可能空手而归，需要下一次轮询；
   这是活性而非正确性问题（不会重复领取）。若 TASK-012 压测发现空转成本高，再调窗口或引入 `LISTEN/NOTIFY`。
2. **退避与抖动**：`5s / 15s`（+20% 抖动）为设计默认值，用例中通过把 `next_retry_at` 拨到过去来驱动，
   未验证真实时间流逝下的分布。
3. **`observation_metadata_json` 语义**：目前由后端补最小快照；真实 Runner 接入（TASK-008）后应由适配器声明
   `traceCoverage`，否则前端无法区分"上游没上报"和"我们没记录"。
4. **TASK-005 前置**：离线 Run 复用 `evaluation_runs`（`mode=OFFLINE`），但 `PENDING → EVALUATING` 的离线路径
   尚未有用例覆盖；批次选择语义（INITIAL / LATEST）已在 `EvaluationBatchService.findInitial` 固定，可直接延用。
5. **TASK-007 前置**：`evaluation_jobs.input_hash` 已冻结为"run + attempt + planItem + config"的规范化摘要，
   Job 的 `PENDING → RUNNING` 领取、Attempt 与 `evaluation_results` 写入留给 007，届时不得改变 004 已写入的
   `accepted_attempt_id` 与 `sealed_at` 语义。

## 8. CI 实跑结果（GitHub Actions）

推送 `develop` 后由 `ci` workflow 验证（`push` 事件；未创建、未合并任何 PR）。

| 项 | 值 |
| --- | --- |
| Run ID | **`39213525194`** |
| Workflow / 事件 | `ci` / `push`（分支 `develop`） |
| Commit | `9014e7a` |
| 结论 | **success，5/5 Job 全绿** |
| 用时 | 1 分 28 秒（07:03:14Z → 07:04:42Z） |
| 链接 | https://github.com/goodddGrades/RepoPilot/actions/runs/39213525194 |

| Job | 结果 | 实际数字 |
| --- | --- | --- |
| 后端（MySQL 8.0.36 + RabbitMQ） | success | 单元 **75**、集成 **106**，Failures 0 / Errors 0 / **Skipped 0** |
| 后端（MySQL 8.0 + RabbitMQ） | success | 单元 **75**、集成 **106**，Failures 0 / Errors 0 / **Skipped 0** |
| Web（构建） | success | `vue-tsc --noEmit && vite build`，`built in 6.29s` |
| 设计字典自检 | success | 迁移与字典一致（生成器覆盖 V2–V7 共 6 个；V1 基线为手写，不在生成范围）、**76** 个 Java 文件一致 |
| Worker（pytest） | success | **28 passed**，1 warning |

CI 日志中 `Skipped: 1` 出现次数为 **0**，与本地零跳过运行一致；两条 MySQL 腿（8.0.36 与 8.0）数字完全相同，
说明结论不依赖单一小版本。
