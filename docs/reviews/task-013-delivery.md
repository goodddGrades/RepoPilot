# TASK-013 交付报告：RabbitMQ 可靠消息链路闭环

> 范围：TASK-013-A / B / C / D / E 全部子项。
> 技术栈不变：Java 21 + Spring Boot 3、Python 3.11 Worker、MySQL 8.0、RabbitMQ 3.13.7。
> 全部验收基于**真实 broker + 真实 MySQL**，未使用内存 broker 或 Mock 作为最终依据。

## 0. 结论摘要

| 问题 | 结果 |
| --- | --- |
| 消息真的经过 RabbitMQ 了吗 | 是。三档退避各进各的等待队列，TTL 到期经再发布层回业务队列，档位用尽进 DLQ |
| 消费侧真的接上了吗 | 是。Worker 消费 → Inbox 登记 → claim → ACK；两条通道都收敛到同一个 claim |
| 失败消息会被丢吗 | 不会。可重试走退避，耗尽进 DLQ 并保留原始信封与 `x-death` |
| 数据会不会被误删 | 不会。Outbox 只删"已发布且超期"，PENDING / FAILED 永不自动删除 |
| 能否重复执行 | 能。`tools/demo-task-013.sh` 连续多轮 EXIT=0 |
| 有没有没做到的 | 有，见 §7。最要紧的一条：**CI 未实际运行**（未获 push 授权） |

**一句话**：链路闭环成立，故障可恢复，清理不误删，测试可重复；CI 状态是"未运行"而不是"通过"。

## 1. 各子项完成情况

### TASK-013-A：拓扑与 Outbox 发布闭环 ✅

**实际拓扑是四层**（不是任务书假设的两层）：

```
业务发布 ──► repopilot.events (topic)
               binding: run.ready / run.finished / evaluation.ready / experiment.finished
               ▼
           业务队列（quorum, durable, DLX→dlx）

消费失败且可重试 ──► repopilot.events.retry.v2 (topic), routing key = retry.tN
                       binding: retry.t1 / retry.t2 / retry.t3（精确键，无通配符）
                       ▼
                    repopilot.retry.wait.tN（quorum, per-queue TTL, DLX→republish）
                       TTL 到期，routing key 仍是合成键 retry.tN
                       ▼
                    repopilot.retry.republish（无 TTL，由 Worker 消费）
                       读 x-original-routing-key，重新发布到 repopilot.events
                       ▼
                   回到业务队列，routing key 复原为 run.ready

重试耗尽 ──► basic_reject(requeue=false) ──► repopilot.events.dlx ──► repopilot.events.dlq
```

* **为什么要第四层"再发布"**：topic 方案下退避消息的 routing key 是 `retry.tN` 这样的**合成键**。
  TTL 到期的死信沿用的正是这个键，而业务交换机上没有它的 binding——消息会被 broker 静默丢弃。
  这一层把键还原成原值。
* **为什么用精确键而不是 `retry.#`**：通配符会让一条消息同时进 3 个等待队列（实测：3 条消息 → 每个旧队列深度 3）。
* **为什么不改旧交换机类型**：`exchange_declare` 对已存在的交换机改类型会抛 `PRECONDITION_FAILED` 并关闭 channel。
  新拓扑用新名字 `repopilot.events.retry.v2`，旧名保留为 `RETRY_EXCHANGE_LEGACY` 供迁移工具识别。
* 声明幂等：`RabbitTopologyDeclarer` 启动时声明 4 个交换机 + 8 个队列，重复声明结果一致（IT + 演示均验证）。
* 发布闭环：`PERSISTENT` + `mandatory` + publisher confirm，**只有 `routed()` 为真才标 PUBLISHED**；
  不可路由 / NACK / 超时 → `retry_count+1` + 指数退避 + 抖动，仍 PENDING；超上限 → FAILED。
* 租约与 fencing 保留：确认回写要求 `claim_token_hash` 匹配且租约未过期，否则拒绝写入（失效发布器不能覆盖新领取者的状态）。
* 「不持锁跨网络」保留：领取在短事务内完成，网络 IO 在事务外。

### TASK-013-B：Worker 真正通过 RabbitMQ 消费 ✅

