# TASK-006 实施交付报告

## 1. 结论

TASK-006「统一 Trace 查询与可视化回放」已完成：在线执行与离线导入产生的轨迹，通过同一个
`GET /api/v1/runs/{id}/events` 读取，由同一块前端面板回放；在线与离线的差异（有没有 Attempt、
轨迹完整度）如实展示，不伪造、不补齐。

| 项目 | 结果 |
| --- | --- |
| 新增接口 | 1 个（`GET /runs/{id}/events`，5 个查询参数、2 类错误） |
| 新增后端代码 | 游标签名、查询服务、游标编解码、DTO、孤儿清理（共 6 个新文件，8 处改造） |
| 新增测试 | 12 条真实 MySQL 集成用例（10 + 2） |
| 后端全量 | 单元 **75**、集成 **136**，0 失败 0 跳过 |
| 前端 | `vue-tsc --noEmit` + `vite build` 通过；新增 `TracePanel.vue`（约 500 行） |
| Worker / 设计字典 | pytest 28 passed；字典生成物 76 Java 文件 / 28 Mapper 一致；DDL 6 迁移一致；`verify_design.py` PASS（1255 项） |
| 端到端演示 | `tools/demo-task-006.sh` 13 步全通（在线 10 条事件 + 离线 2 条轨迹） |

## 2. 接口与行为

### 2.1 `GET /api/v1/runs/{id}/events`

| 参数 | 说明 |
| --- | --- |
| `cursor` | 上一页的 `nextCursor`；省略即第一页。签名串，改一个字符就 400 |
| `limit` | 单页条数，默认 100、范围 1~500 |
| `attemptId` | 只看某次尝试；必须属于该 Run。与 `allAttempts` 互斥 |
| `eventType` | `^[A-Z][A-Z0-9_]{0,63}$`；形状合法但未知的类型返回空页（不 400） |
| `allAttempts` | 排障口径：合并该 Run 的全部尝试，失败尝试的事件也会出现 |

响应 `data`：`items[]`（事件）、`nextCursor`（`hasMore=true` 时必有）、`hasMore`、`scope`
（本次实际生效的 `attemptId` / `allAttempts` / `eventType` / `resolvedFrom`）。

错误：未知 Run → 404 `RESOURCE_NOT_FOUND`；`limit` 越界、`eventType` 形状非法、`attemptId`
不存在或跨 Run、游标被篡改/跨 Run/换过滤条件 → 400 `VALIDATION_ERROR`，`errors[].field`
指到具体参数，文案直接告诉用户"从第一页重查"，不出现数据库错误码或堆栈。

契约文件：`docs/contracts/openapi-trace.yaml`（1 条路径、14 处 `$ref`，全部可解析）。

### 2.2 范围解析（在线与离线的唯一差异点）

| `resolvedFrom` | 触发条件 | 是否按 Attempt 过滤 |
| --- | --- | --- |
| `REQUESTED` | 显式传了 `attemptId` | 是 |
| `ALL` | `allAttempts=true` | 否（合并全部尝试，含失败尝试） |
| `ACCEPTED` | 在线 Run 已封口，默认读接受尝试 | 是 |
| `ACTIVE` | 在线 Run 正在执行，还没有接受尝试 | 是（读当前尝试） |
| `NONE` | 离线 Run，或在线 Run 尚未领取 | 否 |

离线 Run 从来没有 `run_attempt_id`：查询时不做 Attempt 过滤，返回的事件里也没有 `attemptId`
字段；传 `attemptId` 会 400（该 Run 下不存在这个 Attempt），`allAttempts` 对它等价于默认查询。
**没有为了让"看起来统一"而给离线 Run 造一条 Attempt。**

### 2.3 游标

- 内容：`v1|runId|scopeHash|epochMilli|rowId`，`scopeHash` 是 `(attemptId, allAttempts, eventType)`
  的 SHA-256 前 16 位。
- 签名：HMAC-SHA256 截断 16 字节，`base64url(body) + "." + base64url(mac)`；比较用
  `MessageDigest.isEqual`（定长时间）。
- 三种误用都在读取前被拒：跨 Run、改过滤条件、改内容。三者文案不同——用户需要知道
  "该重查"还是"参数写错了"。
