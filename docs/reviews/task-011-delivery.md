# TASK-011 交付报告：Outbox、RabbitMQ 与系统可靠性增强

日期：2026-10-19　分支：`develop`　基线 commit：`9b8cb7a`（TASK-010）

## 1. 实施前的实际状态（不凭推测）

`outbox_events` / `inbox_events` 两张表在 **TASK-002（V6 迁移）就已建好**，但：

| 项 | 实施前 | 实施后 |
| --- | --- | --- |
| Outbox 写入 | 只有 `insert` + `findById`，**没有任何业务代码调用** | 4 个业务接入点，全部同事务 |
| 发布器 | **不存在** | `OutboxScheduler` + `OutboxPublisherService` |
| RabbitMQ 用途 | 只有健康探针 | PERSISTENT + confirms + mandatory |
| inbox | 表存在，**从未写入** | `InboxService` + 唯一键去重 |
| 通知接口 | 不存在 | `POST /internal/v1/notifications/accept` |

**RabbitMQ 容器健康 ≠ 业务解耦完成。** 实施前它只是个健康探针在回答 ping。

## 2. 新增文件（12 个，1 076 行 Java）

| 文件 | 行 | 职责 |
| --- | --- | --- |
| `reliability/event/EventEnvelope.java` | 102 | 事件信封；严格遵守 `event-envelope.schema.json`；写前自检 |
| `reliability/messaging/PublishOutcome.java` | 47 | 三态结果：`success` / `returned` / `uncertain` / `failed` |
| `reliability/messaging/EventPublisher.java` | 32 | 发布器接口（`publish` / `publisherId` / `available`） |
| `reliability/messaging/RabbitEventPublisher.java` | 146 | 生产实现 |
| `reliability/messaging/FaultInjectingEventPublisher.java` | 110 | 可编程故障替身（测试用） |
| `reliability/service/OutboxWriter.java` | 136 | 业务事务内的 Outbox 写入（`MANDATORY` 传播） |
| `reliability/service/OutboxPublisherService.java` | 206 | 领取 → 发布 → 回写；退避 + fencing |
| `reliability/service/OutboxScheduler.java` | 85 | 定时循环 + 4 个 Micrometer 指标 |
| `reliability/service/InboxService.java` | 133 | 消费侧去重；5 种结果 |
| `reliability/web/NotificationController.java` | 79 | 通知唤醒入口 |

测试与文档：

| 文件 | 行 |
| --- | --- |
| `it/MessagingReliabilityIT.java` | 483 |
| `docs/contracts/openapi-messaging.yaml` | 184 |
| `docs/modules/messaging-reliability.md`（运维手册） | 212 |

## 3. 修改文件（9 个，+382 −8）

| 文件 | 改动 |
| --- | --- |
| `reliability/mapper/OutboxEventMapper.java` | +247：领取（`FOR UPDATE SKIP LOCKED`）、fencing 回写、退避重排、判死、人工重投递、运维查询 |
| `reliability/mapper/InboxEventMapper.java` | +23：`findPayloadHash`、`countByConsumer` |
| `modules/experiment/service/ExperimentService.java` | 提交后写 `run.ready` |
| `modules/experiment/service/WorkService.java` | 失败耗尽与租约回收写 `run.finished` |
| `modules/evaluation/service/EvaluationJobService.java` | 创建待评测 Job 时写 `evaluation.ready` |
| `modules/experiment/service/ExperimentAggregationService.java` | 实验收口写 `experiment.finished` |
| `it/AbstractExecutionIT.java` | 清理追加 outbox/inbox 表 |
| `repopilot-web/.../ExperimentDetailView.vue` | +40：等待提示（用户可理解的语言） |
| `docs/README.md` | 索引两行 |

**未新增任何数据库迁移。** 现有表结构已足够——这也是正确的：冻结契约里字段是齐的。

## 4. 三个不可违反的约束如何落地

### 4.1 不持锁跨网络

```
短事务：SELECT … FOR UPDATE SKIP LOCKED → 挂租约+claimToken → COMMIT
  ↓ （事务已结束）
网络：publish + waitForConfirms（5s）
  ↓
短事务：markPublished / scheduleRetry / markFailedPermanently（带 fencing）
```

`publishOne` 里领取与回写是两个独立的 `tx.execute`，网络调用在两者之间。

### 4.2 确认回写 fencing

```sql
UPDATE outbox_events SET status='PUBLISHED', …
 WHERE id=? AND status='PENDING'
   AND claim_token_hash = ?      -- 本次领取写入的摘要
   AND lease_until > ?           -- 且租约未过期
```

租约过期的失效发布器拿到 **0 行**，`applyOutcome` 记日志并返回 `false`，**不做任何写入**。
没有这一条，它会把新领取者正在处理的事件误标已发布，该事件**永久不再重发**。

### 4.3 不确定 ≠ 失败

`PublishOutcome.uncertain`（confirm 超时）走**重试**而不是判死。代价是可能重复投递，
由 inbox 去重兜住。序列化失败才走 `failed`（永久错误，重试无意义）。