* `repopilot-worker/app/consumer.py`：pika BlockingConnection + 独立线程，`prefetch` 可配。
* 消费顺序：**Inbox 登记 → claim → ACK**。先 ACK 会让"进程在干活前崩溃"变成消息永久丢失。
* 消息只带 `runId`（信封里不放正文）：消息不是任务，任务在后端数据库里；消费后用消息触发一次 `claim`。
* 重复投递：Inbox 唯一键 `(consumer_name, event_id)` 让第 2..N 次命中 `DUPLICATE`，仍触发一次唤醒，claim 幂等返回空。
* 长任务不占消费者：回调只做三件短事，真正的执行由轮询线程异步推进；`prefetch=1` 限制的是"并发处理几条唤醒"。
* HTTP 轮询保留为降级通道，两通道都走 `run_once() → claim`，**重复的是唤醒、不是执行**（详见手册 §1.1）。
* 配置：`WORKER_RABBITMQ_ENABLED`（默认 **false**，避免"没配 MQ"这种合法部署起不来）、`WORKER_RABBITMQ_QUEUE`、`WORKER_INBOX_CONSUMER_NAME` 等写入 `.env.example`。

### TASK-013-C：失败消息、重试与死信 ✅

| 失败类别 | 判定 | 处置 |
| --- | --- | --- |
| 可重试的基础设施错误（后端不可用、连接超时） | 抛 `ConsumerError(retryable=True)` | 按当前档位进 `retry.wait.tN` |
| 暂时性依赖故障（登记接口 5xx） | 同上 | 同上 |
| 不可重试的契约错误（信封非法、版本不支持、409 异内容） | `retryable=False` | `basic_reject(requeue=false)` → DLX → DLQ |
| 业务失败（Agent 跑挂、评测不通过） | **不进消息层** | 由 Attempt 状态机记录，消息正常 ACK |

* 退避 5s / 30s / 120s，三档用尽后进 DLQ，**不无限循环**。
* DLQ 保留原始信封、eventId、`x-retry-tier`、`x-last-error`、`x-first-failed-at`、`x-death`。
* 死信查询与受控重放：`GET/POST /internal/v1/messaging/dead-letters`，**没有全量重放入口**（`eventIds` 为空直接 400），单次有上限，重放**保留原 eventId** 因此继续走 Inbox 去重。
* 旧拓扑积压迁移工具 `tools/migrate_retry_topology.py`：强制 drain → verify → purge 三阶段，先保全再清理，**对仍有消息的队列拒绝删除**。

### TASK-013-D：Outbox/Inbox 自动清理与保留 ✅

先核实了表结构与契约：`outbox_events` / `inbox_events` 沿用 TASK-011，**本任务没有新增 Flyway 迁移**（`migrations.lock.json` 未变）。清理规则：

| 表 | 删除条件 | 永不删除 |
| --- | --- | --- |
| `outbox_events` | `status='PUBLISHED'` **且** `published_at < now - outbox-days` | PENDING、FAILED |
| `inbox_events` | `created_at < now - inbox-days` | 保留期内 |

* 用 `published_at` 而不是 `created_at`：后者是"事件产生时间"，一条卡了三天的 PENDING 刚发布就被判超期删掉，等于把"刚成功"当成"很旧"。
* Inbox 保留 30 天，远大于最大重投窗口（5+30+120 ≈ 155 秒）：Inbox 记录被删后同一条消息再投会被当成新事件**重新执行副作用**。
* 分批：`LIMIT 500` + 每批独立短事务，单轮最多 20 批；幂等，重复执行不多删。
* 可配：`outbox-days` / `inbox-days` / `batch-size` / `max-batches-per-run`。
* 运维入口：`GET /internal/v1/messaging/retention` 与 `POST .../purge`（规则与定时器完全相同，不接受任何过滤条件）。

### TASK-013-E：可观测性与故障恢复 ✅

