# TASK-007 实施交付报告

## 1. 结论

TASK-007「评测执行引擎与结果闭环」已完成：Run 进入 `EVALUATING` 之后，评测 Job 由 Worker 用
与在线执行同一套租约协议领取，提交的是**结论**（verdict / score / evidence），结论落库后
Run 与批次按状态机收口，前端读到的就是库里那一份。重评新建批次，历史结果不被覆盖。

| 项目 | 结果 |
| --- | --- |
| 新增接口 | 5 个对外（批次创建/列表/详情/批次内 Run/取消）+ 3 个 Worker 内部（评测 Attempt 心跳/完成/失败） |
| 新增后端代码 | 6 个服务类 + 4 个 DTO + 1 个控制器 + 8 个 Mapper 追加方法 + `WorkerCapabilities`（共 11 个新文件） |
| 新增 Worker 代码 | `app/evaluator/{base,rules,trace,registry}.py` 四个规则算子；`poller.py` 分派评测任务；`runtime.py` 支持纯评测 Worker |
| 新增测试 | `EvaluationEngineIT` **17 条**真实 MySQL 集成用例；Worker 新增 **64 条**算子与分派用例 |
| 后端全量 | 单元 **75**、集成 **153**（含新增 17），0 失败 0 跳过（CI 两条 MySQL 腿各跑一遍） |
| 前端 | `vue-tsc --noEmit` 通过；`vite build` 通过；Run 详情页新增判定/分数/证据/Attempt 历史，实验详情页新增批次卡片与重评入口 |
| Worker / 设计字典 | pytest **92 passed**；字典生成物 76 Java 文件 / 28 Mapper 一致；DDL 6 迁移一致；`verify_design.py` PASS（1260 项） |
| 端到端演示 | `tools/demo-task-007.sh` 11 步（详见 §7） |

## 2. 实施前的五点明确

1. **本阶段必需的算子与 Judge**：`output.non_empty`、`answer.exact_match`、`trace.tool_error`、
   `cost.budget` 四个规则算子（TASK-003 已注册、Worker 已实现）。`judge.rubric`（LLM_JUDGE）
   按 `tasks/TASK-007.md` 的开发边界"不接 LLM Judge"**本阶段不实现**，留 TASK-008；
   `test.command`（TEST 类型）因缺可信断言白名单同样未实现，未实现的算子在 Worker 侧
   明确报 `UNSUPPORTED_EVALUATOR` 而不是静默通过（有用例覆盖）。
2. **Java 与 Python 的职责**：Java 负责 Job 领取、租约与 fencing、心跳、回调校验、结果持久化、
   证据归属校验、Run/批次收口；Python Worker 只负责"读轨迹 + 跑算子 + 交结论"。
   Worker 不连数据库，任何权威状态都在 Java 侧。
3. **Job/Batch/Result 状态与关联**：`evaluation_jobs.status` 走既有状态机
   （PENDING→RUNNING→COMPLETED/FAILED、PENDING→SKIPPED）；`verdict=FAIL` 是质量结论，
   Job 仍为 COMPLETED，只有技术失败才让 Job FAILED。每个 Job 至多一条 Result（唯一键
   `evaluation_results.job_id`），Attempt 历史全部保留，重试不覆盖。
4. **规则算子的输入输出与失败语义**：输入是 `EvaluationInput`（算子配置 + Run 快照 + 轨迹读取
   契约）；输出是 `verdict/score/reason/evidence/result`。配置非法 → 不可重试的技术失败；
   轨迹清单不符 → 不可重试；依赖不可用（Worker 侧）→ 按 `retryable` 决定是否重试；
   任何情况下不伪造缺失的工具输出或 Judge 依据，缺前提时如实给 `NOT_APPLICABLE` / `INDETERMINATE`。
5. **本阶段不做消息调度**：TASK-011 的 RabbitMQ 业务分发与 Outbox 未实施。评测任务的投递
   沿用既有"Worker 轮询 `POST /internal/v1/work/claim`"模型，与 TASK-004/005/006 一致，
   没有新增消息中间件的耦合。