- 密钥取 `repopilot.trace.cursor-secret`；未配置时用一次性随机密钥并 `WARN`：宁可重启后旧游标
  失效（前端回到第一页），也不在仓库里写死一个"所有人共用的秘密"。

### 2.4 单页上界

条数与体积双上界：最多 500 条，且单页 payload 合计约 4 MiB。被体积截断时同样返回
`hasMore=true` 与指向最后一条的 `nextCursor`，**不静默丢弃尾部数据**。

### 2.5 附件与存储安全

- 事件里的 `artifactId` 只在附件**确实归属本 Run** 时返回，跨 Run 引用置空（脏数据不会因为
  出现在列表里就变成"可点开的证据"）。
- 下载前校验对象内容长度与 `sha256` 是否仍与登记一致，不一致返回 409 且日志不含 `objectKey`。
- 孤儿对象清理：只删"数据库里没有元数据且早于宽限期（默认 24h）"的对象；有元数据的一律保留。
  定时任务默认开启（每小时一次），集成测试显式关闭。

### 2.6 前端

`repopilot-web/src/components/TracePanel.vue`，挂在既有 `RunDetailView` 的「评测任务」卡片之前，
复用现有页面、API 层与统一错误处理，未重写任何已完成页面。

- 顶部：范围说明（`resolvedFrom` 的人话解释）、Attempt 下拉（默认 / 各次尝试 / 全部尝试，离线置灰）、
  事件类型过滤、刷新。
- 工具调用配对表：按 `toolCallId` 配 STARTED/COMPLETED；只有一侧时标「仅一侧事件」，不补造另一端。
- 时间线：事件类型标签、`sequenceNo`、`attemptId`（离线事件没有该字段）、`traceId`（缺失标「未知 trace」）、
  父 span 不在本页标「本页未见父 span」、同毫秒且都没有 `sequenceNo` 标「顺序不确定」、
  有 `artifactId` 时给下载入口。
- 状态：加载中（`v-loading`）、空页（区分"没有事件"与"当前过滤条件下没有事件"）、
  请求失败（统一错误信息）、**游标失效单独提示**并给"从第一页重新加载"按钮。
- 载荷：`<pre>` 文本插值 + 折叠，全程不用 `v-html`；模型输出是不可信数据。
- 离线差异：顶部固定说明"没有执行尝试、完整度以导入声明为准"；`traceCoverage != COMPLETE`
  时显示完整度告警条。

## 3. 关键实现决定（有实测依据）

**键集分页而不是页码。** 轨迹会追加、迟到事件会插入旧时间段：页码分页必然出现重复与漏行。
排序键 `(occurred_at ASC, id ASC)`，同一毫秒用数据库行 id 平局，命中索引
`idx_trace_events_run_id_occurred_at_id`。

**游标时间按 `LocalDateTime`（UTC 墙钟）绑定，而不是 `Timestamp`。** 这是本轮唯一一处"为了
执行计划改绑定类型"的地方，有对照实验：同一条语句、同一份数据（800 条事件、每条差 1 秒），

| 绑定方式 | `type` | `key_len` | `rows` |
| --- | --- | --- | --- |
| `Timestamp`（`setTimestamp`/`setObject`） | `ref` | 26（只用 `run_id`） | 800 |
| `LocalDateTime` / 字符串字面量 | `range` | 59（`run_id`+`occurred_at`+`id`） | 101 |

`ref` 计划下深页要把游标之前的索引项全扫一遍（O(页码)），`range` 才是从游标直接起读（O(一页)）。
两者都不回表（`Using index`）、都不文件排序，差别只在读取量。库里 `occurred_at` 本来就是 UTC
墙钟，`LocalDateTime(UTC)` 与 `Timestamp` 语义等价，因此按前者绑定，并用
`EXPLAIN ANALYZE` 把"实际读取量与一页相当"固定成断言——计划形态再变，这条用例也会失败。

## 4. 测试

### 4.1 12 条集成用例（真实 MySQL，`REPOPILOT_IT_REQUIRE_ALL_UP=true`）

