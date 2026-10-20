# 消息可靠性运维手册（TASK-011 / TASK-013）

面向**跑这套系统的人**：发布链路卡住了怎么办、积压怎么看、能不能手动补发。
设计约束见 [`../architecture/06-reliability-design.md`](../architecture/06-reliability-design.md) 第 64-72 行。

## 0. 三句话版本

1. **消息丢了不会变成任务不做。** `outbox_events` 是唯一真相，RabbitMQ 只是加速器。
   broker 挂了业务照跑，只是慢一个轮询周期（默认 1s）。
2. **重复投递是正常的，不是故障。** confirm 丢失、消费者 ACK 前崩溃、发布器租约过期被重新领取，
   三个来源都会产生重复。去重在 `inbox_events`，重复**没有业务效果**。
3. **不要用"消息发成功了"判断 Agent 跑成功了。** 发布成功只表示"消费者被唤醒了"；
   Agent 是否成功由 `evaluation_runs` 的终态和 Attempt 回执决定。
4. **TASK-013 起，Worker 真的通过 RabbitMQ 消费了。** 消费失败按5s/30s/120s
   三档有上限退避，档位用尽进 DLQ（`repopilot.events.dlq`），不再无限重投。
   死信用 `GET/POST /internal/v1/messaging/dead-letters` 查询与受控重放。

## 1. 链路全貌

```
业务事务（实验提交 / Run 封口 / 评测 Job 创建）
  └─ 同事务写 outbox_events（PENDING）        ← 状态变了但通知写失败 = 不可能
        │
OutboxScheduler（每 1s）                      ← 数据库扫描是恢复通道
  └─ 短事务领取：FOR UPDATE SKIP LOCKED → 挂租约 + claimToken → 提交
        │                                    ← 事务在这里就结束了
        ├─ 网络 IO（不持锁）
        │    RabbitEventPublisher：PERSISTENT + mandatory + publisher confirm
        │    每条消息一个独立 channel（confirm 回调是 channel 级状态，共享会串台）
        │
        └─ 短事务回写（带 fencing）
             routed  → PUBLISHED（终态）
             失败    → retry_count+1，指数退避+抖动，仍 PENDING
             超上限  → FAILED，等人工

消费者（Python Worker）
  └─ POST /internal/v1/notifications/accept   ← 只做 inbox 登记，不跑任务
       └─ 200 → ACK → 唤醒本地领取循环 → 走 POST /internal/v1/work/claim
```

**领取与回写都是短事务，中间那次网络往返在事务外。** 这是 `06-reliability-design.md`
"不持锁跨网络"的直接落地：否则一条 5 秒的慢消息会把整行锁住 5 秒，期间所有发布器卡在这行上。

### 1.1 两条消费通道：消息与轮询（TASK-013-B）

Worker 有两条通道能触发执行，**两者并存是有意的**：

| 通道 | 作用 | 开关 | 默认 |
| --- | --- | --- | --- |
| RabbitMQ 消费 | 快速唤醒：消息到了就干活 | `WORKER_RABBITMQ_ENABLED` | **关** |
| HTTP 轮询 | 兜底：消息丢了、broker 挂了、消息误入死信，任务最终仍会执行 | `WORKER_AUTOSTART` | 开 |

**为什么消息通道默认关闭**：Worker 靠轮询就能完整工作，消息是**加速器**不是依赖。
默认打开会让"没配 RabbitMQ"这种完全合法的部署直接起不来。

**为什么不会重复执行**：两条通道最后都收敛到同一个 `run_once() → claim`。
`claim` 在数据库里用 `FOR UPDATE SKIP LOCKED` + 租约决定"谁执行"，
因此**同一任务只会被一个 Worker 领到一次**；另一条通道这一轮领到空，什么也不做。
换句话说，重复的是**唤醒**，不是**执行**。

**ACK 时机**：`claim` 成功返回**之后**才 ACK（见 `consumer.py::_handle`）。
顺序是"Inbox 登记 → claim → ACK"，不是"ACK → 再干活"：
先 ACK 会让"进程在干活前崩溃"变成消息永久丢失。

**长任务不占用消费者**：回调里只做三件短事（登记、claim、ACK），真正的 CLI 执行在
`claim` 之后由轮询线程异步推进。因此 `prefetch=1` 限制的是"同时在处理几条唤醒"，
不是"同时跑几个任务"——一个跑 10 分钟的任务不会把消费者堵死 10 分钟。

