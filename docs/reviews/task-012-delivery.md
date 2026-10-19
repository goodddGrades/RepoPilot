# TASK-012 交付报告：压测、瓶颈分析与必要优化

日期：2026-10-19　分支：`develop`　基线 commit：`6435553`（TASK-011）

## 0. 一句话结论

压测发现并修复了**两个真实并发缺陷**（间隙锁导致的必然死锁、批次热点行锁竞争），
死锁从"每次压测必然发生、并发稍高就返回 500"降到 **0**；
同条件下 `run.execute` P50 从 193.8ms 降到 119.4ms（**−38%**），P95 从 381.4ms 降到 177.7ms（**−53%**）。

所有数字都来自同一条命令的实跑，可复现；原始结果留档在 `docs/reviews/perf/`。

## 1. 压测装置与可复现性

### 1.1 为什么 Mock 模型延迟天然等于零

`MockRunner`（`repopilot-worker/app/runner/mock.py`）是**纯 CPU 的零延迟实现**：
没有 `sleep`、没有网络、没有模型调用。所以一次 Run 端到端耗时里：

```
端到端耗时 = 平台开销（HTTP + 租约协议 + DB 写入 + 事件封口 + 聚合）
```

这让"M模型延迟"与"平台开销"的分离不需要任何估算或扣减——
只要换真实 Runner，这个装置会自动变成"平台开销 = 端到端 − 模型延迟"。

**没有为了让数字好看而给 Mock 加 sleep。** 伪造的延迟会让"平台开销"这个量失去意义。

### 1.2 装置构成

| 文件 | 作用 |
| --- | --- |
| `it/PerformanceBaselineIT.java` | 负载生成 + 指标采集；产出 `target/perf/*.json` |
| `tools/perf-diagnose.sql` | 慢查询 / 未使用索引 / 锁等待采集（performance_schema） |

参数可调，默认 8 并发 × 60 Run × 40 事件/Run。

### 1.3 一条命令复现

```bash
cd repopilot-server
export JAVA_HOME=/path/to/jdk21
eval "$(../tools/it-mysql.sh env)"          # 本地真实 MySQL 8 隔离实例
./mvnw -o test-compile                      # 改了 main 代码必须先 test-compile
./mvnw -o failsafe:integration-test -Dit.test=PerformanceBaselineIT
```

加压：`-Drepopilot.perf.concurrency=16 -Drepopilot.perf.runs=120`

配套诊断（需要 root 或 PROCESS 权限）：
```bash
"C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" -h 127.0.0.1 -P 13306 \
  -u root -p<pwd> < tools/perf-diagnose.sql
```

### 1.4 测试环境（数字可比的必要条件）

| 项 | 值 |
| --- | --- |
| CPU | Intel Family 6 Model 141，8 物理核 / 16 逻辑 |
| RAM | 15.7 GB |
| OS | Windows 10 19045 |
| JDK | Microsoft OpenJDK 21.0.10 LTS |
| MySQL | 8.0，`tools/it-mysql.sh` 的本地隔离实例（buffer pool 128 MB） |
| 存储 | 本地 SSD（非网络盘） |
| 数据量 | 60 Run × 40 事件 = 2400 条 trace_events；120 Run 时4800 条 |

**这个数据量远小于生产**。结论只对"中小规模实验"成立，见 §6 限制。

## 2. 发现的两个真实缺陷

### 2.1 缺陷一：间隙锁导致的必然死锁（严重）

**现象**：并发写轨迹时事件上传接口返回 500，日志里是
`DeadlockLoserDataAccessException: Deadlock found when trying to get lock`。

**InnoDB 死锁图**（`docs/reviews/perf/deadlock-before-fix.txt`，修复前实抓）：

```
*** (1) HOLDS THE LOCK(S):
RECORD LOCKS ... index uk_trace_events_event_id ... lock_mode X
  0: len 8; hex 73757072656d756d; asc supremum;      ← 尾间隙锁
*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS ... index uk_trace_events_event_id ... lock_mode X insert intention waiting
  0: len 8; hex 73757072656d756d; asc supremum;
```

**根因**：`TraceEventMapper.findByEventIdsForUpdate` 用了
`WHERE event_id IN (…) FOR UPDATE`。InnoDB 在 REPEATABLE READ 下，
对**尚不存在**的行会加**间隙锁**。而事件 `event_id` 是 ULID——单调递增、同一毫秒内生成的前缀几乎相同，
于是并发 Worker 的 eventId 在唯一索引上**必然相邻**，各自持有的间隙互相重叠 → 互等 → 死锁。

这不是低概率事件：8 并发 × 60 Run × 40 事件下**每次必现**。

