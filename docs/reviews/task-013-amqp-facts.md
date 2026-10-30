# TASK-013 实测事实（AMQP 语义 / 客户端行为）

本文件记录本任务在**真实 RabbitMQ 3.13.7** 上实测出来的行为，
用于解释代码里那些"看起来奇怪"的选择。每一���都有对应的探测脚本与实测输出。

环境：`repopilot-rabbitmq` 容器（3.13.7-management），队列类型 `quorum`。

---

## 1. `basic.nack(requeue=true)` 把消息放到**队尾**，不是原位

实测（队列 5 条 m1..m5，逐条 `basic_get` + `basic_nack(requeue=true)`）：

```
iter 0: got m1
iter 1: got m2
...
iter 4: got m5
iter 5: got m1     <-- 绕回队首，重复读到同一条
```

**影响**：任何"取一条 → 放回去 → 再取一条"的扫描循环都会**重复读到同一条消息**。
TASK-013 第一版的 `DeadLetterService.query()` 就是这么写的，
`limit=20` 而队列只有 5 条时，查询结果里有 15 条是重复的。

**正确做法**：先把要看的消息**全部取出来 hold 住**（不 ACK），
读完之后再统一放回。hold 住期间 `basic_get` 只会返回**还没取过**的消息：

```
held (distinct): ['m1', 'm2', 'm3', 'm4', 'm5']
get while holding -> None (all held)
```

批量 hold 300 条也不报错（quorum 队列允许）。

## 2. `basic.recover(requeue=true)` 同样是放到队尾

实测（hold 3 条 m1..m3，队列里还剩 m4,m5）：

```
order after recover: ['m4', 'm5', 'm1', 'm2', 'm3']
```

**结论**：AMQP 0-9-1 **没有"只读窥视"原语**。
任何基于 `basic.get` 的查询都会改变队列顺序。
本任务接受这个副作用（DLQ 是诊断队列，集合不变即可），
但**必须保证不丢消息、不产生重复条目**。

## 3. `basic.reject` / `basic.nack` **无法指定死信 routing key**

死信必然沿用原消息的 routing key（改写只能靠 `basic.publish` 的 `CC` 头，
reject 路径没有该能力）。所以 DLQ 必须绑定**全部业务 routing key**，
不能用 `dead.*` 前缀——否则死信被 DLX 收到却匹配不到队列、被 broker 丢弃。

## 4. Java 客户端 `waitForConfirms(long)` 返回 `boolean`

字节码（`ChannelN.waitForConfirms`）：

```
61: getfield onlyAcksReceived
65: istore 6
67: iconst_1
69: putfield onlyAcksReceived     <-- 读出后立刻复位
72: iload 6
77: ireturn                       <-- 返回的是"这一轮是否只有 ack"
```

`false` = 这一轮里出现过 **NACK**。**返回值必须检查**，
否则 broker 拒绝的消息会被当成"发布成功"，Outbox 标成 PUBLISHED 后永不重发。

另外 `waitForConfirms` 在**没有** `confirmSelect()` 时抛
`IllegalStateException("Confirms not selected")`——TASK-013 第一版的
`DeadLetterService.republish()` 就是漏了这一句，重放功能完全不可用。

`waitForConfirmsOrDie` 在 NACK 时会**关掉整个 channel** 并抛 IOException，
所以这里用 `waitForConfirms` + 检查返回值，而不是 `OrDie` 版本。

## 5. `mandatory=true` 在**非 confirm 模式**下检测不到不可路由

pika 源码（`BlockingChannel.basic_publish`）：

```python
if self._delivery_confirmation:
    ...  # 这里才会 raise UnroutableError / NackError
else:
    self._impl.basic_publish(..., mandatory=mandatory)
    self._flush_output()          # 不等 return，也不报错
```

docstring 也写了："mandatory may be enabled even without delivery
confirmation, but in the absence of delivery confirmation the synchronous
implementation has no way to know how long to wait for the Basic.Return."

**影响**：消费者重试时如果不开 confirm 模式，
`basic_publish(mandatory=True)` 到一个没有 binding 的交换机**会静默丢失**，
而原消息已经被 ACK —— 消息彻底没了。

**正确做法**：消费用的 channel 必须 `confirm_delivery()`。

## 6. pika 在消费者回调里做 confirm 模式发布是安全的

`BlockingChannel._on_consumer_message_delivery` 只把事件塞进 `_pending_events`，
真正派发由 `BlockingConnection._dispatch_channel_events` 在
`_acquire_event_dispatch()` 保护下完成，嵌套派发被 `_event_dispatch_suspend_depth` 抑制。