**重复投递**是正常边界：同一条消息投 N 次，Inbox 唯一键让第 2..N 次命中 `DUPLICATE`，
仍然触发一次唤醒（上一轮可能 ACK 前崩溃），claim 幂等返回空。

## 2. 三个不可违反的约束

| 约束 | 不这么做会怎样 |
| --- | --- |
| **确认回写必须 fencing**（`claim_token_hash` 匹配 **且** `lease_until > now`） | 租约过期的失效发布器回来写确认，会把新领取者正在处理的事件误标 PUBLISHED，该事件**永久不再重发** |
| **不确定 ≠ 失败**（confirm 超时按可重试算） | 把"可能已投递"当成"没投递"，事件被无限重投；反之当成成功则事件静默丢失。可重试的代价是重复，inbox 兜住 |
| **Outbox 写入必须 `MANDATORY` 传播** | 用 `REQUIRED` 时"忘记加事务"会静默成功：Outbox 单独提交、业务回滚，产生指向不存在 Run 的通知 |

## 3. 指标

### 3.1 发布侧（Outbox）

| 指标 | 含义 | 告警建议 |
| --- | --- | --- |
| `repopilot.outbox.published` | 确认成功并标 PUBLISHED 的事件数（Counter） | 突然归零而 `pending` 上升 → 发布链路卡住 |
| `repopilot.outbox.scan.errors` | 发布轮次抛异常的次数（Counter） | 持续增长 → 数据库或配置有问题 |
| `repopilot.outbox.publish.failed` | 单条事件发布未成功的次数（Counter） | 持续增长 → 不可路由 / 连接故障 / confirm 超时 |
| `repopilot.outbox.exhausted` | 重试达到上限被置 FAILED 的事件数（Counter） | 增长 → 有人需要重新投递 |
| `repopilot.outbox.pending` | 仍待发布的事件数 | 持续增长 → MQ 不可用或发布器停摆 |
| `repopilot.outbox.oldest.pending.age.seconds` | 最老的未发布事件已积压的秒数 | **最有效的单一信号**：>60s 说明链路有问题 |
| `repopilot.outbox.failed` | FAILED 事件存量（永不自动清理） | >0 → 需人工介入 |

`oldest.pending.age` 比 `pending` 数量更有用：积压 3 条可能是正常突发，
积压 3 条且最老的等了 10 分钟一定是故障。

### 3.2 消费侧（TASK-013-E）

| 指标 | 含义 | 告警建议 |
| --- | --- | --- |
| `repopilot.consumer.consumed` | 消费成功并 ACK 的消息数（Counter） | — |
| `repopilot.consumer.failed` | 消费失败的消息数（Counter） | 增长 → 消费者侧有系统性问题 |
| `repopilot.consumer.retried` | 进入退避重试的消息数（Counter） | 占比升高 → 下游依赖抖动 |
| `repopilot.consumer.dead_lettered` | 进入死信队列的消息数（Counter） | **增长即需人工介入** |
| `repopilot.consumer.duplicate` | 命中 Inbox 去重被跳过副作用的消息数（Counter） | 突增 → 有消费者在 ACK 前崩溃 |
| `repopilot.consumer.republished` | 退避到期后重新投递回业务交换机的消息数（Counter） | 与 `retried` 一起看，确认退避链路通畅 |
| `repopilot.consumer.rejected` | 拒绝动作本身失败的消息数（Counter） | >0 → 连接不稳定，消息由 broker 重投 |
| `repopilot.consumer.dlq.depth` | 死信队列**当前存量** | >0 → 有死信待排查 |
| `repopilot.consumer.backlog.runs` | 待 Worker 领取的 Run 数（QUEUED） | 持续增长 → Worker 不足或消费者掉线 |
| `repopilot.consumer.backlog.oldest.age.seconds` | 最老的待领取 Run 已等待的秒数 | >300s → 任务在排队等 Worker |
| `repopilot.consumer.lease.expired` | 租约已过期但仍是 RUNNING 的 Attempt 数 | **持续 >0 → Worker 失联** |
| `repopilot.consumer.delivery.latency.avg.seconds` | 消息端到端平均延迟（发布确认→Inbox 登记） | 升高 → 链路排队 |
| `repopilot.consumer.delivery.latency.max.seconds` | 消息端到端**最坏**延迟 | 与均值并存：均值会掩盖卡住的那一条 |