## 5. 修掉的真实缺陷（6 个）

| # | 缺陷 | 影响 | 修法 |
| --- | --- | --- | --- |
| 1 | `claimForPublish` / `markPublished` 的 `@Update` 用 `&lt;`/`&gt;` **未包 `<script>`** | 字面量 `&lt;` 发给 MySQL，所有发布路径 100% 报语法错误 | 加 `<script>` 包裹 |
| 2 | `lockPublishable` / `countStalePending` 同样问题 | 领取阶段直接崩 | 同上 |
| 3 | `PAYLOAD_CONFLICT` 抛 400 | 消费者会把它当参数错误继续重试，违反契约 | 改 `RESOURCE_CONFLICT`（409） |
| 4 | `UNSUPPORTED_VERSION` 抛 `VALIDATION_ERROR` | 同上，且丢失了已有的专用错误码 | 改 `UNSUPPORTED_EVENT_VERSION`（400） |
| 5 | `writeRunReady` **定义但无调用点** | 提交实验后完全不产生 `run.ready`，消息链路形同虚设 | 在 `ExperimentService.startOnline` 的 `insertSlots` 后循环写入 |
| 6 | 测试助手 `outboxRows()` 只 SELECT `payload_json`，调用方却按 `row.path("payload_json")` / `row.path("id")` 取值 | **断言两边都是空值也能"通过"**——这是最危险的一种：测试看起来在验证，实际什么都没验 | 改为返回整行对象 |

第 6 个尤其值得记：`schemaVersion` 断言写成 `assertThat(envelope.path("schemaVersion").asInt()).isEqualTo(1)`，
在 helper 错误的情况下 `envelope` 是 MissingNode、`asInt()` 返回 0 —— 但 `eventId` 断言先失败，
才暴露出问题。如果两条断言恰好都写在同一个空路径上，它会一直绿下去。

**顺带做了一次全仓库排查**：所有含 `&lt;`/`&gt;` 的 SQL 注解共 10 处，现在全部包了 `<script>`。

## 6. Spring bean 装配的三轮失败（记录下来避免重犯）

| 轮次 | 错误 | 修法 |
| --- | --- | --- |
| 1 | `NoUniqueBeanDefinitionException: rabbitEventPublisher, eventPublisher` | 加 `@Primary` |
| 2 | `more than one 'primary' bean found` | Rabbit 加 `@ConditionalOnProperty(havingValue="rabbit", matchIfMissing=true)` |
| 3 | `@Qualifier("${repopilot.outbox.publisher-bean:...}")` → `NoSuchBeanDefinitionException` | **占位符在 `@Qualifier` 上不解析**，放弃该方案 |

最终方案是**条件互斥**而不是 `@Qualifier` 选择。

## 7. 故障演练：13 个用例

`MessagingReliabilityIT` 用可编程替身制造真实故障——不真的把 RabbitMQ 弄挂
（那既不可重复也会让 CI 不稳定）。

| 用例 | 验证的不变式 |
| --- | --- |
| 业务状态与 Outbox 同事务 | 不会出现「Run 回滚了但通知发出去了」 |
| 信封只带 ID | 消息里没有待评测代码输出、模型返回或密钥 |
| confirm 丢失 → 重发 | 消费者无重复业务效果 |
| 消息被退回 | **不算发布成功**（无队列绑定必须重发） |
| 失效发布器 fencing | 拿不到 0 行以外的结果 |
| 租约过期被接管 | 发布器崩溃后事件最终能发出 |
| 毒消息有限重试 | 8 次后 FAILED，不无限重投 |
| 人工重投递保留 event_id | 消费者侧仍能去重 |
| MQ 不可用时 DB 扫描继续 | 恢复后不重跑已完成 Run |
| 全量重投 | 已完成 Run 不被拉回执行态 |
| 异内容隔离 | 不当正常重复静默 ACK |
| 通知接口状态码语义 | 重复 200 / 冲突 409 / 未知版本 400 |
| 未鉴权 401 | 不留任何痕迹 |

## 8. 实际执行命令与数字

| 命令 | 结果 |
| --- | --- |
| `./mvnw -o test-compile` | 退出码 0 |
| `./mvnw -o failsafe:integration-test -Dit.test=MessagingReliabilityIT` | **13 run / 0 失败 / 0 错误 / 0 跳过** |
| `./mvnw -o verify`（全量） | **BUILD SUCCESS，208 run / 0 失败 / 0 错误 / 1 跳过** |
| `-Dit.test=ApiHealthIT`（带 Rabbit 凭据） | **4 run / 0 跳过**（含 readiness 真实返回 200） |
| `python tools/verify_design.py` | **PASS，1270 checks / 28 tables** |
| `npm run type-check` | 通过 |
| `npx vite build --emptyOutDir=false` | 成功，`ExperimentDetailView` 14.21 kB |

### 关于那 1 个跳过