而 `basic_publish`（confirm 模式）走的是
`self._flush_output(self._message_confirmation_result.is_ready)`
→ `BlockingConnection._flush_output` **只 poll ioloop + 处理超时，不派发 channel 事件**。

结论：在 on_message 回调里做 confirm 发布不会重入消费者回调。

## 7. 整数在 AMQP 字段表里的类型必须两侧一致

- pika：Python `int` → `struct.pack('>ci', b'I', value)` = **有符号 32 位（'I'）**
- Java 客户端：`Integer` → `writeOctet('I')` + `writeInt` = **有符号 32 位（'I'）**

两侧一致，所以两侧用 `Integer` 时编码相同。

> **这条结论对最终方案已不再重要**：退避分档改用 topic + routing key（第 8、10 条），
> 不再依赖 headers 匹配。保留本条是因为它揭示了 AMQP 字段表的一个通用陷阱——
> 跨语言传结构化数据时，同名字段的**类型编码**也必须一致，
> 否则匹配失败且完全静默。本条同时也是第 8 条的伏笔：
> 如果 headers 绑定**真的**生效了，跨语言类型不一致就会成为下一个坑。

若一侧发 `long`（'l'）另一侧绑定 `int`（'I'），headers 交换机按类型严格比较，
**匹配不上**——消息会被静默丢弃。

> **本条的初版实测有个致命缺陷**：只发了 `x-retry-tier=1`（正例），
> 看到它进了 t1 队列就宣布方案成立。补发 `x-retry-tier=99`（反例）
> 才发现分档压根没生效——见第 8 条。
> **只验证正例、不验证反例，是这类实测最常见的失效方式。**

## 8. `x-match=all` 会**忽略 `x-` 前缀的绑定字段** —— headers 分档方案失效

> **本条推翻了第 7 条的初版结论。初版只验证了"匹配能成功"，
> 没验证"不匹配能被挡住"，于是得出了错误的乐观结论。**

初版实测只发了一条 `x-retry-tier=1` 的消息，看到它进了 `t1` 队列，
就认为 headers 分档方案成立。**只验证正例、不验证反例是这类实测最常见的失效方式**。

补测反例（`x-retry-tier=99`，没有任何 binding 匹配它）：

```
# Python 客户端，隔离的新拓扑
tier=99: 无异常          <-- 应该抛 UnroutableError
   {'t1': 1, 't2': 1, 't3': 1}     <-- 三个队列全都收到了！

# Java 客户端（amqp-client 5.25.0），同样隔离的新拓扑
tier=1  waitForConfirms=true   t1=1 t2=1
tier=2  waitForConfirms=true   t1=2 t2=2
tier=99 waitForConfirms=true   t1=3 t2=3   <-- 非法档位照样进队列
```

**根因**：RabbitMQ 的 `x-match=all` 语义是"binding 中**所有非 `x-` 前缀**的字段
都必须匹配"。`x-retry-tier` 带 `x-` 前缀，因此**从未参与匹配**——
交换机表现得像 fanout，所有 binding 全部命中。

排除过程的证据（确认不是环境或用法问题）：

| 排除项 | 手段 | 结果 |
|---|---|---|
| binding 参数没送出去 | `rabbitmqctl list_bindings` | `[{"x-match","all"},{"x-retry-tier",1}]` **已正确登记** |
| 客户端差异 | Java `amqp-client 5.25.0` + pika 1.3.2 | **两者都复现** |
| vhost 污染 | `rabbitmqctl add_vhost hdrprobe` 后重测 | **全新 vhost 同样复现** |
| policy 干扰 | `rabbitmqctl list_policies` / `list_operator_policies` | 均为空 |
| binding routing key 是空串 | 对照实验（空串 vs 非空串两个队列） | **两者都失效**，与 routing key 无关 |

**影响**：如果按初版设计上线，"3 档指数退避"会退化为
**"全部消息按第一档（5 秒）并发回流"**——2/3 档形同虚设，
而表面上 `retry.tier` 计数、指标、日志全都正常。
这类故障在监控上完全不可见。

**最终方案**：改用 **topic 交换机 + 精确 routing key `retry.tN`**
（见第 10 条）。同时保留 `x-retry-tier` 头，但**只用于诊断**，
不再参与路由决策——这样"头"和"实际进了哪个队列"可以互相印证，
而不是互为因果。

## 9. 重试队列 TTL 到期后 routing key 保持原值