**为什么消费计数由消费者上报**：一条被 reject 进死信的消息，
在 `outbox_events` 里仍然是 `PUBLISHED`（发布确实成功了），
在 `inbox_events` 里也可能没有记录（它压根没走到登记那一步）。
**只有 broker 和消费者知道它的真实结局**。后端能观测到的
"外部信号"是队列深度与 DLQ 深度。

**上报的是增量不是累计值**：累计值在消费者进程重启后会归零，
后端累加就会让计数**倒退**。一个会倒退的计数器比没有计数器更危险，
因为它看起来是对的。

### 3.3 去重与清理

| 指标 | 含义 | 告警建议 |
| --- | --- | --- |
| `repopilot.inbox.duplicate` | 命中 Inbox 去重的次数（Counter） | 突增 → 重复投递变多 |
| `repopilot.inbox.payload.conflict` | 同 eventId 出现不同内容（Counter） | **出现即数据损坏，必须立刻查** |
| `repopilot.retention.outbox.deleted` | 清理任务删除的 Outbox 行数（Counter） | 长期为 0 → 清理任务可能没跑 |
| `repopilot.retention.inbox.deleted` | 清理任务删除的 Inbox 行数（Counter） | 同上 |
| `repopilot.retention.errors` | 清理任务出现异常的轮次（Counter） | 增长 → 清理在失败 |
| `repopilot.retention.last.run.epoch.seconds` | 最后一次执行清理的时间 | 距今过大 → 清理任务没被调度 |
| `repopilot.retention.last.success.epoch.seconds` | 最后一次**成功**清理的时间 | 0 → 从未成功过 |
| `repopilot.retention.last.failure.epoch.seconds` | 最后一次**失败**清理的时间 | 0 → 从未失败过 |

**为什么"成功"与"失败"分开记**：合成一个布尔会丢掉排查方向。
"清理任务从 3 天前就一直在失败"要查为什么失败；
"3 天前成功过、之后没再跑"要查为什么没被调度——两者都显示"不健康"，
但处置完全不同。

### 3.4 指标暴露方式

后端用 Micrometer 注册。当前 `application.yml` 只暴露 `health,info`，
要抓指标需在部署配置里加：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

或者引入 `micrometer-registry-prometheus` 后暴露 `prometheus` 端点。

**Worker 侧的累计消费统计**在 `GET /worker/status` 的 `consumer.totals` 字段里，
由 Worker 主动上报给后端（`POST /internal/v1/messaging/consumer-stats`）。

## 4. 排查手册

### 4.1 先看这三个查询

```sql
-- 各状态计数
SELECT status, COUNT(*) FROM outbox_events GROUP BY status;

-- 最老的未发布事件（判断卡了多久）
SELECT id, event_type, aggregate_id, retry_count,
       TIMESTAMPDIFF(SECOND, created_at, NOW()) AS age_seconds
  FROM outbox_events WHERE status = 'PENDING'
 ORDER BY created_at LIMIT 20;

-- FAILED（需要人工处理）
SELECT id, event_type, aggregate_id, retry_count, created_at
  FROM outbox_events WHERE status = 'FAILED' ORDER BY created_at;
```

### 4.2 按现象定位

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| `pending` 持续增长、`oldest age` 持续变大 | broker 不可用 | 查 `/api/health` 的 rabbitmq 组件；恢复后会自动补发，**不要手工改状态** |
| `pending` 不增长但 Run 一直 `QUEUED` | Worker 没在领取 | 与消息无关：查 Worker 是否在跑、`/internal/v1/work/claim` 是否 401 |
| `retry_count` 接近 `max-attempts`（默认 8） | 路由问题 | 多半是 **exchange 没绑 queue**：mandatory 退回会被当成失败。确认队列绑定后再重发 |
| 日志刷"发布确认被 fencing 拒绝" | 租约过短或发布太慢 | 调大 `app.repopilot.outbox.lease-seconds`（默认 30）。这是**告警不是错误**：说明 fencing 正在正确工作 |
| 日志刷"Inbox 内容冲突" | 上游发了同 ID 异内容 | **数据损坏，必须查**。见 §5 |

### 4.3 队列绑定

路由键就是 `eventType`（`run.ready` / `evaluation.ready` / `run.finished` / `experiment.finished`）。
交换机需要为每种类型绑至少一个队列，否则 mandatory 退回、事件永远发不出去：