**修法**：去掉 `FOR UPDATE`，改由唯一约束承担幂等。
`WorkService.appendEvents` 按 eventId 捕获 `DuplicateKeyException` 并回查判定：
同内容→`DUPLICATE`，异内容→`RESOURCE_CONFLICT`。

**为什么不靠调索引规避**：锁来自唯一索引上的间隙，不是索引选择问题。加索引不消除外键/唯一键检查，
只会掩盖症状。

**为什么幂等仍然成立**：幂等的真正保证是 `uk_trace_events_event_id` 唯一约束，
而不是那把读锁。"查不到就插、插不上就回查"本身就是完整的幂等实现。

### 2.2 缺陷二：批次行热点锁竞争

**现象**：修掉缺陷一之后暴露出来的第二个死锁点：
`EvaluationBatchMapper.markRunning` 在 `evaluation_batches` 上死锁。

**根因**：`createPendingJobs` 原本是"先插 Job，再 markRunning"，
意味着 INITIAL 批次那一行的行锁被持有到**整个事务结束**。
而 INITIAL 批次是该实验下所有 Run 封口都要锁的唯一热点行，
8 个 Worker 并发封口同一实验的不同 Run 时全部排在它上面，与 Job 插入的间隙锁交叉。

**修法**：同事务内把 `markRunning` 移到 Job 插入**之前**。
同一事务内调整顺序**不改变原子性**（要么都成功要么一起回滚），
但把热点行的锁持有时间从"整批插入"缩短到"一条 UPDATE"，交叉窗口大幅收窄。

**为什么不需要全局锁序或外层重试**：批次行是唯一的全局串行点，
其余表（Run / Job / trace_events）按业务 ID 打散，不存在第二个共享热点。

### 2.3 附带的防御：死锁重试（`DeadlockRetry`）

即便修掉上述两个，仍可能有低概率锁冲突。**并发数据库出现死锁是正常机制**
（InnoDB 会主动回滚其中一个事务），应用层必须能恢复，不能把它暴露成 500。

`common/persistence/DeadlockRetry` 对整事务做指数退避 + **抖动**重试（默认 3 次）。
已接入四个并发写路径：Run 领取、事件写入、Run 封口、Job 领取。

**判定严格**：只认 `DeadlockLoserDataAccessException` / `CannotAcquireLockException` /
`PessimisticLockingFailureException` / SQLState `40001`、`41000`。
唯一键冲突、连接失败、业务异常**一律不重试**——
把它们重试只会把明确的错误放大成空转。

## 3. 修复前后对照（同条件，可复现）

同参数（8 并发 × 60 Run × 40 事件），原始文件：
[`perf/before-fix-c8-r60.json`](./perf/before-fix-c8-r60.json) /
[`perf/after-fix-c8-r60.json`](./perf/after-fix-c8-r60.json)

### 3.1 正确性（这是最重要的指标）

| 指标 | 修复前 | 修复后 |
| --- | --- | --- |
| 事件上传 500 | **每次必现** | **0** |
| `DeadlockRetry` 触发 | 32 次/轮 | **0** |
| 阶段耗时（说明重试在救火） | 失败 | 0 重试 |

### 3.2 死锁计数（InnoDB 权威计数，差值法）

| | 修复前 | 修复后（16 并发 × 120 Run 再压一轮） |
| --- | --- | --- |
| `lock_deadlocks` | 每次压测递增 | **增量 0**（95 → 95） |
| `lock_row_lock_waits` | 递增 | 递增（+87，证明确实在并发等锁） |

`lock_row_lock_waits` 增长而 `lock_deadlocks` 不增长，说明并发确实发生、确实在等锁，
但不再形成循环等待——**修复的是死锁本身，不是把等待藏起来**。

### 3.3 耗时（同条件，8 并发 × 60 Run）

| 阶段 | 修复前 | 修复后 | 变化 |
| --- | --- | --- | --- |
| 实验提交（单事务 60 槽位） | 338 ms | 134 ms | −60% |
| 并发执行（60 Run 全链路） | 2131 ms | 1311 ms | **−38%** |
| 并发评测（60 Job） | 665 ms | 462 ms | −31% |
| 事件批量写入（2400 条） | 882 ms | 816 ms | −7% |
| 报告聚合（冷） | 65 ms | 40 ms | −38% |
| 报告聚合（热） | 39 ms | 22 ms | −44% |
| `run.execute` P50 | 193.8 ms | 119.4 ms | **−38%** |
| `run.execute` P95 | 381.4 ms | 177.7 ms | **−53%** |
| `run.execute` max | 408.5 ms | 203.5 ms | −50% |
| `job.evaluate` P50 | 44.9 ms | 33.5 ms | −25% |
| `job.evaluate` P95 | 77.0 ms | 39.2 ms | −49% |