## 3. 架构与职责边界

```
Worker(评测)                     Java 服务端                        MySQL
-----------                     -----------                        -----
claim(kind=EVALUATION)  ──►  EvaluationClaimService
                              · 候选查询（SKIP LOCKED + 串行约束）    evaluation_jobs
                              · 插 Attempt + 置 RUNNING（fencing）    evaluation_job_attempts
  ◄── WorkItem(input=EvaluationInput)
      TraceReader 读轨迹 ──► GET /runs/{id}/events（授权范围内的 URL）
      规则算子计算
  POST /job-attempts/{id}/complete ──► JobAttemptService
                              · 令牌/租约/父记录校验                 evaluation_results
                              · 分数、generatedBy、证据归属校验
                              · 结果落库 + Job 收口                   evaluation_runs
                                                              ──►  EvaluationAggregationService
                                                                   · Run EVALUATING→COMPLETED/FAILED
                                                                   · 批次 PENDING→RUNNING→终态
```

**评测执行器与 Agent 执行器不是同一个组件**：不同的 `WorkItem.kind`（`RUN` / `EVALUATION`）、
不同的回调端点（`/run-attempts/**` 与 `/job-attempts/**`）、不同的输入契约、不同的结果语义
（"执行完了"与"判定是什么"）。租约、心跳、fencing、退避与回收的规则一致，但各自独立实现与测试。

## 4. 关键设计与失败语义

### 4.1 领取：全局队列 + 同一 Run 内串行

`listClaimCandidates` 的四个条件缺一不可：批次与实验都未结束（允许实验已 COMPLETED 的重评批次）、
Job 为 PENDING 或"退避到点的 RUNNING 且无活动 Attempt"、`attempt_count < maxAttempts`、
算子命中 Worker 声明的 `EVAL:<key>` 能力；外加**串行**约束：同一 Run 在本批次里没有更早的
未终态 Job。技术失败（FAILED）也算终态，后续取证算子仍会执行，不自动跳过。

锁顺序固定 Attempt → Job；抢占用 `FOR UPDATE OF j SKIP LOCKED`，两个执行者不会拿到同一条。

### 4.2 结论提交的三类硬校验

- **分数**：`0~1`、最多 5 位小数（列定义 `DECIMAL(6,5)`）。越界或多余小数位一律 400，
  不做静默四舍五入——分数是被比较的量，改精度会让"同一结果复算"对不上。
- **generatedBy 必须与算子类型匹配**（`RULE/TRACE/COST→RULE`，`TEST→TEST`，`LLM_JUDGE→LLM`），
  防止把规则结果伪装成模型评分。
- **证据归属**：只能引用本 Run 的事件、附件、output/usage 路径，且 `casePath` 限
  `input./expected./metadata.` 前缀、`usagePath` 限 `usage.` 前缀，路径不得含 `..`。
  跨 Run 引用直接 400——不允许"拿别的 Run 的证据支撑本 Run 的结论"。

### 4.3 幂等与历史保护

| 场景 | 行为 |
| --- | --- |
| 同 Attempt + 同正文完成回调（重发） | 200，返回原回执（`completionHash` 不含 `leaseToken`） |
| 同 Attempt + 不同正文 | 409 `STALE_ATTEMPT` |
| Job 已被后续 Attempt 接管/完成 | 409 `STALE_ATTEMPT`，旧令牌写不进结果 |
| 失败回执重发 | 仅当 Job 当前状态仍由这次失败造成时幂等重放；否则 409 |
| 结果唯一键冲突 | 409 `RESOURCE_CONFLICT`（显式冲突，不静默覆盖） |
| 租约过期后的写入 | 409；回收器把 Attempt 置 FAILED/TIMED_OUT，Job 按预算重试或失败 |