```bash
# 列出当前绑定
rabbitmqctl list_bindings source_name destination_name routing_key

# 示例：绑定 run.ready
rabbitmqctl bind_queue repopilot.run source_name=repopilot.events routing_key=run.ready
```

## 5. 隔离与人工介入

### 5.1 什么情况下消息会被隔离

**先分清两件不同的事**：

| 类别 | 例子 | 去向 | 需要人管吗 |
| --- | --- | --- | --- |
| **业务 FAIL** | Agent 跑失败了、评测不通过 | **业务状态**（`evaluation_runs.status=FAILED`），消息本身处理成功 | 否，看业务报表 |
| **消息基础设施失败** | 契约错误、schema 不支持、payload 冲突、重试耗尽 | **DLQ**（`repopilot.events.dlq`） | **是**，要人工判断 |

把这两者混为一谈是排查困难的主要来源：业务失败会让消息"成功"消费掉，
若把它也丢进 DLQ，DLQ 里就会塞满根本不需要重放的正常业务失败。

**消息侧进入 DLQ 的触发条件**：

| 触发 | 机制 | 该做什么 |
| --- | --- | --- |
| 信封 JSON 非法 | 消费者 `basic_reject(requeue=false)` | 查发布侧，通常是代码 bug |
| `schemaVersion` 不受支持 | 同上 | 升级消费者或回退消息版本 |
| 缺必填字段 / `payload_hash` 冲突 | 同上 | **停止重试**，确认哪条消息是对的 |
| 退避三档用尽 | 同上（`x-retry-tier=3` 仍失败） | 先查依赖是否恢复，再受控重放 |
| 再发布时缺 `x-original-routing-key` | 同上 | **不猜**：进 DLQ 等人确认 routing key |

**这三类都不允许 requeue 重试。** 无限重试会把一条坏消息变成队列永久阻塞——
这是 TASK-013 修掉的核心缺陷之一（此前用 `basic.nack(requeue=true)` 会把消息
放回队尾，"取一条→放回→再取一条"会绕回队首重复读到同一条）。

**DLQ 里能看到什么**：`x-death`（broker 记录的死信原因：哪个队列、因为什么、
几次）、原始信封（逐字节保留）、`x-retry-tier`、`x-last-error`、`x-first-failed-at`。
`x-death` 存在是"可追溯"的前提——没有它就无法知道一条死信是怎么死的。

### 5.2 人工重新投递

`FAILED → PENDING`，**保留同一个 `event_id`**，并把 `retry_count` 归零。

```sql
-- 先确认这条事件确实还值得发（Run/Job 是否已到终态）
SELECT status FROM evaluation_runs WHERE id = (
  SELECT aggregate_id FROM outbox_events WHERE id = '<event_id>');

-- 确认无误后重新投递（保留 event_id，消费者侧仍会去重）
UPDATE outbox_events
   SET status = 'PENDING', retry_count = 0, next_retry_at = NULL,
       lease_until = NULL, publisher_id = NULL, claim_token_hash = NULL,
       published_at = NULL, updated_at = NOW(3)
 WHERE id = '<event_id>' AND status = 'FAILED';
```

服务内等价入口：`OutboxPublisherService.requeue(eventId)`。

**为什么必须保留 `event_id`**：换 ID 会让同一条业务事实被当成两次通知，触发两次副作用。
`retry_count` 归零是因为它统计的是"上一轮投递尝试了几次"，人工介入意味着新一轮从零开始。

**重新投递前必须先看聚合状态。** 如果 Run 已经 COMPLETED，重发只是让消费者收到一条
"处理一个已经处理完的东西"的唤醒——inbox 会去重掉，不会真的重跑，但日志会让人误判。

## 6. 保留策略

| 表 | 保留 | 理由 |
| --- | --- | --- |
| `outbox_events`（PUBLISHED） | 30 天 | 只为排查"某条消息什么时候发的"服务；业务真相在 Run/Job 行上 |
| `outbox_events`（PENDING / FAILED） | **永不自动删除** | PENDING 可能在重试窗口内；FAILED 是人工处理的唯一线索 |
| `inbox_events` | 90 天（可配） | **不得短于 outbox 的最大重试窗口 + 消息最大重投窗口** |