| 任务书要求 | 指标 | 归属 |
| --- | --- | --- |
| Outbox 待发布数 / 最老年龄 / 发布失败数 | `repopilot.outbox.pending`、`outbox.oldest.pending.age.seconds`、`outbox.publish.failed`、`outbox.exhausted` | `OutboxScheduler` |
| RabbitMQ 消费成功/失败/重试/DLQ 数 | `consumer.consumed`、`consumer.failed`、`consumer.retried`、`consumer.dead_lettered`、`consumer.dlq.depth` | `ConsumerMetrics` + 消费者上报 |
| Inbox 重复命中数 | `repopilot.inbox.duplicate`（按 outcome 打标签） | `InboxMetrics` |
| 消费者处理延迟 / 积压 / 租约超时 | `consumer.delivery.latency.avg/max.seconds`、`consumer.backlog.runs`、`consumer.backlog.oldest.age.seconds`、`consumer.lease.expired` | `MessagingObservability` |
| 清理处理量 / 失败数 / 最后执行结果 | `retention.outbox.deleted`、`retention.inbox.deleted`、`retention.errors`、`retention.last.{run,success,failure}.epoch.seconds` | `RetentionService` |

* **消费计数由消费者上报**（`POST /internal/v1/messaging/consumer-stats`，上报**增量**）：数据库无法区分"进 DLQ"与"没人消费"——两者在 `outbox_events`/`inbox_events` 里完全同形。
* **DLQ 深度探测失败保留上次值，不当作 0**：0 表示"确定没有死信"，探测失败是"不知道"，把后者报成前者会让告警在真故障时反而不响。
* 故障恢复实测：RabbitMQ 不可用 → 恢复后重连、Worker 重启、未完成任务恢复（租约过期 → 回收 → 新 Attempt 领取），见 §5。

## 2. 实际修改的关键模块、接口、迁移与配置

### 新增（Java，12 个主代码文件 + 4 个集成测试类）

| 文件 | 职责 |
| --- | --- |
| `messaging/RabbitTopology.java` | 拓扑常量的**唯一来源**：4 交换机 / 8 队列 / 路由键 / 退避档位 |
| `messaging/RabbitTopologyDeclarer.java` | 启动时幂等声明拓扑 |
| `service/DeadLetterService.java` | 死信查询（hold-all-then-restore）与受控重放 |
| `service/RetentionService.java` | 分批清理 + 保留期指标 + 最后执行结果 |
| `service/ConsumerMetrics.java` | 消费计数（由上报累加） |
| `service/InboxMetrics.java` | Inbox 去重命中计数 |
| `service/MessagingObservability.java` | 延迟 / 积压 / 租约超时 / DLQ 深度 |
| `service/DeadLetterDepthScheduler.java` | 定期探测 DLQ 深度（探测在网络侧，不进 gauge 取值函数） |
| `mapper/ReliabilityMetricsMapper.java` | 积压、租约超时、延迟的只读查询 |
| `web/DeadLetterController.java` | 死信查询 + 重放 |
| `web/ConsumerStatsController.java` | 消费统计上报 |
| `web/RetentionController.java` | 保留期状态与手动触发 |

新增集成测试：`RabbitTopologyIT`、`MessagingObservabilityIT`、`MessagingRecoveryIT`、`ScriptedEventPublisher`（测试替身）。
Worker 新增 `app/consumer.py` 与 4 个测试文件；工具新增 `demo-task-013.sh`、`task013_broker.py`、`migrate_retry_topology.py`。

### 修改（22 个文件，+1320 −61）

后端：`RabbitEventPublisher`（confirm/mandatory/返回处理）、`OutboxPublisherService`（fencing、上限）、`OutboxScheduler`（可禁用、指标）、`InboxService`/`InboxEventMapper`、`OutboxEventMapper`（领取与清理查询）、`application.yml`。

Worker：`app/consumer.py`（新）、`app/runtime.py`、`app/client.py`、`app/config.py`、`app/main.py`、`.env.example`、`requirements*.txt`、`pyproject.toml`。

### 接口（4 个新增，全部在 `/internal/v1` 下，共用 `WorkerAuthFilter`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/internal/v1/messaging/dead-letters?limit=N` | 死信摘要（**只读，不改变队列**） |
| POST | `/internal/v1/messaging/dead-letters/replay` | 受控重放，必须显式列 eventId |
| POST | `/internal/v1/messaging/consumer-stats` | 消费统计增量上报 |
| GET / POST | `/internal/v1/messaging/retention[/purge]` | 清理状态 / 立即执行一轮 |

### 迁移