"失败回执幂等只在 Job 仍由该次失败持有时放行"是本阶段修掉的一个真实缺陷：否则 Job 被后续
Attempt 完成后，一次网络重发式的旧失败回执会以"幂等"名义再次触碰成功历史。

### 4.4 收口

Run 只在 INITIAL 批次全部 Job 终态后 EVALUATING→COMPLETED/FAILED；**重评不回写 Run**
（否则会把已完成的历史 Run 改成 FAILED）。批次终态：有完成且无失败/跳过 → COMPLETED，
有完成也有失败/跳过 → PARTIAL_FAILED，全失败 → FAILED。

## 5. 一致性做法

- 跨实验/跨批次/跨方案版本的关联在**事务内**建立：重评批次在同一个事务里写批次、登记行与 Job，
  任一步失败整体回滚，不存在"批次建了但 Job 没建"的中间态。
- 一致性靠数据库约束与条件更新，不靠 Controller 预检查：`evaluation_results.job_id` 唯一键、
  `evaluation_jobs.active_attempt_id` 外键、各状态迁移的 `WHERE status='...'` 条件更新。
- 重评只接受**已封口**且状态为 `EVALUATING/COMPLETED` 的 Run；未封口 409，跨实验 400。
- 批次登记行 `evaluation_batch_runs` 是范围真相，`selection_json` 只作审计留痕（至多 100 个 runId）。

## 6. 测试

### 6.1 后端（真实 MySQL 8.0.22 @13306，`REPOPILOT_IT_REQUIRE_ALL_UP=true` 零跳过）

`EvaluationEngineIT` 17 条，逐条对应任务的验收点：

| # | 用例 | 断言要点 |
| --- | --- | --- |
| 1 | 有效 Job 执行并持久化 | 结果落库、Attempt 有 completionHash、Run/批次收口、详情页读到同一份 |
| 2 | 不同算子的判定语义 | PASS/FAIL/INDETERMINATE/NOT_APPLICABLE 各自的结果与分数（不适用时分数为 NULL） |
| 3 | 无效配置 | `EVALUATOR_CONFIG_INVALID`，不留下伪造结论，required 算子失败 → Run FAILED |
| 4 | 不完整轨迹与跨 Run 证据 | 缺轨迹不编造；跨 Run 事件被 400 拒绝 |
| 5 | 租约过期 | 旧令牌 409，回收器改判 Job 状态 |
| 6 | 重复完成 | 同正文重放 200、不同正文 409、结果仍只有一条 |
| 7 | 可重试失败 | `retryScheduled=true` + 退避时间；退避未到点领不到；旧令牌失效 |
| 8 | 重试预算 | 预算耗尽后 Job FAILED，不再生成 Attempt |
| 9 | 成功后迟到失败回执 | 409 `STALE_ATTEMPT`，成功结果与 Job 状态不变 |
| 10 | 在线离线同一引擎 | 离线 Run 无执行 Attempt、按 Run 读轨迹、结果同样落库 |
| 11 | 业务失败仍评测 | taskOutcome=FAILURE 的 Run 进入评测，缺输出 → NOT_APPLICABLE 而非 0 分 |
| 12 | 多 Run 多算子汇总 | 一批 2 Run × 2 算子 = 4 个任务，计数与收口正确 |
| 13 | 部分失败 | 一成功一失败 → 批次 PARTIAL_FAILED，两个 Run 分别 COMPLETED/FAILED |
| 14 | 重评历史保护 | 新方案版本重评：旧结果 id/verdict/Attempt 不变，新结果另存，Run 不回退 |
| 15 | 重评入参校验 | 未封口 409、跨实验 400，且不产生被污染的批次 |
| 16 | 未实现算子 | `judge.rubric` 领不到任务，不会被静默"通过" |
| 17 | 取消规则 | INITIAL 批次不可单独取消；重评批次取消后 PENDING → SKIPPED、RUNNING → FAILED |

回归：`./mvnw verify` 覆盖 TASK-001~006 全部用例，单元 75 + 集成 153，0 失败 0 跳过。