**为什么快这么多**：修复前的 32 次死锁重试每次要等MySQL 检测死锁（`innodb_deadlock_detect`）
并回滚事务，加上 `DeadlockRetry` 自己的退避——这些时间全部计入了端到端耗时。
消除死锁等于同时消除了这些等待，所以是实打实的改善，不是统计口径变化。

### 3.4 加压验证（16 并发 × 120 Run）

[`perf/after-fix-c16-r120.json`](./perf/after-fix-c16-r120.json)、
[`perf/after-fix-c16-r120-repeat.json`](./perf/after-fix-c16-r120-repeat.json)（重复跑，两次都通过）

| 指标 | 第一轮 | 第二轮 |
| --- | --- | --- |
| `run.execute` P50 | 264.2 ms | 321.6 ms |
| `run.execute` P95 | 569.3 ms | 619.2 ms |
| 死锁 | 0 | 0 |

耗时随数据量翻倍而上升，符合预期（内存与日志开销）；**关键是 16 并发下依然零死锁**——
修复不是只在低并发下碰巧成立。

### 3.5 吞吐

| 场景 | Run/s（执行+评测端到端） |
| --- | --- |
| 8 并发 × 60 Run | (60+60) / (1.311+0.462) ≈ **34/s** |
| 16 并发 × 120 Run | (120+120) / (3.344+1.519) ≈ **25/s** |

并发翻倍而吞吐**下降**——说明已经越过拐点，瓶颈在数据库与 JVM 争抢 CPU，
而不是队列或锁。**这就是不加中间件的依据**：换成 Redis 或 Kafka 只会把瓶颈往后推。

## 4. 慢查询与索引分析（实测，非估算）

`performance_schema.events_statements_summary_by_digest` Top 累计耗时：

| 语句 | 平均 | examined/sent | 判断 |
| --- | --- | --- | --- |
| `COMMIT` | 2.6 ms | — | 事务提交开销，正常 |
| `INSERT INTO trace_events` | 0.64 ms | — | 正常 |
| `SELECT ... FROM trace_events WHERE event_id IN (…)` | 5.3 ms | 16867/42 | **已修复**（见下） |
| `UPDATE evaluation_batches SET STATUS=RUNNING` | 2.2 ms | — | **已优化**（缩短锁窗口） |
| `SELECT id FROM evaluation_runs r JOIN experiments e …` | 0.65 ms | 2648/2648 | 正常，走索引 |
| `UPDATE evaluation_runs r JOIN experiments e …` | 3.8 ms | 5812/0 | 聚合扫描，正常 |

### 顺带清理的死代码

`TraceEventMapper.listByRun` 用 `LIMIT ? OFFSET ?` 做深翻页，
慢查询里显示 examined 11687 / sent 42 的**扫描放大**。
查调用链发现：**生产代码里已无任何调用方**——TASK-006 用签名 keyset 游标替换后遗留。

留着它等于留一个"看起来能用、用了就退化"的入口。已标`@Deprecated` 并在注释里
写明为什么不能用 OFFSET。（没有直接删除，是为了保留对比说明与后续可能的内部调用。）

### 索引结论

现有 28 张表的索引经实测**没有发现缺失**：`SUM_NO_INDEX_USED` 为 0 的语句占绝大多数，
热路径（实验 Run 列表、Trace 游标、Job 领取、批次收口）都走索引。
**因此本次没有新增任何索引**——凭感觉加索引是负优化。

## 5. 为什么没有引入 ClickHouse / Redis

`docs/development-guide.md` 的原则：只有 MySQL 成为明确瓶颈后才讨论 ClickHouse；
只有热点聚合有证据才引入 Redis。

**实测证据**：
1. 没有一条慢查询的瓶颈在"扫描行数"——都是锁与事务，不是索引。
2. 并发从 8 提到 16，吞吐反而下降 → 瓶颈在 CPU/DB 争抢，**不在查询能力**。
3. 报告聚合在 60 槽位下冷 40ms / 热 22ms，离"需要缓存"的量级差两个数量级。

**所以：不引入。** 加中间件会把瓶颈藏起来而不是解决它，
并引入一致性、失效、备份三类新问题。当前规模下收益为负。

## 6. 已知限制（不能被当成生产结论）