`ApiHealthIT.readinessShouldBeUpWhenEveryDependencyIsAvailable` 被
`@EnabledIfEnvironmentVariable(REPOPILOT_IT_REQUIRE_ALL_UP)` 门控。**我没有只信注解**，
而是在本地按 CI 的方式带上凭据实跑：

```
REPOPILOT_IT_REQUIRE_ALL_UP=true SPRING_RABBITMQ_USERNAME=repopilot \
SPRING_RABBITMQ_PASSWORD=repopilot_dev_pwd → 4 run / 0 跳过 / readiness 返回 200
```

结论：**skip 是本地缺 `SPRING_RABBITMQ_*` 环境变量，不是代码问题**；CI 的 `ci.yml`
已配置这两个变量与 `REPOPILOT_IT_REQUIRE_ALL_UP: 'true'`，所以 CI 上这个用例真跑。

### 数字口径

- 195（TASK-010 既有）+ 13（本任务新增）= 208，与全量输出一致。
- 单元测试未单独统计（本次无新增单元测试）；surefire 目录含上一轮的陈旧 XML，
  按 mtime 过滤后未计入。

## 9. 契约变化

| 文件 | 内容 |
| --- | --- |
| `docs/contracts/openapi-messaging.yaml`（新增） | `POST /internal/v1/notifications/accept`；状态码语义表；**说明这个接口不创建 Run/Job、不跑长任务** |
| `docs/modules/messaging-reliability.md`（新增） | 运维手册：链路图、3 条约束、4 个指标、按现象排查、队列绑定、人工重投递、保留策略、故障演练 |
| `docs/README.md` | 索引两行 |

**事件契约未变**——`event-envelope.schema.json` 原本就是正确的，本任务按它实现，
没有借机"改进"格式。

## 10. 边界声明（明确不是什么）

| 项 | 说明 |
| --- | --- |
| **不宣称恰好一次** | 至少一次投递 + inbox 去重。confirm 丢失必然导致重复，这是设计而非缺陷 |
| **publisher confirm ≠ Agent 成功** | confirm 只表示 broker 收下了消息；Agent 成功由 Run 终态与 Attempt 回执决定 |
| **不用消息代替 DB 状态** | 消息只是唤醒信号。信封里**只放 ID 不放正文**；真正的数据仍走数据库领取协议 |
| **未新增迁移** | 现有表结构已足够 |
| **未引入新基础设施** | 没有为任务数量引入 Redis/Kafka/ClickHouse |

## 11. 已知限制（未验证 / 未实现）

| # | 项 | 说明 |
| --- | --- | --- |
| 1 | **真实 RabbitMQ 端到端发布未验证** | 13 个用例用的是故障注入替身。本机有 RabbitMQ 3.13.7 的实跑核查记录（`reviews/rabbitmq-local-verification.md`），但**没有对真实 broker 跑过"发布→confirm→routingKey 路由成功"的完整链路** |
| 2 | **Python Worker 未接入 RabbitMQ 订阅** | 通知接口已实现并有鉴权，但 Worker 仍靠 `poller.py` 周期领取。**通知接口目前是"可被唤醒"的通道，不是"已经在用"的通道** |
| 3 | **RabbitMQ 拓扑未声明** | 代码只负责发布（routingKey = eventType）；exchange/queue/binding 留给部署侧。compose 里的 RabbitMQ 只有健康探针，**没有预建绑定** |
| 4 | **无自动 DLQ** | 409/400 的隔离只体现在 HTTP 状态码与日志，没有自动投递到独立死信队列 |
| 5 | **未接告警平台** | Micrometer 指标已注册（4 个），阈值是手册里的建议值，需要自行接入 |
| 6 | **Outbox/Inbox 无自动清理** | V1 手工分批执行（手册给了 SQL 与理由） |
| 7 | **Outbox 无对外 API** | 有意为之：人工重投递走运维 SQL / 服务方法，避免用户绕过业务状态机 |
| 8 | **单发布器吞吐** | 每条消息一个独立 channel + `waitForConfirms` 串行确认，是为正确性牺牲吞吐。TASK-012 压测时需重新评估 |

## 12. 下一任务（TASK-012）前置是否满足

`tasks/TASK-012.md` 要求压测与有证据的优化。前置检查：

| 前提 | 状态 |
| --- | --- |
| 有可复现的负载脚本与数据集 | 需 TASK-012 建立 |
| 有基线与瓶颈定位手段 | MySQL 真实实例可用；Outbox 已有 4 个指标可观测 |
| 只改已验证的瓶颈 | 需先测出瓶颈 |
| 不为任务数量引入新中间件 | 本任务未引入新中间件 ✓ |

**但有一处必须先解决**：TASK-012 压测会立刻撞上限制 #8（每条消息一个 channel +
串行 confirm）。这不是可以绕过的——要么压测时把 `repopilot.outbox.publisher` 设为
`fault-injecting` 绕开真实 broker 测数据库路径，要么先做批量 confirm 优化并**用
`MessagingReliabilityIT` 的 13 个用例证明语义没变**。后者是正确做法。