### 6.2 Worker（pytest 92 passed，新增 64 条）

- 四个算子的判定语义与边界：缺 ground truth、缺成本、工具未闭合、空输出、只登记附件、证据截断。
- 配置错误 → 不可重试失败；依赖不可用 → 按 `retryable`；未知算子 → `UNSUPPORTED_EVALUATOR`。
- 轨迹读取：分页上限、`eventCount` 与 `manifestSha256` 双校验，不符即不可重试失败。
- 分派层：`kind=EVALUATION` 不走 Agent 心跳、不提交 Agent 结果；租约失效后不提交结论。
- 纯评测 Worker（无 Runner）能启动并在 status 里如实报告 `runner=null`。

### 6.3 前端与生成物

`vue-tsc --noEmit` 通过；`vite build` 通过（Run 详情页 20.83 kB / gzip 7.33 kB）。
`gen_ddl_from_dict.py --check` OK（6 个迁移且未被改动）、`gen_java_from_dict.py --check` OK（76 文件）、
`verify_design.py` PASS（1260 项检查 / 28 表）。四份 OpenAPI 契约均可被 YAML 解析。

## 7. 端到端演示

`tools/demo-task-007.sh`（11 步，可对同一个库重复执行，每次带随机后缀）真实跑通：
建版本 → 提交实验 → 在线执行两个 Run → 评测 Worker 领取并提交结论 → 落库与收口 →
四类非法提交被拒 → 重评新建批次 → 取消批次 → 批次列表。关键输出：

```
== 4. 评测 Worker 领取 Job：输入含算子配置、Run 快照与轨迹读取契约 ==
  算子与配置："key":"output.non_empty","version":1,"type":"RULE"
  轨迹契约："sealed":true,"eventsUrl":"/api/v1/runs/.../events?limit=200&attemptId=...","eventCount":4
  输出快照："output":{"answer":"2"}     数据集期望："expected":{"answer":"2"}

== 5. 提交结论（verdict/score/evidence） ==
回执："attemptId":"01M5AA8RZ1F19V0P2MMT3Z8NAM","status":"COMPLETED"
证据引用的是该 Run 的事件：01M5AA5QPZS61BEMSEQ71VTPW1

== 7. 落库结果与收口 ==
run.status=COMPLETED
  output.non_empty v1 RULE  → 结论 PASS 分数 1.00000 依据「输出非空」
  answer.exact_match v1 RULE → 结论 PASS 分数 1.00000 依据「与期望值一致」
批次状态=COMPLETED  runCount=2  expectedJobCount=4

== 8. 五类非法提交全部被拒（不是 500，而是带错误码的 4xx） ==
分数越界(1.5)      → HTTP 400 VALIDATION_ERROR
分数精度超限       → HTTP 400 VALIDATION_ERROR
generatedBy 不匹配 → HTTP 409 STALE_ATTEMPT
旧令牌重放         → HTTP 409 STALE_ATTEMPT
完成后迟到失败回执 → HTTP 409 STALE_ATTEMPT

== 9. 重评：同一批 Run 换方案版本 → 新批次，历史结果与 Run 状态不动 ==
该 Run 关联的批次数：2（初始 1 个 + 重评 1 个）
  批次 no=1 trigger=INITIAL       status=COMPLETED jobs=4
  批次 no=2 trigger=REEVALUATION  status=COMPLETED jobs=1
run.status=COMPLETED（重评不回写已完成的历史 Run）

== 10. 取消未终态批次 ==
取消后 status=CANCELLED jobs.total=1 skipped=1；再次查询批次详情仍是 CANCELLED

== 11. 批次列表 ==
  批次 3 MANUAL / 批次 2 REEVALUATION / 批次 1 INITIAL
  状态 CANCELLED / COMPLETED / COMPLETED
```