实测（`x-message-ttl=5000` + `x-dead-letter-exchange=probe.events`）：

```
depth probe.retry.t1 = 0
depth probe.biz      = 1     (回到了业务队列)
routing_key: run.ready
headers: {..., 'x-retry-tier': 1, 'x-last-error': 'boom', 'x-death': [...]}
```

**关键补充**：只有当消息的 routing key **本身就是业务键**时，
"沿用原值"才有意义。一旦 routing key 是合成键（如第 10 条的 `retry.tN`），
死信回流时沿用的就是合成键，业务交换机上没有它的 binding，消息会被静默丢弃。
这正是需要"再发布"中转层的原因。

自定义 header 在死信回流后**依然保留**（只有 `x-death` 是 broker 加的）。

## 10. topic + 精确 routing key 分档实测通过

与第 8 条对照的验证。Java 客户端在真实 broker 上：

```
// 声明 repopilot.events.retry.v2 (topic)，三档队列绑 retry.t1/t2/t3
// 分别发 retry.t1 / retry.t2 / retry.t3
  repopilot.retry.wait.t1 depth=1
  repopilot.retry.wait.t2 depth=1
  repopilot.retry.wait.t3 depth=1     <-- 各 1 条，无广播误路由
```

**非法档位必须被挡住**（这是第 8 条缺失的验证）：

```
publish retry.t0 / retry.t4 / retry.t1x / retry.t9
  -> 全部抛 UnroutableError（confirm + mandatory 模式）
  -> 三个等待队列深度均为 0
```

只绑精确键、不用 `retry.#` 通配符：后者会让一条消息同时进 3 个队列，
退避变成三倍投递。

## 11. pika 的 `BasicProperties` 上**没有** `routing_key` 字段

```python
props = pika.BasicProperties(content_type="application/json", delivery_mode=2)
hasattr(props, "routing_key")   # -> False
```

routing key 在**回调的 `method` 参数**上（`spec.Basic.Deliver.routing_key`），
不在 `properties` 上。

**影响**：第一版 `_publish_to_retry` 写的是
`getattr(properties, "routing_key", None) or "run.ready"`，
该表达式**恒为兜底值**。于是 `evaluation.ready` 的消息退避后
会以 `run.ready` 回流——**消息不丢，但被投递给错误的消费者**。
这是真实 broker 测试抓出来的，替身测试永远发现不了
（`FakeProperties` 上有 `routing_key` 字段，看起来是对的）。

**同类陷阱**：`AMQP.BasicProperties`（Java）同样没有 `routing_key`，
它在 `Envelope.getRoutingKey()` 上。

## 12. `GetResponse` 的属性访问器是 `getProps()`，不是 `getMessageProperties()`

```
$ javap -cp amqp-client-5.25.0.jar com.rabbitmq.client.GetResponse
public class com.rabbitmq.client.GetResponse {
  public com.rabbitmq.client.Envelope getEnvelope();
  public com.rabbitmq.client.AMQP$BasicProperties getProps();   <-- 是 getProps
  public byte[] getBody();
  public int getMessageCount();
}
```

`DeadLetterService` 第一版写的是 `response.getMessageProperties()`，编译直接失败。
**任何"看起来很像"的方法名都要用 `javap` 查证**，不能凭直觉写。

## 13. Java 客户端的 `queueBind` 不接受 null routing key

```
java.lang.IllegalStateException: Invalid configuration: 'routingKey' must be non-null.
```

headers 交换机不需要 routing key 时要传**空字符串 `""`**，不能传 `null`。
（`javap` 签名上看不出这个约束——它在 `validators.require_string` 里。）

## 14. `queue_declare` 带不同参数作用在已存在队列上 = **按新参数重建队列**

这是本任务里**最隐蔽也最危险**的一条，因为它毁掉的是别人（生产拓扑）的东西，
而报错出现在完全无关的地方。

**实测经过**：演示脚本为了"验证队列是 quorum"，写了这样的探测：

```python
#错误做法：用"改参数重新 declare"来探测类型
channel.queue_declare(queue, durable=True, auto_delete=True)   # 期望它报错
```

推理是"quorum 队列不允许 `auto_delete`，所以它会抛异常"。

**实际发生的事**：quorum 队列**接受**了 `auto_delete=true`，
broker 把它当作参数变更，于是**删除了原队列并按新参数重建**——
`x-message-ttl`、`x-dead-letter-exchange`、`x-dead-letter-routing-key`
全部消失。随后：