| 要求 | 用例 |
| --- | --- |
| ① 在线 Run 事件查询 | `onlineRunShouldReadAcceptedAttemptInStableOrder` |
| ② 离线 Run 与无 Attempt 语义 | `offlineRunShouldQueryWithoutAttempt` |
| ③ 同时间戳稳定排序 | 同上（①③ 两条用例都断言同毫秒事件按 id 升序） |
| ④ 多页无重复无遗漏 | `pagingShouldNotRepeatOrSkip`（拼起来等于一次取全） |
| ⑤ 非法/篡改游标被拒 | `tamperedCursorShouldBeRejected` |
| ⑥ 游标与不同过滤条件混用 | `cursorShouldNotBeReusableAcrossFiltersOrRuns` |
| ⑦ 无效 Run / Attempt / 过滤条件 | `invalidRequestsShouldFailWithContractErrors` |
| ⑧ 空事件集与部分轨迹 | `emptyTraceShouldReturnEmptyPage` |
| ⑨ 事件与 Artifact 权限边界 | `eventArtifactShouldBeScopedToItsRun` + `ArtifactAccessIT`（2 条） |
| ⑩ 前端通过真实接口展示 | 前端只调 `GET /runs/{id}/events`（`api/traces.ts`），演示脚本用同一接口产出页面数据 |
| ⑪ 大量事件下不读整条 Trace | `largeTraceShouldStayBounded`（`EXPLAIN` + `EXPLAIN ANALYZE` 双断言） |
| ⑫ 回归 TASK-004/005 | 全量 `verify`：136 条集成用例全绿 |

每条用例都同时断言响应结构、数据库事实与分页不变式；不以 200 作为通过标准。

### 4.2 全量结果

```
# 单元测试
Tests run: 75, Failures: 0, Errors: 0, Skipped: 0

# 集成测试（真实 MySQL 8.0.22 + RabbitMQ）
Tests run: 136, Failures: 0, Errors: 0, Skipped: 0
  TASK-006 统一 Trace 查询            10
  TASK-006 附件访问与清理              2
  OfflineImportIT                     18（TASK-005 回归，含 observationMetadata 用例）
  其余 TASK-002/003/004 用例          106
BUILD SUCCESS
```

## 5. 端到端演示（实际执行）

```bash
SERVER_PORT=18080 REPOPILOT_WORKER_TOKEN=local-demo-token \
REPOPILOT_CURSOR_SECRET=demo-cursor-secret MYSQL_PASSWORD=repopilot_it_only \
  java -jar repopilot-server/target/repopilot-server.jar

BASE=http://127.0.0.1:18080/api/v1 WORKER_TOKEN=local-demo-token bash tools/demo-task-006.sh
```

关键输出（节选，均为真实响应）：

```
== 5. Run 详情 ==
mode=ONLINE  status=EVALUATING  taskOutcome=SUCCESS  attemptCount=1  traceEventCount=10

== 6. 第 1 页（limit=4）==
hasMore=true  resolvedFrom=ACCEPTED
本页事件类型：RUN_STARTED,MODEL_REQUEST,TOOL_STARTED,TOOL_COMPLETED

== 7. 第 2 页（带签名游标）==
第 2 页事件类型：TOOL_STARTED,ERROR,OUTPUT_PRODUCED,RUN_COMPLETED
两页重叠条数：0

== 9. 事件类型过滤 ==
过滤后条数：2   过滤后类型：TOOL_STARTED

== 10. 游标的三种误用 ==
篡改游标      → HTTP 400  "code":"VALIDATION_ERROR"
换过滤条件复用 → HTTP 400  "过滤条件已变化，游标不能复用，请从第一页重新查询"
未知 Run      → HTTP 404

== 12. 离线轨迹查询 ==
mode=OFFLINE  attemptCount=0  traceEventCount=3  traceCoverage=PARTIAL  acceptedAttemptId=
scope.resolvedFrom=NONE  hasMore=false
事件类型：MODEL_REQUEST,MODEL_RESPONSE,OUTPUT_PRODUCED
事件里的 attemptId 字段出现次数：0
无事件的离线 Run："items":[] "hasMore":false
```

页面入口（数据与接口完全一致，页面不写死任何演示内容）：