> 领取是全局队列语义：库里若残留别次演示的待评测 Job，脚本会把不属于本实验的任务
> 以 `DEMO_DEFERRED`（安排重试、不判失败）放回队列，而不是替别人做完或算作失败。
> 脚本还有两条为真实环境做的约束：①领取**之前**就把证据事件 id 取好，领取后只做一次提交请求
> （真实 Worker 会心跳续租，脚本是一次性 Worker）；②解析 JSON 用一次 `awk` 摊平字段再取，
> 而不是反复 `grep`/`sed`——本机每起一个子进程约 1s，反复解析会把 60s 租约耗光。

## 8. 已知限制与未验证项

1. **未实现 LLM Judge**：`judge.rubric` 按 TASK-007 边界留给 TASK-008。测试项"④Judge
   超时/无效响应/依赖不可用"因此**未验证**——本阶段没有可用的模型凭据，也没有 Judge 实现，
   不做任何"用固定字符串冒充评分"的桩。`Judge` 的接口形状已在契约里预留（`generatedBy=LLM`
   与算子类型校验已就位），接上模型适配层即可复用现有失败语义。
2. **未实现 `test.command`（TEST 类算子）**：缺少可信断言白名单，避免把任意文本当断言执行。
3. **没有异步消息分发**：仍为 Worker 轮询领取，TASK-011 的 RabbitMQ/Outbox 未实施。
4. **并发验证限于用例覆盖**：`EvaluationEngineIT` 覆盖了租约过期、重复回调、幂等与重试预算；
   真正的多 Worker 压测未做，结论基于 SQL 条件更新与行锁推理。
5. **Vite 构建在本机的注意点**：`vite build` 需加 `--emptyOutDir=false`（宿主回收站 shim
   在清空目录时超时），CI 环境无此问题。

## 9. 变更清单

| 边界 | 内容 |
| --- | --- |
| 后端引擎 | `WorkerCapabilities`、`EvaluationClaimService`、`JobAttemptService`、`EvaluationAggregationService`、`EvaluationInputService`、`EvidenceValidator`、`EvaluationBatchService`、5 个 DTO、`EvaluationBatchController`、`WorkerController` 评测端点、`WorkService` 分派、`RunQueryService` 结果视图 |
| 测试 | `EvaluationEngineIT`（17 条）、`AbstractExecutionIT` 清理顺序修正 |
| Worker | `app/evaluator/*`、`app/poller.py`、`app/runtime.py`、`app/client.py`、2 个测试文件 |
| 前端 | `types/domain.ts`、`api/evaluation.ts`、`RunDetailView.vue`、`ExperimentDetailView.vue` |
| 契约与文档 | `docs/contracts/openapi-evaluation.yaml`（新建）、`openapi-execution.yaml` 的 Job/WorkItem 扩展、`docs/README.md`、`tools/demo-task-007.sh` |
## 10. Git 与 CI

| 项 | 值 |
| --- | --- |
| 分支 | `develop`（未合并 PR，未开始 TASK-008） |
| 提交 | `9c746fa` 后端引擎 → `20d3137` 测试 → `52ac728` Worker → `a620010` 前端 → `76197ad` 契约/演示/报告 |
| CI Run ID | **39266901234**，5 个 Job 全绿 |
| CI 实跑数字 | 后端两条 MySQL 腿：单元 75、集成 153（0 失败 0 跳过）；Worker 92 passed；Web 构建成功；设计字典自检通过（DDL 6 迁移 / Java 76 文件 / 静态检查 1260 项） |
| 数据库迁移 | 无新增。Flyway V1~V7 与 `migrations.lock.json` 未改动（`gen_ddl_from_dict.py --check` 确认 6 个迁移未被篡改） |

CI 的两条 MySQL 腿分别是 MySQL 8.0.36 与 MySQL 8.0，均开启 `REPOPILOT_IT_REQUIRE_ALL_UP=true`
（就绪探针真的要求 DB 与 RabbitMQ 都 UP），因此本报告中的"0 跳过"是硬断言的结果，
不是把外部依赖检查关掉换来的。