```
$ docker exec repopilot-rabbitmq rabbitmqctl list_queues name arguments | grep retry.wait
（三个队列全部消失）
```

再之后，后端/测试按契约声明这些队列时收到：

```
PRECONDITION_FAILED - inequivalent arg 'x-message-ttl'
  for queue 'repopilot.retry.wait.t1' in vhost '/':
  received the value '5000' of type 'long' but current is none
```

而 Java 集成测试的表面症状是 **18 个用例集体 error**，
错误信息指向 `x-message-ttl` 不一致——**完全指错了方向**。
真正的原因（"某个演示脚本早先删掉了这个队列"）不在任何日志里。

**正确做法：只读探测，绝不带着不同参数 declare 生产实体**

```python
# 只读：passive declare 不带任何参数，只问"存在吗、有多少条"
channel.queue_declare(queue=queue, passive=True)
# 类型用 management API（GET /api/queues/%2F/<name>）读，或 rabbitmqctl
```

**三条一般性教训**：

1. **探测性操作必须只读。** `passive=True` 是安全的；
   带参数 declare 不是。凡是为了"看一眼"而写的代码，先确认它不会写。
2. **队列参数不是可以来回改的**。它属于"声明期契约"，
   和第 9 条的 `x-message-ttl` 是同一个道理，但后果更重——
   TTL 不一致只是报错，参数被改掉会**静默丢失全部配置**。
3. **"某个组件的测试集体失败"要怀疑测试环境被污染**，
   而不是先怀疑被测代码。18 个用例同时失败、且错误信息指向一个
   "看起来很合理"的配置冲突时，先去`rabbitmqctl list_queues`
   核对实体是否还存在、参数是否还对。

## 15. Spring 的 `@ConditionalOnProperty` 必须在注入方与被注入方之间对齐

不算AMQP 事实，但同样值得记：这是本任务排查时间最长的一个失败。

`DeadLetterService` 上有
`@ConditionalOnProperty(name = "repopilot.outbox.publisher", havingValue = "rabbit")`，
而 `DeadLetterController` 是**无条件**的 `@RestController`。
于是TASK-011 的测试（`publisher=fault-injecting`）启动时：

```
Caused by: NoSuchBeanDefinitionException:
  No qualifying bean of type 'DeadLetterService' available
```

后果是**整个 `ApplicationContext` 起不来**，TASK-011 全部 13 个用例
报 `Failed to load ApplicationContext`——而栈顶是一个离根因十万八千里的
`DefaultCacheAwareContextLoaderDelegate`。
更糟的是它会**级联**：`failure threshold (1) exceeded`
让后续用例全部跳过真正的加载过程，于是 13 个一模一样看不出所以然的报错。

**教训**：条件化的 bean 被注入到无条件加载的 bean 里时，
**条件必须复制一份到注入方**。少复制的那一份，在正常配置下完全看不出来
（默认 `matchIfMissing=true` 会让它正常注册），
只在切换到别的 profile 时炸。

## 16. 本机 JDK 的实际位置：项目要求 21，`java -version` 却显示 18

早期结论是"本机只有 JDK 18，无法运行 `./mvnw verify`"——**这个结论是错的**。

```
$ java -version
openjdk version "18" 2022-03-22          # 来自 PATH
$ ls -d /c/Users/15334/.jdks/*
/c/Users/15334/.jdks/corretto-17.0.14
/c/Users/15334/.jdks/ms-21.0.10           <-- 项目要求的 21 在这里
/c/Users/15334/.jdks/openjdk-18.0.2.1
```

JDK 21 装在**用户目录**下，不在 `Program Files`，
所以只看 `java -version` 和 `Program Files/Java` 会漏掉它。

**教训**：在判定"某个工具链不可用"之前，把
`~/.jdks`、`/usr/lib/jvm`、`/opt/java`、SDKMAN、
`Program Files/Eclipse Adoptium` 都看一遍。
把"我没找到"当成"它不存在"，会让一份**本可运行的测试**
被写成"无法验证"——而"未验证"在交付报告里会被读成"有问题"。

## 17. 集成测试的环境变量前缀与应用不同

```
应用（application.yml）:   MYSQL_HOST / MYSQL_PORT / MYSQL_DATABASE / ...
集成测试（application-it.yml）: REPOPILOT_IT_DB_HOST / REPOPILOT_IT_DB_PORT / ...
```

用 `MYSQL_*` 跑 `mvn verify`，Flyway 会报
`Communications link failure`——看起来像"连不上数据库"，
实际是**变量名没被读取，于是用了默认值 `root@localhost:3306`**。