**没有新增 Flyway 迁移。** TASK-013 复用 TASK-011 的 `outbox_events` / `inbox_events` 表结构，
所有清理与领取条件都能用现有列与索引表达，因此不改表、不改 `migrations.lock.json`，
升级路径就是"部署新版本，Flyway 无待执行迁移"。

### 配置（新增项）

```yaml
app.repopilot.outbox:
  poll-interval-ms: 1000
  lease-seconds: 30
  max-attempts: 8
  batch-limit: 50
  scheduler-enabled: true        # 集成测试关掉，避免抢走别的用例造的数据
app.repopilot.retention:
  outbox-days: ${REPOPILOT_RETENTION_OUTBOX_DAYS:7}
  inbox-days: ${REPOPILOT_RETENTION_INBOX_DAYS:30}
  batch-size: ${REPOPILOT_RETENTION_BATCH_SIZE:500}
  max-batches-per-run: 20
```

Worker：`WORKER_RABBITMQ_ENABLED`（默认 false）、`WORKER_RABBITMQ_QUEUE`、`WORKER_RABBITMQ_PREFETCH`、
`WORKER_RABBITMQ_HEARTBEAT`、`WORKER_INBOX_CONSUMER_NAME`、`WORKER_TEST_RABBIT_*`（真实 broker 测试开关）。

### 文档

`docs/architecture/05-event-contract.md`（四层拓扑整节）、`docs/modules/messaging-reliability.md`
（新增 §1.1 双通道、§9.1 查询后深度为 0 的陷阱、§9.2 清理运维入口、§9.3 消费计数来源）、
`docs/contracts/openapi-messaging.yaml`（4 个端点 + 7 个 schema）、`docs/README.md`（索引）、
`docs/reviews/task-013-amqp-facts.md`（**28 条**实测事实）。

**JSON Schema 未变**：事件信封结构、`event-envelope.schema.json` 与 payload 约束均未改动——
TASK-013 只改变消息**怎么走**（拓扑与重试），不改变消息**长什么样**。
消费者仍然只接受 `schemaVersion=1`，更高版本进隔离队列而不是"尽力解析"。

## 3. 真实消息链路的执行证据

全部来自 `tools/demo-task-013.sh`（真实 broker + 真实 MySQL）与手工 HTTP 调用。

### 3.1 拓扑与分档（场景 1、6）

| 验证 | 方法 | 实测结果 |
| --- | --- | --- |
| 拓扑声明幂等 | 连续声明两次，比较输出 | 两次结果完全一致 |
| 交换机类型 | management API 只读查询 | `events` / `retry.v2` / `republish` 均为 `topic` |
| t1/t2/t3 精确分档 | 分别发布 `retry.t1/t2/t3`，查三个队列深度 | 只有目标队列 = 1，另外两个 = 0 |
| 非法档位 | `retry.t0 / t4 / t1x / tX / t9` | 全部抛 `UnroutableError`，三个队列深度均为 0 |
| 等待队列 binding 数 | 查 inbound binding | t1/t2/t3 各 **1** 个（精确键，无通配符） |

### 3.2 退避回流的两段链路（场景 6c）

```
发布 retry.t1 → repopilot.retry.wait.t1（深度 1，TTL=5s，含 x-original-routing-key=run.ready）
  ↓ TTL 到期，死信到 repopilot.events.republish
repopilot.retry.republish（深度 1，routing key = retry.republish，原键在头里，档位头保留）
  ↓ Worker 读 x-original-routing-key 重新发布
repopilot.run.ready（深度 1，routing key 复原为 run.ready）
```

### 3.3 死信与重放（场景 7、8 + 手工 HTTP）

| 验证 | 实测 |
| --- | --- |
| 不可重试消息进 DLQ | 业务队列深度 1 → reject → DLQ 深度 1 |
| 原始信封与档位保留 | `x-retry-tier=3`，`x-death=[{reason: rejected, queue: repopilot.run.ready, ...}]` |
| 查询不改变队列 | 查询前后深度均为 1；摘要中 eventId 无重复 |
| **受控重放（真实 HTTP）** | `{"requested":1,"replayed":1,"skipped":2}` → 业务队列深度 0→1，eventId **未变**，routing key 复原为 `run.ready`，新增 `x-replayed-at` 头，`x-death` 保留 |
| 拒绝全量重放 | `{"eventIds":[]}` → **400**「必须显式指定要重放的 eventId；本接口不提供全量重放」 |
| 未鉴权 | `GET /internal/v1/messaging/retention` → **401** |