- 在线 Run：`http://127.0.0.1:5173/#/runs/<在线 Run>`
- 离线 Run：`http://127.0.0.1:5173/#/runs/<离线 Run>`

演示脚本每次运行带随机后缀，可对同一个库重复执行；领取是全局队列语义，脚本会校验领到的任务
确实属于本次实验，否则直接报错而不是"接着演"。

## 6. 检查项

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量 | `./mvnw -B verify` | 单元 75 / 集成 136，0 skipped |
| 前端类型检查 + 构建 | `npm run build`（含 `vue-tsc --noEmit`） | 通过，`RunDetailView` 17.9 kB |
| Worker | `python -m pytest -q` | 28 passed |
| 字典 → Java | `gen_java_from_dict.py --check` | 76 文件 / 28 Mapper 一致 |
| 字典 → DDL | `gen_ddl_from_dict.py --check` | 6 个迁移一致且未被改动 |
| 设计静态检查 | `verify_design.py` | PASS，1255 项 / 28 表 / 4 schema |
| OpenAPI 引用完整性 | 本地脚本解析 4 个 `openapi-*.yaml` | `openapi-trace.yaml` 1 路径 / 14 `$ref`，全部可解析 |
| 端到端演示 | `tools/demo-task-006.sh` | 13 步全通，退出码 0 |

未改动任何已冻结 Flyway 迁移（`migrations.lock.json` 校验通过）；Schema 未变更。

## 7. 已知限制

1. **游标密钥未配置时随进程生成**：本机开发若希望重启后仍能用旧游标，需要配置
   `REPOPILOT_CURSOR_SECRET`。这也意味着多实例部署必须配置同一个值，否则跨实例翻页会 400。
2. **工具调用配对只在当前已加载的事件页内进行**：一次工具调用被分页切开时，两页会各自显示
   「仅一侧事件」。这是如实展示（页内确实只有一侧），但不是全局配对——需要全量配对时应先翻完
   或按 `eventType=TOOL_STARTED/TOOL_COMPLETED` 过滤后查看。
3. **`allAttempts=true` 会混入失败尝试**：这是契约规定的排障口径，不进入自动评测输入；页面上
   用醒目文案提示，不默认开启。
4. **未做浏览器内 E2E 点击验证**：仓库没有 E2E 框架（无 Playwright/Cypress 依赖），本轮验证到
   "生产构建通过 + 面板只调用真实接口 + 演示脚本产出同样的数据 + 可点击入口"。要"点一遍"仍需
   人工或后续引入 E2E 框架。
5. **OpenAPI 校验仍是本地一次性脚本**：本轮发现 `openapi-trace.yaml` 有一处 YAML 语法错误
   （`description` 以反引号开头的裸标量），已修复；但 CI 目前不校验 OpenAPI，同类问题仍可能
   再次漏网。建议后续把"OpenAPI 可解析 + `$ref` 可解析"加进 `verify_design.py`。
6. **孤儿清理会删"无元数据"的存储对象**：默认宽限期 24h，只在对象确实没有 `artifacts` 行时删除。
   它不修复"有元数据但对象丢失"的反向损坏——那是数据损坏，应告警而不是静默重建。

## 8. CI 实跑

Commit `b0c226d` 推送到 `origin/develop`（fast-forward，`96aefb5..b0c226d`），
GitHub Actions run **39241959291**，5 个 Job 全部通过：

| Job | 结果 |
| --- | --- |
| 后端（MySQL 8.0.36 + RabbitMQ） | 单元 75 / 集成 **136**，0 失败 0 跳过 |
| 后端（MySQL 8.0 + RabbitMQ） | 单元 75 / 集成 **136**，0 失败 0 跳过 |
| Web（构建） | `npm run build`（含 `vue-tsc --noEmit`）通过，`built in 7.11s` |
| Worker（pytest） | 28 passed |
| 设计字典自检 | 迁移 6 个一致且冻结未改；76 个 Java 文件 / 28 个 Mapper 一致 |

CI 里没有任何 `@Disabled`、跳过或"期望失败"的用例；集成测试要求就绪探针真的返回 UP
（`REPOPILOT_IT_REQUIRE_ALL_UP=true`），迁移版本校验为 `1,2,3,4,5,6,7`。