判定方法：MySQL 本身用 mysql.exe 直连是通的，
说明不是网络或服务问题，而是**配置没生效**。
此时该找的是"这个 profile 到底读哪些变量名"，
而不是去查防火墙、端口占用或数据库权限。

## 18. `GetResponse.getProps()` 可能返回 null —— 查询接口的 NPE

死信查询接口在真实调用下返回 **500**，而单元测试全绿。

根因在 `DeadLetterService.summarize()`：

```java
AMQP.BasicProperties properties = response.getProps();
Map<String, Object> headers = properties.getHeaders();   // <-- NPE
...
properties.getTimestamp()                              // <-- 同样 NPE
```

**为什么测试测不出来**：所有构造的 `GetResponse` 都带完整的 properties。
而真实 DLQ 里的消息恰恰相反——最需要人工处理的那批
（外部生产者投递的、或在设置 properties 之前就被 reject 的）**信息最少**。

```
$ curl -s -H "Authorization: Bearer $token" \
    "$BASE/internal/v1/messaging/dead-letters?limit=5"
{"code":"INTERNAL_ERROR","message":"服务内部错误",...}
```

修法：`properties`、`getEnvelope()` 分别判空；`routingKey` 为空时
**不猜**，记一条原因让运维判断，而不是静默投到错的地方。

**教训**：给"处理任意输入"的接口写测试时，
**必须有一条用例专门喂最贫瘠的输入**（这里是不带任何 properties 的裸消息）。
构造整齐的测试数据只能证明代码在理想路径上是对的。

## 19. Spring AMQP 的 `Connection.createChannel(false)` 直接返回 Channel

```java
// DeadLetterService.query() 的正确写法
try (Connection connection = connectionFactory.createConnection();
     Channel channel = connection.createChannel(false)) { ... }
```

误写会得到两类与业务毫无关系的编译错误：

| 误写 | 报错 | 真实原因 |
| --- | --- | --- |
| `((com.rabbitmq.client.Connection) springConn).createChannel(false)` | `boolean 无法转换为 int` | 原生 `Connection` 只有 `createChannel()` / `createChannel(int)` |
| `springConn.createChannel(false).getConnection()` | `找不到符号` | Spring 的 `createChannel` 已经**直接返回** `com.rabbitmq.client.Channel`，没有 `getConnection()` |

**教训**：跨库调同一个概念（"连接 / 通道"在 Spring AMQP 与 amqp-client 里
类型层次完全不同）时，**照抄同文件里已经在跑的代码**，
而不是凭直觉推断 API 形状。

## 20. 测试与执行顺序耦合会把确定性缺陷伪装成偶发失败

`MessagingReliabilityIT.poisonMessageStopsAfterRetryBudget`：

```
单跑该用例                → 1 passed
与 RabbitTopologyIT 一起跑 → expected "FAILED" but was "PUBLISHED"
```

根因是 `firstPendingEventId()` 取**全表第一条** `status='PENDING'`：

```sql
SELECT id FROM outbox_events WHERE status = 'PENDING' ORDER BY created_at, id
```

集成测试共用同一个数据库，其他类（尤其会批量插入造数据 PENDING 行的
`RabbitTopologyIT`）留下的旧行会被优先取走。
于是这个用例有时在测自己造的事件、有时在测别人的。

修法不是"重跑一次就好了"，而是**把断言锁定在本用例的素材上**：
`createAndStartExperiment()` 记录自己创建的 `experimentId`，
`firstPendingEventId()` 据此过滤。

**这类缺陷比真失败更危险**：它看起来像环境抖动，
于是被"再跑一遍"掩盖过去，直到某天在 CI 上偶发红一次才被人看见。
识别方法很简单——**单跑通过、混跑失败**，就去找共享状态。

## 21. `@Value("${key:" + CONSTANT + "}")` 编译得过、运行即错

死信查询接口一直报队列不存在：

```
ShutdownSignalException: NOT_FOUND
  - no queue '${repopilot.rabbitmq.dead-letter-queue:repopilot.events.dlq}'
  in vhost '/'
```

注意错误信息里的队列名**带着完整的占位符原文**——
`${repopilot.rabbitmq.dead-letter-queue:repopilot.events.dlq}`。
这个"占位符形状的队列名"就是指纹：说明 Spring 根本没解析它，
于是把整串默认字符串当成了值。

原因写在构造器里：

```java
// 看起来像"默认值取自常量"，实际无效
@Value("${repopilot.rabbitmq.dead-letter-queue:"
        + RabbitTopology.DEAD_LETTER_QUEUE) String deadLetterQueue
```