### 3.4 消费中断（场景 8b）

```
发 1 条 → 深度 1 → basic_get 取走但不 ACK → 断连 → 等待 1.5s → 深度恢复为 1
```

### 3.5 Outbox 发布闭环（场景 2、3）

脚本插入一条 PENDING 记录（`next_retry_at` 设为未来 1 小时，使在跑的发布器抢不走），
静置 1.5 秒后仍为 PENDING；随后经真实发布路径（confirm + mandatory）发出，
业务队列深度 1，队列中消息的 eventId 与库中记录**逐字节一致**，ACK 后深度回 0。

## 4. 自动化测试与全量回归的实测数字

### 后端（`./mvnw -o -B verify`，真实 MySQL + 真实 RabbitMQ）

| 项目 | 数字 |
| --- | --- |
| 单元测试（surefire） | **75 通过**，0 失败 0 错误 **0 跳过** |
| 集成测试（failsafe） | **258 通过**，0 失败 0 错误 **0 跳过** |
| 合计 | **333 个用例**，BUILD SUCCESS |

最终验收命令（与 `ci.yml` 的环境变量一致）：

```bash
REPOPILOT_IT_REQUIRE_ALL_UP=true \
REPOPILOT_IT_DB_HOST=127.0.0.1 REPOPILOT_IT_DB_PORT=13306 \
REPOPILOT_IT_DB_NAME=repopilot_it REPOPILOT_IT_DB_USER=repopilot \
REPOPILOT_IT_DB_PASSWORD=repopilot_it_only \
REPOPILOT_IT_RABBIT_HOST=127.0.0.1 REPOPILOT_IT_RABBIT_USER=repopilot \
REPOPILOT_IT_RABBIT_PASSWORD=repopilot_dev_pwd \
./mvnw -B -o verify -DargLine="-Xmx1280m -XX:MaxMetaspaceSize=512m -Xss512k"
```

> **`-DargLine` 是本机内存限制，不是代码配置**：本机 16 GB 物理内存在首次验收时只剩 382 MB，
> JVM 报 `Native memory allocation (malloc) failed`（`hs_err` 已确认），fork 崩溃 ——
> 这是环境资源问题，不是测试失败。给测试 JVM 设 1.25 GB 堆后同一套用例全绿。
> CI 的 `ubuntu-latest` 不需要该参数。

TASK-013 新增/改写的集成测试：

| 类 | 用例数 | 覆盖 |
| --- | --- | --- |
| `RabbitTopologyIT` | 26 | 拓扑声明幂等、分档路由、confirm/return、租约 fencing、死信查询与重放、保留策略分批删除、`@Value` 默认值一致性 |
| `MessagingObservabilityIT` | 12 | 七类指标注册、消费计数累加（含 failed 推导）、负数忽略、积压/租约/延迟查询、清理最后执行结果、DLQ 探测失败保留旧值 |
| `MessagingRecoveryIT` | 3 | 消息链路 + 租约过期 + 任务恢复串起来 |
| `MessagingReliabilityIT`（改） | 13 | 发布确认、返回、不可路由、毒消息重试预算、Inbox 幂等与冲突 |

> **原"1 skipped"已消除**：那条是 `ApiHealthIT.readinessShouldBeUpWhenEveryDependencyIsAvailable`，
> 由 `@EnabledIfEnvironmentVariable(named = "REPOPILOT_IT_REQUIRE_ALL_UP", matches = "true")` 门控，
> **不是"依赖外网"**（早期报告写错了）。本机 MySQL 8.0.22 与 RabbitMQ 3.13.7 都在，
> 补上该变量后用例真实执行并通过，`ApiHealthIT` 4/4、全量 0 跳过。

### Worker（Python 3.13 + pika 1.3.2 + 真实 broker）

```
232 passed, 4 skipped, 9 warnings in 162.58s (0:02:42)
```