**TASK-013 起已实现自动清理**（`RetentionService`，定时 + 分批），
不再是TASK-011 文档里说的"手工执行"。三条铁律：

1. **只删已完成且过期的。** Outbox 的判据是 `status='PUBLISHED'` **且 `published_at` 超保留期**，
   不是 `created_at`——3 天前创建、今天才发布的事件刚发布，可能还有消费者没处理完。
2. **Inbox 保留期必须显著长于消息最大重投窗口。**
   退避最坏情况是 5+30+120 秒 ≈ 155 秒，Inbox 保留 90 天是它的**上万倍**。
   若Inbox 先被清理，同一条消息重投时会被当成新消息，**已完成的业务会被重复执行**。
3. **分批删除。** 每条 `DELETE` 带 `LIMIT`，每批独立短事务，
   避免长事务锁住表、阻塞业务写入。批次大小可配（默认 500）。

### 6.1 排查看被清理了什么

```sql
-- 待发布积压与最老事件年龄（Outbox 未清理前一直可查）
SELECT COUNT(*) AS pending, MIN(created_at) AS oldest
  FROM outbox_events WHERE status = 'PENDING';

-- DLQ 深度（消息还在 broker 里，未被清理）
docker exec repopilot-rabbitmq rabbitmqctl list_queues name messages | grep dlq
```

## 7. 配置项

| 配置键 | 默认 | 调整它的时机 |
| --- | --- | --- |
| `app.repopilot.outbox.poll-interval-ms` | 1000 | 积压严重时可调小（会增加数据库轮询压力） |
| `app.repopilot.outbox.lease-seconds` | 30 | 日志频繁出现 fencing 拒绝时调大 |
| `app.repopilot.outbox.max-attempts` | 8 | 队列绑定长期修不好时可调大（但每次失败都会延后到 300s 上限） |
| `app.repopilot.outbox.batch-limit` | 50 | 单轮发布量大时调大；**注意这是每条一个短事务**，不是批量事务 |
| `repopilot.outbox.publisher` | `rabbit` | 测试用 `fault-injecting` 注入故障 |

## 8. 故障演练

`MessagingReliabilityIT` 用可编程替身制造真实故障，不真的把 RabbitMQ 弄挂
（那既不可重复也会让 CI 不稳定）。13 个用例覆盖：

| 用例 | 验证的不变式 |
| --- | --- |
| 业务状态与 Outbox 同事务 | 不会出现"Run 回滚了但通知发出去了" |
| 信封只带 ID | 消息里没有待评测代码的输出、模型返回或密钥 |
| confirm 丢失 → 重发 | 消费者无重复业务效果 |
| 消息被退回 | 不算发布成功（无队列绑定时必须重发） |
| 失效发布器 fencing | 拿不到 0 行以外的结果，不会误标已发布 |
| 租约过期被接管 | 发布器崩溃后事件最终能发出 |
| 毒消息有限重试 | 8 次后置 FAILED，不无限重投 |
| 人工重投递保留 event_id | 消费者侧仍能去重 |
| MQ 不可用时 DB 扫描继续 | 恢复后不重跑已完成 Run |
| 全量重投 | 已完成 Run 不被拉回执行态 |
| 异内容隔离 | 不当正常重复静默 ACK |
| 通知接口状态码语义 | 重复 200 / 冲突 409 / 未知版本 400 |
| 未鉴权 401 | 不留任何痕迹 |

```bash
cd repopilot-server
export JAVA_HOME=/path/to/jdk21
eval "$(../tools/it-mysql.sh env)"
./mvnw -o test-compile
./mvnw -o failsafe:integration-test -Dit.test=MessagingReliabilityIT
```

## 9. 死信查询与受控重放（TASK-013）

```bash
BASE=http://127.0.0.1:18080
TOKEN=local-demo-token

# 查询（**不消费、不改变队列深度**）
curl --noproxy '*' -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/internal/v1/messaging/dead-letters?limit=20"

# 受控重放：必须显式列出 eventId
curl --noproxy '*' -s -X POST -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"eventIds":["<eventId>"],"limit":10}' \
  "$BASE/internal/v1/messaging/dead-letters/replay"
```

**刻意不提供全量重放**：`eventIds` 为空返回 400，而不是"那就把整个 DLQ 都重放一遍"。
原因很直接——DLQ 里混着"数据损坏"和"依赖暂时不可用"两类消息，
批量重放会把前者也放回业务链路。