Java 侧看到的是字符串拼接，但 **Spring 先解析占位符、再处理拼接后的结果**：
占位符语法里 `key:default` 的 default 部分是**纯字面量**，
不支持表达式。于是解析出的配置 key 是
`repopilot.rabbitmq.dead-letter-queue:repopilot.events.dlq`（一个不存在的 key），
Spring 找不到它 → 把整个字符串当值返回。

**正确写法**：默认值位置只能写字面量。

```java
@Value("${repopilot.rabbitmq.dead-letter-queue:repopilot.events.dlq}")
```

但这样常量就在两处各写了一份字符串，会悄悄漂移。补一个断言守住：

```java
public static final boolean DEFAULT_DEAD_LETTER_QUEUE_MATCHES_TOPOLOGY =
        DEFAULT_DEAD_LETTER_QUEUE.equals(RabbitTopology.DEAD_LETTER_QUEUE);
```

**教训**：配置占位符里出现**本该是变量却长得像值**的内容时，
先去Spring 文档确认"默认值支持什么语法"，而不是假设它和 Java 表达式一样。
另外：错误信息里出现占位符原文，几乎总是"这一整串被当成了值"。

## 22. `Map.of()` 返回不可变集合，`put` 会抛 `UnsupportedOperationException`

死信重放接口在真实调用下 500：

```
java.lang.UnsupportedOperationException
    at java.util.ImmutableCollections.uoe(ImmutableCollections.java:142)
    at java.util.ImmutableCollections$AbstractImmutableMap.put(...)
    at DeadLetterService.republish(DeadLetterService.java:366)
```

第一版的写法看着很"聪明"：

```java
Map<String, Object> headers = original.getHeaders() == null
        ? Map.of()                          // <-- 不可变
        : new HashMap<>(original.getHeaders());
headers.put(HEADER_REPLAYED_AT, ...);        // <-- Map.of() 分支上炸
```

**为什么难以发现**：这条分支只在"死信**没有** headers"时才走到。
而本系统自己发的消息**总是带 headers**，所以：
* 演示脚本、Python 测试、绝大多数场景都走 `new HashMap<>(...)` 分支，正常；
* 只有"外部生产者投递的裸消息"才会触发。

正确写法是先建可变容器、再拷贝：

```java
Map<String, Object> headers = new HashMap<>();
if (original != null && original.getHeaders() != null) {
    headers.putAll(original.getHeaders());
}
headers.put(HEADER_REPLAYED_AT, ...);
```

**教训**：`Map.of()` / `List.of()` 与"我只是临时用一下"的直觉相反——
它们是**不可变**的。凡是后面还要 `put`/`add` 的容器，
初始值就不能是 `of(...)`。这一点在"分支里给空值兜底"时尤其容易踩：
兜底路径往往最没人测。

## 23. quorum 队列的 `basicNack(requeue=true)` 重投是**异步**的

实测（quorum 队列，pika）：

```
after publish          depth = 1
basic_get(auto_ack=False) → held = 1
basic_nack(requeue=true)
after nack             depth = 0     <-- 注意这里
连接关闭
after reconnect        depth = 1     <-- 重投在这里才发生
```

也就是说 `basicNack(requeue=true)` **立刻**把消息移出 ready 队列
（深度变 0），但要等 channel/连接真正关闭后才放回。

**这对运维有直接影响**：
`DeadLetterService.query()` 用的是"hold 全部 → 读 → 统一 nack 放回"，
所以在 `query()` 返回后的**几百毫秒内**去查 DLQ 深度会得到 0，
让人误以为"死信被清空了"。更糟的情形是：这段时间里若执行 purge，
**重投中的消息会真的丢失**。

因此：
* 测试断言必须 sleep 后再看**稳定态**深度；
* 运维排查时若发现 DLQ 深度为 0，**先确认没有任何查询刚发生过**，
  再考虑"消息是不是真的没了"。

**教训**：任何"读一下再放回"的实现都要意识到
**"放回"不是一个瞬时可见的完成状态**。断言与运维手册都要按稳定态写，
否则会把正常行为误判成数据丢失——而这种误判会诱发不必要的紧急处置。

## 24. 批量操作 + 事后取"第一条" = 与执行顺序耦合的断言

补第 20 条的后半段。前半段讲的是"全表第一条被别人抢走"，
这里讲的是**同一用例内部**的耦合：