4 条 skipped 全部是 `test_cli_runner_smoke.py` 里"需显式开启、会产生真实费用"的真实 CLI 冒烟：
**本轮按未执行处理，不计入通过数**。除这 4 条外，Worker 侧全部用例（含真实 broker 的
`test_consumer_rabbit.py` / `test_recovery_rabbit.py`）都实际执行并通过。
新增测试文件：`test_consumer_protocol.py`（协议与统计上报）、`test_consumer_rabbit.py`（真实 broker 消费）、
`test_recovery_rabbit.py`（7 个故障恢复）、`test_migrate_retry_topology.py`（10 个迁移工具）。

### 端到端演示

```
bash tools/demo-task-013.sh   →   EXIT=0   全部 9 个场景通过
```

### 设计与生成器校验

| 检查 | 结果 |
| --- | --- |
| `tools/verify_design.py` | PASS（1289 checks / 28 tables / 4 json_schemas） |
| `tools/gen_ddl_from_dict.py --check` | OK：6 个迁移文件与字典一致 |
| `tools/gen_java_from_dict.py --check` | OK：76 个 Java 文件与字典一致 |
| `tools/gen_java_from_dict.py --self-test` | 通过 6 项 |

### 前端

本任务**未改动前端**。`npm run build`（`vue-tsc --noEmit && vite build`）EXIT=0。

## 5. DLQ、重试、幂等与清理的验证结果

| 验证项 | 结论 | 证据 |
| --- | --- | --- |
| 可重试故障按档位退避 | 通过 | t1/t2/t3 各进各队列，非法档位不可路由 |
| 退避有上限、不无限循环 | 通过 | 3 档用尽 → reject → DLQ；Outbox 侧 `max-attempts=8` → FAILED |
| 进 DLQ 后保留完整诊断 | 通过 | 原始信封逐字节一致，`x-death` / 档位 / `x-last-error` 齐全 |
| 死信查询不改变队列 | 通过 | 查询前后深度一致，eventId 无重复 |
| 重放保留 eventId 并继续去重 | 通过 | 重放后 eventId 未变，Inbox 记录数不变 |
| 拒绝全量重放 | 通过 | 空 `eventIds` → 400 |
| Inbox 去重 | 通过 | 同一 eventId 投 3 次 → 1 条记录；不同 consumer 各自独立 |
| 重复投递无重复副作用 | 通过 | 重复命中 `DUPLICATE`，claim 幂等返回空 |
| 清理只删已发布且超期 | 通过 | PUBLISHED 超期删除；PENDING / FAILED / 保留期内全部保留 |
| 清理分批且幂等 | 通过 | 超过一个批量的行在分批循环内删完；重复执行结果不变 |
| RabbitMQ 不可用 → 恢复重连 | 通过 | 断连后消费者自动重连并继续消费（`test_recovery_rabbit.py`） |
| Worker 重启 | 通过 | 未 ACK 消息回队（深度 1 → 1） |
| 未完成任务恢复 | 通过 | 租约过期 → 回收 → 新 Attempt 领取（`MessagingRecoveryIT`） |
| 死信查询不再 500 | 通过 | 真实调用 `GET .../dead-letters` → **200**，无 properties 的裸消息也能摘要 |

## 6. CI 结果、Commit ID、本地与远端状态

| 项 | 状态 |
| --- | --- |
| 分支 | `develop` |
| 远端 | `origin https://github.com/goodddGrades/RepoPilot.git` |
| 远端基线 | `d0010c4` `docs(task-007): 补记 CI 重跑结果与基础设施抖动说明` |
| 本地提交（本任务） | **`a99b00c`** `feat(messaging): RabbitMQ 四层拓扑、真实消费接入、死信重放与保留策略（TASK-013）`（48 个文件，+11044 −81）<br>**`36efd03`** `docs(task-013): 交付报告补记提交 ID 与演示最终的保留期数字`<br>**`b3f9f2d`** `docs(task-013): 验收轮更正 readiness 跳过原因与长任务占用消费者的描述`<br>外加一个紧随其后的报告更新提交（记录验收轮数字与 Git 基线） |
| 待推送范围 | **`origin/develop..HEAD` 共 12 个提交**（`git rev-list --left-right --count origin/develop...HEAD` = `0 12`，远端无落后，可 fast-forward）：<br>`36efd03` `a99b00c` `8a3c8c0` `879348b` `6435553` `9b8cb7a` `02f0f1c` `7b3c7ac` `71428d6` `d57d1c3` + 验收轮两个文档提交<br>覆盖 TASK-008 ~ TASK-013，**不是只有本任务两个提交** |
| 推送 | **未 push**。`AGENTS.md` 规定"push 需逐次显式授权"，本任务未获得授权 |
| GitHub Actions | **未运行**（未 push，无法触发）；本地等价检查已全部执行 |