**重放保留原eventId**，因此会继续走 Inbox 去重：若这条事件此前已成功处理过，
重放**不会造成重复业务副作用**（实测：重放后 Inbox 记录数不变）。

**查询必须不改变队列**。实现上用 "hold 住全部 → 读完 → 统一放回"，
而不是"取一条→nack 放回→再取一条"——后者因为 nack 把消息放回**队尾**，
扫描会绕回队首重复命中同一条（实测：5 条消息 limit=20 得到 15 条，其中 10 条重复）。

### 9.1 查询后**立刻**查深度会看到 0 —— 这不是消息丢了

quorum 队列的 `basic.nack(requeue=true)` 是**异步重投**的：

```
basic_get(auto_ack=False) -> hold 住
basic_nack(requeue=true)  -> 此刻查深度 = 0
连接关闭                 -> 深度恢复为 1
```

所以在 `query()` 返回后的**几百毫秒内**用
`rabbitmqctl list_queues` 查 DLQ，会看到深度 0，
很容易误判成"死信被清空了"，甚至触发不必要的紧急处置。

**处置顺序**：

1. 先确认**最近是否有人调用过查询接口**（或跑过演示脚本 / 迁移工具）；
2. 若有，等 1 秒左右再看；
3. 仍为 0 才考虑"消息真的没了"。

**绝对不要**在查询后立刻执行 `queue_purge` 或 `queue_delete`——
此时消息正在重投途中，purge 会**真的把它丢掉**。

### 9.2 保留期清理的运维入口

清理是后台定时器，**成功时不打日志**（否则每小时一行噪声）。
于是"一直在跑"与"早就停了"在日志里完全同形——都是空的。
所以补了一个查询入口：

```bash
# 看状态：上次什么时候跑、删了多少、成功还是失败
curl --noproxy '*' -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/internal/v1/messaging/retention"

# 立即执行一轮（规则与定时器完全相同，不接受任何过滤条件）
curl --noproxy '*' -s -X POST -H "Authorization: Bearer $TOKEN" \
  "$BASE/internal/v1/messaging/retention/purge"
```

响应里三个时间戳**分开**：`lastRunEpochSecond`（跑过没有）、
`lastSuccessEpochSecond`（成功过没有）、`lastFailureEpochSecond`（失败过没有）。
它们回答的是三个不同的问题：

| 现象 | 含义 | 先查什么 |
| --- | --- | --- |
| `lastRun=0` | 从未执行过 | 调度器是否启用、`app.repopilot.retention.*` 是否生效 |
| `lastRun` 很旧、`lastSuccess` 也旧 | 停了 | 应用是否还活着、定时线程池是否被占满 |
| `lastFailure` 比 `lastSuccess` 新 | 一直在失败 | 数据库锁、慢查询、删除条件 |

**手动 purge 不能成为绕过规则的入口**：接口不接受任何条件参数，
删什么完全由 `RetentionService` 决定——只删已发布且超期的 Outbox 行，
PENDING / FAILED 永不自动删除。FAILED 的人工清理只在代码里
（`purgeFailedOutbox`，强制要求显式时间下界），不暴露为 HTTP。

### 9.3 消费计数由消费者上报

`POST /internal/v1/messaging/consumer-stats`，**上报增量**。
后端的 `outbox_events` / `inbox_events` 无法回答"这一小时消费了多少条"：

| 消息结局 | `outbox_events` | `inbox_events` | 数据库能分辨吗 |
| --- | --- | --- | --- |
| 消费成功 | PUBLISHED | 有记录 | 能 |
| 投递了但没人消费 | PUBLISHED | 无记录 | **不能**（与进 DLQ 同形） |
| 消费失败进 DLQ | PUBLISHED | 通常无记录 | **不能**（与没人消费同形） |

"进 DLQ"（要重放）与"没人消费"（要查消费者为什么没启动）在数据库里
完全一样，所以这个计数只能由消费者自己上报。

## 10. 重试拓扑迁移（headers v1 → topic v2）

TASK-013 初版用 headers + `x-match=all` + `x-retry-tier` 分档，
在真实 broker 上**分档完全失效**（`x-` 前缀字段不参与匹配，退化成全部按第一档回流）。
现网若已部署该版本，用下面的工具受控迁移：