```java
createAndStartExperiment();          // 造出 N 条事件
faults.reset(PublishOutcome.failed(...));
for (int i = 0; i < 12; i++) {
    makeRetryDue(eventId);           // 只把**一条**拨到可重试
    publisher.scanOnce();            // scanOnce 一次处理**最多 50 条**
}
assertThat(statusOf(eventId)).isEqualTo("FAILED");
```

两个独立的耦合点：

1. **取值时机**：`scanOnce()` 是批量的。若在扫描**之后**才取"第一条 PENDING"，
   取到的是哪一条取决于 `created_at` 排序——它未必是被本次故障影响的那条。
2. **拨回时机**：`makeRetryDue` 只改 `next_retry_at`。
   若目标事件当前是 `PUBLISHED`（被前面的 `createAndStartExperiment` 内部调度发布掉了），
   它**根本不会进入候选列表**，12 轮一次都没被处理——
   断言于是报出一个假的"重试没有停下来"。

正确写法：

```java
String experimentId = createAndStartExperiment();
List<String> eventIds = allEventIdsOf(experimentId);   // 先全部取出来
faults.reset(...);
for (int i = 0; i < 12; i++) {
    for (String id : eventIds) {
        forceRepublishable(id);      // 同时拨时间**和状态**
    }
    publisher.scanOnce();
}
```

**教训**：测试断言的可靠性取决于它与被测代码的**执行粒度**是否匹配。
被测代码是批量的（`scanOnce` 处理 N 条），断言就必须**显式处理 N 条**，
而不是假设"我关心的那条一定在里面"。

## 25. 指标写错了不会让程序失败 —— 它只是安静地不出现

TASK-013-E 补齐可观测性时，最难的不是写指标，是**确认指标真的存在**。

指标写错的失败模式与普通代码完全不同：

| 错误写法 | 后果 | 会失败吗 |
| --- | --- | --- |
| `Counter.builder("repopilot.outbox.published")` 少写一个词 | 指标名变成 `repopilot.outbox.publish` | **不会** |
| Gauge 的取值函数返回了错误的东西 | 数字是错的 | **不会** |
| Gauge 引用了一个 null 对象 | 取值时 NPE | 只在第一次被 scrape 时 |
| 指标压根没注册 | 监控面板上一片空白 | **不会** |

也就是说：**所有这些缺陷的共同表现是"没有东西失败"**。
没有测试覆盖的话，它们会一直静默存在，直到某天真的出事，
而那时排查的人会盯着一个从来没被验证过的数字。

所以本任务给指标写了测试（`MessagingObservabilityIT`），
断言的不是"调用了 `Counter.builder` 没抛异常"，而是
**"这个指标名在 registry 里查得到，而且取值符合事实"**：

```java
assertThat(registry.find("repopilot.consumer.delivery.latency.avg.seconds").gauge())
        .isNotNull();
assertThat(registry.find("repopilot.consumer.dlq.depth").gauge()).isNotNull();
```

**教训**：指标和日志一样，属于"错了也不会有人发现"的一类代码。
它们需要与业务逻辑同等的测试强度——只是断言的对象从"返回值"变成了
"指标是否存在 + 取值是否与事实一致"。

## 26. "探测失败"不等于"结果是 0"

补第 23 条的观测侧后果。

死信队列深度的探测会失败（broker 不可用、连接超时）。
一个自然的写法是：

```java
try {
    int count = channel.queueDeclarePassive(queue).getMessageCount();
    deadLetterDepth.set(count);
} catch (Exception ex) {
    deadLetterDepth.set(0);      // ← 错
}
```

`0` 的含义是"**确定没有死信**"，而探测失败的含义是"**不知道**"。
把"不知道"报成"确定没有"，会让告警在真正出问题时反而不响——
监控在最需要它的时候变成了瞎子。

正确做法是**保留上次的值**：

```java
} catch (Exception ex) {
    // 保留旧值：探测失败不等于队列空了
    log.debug("探测死信队列深度失败（保留上次值 {}）: {}", deadLetterDepth.get(), ex.getMessage());
}
```

同一个道理适用于所有"外部状态"指标：租约超时的计数、队列深度、
端到端延迟的样本窗口。**指标要么给出事实，要么明确说"不知道"，
不能用一个看起来正常的数字掩盖"没测到"这件事。**

**教训**：监控系统里最危险的值不是异常值，而是**看起来正常的错值**。
"0 个死信"在告警规则里读作"一切正常"，而它的真实含义可能是"我连不上 broker"。

## 27. 消费计数必须由消费者上报 —— 后端数据库看不到消费结局