CI 工作流已更新（`.github/workflows/ci.yml`）：

* `server` Job 增加 `REPOPILOT_IT_RABBIT_*`，让 `RabbitTopologyIT` 在 CI 上真的跑而不是整体跳过；
* `worker` Job 增加 `rabbitmq:3.13.7-management` service 容器 + 建账号授权，
  让退避重试跑在真实 broker 上——**替身验证不了 AMQP 语义**，第一版 headers 方案就是在全绿的替身测试下漏掉的。

## 7. 尚未解决的限制与未完成的验收项

| 项 | 状态 | 说明 |
| --- | --- | --- |
| **CI 未运行** | ❌ 未验证 | 未 push，Actions 未触发。本地 `verify` / `pytest` / `build` 全绿不等于 CI 绿 |
| 告警接入 | ❌ 未实现 | 指标已注册，但**未接告警平台**；阈值是建议值 |
| 退避档位可配置 | ❌ 未实现 | 5/30/120s 编译在常量里。`x-message-ttl` 是声明期参数，改了会 `PRECONDITION_FAILED` 并关 channel，调整需重建队列（停机操作） |
| 清理任务的调度语义 | ⚠️ 单实例 | 依赖 Spring 定时器，多副本会重复执行（幂等所以不会出错，但没有 leader 选举） |
| DLQ 的独立保留策略 | ❌ 未实现 | DLQ 消息在 broker 里，不受 `RetentionService` 管辖；积压需人工或独立策略 |
| 消费延迟指标的口径 | ⚠️ 近似 | 用"发布确认 → Inbox 登记"的时间差推算，**不是**端到端真实处理耗时；且只统计最近 100 条已发布事件 |
| 消费统计的上报可靠性 | ⚠️ 下界 | Worker 主动上报、不保证送达；失败会在下一轮重报，但进程被杀时未上报的增量会丢失。后端计数是**下界**，精确对账看 `inbox_events` 行数 |
| **长任务占住消费者线程** | ⚠️ 延迟 | `on_wakeup` 是 `runtime.run_once()`，在 AMQP 回调线程里**同步**跑完 claim → 执行 → 回传 → ACK。任务执行期间不取新消息，**再发布队列一并暂停**，重试回流被推迟到任务结束。不丢消息、不重复执行，但严格意义上不满足"长任务不占用消费者"。改法是把执行移到独立线程、回调只做登记 + 置位唤醒 + ACK；验收轮未改动执行模型，已写入 `docs/modules/messaging-reliability.md` §11 |
| 迁移工具的真实积压演练 | ⚠️ 部分 | 10 个用例在真实 broker 上验证过（含"拒绝删除非空队列"）；**未在真实生产积压上执行** |
| 跨机房/多集群 | ❌ 未验证 | DLX 与业务交换机同 vhost；跨集群需 federation，语义要重新验证 |
| 前端 | ➖ 未改动 | 本任务不涉及 UI |

### 必须明确说"没做到"的三件事

1. **CI 没跑过。** 报告里所有数字都来自本机。把本机全绿写成"CI 通过"是伪造。
2. **重放接口在演示脚本里的验证取决于后端是否启动。** 脚本在后端未启动时会打印
   `【未验证】受控重放：后端未启动`，不伪装成通过；后端在线时的真实 HTTP 重放见 §3.3。
3. **生产级告警与容量数据没有。** 指标能采，但没有接告警、没有长跑压测，
   因此"阈值多少合适"没有实测依据。

## 8. 过程中修掉的真实缺陷（按发现顺序）

