# 事件与消息契约

## 两类eventId

TraceEvent描述Agent执行内部动作；Outbox消息描述业务资源可领取或已完成。二者分别生成ULID，不能把同一个运行中所有动作都复用Run ID作为eventId。

`trace-event.schema.json`定义规范化轨迹；`event-envelope.schema.json`定义MQ信封。所有生产者遵守schemaVersion=1。未知版本返回400/隔离，不尝试猜测字段。新增可选字段不改变旧语义，破坏性变更升级版本并同时提供迁移策略。

## 轨迹事件

| eventType | payload最低字段 | 用途 |
| --- | --- | --- |
| RUN_STARTED / RUN_COMPLETED | runnerType / taskOutcome | 执行边界，最终状态仍以Run为准 |
| MODEL_REQUEST | model,requestSummary | 记录脱敏模型请求摘要 |
| MODEL_RESPONSE | model,outputSummary,usage? | 响应与用量，可引用Artifact |
| TOOL_STARTED | toolCallId,toolName,argumentsSummary | 工具调用开始 |
| TOOL_COMPLETED | toolCallId,toolName,outcome,durationMs | 工具结束，outcome为SUCCESS/FAILURE |
| OUTPUT_PRODUCED | outputSummary | 最终输出或附件引用 |
| ERROR | errorCode,message | 脱敏异常 |

Schema约束信封，payload按本注册表在服务层二次校验。规范字段完整不代表来源观测完整。sourceEventId为上游标识；eventId是归一化标识，重放稳定。在线Worker在首次生成事件时确定ID，网络重发保持ID和正文；离线先检查来源记录幂等，再生成事件ID，并在同一行事务提交。

traceId为32位小写十六进制非零，spanId/parentSpanId为16位非零，可缺失；来源不符合OTel格式时保存externalTraceRef，规范traceId为null。不要生成伪造OTel Trace ID填补未知上游ID。平台自身HTTP观测Trace与被评测Agent Trace分开关联。

拓扑优先parentSpanId与toolCallId；顺序使用sequenceNo和occurredAt辅助；数据库id只作稳定平局排序。parent不存在显示缺失父节点；重复sequenceNo允许；乱序抵达不能导致状态错误。对于两个都缺失顺序/时间分辨率不足的事件，显示“顺序不确定”。

## 消息信封

字段：eventId,eventType,schemaVersion,occurredAt,aggregateType,aggregateId,correlationId,payload。correlationId使用Experiment ID或Run ID，不能假设它是OTel Trace ID；需要链路传播另用traceparent头。

V1通知注册：run.ready（payload.runId）、evaluation.ready（payload.jobId）、run.finished（payload.runId）、experiment.finished（payload.experimentId）。只携带身份，不在MQ发送prompt、密钥、完整Trace或最终大输出。

交换机 repopilot.events，topic 类型；队列 repopilot.run.ready、repopilot.evaluation.ready、repopilot.experiment.finished，均为 quorum 队列。消息持久化、队列 durable，manual ACK；prefetch 默认与本地并发一致。

**消费与执行分离**：消费回调只做三件短事——Inbox 登记、唤醒计数+置位、ACK；
Agent Run 在 Worker 的独立执行线程里串行跑（`worker-exec`，只有一个）。
ACK 表达"唤醒已被接住"，不表达"任务已执行"；任务由数据库 + claim 租约保证。
因此长任务不会阻塞 AMQP 连接，后续消息与再发布队列照常消费。

### TASK-013 实际拓扑（四层）

TASK-013 把重试从"业务队列 → DLQ"两节点扩展为四层。每一层解决一个具体问题，缺一层就会丢消息或无限重投。

```
发布者 ──► repopilot.events（业务交换机，topic）
              │  binding: run.ready / run.finished / evaluation.ready / experiment.finished
              ▼
          业务队列（quorum, durable, DLX→repopilot.events.dlx）
              │  消费失败且可重试 → 消费者主动 publish 到 repopilot.events.retry.v2
              │  routing key = retry.tN
              ▼
消费者 ──► repopilot.events.retry.v2（重试交换机，topic）
              │  binding: retry.t1 / retry.t2 / retry.t3（精确键，无通配符）
              ▼
          退避等待队列 repopilot.retry.wait.tN
          （quorum, per-queue TTL 5s/30s/120s, DLX→repopilot.events.republish）
              │  TTL 到期死信，routing key 固定为 retry.republish
              ▼
          再发布队列 repopilot.retry.republish（无 TTL，DLX→业务 DLX）
              │  Worker 读 x-original-routing-key 复原后重新投递
              ▼
          回到业务队列（routing key 复原为原值）

消费失败且重试耗尽 → basic_reject(requeue=false)
              ▼
          repopilot.events.dlx ──► repopilot.events.dlq（quorum, durable）
```

**退避档位**：5 秒 / 30 秒 / 120 秒（`RabbitTopology.RETRY_BACKOFF_MILLIS`）。三档用尽后进 DLQ，不得无限重投。