补第 25 条的业务侧原因。

一个自然的想法是："后端已经有 Outbox 和 Inbox 两张表，
统计一下不就知道消费了多少吗？"

**不成立**。逐个场景看：

| 消息结局 | `outbox_events` | `inbox_events` | 能从数据库看出来吗 |
| --- | --- | --- | --- |
| 消费成功 | PUBLISHED | 有记录 | 能 |
| 消息投递了但没人消费 | PUBLISHED | 无记录 | **不能**（与"进 DLQ"完全一样） |
| 消费失败进 DLQ | PUBLISHED | 通常无记录 | **不能**（与"没人消费"完全一样） |
| 消息还没发布 | PENDING | 无记录 | 能（但那是发布侧） |

也就是说，"进 DLQ"和"消息躺在队列里没人管"在数据库里**完全同形**。
两者的处置却相反：前者要重放，后者要查消费者为什么没启动。

所以消费侧的精确计数只能由**消费者进程**上报
（`POST /internal/v1/messaging/consumer-stats`）。
后端侧能观测到的外部信号只有队列深度与 DLQ 深度。

**上报的是增量不是累计值**：累计值在消费者进程重启后会归零，
后端累加会让计数**倒退**。一个会倒退的计数器比没有计数器更危险，
因为它看起来是对的。

**教训**：设计观测指标时，先问"这个事实的**真值在哪里**"。
真值在消费者进程里，就只能由消费者上报；数据库里的推论只是猜测，
而猜测长得像事实时比没有数据更危险。

## 28. "我的行一定在第一批里" —— 批量扫描造成的测试假失败

第 20 条讲的是"取第一条"的耦合，本条是它的**批量版本**。

现象：

```
RabbitTopologyIT.publishesToRealBroker
  expected: PUBLISHED
   but was: PENDING
RabbitTopologyIT.unroutableEventStaysPending
  expected: 1
   but was: 0
```

两个用例单独跑都过，只有在全量 `verify` 里失败；而且**上一轮 verify 里它们还是绿的**。

### 根因

`scanOnce()` 扫的是**整张表**：

```sql
SELECT id FROM outbox_events
 WHERE status = 'PENDING' AND retry_count < #{maxAttempts}
   AND ((next_retry_at IS NULL AND lease_until IS NULL) OR ...)
 ORDER BY created_at ASC, id ASC
 LIMIT #{limit}          -- 默认 50
```

用例先插入自己的一行，再调 `scanOnce()`，然后断言**那一行**变成了 PUBLISHED。
当前提成立时（表里 PENDING 少于 50 条）没问题。一旦其他测试类留下超过 50 条
**更早**的 PENDING 行，这一批只会领取那 50 条最老的，本用例的行排在第 51 位之后：

* `scanOnce() > 0` → **断言通过**（确实发布了别的行）
* 自己那行仍是 PENDING → **下一个断言失败**

于是失败信息完全指错方向：看起来像"发布器没把事件标成 PUBLISHED"，
实际是"发布器压根没看到这条事件"。

### 为什么这不是产品缺陷

批次饥饿在真实环境里是**合法状态**：积压超过一批就该分多轮处理，
一轮只看 50 条是设计目标，不是 bug。要改的是测试的前提，不是产品的行为。

### 修法

用例显式声明"本轮我只看自己这条"：

```java
private void quarantineOtherPendingEvents(String keepId) {
    jdbc.update("UPDATE outbox_events SET next_retry_at = DATE_ADD(NOW(3), INTERVAL 1 DAY)"
            + " WHERE status = 'PENDING' AND id <> ?", keepId);
}
```

推 `next_retry_at` 而不是删别人的行：延迟可逆，删除会让其他用例的数据凭空消失——
那才是真正查不出来的干扰。

验证方式也换了：**先造 60 条更早的 PENDING 积压**（超过批上限），再跑这个类。
不造积压就跑，"修好了"和"没复现"看起来一模一样。

### 教训

* 任何"全局批量扫描 + 断言我这一行"的用例，都在隐式依赖**整张表的状态**。
  写的时候要问一句："如果表里已有 10 万行，这一轮还轮得到我吗？"
* 这类缺陷的特征是**跨轮次不稳定**：同一份代码，上一轮全绿、这一轮两红，
  而且单跑必过。不要第一反应去怀疑"是不是 flaky"，先找**共享的可变状态**。
* 修测试的前提时要**制造能复现的前提**再验证。没有复现条件就宣布修好，
  等于把同一个坑留给下一次全量跑。