| # | 缺陷 | 性质 |
| --- | --- | --- |
| 1 | `x-match=all` 忽略 `x-` 前缀字段，三档退避退化为全按第一档回流 | 协议语义误用，监控完全不可见 |
| 2 | 带参数 `queue_declare` 作用在已存在的 quorum 队列上会**删除重建**，TTL/DLX 全丢 | 探测工具破坏生产队列 |
| 3 | `DeadLetterController` 无条件加载但依赖有条件注册的 bean，整个上下文起不来 | Spring 条件装配不一致 |
| 4 | `GetResponse.getProps()` 可能为 null → 死信查询真实调用 500，而单测全绿 | 测试构造的数据比真实数据"更完整" |
| 5 | `Map.of()` 不可变 → 重放时 `UnsupportedOperationException` | 集合不可变性 |
| 6 | `@Value("${k:" + CONSTANT + "}")` 把常量拼进默认值 → 队列名变成整串占位符 | Spring 占位符是字面量 |
| 7 | quorum 队列 `nack(requeue=true)` 是异步重投，查询后立刻看深度 = 0 | 把正常行为误判成丢数据 |
| 8 | `OutboxScheduler` 在集成测试里仍以 1 秒节拍抢发布其他用例的数据 | 测试环境里的后台线程改被测对象 |
| 9 | 用例假设"自己插入的行一定在全局批量的第一批里"，积压超过 `batch-limit` 后必红 | 测试对全局表状态隐式依赖 |
| 10 | `repopilot.consumer.failed` 恒为 0（Worker 从不上报 `failed`） | 指标存在但永远不增长 |

前 9 条的实测经过写在 `docs/reviews/task-013-amqp-facts.md`（28 条），第 10 条见 `ConsumerStatsController` 注释。

## 9. 最终交付验收（2026-10-20）

本节只记录**验收轮实际重跑**的结果，不重复前文的历史数字。

| 项 | 命令 / 条件 | 结果 |
| --- | --- | --- |
| 后端单元 + 集成 | `./mvnw -B -o verify`，真实 MySQL 8.0.22 @13306 + 真实 RabbitMQ 3.13.7 @5672，`REPOPILOT_IT_REQUIRE_ALL_UP=true` | **75 单测 + 258 集成，0 失败 0 错误 0 跳过，BUILD SUCCESS**（2:50） |
| Worker | 真实 broker，`WORKER_TEST_RABBIT_*` 指向本机 | **232 passed / 4 skipped**（4 条付费 CLI 冒烟**未执行**，不计入通过数；其余含真实 broker 用例全部执行） |
| 前端 | `npm run build` | **EXIT=0**（本任务未改前端；1.1 MB 单 chunk 警告是既有问题） |
| 设计校验 | `tools/verify_design.py` | **PASS**（1289 checks / 28 tables / 4 json_schemas） |
| 生成器一致性 | `gen_ddl_from_dict.py --check`、`gen_java_from_dict.py --check`、`--self-test` | 全部 OK（6 个迁移 / 76 个 Java 文件 / 自测 6 项） |
| 端到端演示 | `bash tools/demo-task-013.sh`（**后端未启动**） | **9/9 通过，EXIT=0**；脚本对需要后端的重放路径打印"【未验证】"并指向自动化测试，未伪装成已通过 |

### 9.1 跳过项的处置

唯一一条后端 skipped 是 `ApiHealthIT.readinessShouldBeUpWhenEveryDependencyIsAvailable`，
门控条件 `REPOPILOT_IT_REQUIRE_ALL_UP=true`。本机 MySQL 与 RabbitMQ 都在，
**属于"本应执行"**：补上环境变量后用例真实执行并通过（`ApiHealthIT` 4/4），全量 0 跳过。

### 9.2 验收轮发现的文档缺陷（已修）

`docs/modules/messaging-reliability.md` §1.1 与 `consumer.py` 头部原称"长任务不占用消费者，
执行由轮询线程异步推进"，与实现不符：`on_wakeup` 接的是 `runtime.run_once()`，
在 AMQP 回调线程里**同步**跑完 claim → 执行 → 回传 → ACK。已改为如实描述并列入已知限制。
功能层面的缺口（执行期间再发布队列一并暂停）未在验收轮改动，见 §7。

### 9.3 未验证项（明确不计入通过）

* 4 条付费真实 CLI 冒烟（`test_cli_runner_smoke.py`）—— 需真实费用，未执行。
* 后端在线时的受控重放 HTTP 路径 —— 本轮演示时后端未启动，证据来自
  `RabbitTopologyIT` 的重放用例与 `MessagingObservabilityIT` 的真实 HTTP 端点用例。
* GitHub Actions —— 未 push，未触发。
