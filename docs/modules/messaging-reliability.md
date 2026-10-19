# 消息可靠性运维手册（TASK-011）

面向**跑这套系统的人**：发布链路卡住了怎么办、积压怎么看、能不能手动补发。
设计约束见 [`../architecture/06-reliability-design.md`](../architecture/06-reliability-design.md) 第 64-72 行。

## 0. 三句话版本

1. **消息丢了不会变成任务不做。** `outbox_events` 是唯一真相，RabbitMQ 只是加速器。
   broker 挂了业务照跑，只是慢一个轮询周期（默认 1s）。
2. **重复投递是正常的，不是故障。** confirm 丢失、消费者 ACK 前崩溃、发布器租约过期被重新领取，
   三个来源都会产生重复。去重在 `inbox_events`，重复**没有业务效果**。
3. **不要用"消息发成功了"判断 Agent 跑成功了。** 发布成功只表示"消费者被唤醒了"；
   Agent 是否成功由 `evaluation_runs` 的终态和 Attempt 回执决定。

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

## 2. 三个不可违反的约束

| 约束 | 不这么做会怎样 |
| --- | --- |
| **确认回写必须 fencing**（`claim_token_hash` 匹配 **且** `lease_until > now`） | 租约过期的失效发布器回来写确认，会把新领取者正在处理的事件误标 PUBLISHED，该事件**永久不再重发** |
| **不确定 ≠ 失败**（confirm 超时按可重试算） | 把"可能已投递"当成"没投递"，事件被无限重投；反之当成成功则事件静默丢失。可重试的代价是重复，inbox 兜住 |
| **Outbox 写入必须 `MANDATORY` 传播** | 用 `REQUIRED` 时"忘记加事务"会静默成功：Outbox 单独提交、业务回滚，产生指向不存在 Run 的通知 |

## 3. 指标

| 指标 | 含义 | 告警建议 |
| --- | --- | --- |
| `repopilot.outbox.published` | 确认成功并标 PUBLISHED 的事件数（Counter） | 突然归零而 `pending` 上升 → 发布链路卡住 |
| `repopilot.outbox.scan.errors` | 发布轮次抛异常的次数（Counter） | 持续增长 → 数据库或配置有问题 |
| `repopilot.outbox.pending` | 仍待发布的事件数 | 持续增长 → MQ 不可用或发布器停摆 |
| `repopilot.outbox.oldest.pending.age.seconds` | 最老的未发布事件已积压的秒数 | **最有效的单一信号**：>60s 说明链路有问题 |

`oldest.pending.age` 比 `pending` 数量更有用：积压 3 条可能是正常突发，
积压 3 条且最老的等了 10 分钟一定是故障。

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

| 触发 | HTTP | 含义 | 该做什么 |
| --- | --- | --- | --- |
| 同 `(consumerName, eventId)` 已有记录但 `payload_hash` 不同 | 409 | **数据损坏或上游 bug** | 停止重试。查两条消息的实际内容；确认哪条是对的再处理 |
| 信封版本不受支持 | 400 `UNSUPPORTED_EVENT_VERSION` | 消费者比消息旧 | 升级消费者；不要 requeue |
| 信封缺必填字段 | 400 `VALIDATION_ERROR` | 消息损坏 | 检查发布侧 |

**这三类都不允许 requeue 重试。** 无限重试会把一条坏消息变成队列永久阻塞。

**冲突为什么必须返回 409 而不是 200**：静默 ACK 会让数据损坏永远查不出来——
生产者以为发出去了，消费者以为处理完了，没人知道两条消息不一样。

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
| `outbox_events`（FAILED） | **不删** | 需要人工处理；删掉就等于把问题变成静默丢失 |
| `inbox_events` | 90 天 | **不得短于 outbox 的最大重试窗口** |

**inbox 的保留期必须覆盖 outbox 的最大重投窗口**，否则会出现这种事故：
第 80 天清理了 inbox，第 85 天某条 FAILED 事件被人工重投，inbox 里已经没有记录，
消费者认为这是新消息，重新执行了一遍已经完成过的业务。

V1 未实现自动清理（避免在还没有生产数据量级概念时引入定时删除任务）。
需要清理时按上面的期限**手工分批执行**，每次不超过 10000 行：

```sql
DELETE FROM inbox_events WHERE processed_at < NOW() - INTERVAL 90 DAY LIMIT 10000;
DELETE FROM outbox_events
 WHERE status = 'PUBLISHED' AND published_at < NOW() - INTERVAL 30 DAY LIMIT 10000;
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

## 9. 尚未实现 / 已知限制

| 项 | 说明 |
| --- | --- |
| **RabbitMQ 拓扑声明** | 代码只负责发布（routingKey = eventType）；exchange/queue/binding 的声明留给部署侧。本仓库 compose 里的 RabbitMQ 只配了健康探针，**没有预建绑定** |
| **真实 RabbitMQ 集成验证** | 本地有 RabbitMQ 3.13.7 实跑核查记录（`reviews/rabbitmq-local-verification.md`），但 TASK-011 的自动化测试用的是故障注入替身，**没有对真实 broker 跑过端到端发布-确认** |
| **Python Worker 消费端** | `POST /internal/v1/notifications/accept` 已实现并有鉴权，**但 Worker 进程尚未接入 RabbitMQ 订阅**——目前仍靠 `poller.py` 的周期领取。通知接口是"可被唤醒"的通道，不是"已经在用"的通道 |
| **自动清理** | 见 §6，V1 手工执行 |
| **DLQ / 隔离队列** | 409/400 的隔离目前只体现在 HTTP 状态码与日志，没有自动投递到独立 DLQ；运维靠日志与 §5.1 的查询发现 |
| **告警接入** | Micrometer 指标已注册，但**未接告警平台**；§3 的阈值是建议值，需要接入时自行配置 |
| **Outbox 无对外 API** | 有意为之：人工重投递走运维 SQL / 服务方法，避免用户绕过业务状态机直接改消息状态 |