**为什么用 topic 而不是 headers 交换机**：初版用 headers + `x-match=all` + `x-retry-tier`分档，在真实 broker 上**分档完全失效**——`x-match=all` 的语义是"binding 中所有**非 `x-` 前缀**的字段都必须匹配"，而 `x-retry-tier` 带 `x-` 前缀，从未参与匹配，交换机表现得像 fanout。结果是"三级指数退避"退化为"全部消息按第一档 5 秒并发回流"，而 `retry.tier` 计数、指标与日志全都正常，监控完全看不见。完整排查见 `docs/reviews/task-013-amqp-facts.md` 第 8 条。

**为什么多一层再发布队列**：topic 方案下退避消息的 routing key 是 `retry.tN` 这样的**合成键**，而 TTL 到期的死信**沿用的正是这个合成键**，业务交换机上没有它的 binding，消息会被静默丢弃。再发布层负责复原 routing key（从 `x-original-routing-key` 头读取）。另一个可选方案是按事件类型展开队列（3 档 × 4 事件类型 = 12 个），队列数随事件类型线性增长；再发布层让等待队列恒为 3 个。

**为什么绑定精确键而不用 `retry.#`**：通配符会让一条消息同时进 3 个队列，退避变成三倍投递。实测确认：精确键绑定下三档深度各为 1；`retry.t0` / `retry.t4` / `retry.t1x` 全部抛 `UnroutableError` 且不污染任何队列。

**消息头**：`x-retry-tier`（仅用于诊断，**不参与路由决策**）、`x-original-routing-key`、`x-first-failed-at`、`x-last-error`。让"头"与"实际进了哪个队列"可以互相印证，而不是互为因果。

**Publisher Confirm 必须检查返回值**：`waitForConfirms` 出现 NACK 时返回 `false` 而非抛异常。忽略返回值等于把 broker 的明确拒绝当成发布成功，Outbox 于是标 PUBLISHED，这条消息永远不再重发。

**队列参数是声明期契约**：`x-message-ttl`、`x-dead-letter-*`、`x-queue-type` 都不能事后修改。与既有队列参数不一致会 `PRECONDITION_FAILED` 并**关掉整个 channel**。另外，用带不同参数的 `queue_declare` 作用在已存在队列上会让 broker **按新参数重建该队列**（原参数全部丢失）——这意味着"探测性 declare"是破坏性操作，只能用 `passive=True`。详见事实记录第 14 条。

**死信查询与受控重放**：`GET /internal/v1/messaging/dead-letters?limit=N` 查询（不消费、不改变队列），`POST /internal/v1/messaging/dead-letters/replay` 受控重放。**刻意不提供全量重放**：`eventIds` 为空直接返回 400。重放保留原 eventId，继续走 Inbox 去重与业务幂等。

**重试拓扑迁移**：旧 headers 交换机的积压用 `tools/migrate_retry_topology.py` 受控迁移，强制 drain → verify → purge 三阶段，**先保全再清理**，对仍有消息的队列拒绝删除。

**消费统计上报（TASK-013-E）**：`POST /internal/v1/messaging/consumer-stats` 接收消费者上报的消费结果增量（acked / failed / retried / deadLettered / duplicates / republished / rejectFailed），后端累加为 Micrometer 计数。

为什么需要这个端点：一条被 reject 进死信的消息，在 `outbox_events` 里仍然是 `PUBLISHED`（发布确实成功了），在 `inbox_events` 里也可能没有记录（它根本没走到登记那一步）。**消费结局的真值只在消费者进程里**，后端数据库无法回答"这一小时消费了多少、失败了多少、进了多少死信"。

上报的是**增量**不是累计值：累计值在消费者进程重启后会归零，后端累加就会让计数倒退。一个会倒退的计数器比没有计数器更危险，因为它看起来是对的。上报失败会被消费者吞掉（观测手段坏了不该让业务跟着坏），未上报的增量在下一轮重报。

**可观测性分工**：发布侧指标由后端自发（`repopilot.outbox.*`），消费侧计数由消费者上报（`repopilot.consumer.*`），链路级状态（积压、延迟、租约超时、DLQ 深度）由后端从数据库与 broker 探测（`repopilot.consumer.backlog.*`、`*.delivery.latency.*`、`*.lease.expired`、`*.dlq.depth`）。完整清单见 `docs/modules/messaging-reliability.md` §3。

Java发布、Python消费用于唤醒领取；服务内部事件聚合可以在事务后调用同一个调度器。事件通知无法取代数据库状态检查。消息重复是正常边界，不宣称恰好一次。

## 幂等与脱敏

Java计算eventHash，忽略接收时间和数据库ID，保留业务字段；相同eventId同hash返回DUPLICATE，相同eventId异hash返回409并整批回滚。artifactId必须归属同Run。sourceEventId只做关联，不替代eventId全局唯一约束。

认证头、Cookie、API key、secret字段在采集端删去，服务端再次按规则校验与脱敏。模型输出与日志作为不可信数据存储，前端只做转义展示，不能直接渲染为可执行HTML。