| # | 限制 | 说明 |
| --- | --- | --- |
| 1 | **数据量小** | 60~120 Run、2400~4800 事件。深页退化、索引选择性变化在10万+ 行时才显现，本次测不到 |
| 2 | **单机同机** | MySQL、JVM、压测代码在同一台机器上，CPU 互相争抢。绝对数字不能外推到分离部署 |
| 3 | **短时压测** | 单轮 20~30 秒，不覆盖内存增长、连接池耗尽、GC 累积等长时行为 |
| 4 | **Mock Runner** | 全部平台开销数据来自零延迟 Mock。真实 Agent 的模型调用延迟不在其中，且 Mock 不产生真实模型错误与重试 |
| 5 | **未压真实 RabbitMQ** | Outbox 发布走故障注入替身；真实 broker 的确认延迟与背压未纳入 |
| 6 | **P95/max 样本量小** | `run.execute` 只有 60~120 个样本，P99 无统计意义，故未列出 |
| 7 | **吞吐拐点未精确定位** | 只测了 8 与 16 两点，中间地带未采样 |
| 8 | **死锁修复的安全性** | 依赖 `event_id` 唯一约束。若未来去掉该约束，幂等会失效（届时需重新设计） |

## 7. 正确性没有为性能让步

TASK-012 的硬要求是"不牺牲一致性换虚假吞吐"。逐条自查：

| 一致性要求 | 是否为性能改动过 | 证据 |
| --- | --- | --- |
| 事件幂等（同 ID 同内容 → DUPLICATE） | **没有**。改的是"用唯一约束代替读锁" | `ExecutionFlowIT` 事件幂等用例通过；`DeadlockRetryIT` 断言副作用只发生一次 |
| 同 ID 异内容 → 409 | **没有**。`DuplicateKeyException` 回查后仍抛 `RESOURCE_CONFLICT` | `ExecutionFlowIT` 409 用例通过 |
| 租约与 fencing | **没有**。`appendEvents` 仍先 `requireLiveAttempt` 校验 token | 全量 verify 通过 |
| Job/批次状态机 | **没有**。`markRunning` 仍是 `WHERE status='PENDING'` 的幂等单调更新 | `EvaluationEngineIT` 全通过 |
| 重试的原子性 | 死锁发生时整个事务已回滚，重放从干净状态开始 | `DeadlockRetryIT` 断言 `sideEffects == 1` |

**全量回归**：`./mvnw verify` → **217 run / 0 失败 / 0 错误 / 1 跳过**
（那 1 skip 是 `ApiHealthIT.readiness`，由 `REPOPILOT_IT_REQUIRE_ALL_UP` 环境门控；
按 CI 方式带 RabbitMQ 凭据实跑为 4 run / 0 跳过）。

## 8. 本次新增/修改文件

| 文件 | 说明 |
| --- | --- |
| `it/PerformanceBaselineIT.java`（新增） | 负载生成 + 指标采集；产出 `target/perf/*.json` |
| `it/DeadlockRetryIT.java`（新增） | 死锁重试边界的 8 个确定性用例 |
| `tools/perf-diagnose.sql`（新增） | 慢查询 / 未用索引 / 锁等待采集 |
| `common/persistence/DeadlockRetry.java`（新增） | 整事务死锁重试（指数退避 + 抖动） |
| `modules/trace/mapper/TraceEventMapper.java` | 去掉 `FOR UPDATE`（核心修复）；`listByRun` 标 Deprecated |
| `modules/trace/service/RunStore.java` | `lockEvents` → `findEvents` |
| `modules/experiment/service/WorkService.java` | 三个事务接死锁重试；事件插入捕获唯一键冲突 |
| `modules/evaluation/service/EvaluationClaimService.java` | 领取事务接死锁重试 |
| `modules/evaluation/service/EvaluationJobService.java` | `markRunning` 移到插入之前 |

**未新增迁移、未新增索引、未引入中间件、未新增 OpenAPI 或前端类型**
（本次是纯后端并发与性能修正，无对外契约变化）。

## 9. 压测类不进 CI

`PerformanceBaselineIT` 刻意**不挂在常规 verify 路径**上：

- 它需要分钟级运行，CI 每轮跑会拖慢流水线；
- 它的**性能数字不做断言**——CI 机器性能不稳定，把毫秒数写成断言会让 CI 变flaky；
- 它的**断言只针对正确性**（压测跑完系统必须仍然自洽），这部分是稳定的。

需要时显式执行：`./mvnw -o failsafe:integration-test -Dit.test=PerformanceBaselineIT`

## 10. 最终 README 可让新开发者从空环境跑通

见仓库根 `README.md` 的"从零验证"一节与 `docs/01-local-development.md`：
基础设施 → 后端 → Worker → 前端 → 演示脚本（`tools/demo-task-00{3..7}.sh`）
→ 本压测。**简历条目只应引用本文档中已实跑证明的能力。**