```bash
# 1. 只看现状（只读，不改任何实体）
python tools/migrate_retry_topology.py --verify-only

# 2. 排空旧队列并按档位重新投递到新拓扑
python tools/migrate_retry_topology.py --phase drain

# 3. 校验旧队列已排空、新拓扑已收到
python tools/migrate_retry_topology.py --phase verify

# 4. 确认无误后删除旧实体（**对仍有消息的队列会拒绝删除**）
python tools/migrate_retry_topology.py --phase purge
```

**顺序不能换**：先删后迁 = 丢消息。工具会强制这个顺序，
且 `verify` 在有积压时返回非 0 并阻止 `purge`。

迁移保留 eventId 与全部诊断头，并打上 `x-migrated-from` 以便追溯。
旧拓扑下消息被广播到全部 3 个队列（一变三），工具会**忠实按头里的档位
把三份副本各自归位**——三份的 eventId 相同，去重后等价于一条。

## 11. 尚未实现 / 已知限制

| 项 | 说明 |
| --- | --- |
| **告警接入** | Micrometer 指标已注册（含 TASK-013-E 补齐的消费侧、延迟、积压、租约超时、清理结果），但**未接告警平台**；§3 的阈值是建议值 |
| **DLQ 的独立保留策略** | DLQ 消息在 broker 里，**不受 `RetentionService` 管辖**（它只管数据库表）。DLQ 积压需要人工或独立策略处理。当前只提供 `repopilot.consumer.dlq.depth` 指标供告警，不自动清理 |
| **退避档位不可配** | 5s/30s/120s 编译在 `RabbitTopology.RETRY_BACKOFF_MILLIS` 里。**改了会破坏已有队列**（`x-message-ttl` 是声明期参数，不一致会 `PRECONDITION_FAILED` 并关掉 channel），因此调整需要重建队列，属停机操作 |
| **清理任务的调度器** | `RetentionService` 依赖 Spring 定时器，**单实例语义**。多副本部署会重复执行（幂等所以不会出错，但没有 leader 选举） |
| **跨机房/多集群** | 未验证。DLX 与业务交换机在同一 vhost，跨集群需要 federation 或镜像，语义要重新验证 |
| **消费统计的上报可靠性** | Worker 主动上报，**不保证送达**。上报失败的增量会在下一轮重报（宁可后端多计一次，也不让"处理了多少"对不上账），但 Worker 进程被杀时未上报的增量会丢失——所以后端计数是**下界**，不是精确值。精确对账看 `inbox_events` 行数 |
| **端到端延迟的样本窗口** | 延迟指标只统计**最近 100 条已发布事件**。窗口之外的慢消息不计入。这是为了避免全表 JOIN 在大库上变贵，代价是"很久以前卡住的那条"不会出现在延迟指标里 |

## 12. 本地验证怎么跑

```bash
# 真实 RabbitMQ + 真实 MySQL 的 9 场景端到端演示
PY=/path/to/python RABBIT_PASSWORD=repopilot_dev_pwd bash tools/demo-task-013.sh

# 重试拓扑迁移工具的自动化测试（真实 broker，含"拒绝删除非空队列"等安全断言）
cd repopilot-worker
WORKER_TEST_RABBIT_HOST=127.0.0.1 WORKER_TEST_RABBIT_USER=repopilot \
WORKER_TEST_RABBIT_PASSWORD=repopilot_dev_pwd pytest tests/test_migrate_retry_topology.py -v

# 后端全量（含真实 broker 的 RabbitTopologyIT）
cd ../repopilot-server
export JAVA_HOME=/c/Users/15334/.jdks/ms-21.0.10
REPOPILOT_IT_DB_HOST=127.0.0.1 REPOPILOT_IT_DB_PORT=13306 \
REPOPILOT_IT_DB_NAME=repopilot_it REPOPILOT_IT_DB_USER=repopilot \
REPOPILOT_IT_DB_PASSWORD=repopilot_it_only \
REPOPILOT_IT_RABBIT_HOST=127.0.0.1 REPOPILOT_IT_RABBIT_USER=repopilot \
REPOPILOT_IT_RABBIT_PASSWORD=repopilot_dev_pwd ./mvnw -o verify
```

**注意环境变量前缀不同**：应用读 `MYSQL_*`，集成测试读 `REPOPILOT_IT_DB_*`。
用错前缀会得到 `Communications link failure`——看起来像连不上库，
实际是变量没被读取、退回了默认值 `root@localhost:3306`。
