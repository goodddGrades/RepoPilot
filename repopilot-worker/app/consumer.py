"""RabbitMQ 消费者：把消息当作"该干活了"的唤醒信号（TASK-013）。

设计核心：**消息不是任务，任务在后端数据库里。**

这条决定决定了整个模块的形状，必须先说清楚：

* ``run.ready`` 消息里只有一个 ``runId``。它**不携带**能力声明、租约令牌、
  超时配置、轨迹读取契约——真正的任务输入只有 ``/internal/v1/work/claim``能给。
* 所以消费消息之后**不能**按消息内容直接执行，而是用消息去触发一次
  ``claim``：谁抢到任务、给什么租约、能领几个，全由后端按能力竞争决定。
* 这带来三个直接好处：
  1. **不可能双重执行**。同一任务永远只被一个 claim 拿到，
     即便同一条消息被投递两次、或消息与轮询同时触发。
  2. **不需要信任消息**。消息里的 runId 可能已过期、已取消、已分配给别人；
     claim 会按状态机过滤，Worker 无需自己判断。
  3. **降级天然可用**。消息丢了只是慢一个轮询周期，任务不会被"卡住"。

**ACK 时机**：Inbox 登记成功、**且唤醒已交给执行线程**之后才 ACK。
ACK 表达的是"这条唤醒已经被可靠接住"，**不是**"任务已经执行完"：

* 任务本身在数据库里，由 claim + 租约决定谁执行；消息只是触发器。
* 登记失败（后端不可用）→ 不 ACK，按可重试失败进退避队列。
* 执行线程已停止 → 交接失败 → 不 ACK，连接关闭后由 broker 重投，不会静默丢唤醒。
* 极端情况：ACK 之后进程被强杀，这次唤醒没被执行——任务仍是 QUEUED，
  ``WORKER_AUTOSTART=true``（默认）时由轮询兜底领走。纯消息模式下这个窗口存在，
  属于已知限制（见 docs/modules/messaging-reliability.md §11）。

**长任务不占用消费者**：``on_wakeup`` 只做"唤醒计数 +1 并置位 Event"就返回，
真正的 Agent Run 由 ``runtime`` 里**唯一的执行线程**串行跑。
因此 ``prefetch=1`` 也不会让一个跑十分钟的任务把连接堵住——
消息在回调里几百微秒就处理完（登记 + 记账 + ACK），后续消息与再发布队列照常消费。

**Inbox 登记在 claim 之前**：``POST /internal/v1/notifications/accept``。
这一步不是"多余的一次 HTTP"——它承担三件事：
  1. **去重**：``(consumer_name, event_id)`` 唯一键让重复投递没有业务效果；
  2. **版本与结构校验**：后端是契约的唯一权威，Worker 自查一遍会漂移；
  3. **异内容检测**：同一个 eventId 出现不同载荷是**数据损坏**，
     必须隔离告警，而不是当正常重复静默 ACK。
跳过它，Inbox 表在消息通道上形同虚设，"重放不会产生重复副作用"也无从证明。

**失败分类与退避**（TASK-013-C）：
  * **不可重试**（契约错误：非法 JSON、缺字段、未知版本）→ 直接进 DLQ。
    重投一万次结果一样，继续重试只是让坏消息在队列里空转。
  * **可重试**（基础设施错误：后端暂时不可用、依赖抖动）→ 进入<b>有上限的
    指数退避</b>：按 ``retry.tN`` 路由键投递到对应 TTL 的等待队列，
    TTL 到期后进入再发布队列，由本消费者复原routing key 后重新投递回业务队列。
    退避档位用尽后进 DLQ。
    退避靠**带 TTL 的等待队列**而不是 ``basic_nack(requeue=true)``：
    requeue 会把消息立刻放回队首形成热循环，把 CPU 和 broker 一起打满。

**为什么退避用 topic 而不是 headers**（实测结论，2026-11）：
第一版用 ``headers`` 交换机 + ``x-match=all, x-retry-tier=N`` 绑定分档，
在真实 RabbitMQ 3.13.7 上**分档完全失效**——``x-match=all`` 会**忽略
``x-`` 前缀的绑定字段**，于是 ``x-retry-tier`` 从未参与匹配，
所有消息都进了每一个档位队列。后果是"3 档指数退避"退化为
"全部按第一档 5 秒并发回流"，2/3 档形同虚设。
Java 客户端与 pika、默认 vhost 与新建 vhost 均复现，
``rabbitmqctl list_bindings`` 确认 binding 参数已正确登记。
详见 ``docs/reviews/task-013-amqp-facts.md``。

**为什么多一层"再发布"队列**：topic 方案下退避消息的 routing key 是
``retry.tN`` 这样的合成键，而 TTL 到期的死信**沿用的正是这个合成键**，
业务交换机上没有它的 binding，消息会被静默丢弃。
两个选择：
  a) 按事件类型展开队列（3 档 × 4 事件类型 = 12 个），用
     ``x-dead-letter-routing-key`` 固定回流目标——队列数随事件类型线性增长；
  b) **本实现采用**：等待队列 TTL 到期后统一死信到再发布交换机，
     由消费者读 ``x-original-routing-key`` 头复原 routing key 后重新投递。
     等待队列恒为 3 个，与事件类型数量无关。
代价是多一次 broker 转发与一次本地处理，换来队列数不随事件类型膨胀。
"""

from __future__ import annotations

import json
import threading
from dataclasses import dataclass, field
from typing import Any, Callable

from app.common.logging import get_logger

log = get_logger("app.consumer")

# 与 Java 侧 RabbitTopology 保持一致的常量。
# 这里的重复定义是刻意的：Worker 是独立进程，不能依赖 Java 侧的类。
# 两侧的测试都断言这些值一致，改动时必须同步。
EVENTS_EXCHANGE = "repopilot.events"
RETRY_EXCHANGE = "repopilot.events.retry.v2"
REPUBLISH_EXCHANGE = "repopilot.events.republish"
DLX_EXCHANGE = "repopilot.events.dlx"

# 退避档位数：超过则进 DLQ
RETRY_TIER_COUNT = 3

# 重试 routing key 前缀：retry.t1 / retry.t2 / retry.t3
RETRY_KEY_PREFIX = "retry.t"

# 再发布 routing key：等待队列 TTL 到期后统一用这个键死信到再发布交换机
RK_REPUBLISH = "retry.republish"

# 再发布队列：TTL 到期后的消息落在这里，等消费者复原 routing key 后重新投递。
REPUBLISH_QUEUE = "repopilot.retry.republish"

# 消息头
HEADER_RETRY_TIER = "x-retry-tier"
HEADER_ORIGINAL_ROUTING_KEY = "x-original-routing-key"
HEADER_FIRST_FAILED_AT = "x-first-failed-at"
HEADER_LAST_ERROR = "x-last-error"

# routing key 拿不到时的兜底值。
#
# **只有 method.routing_key 不可用时才会走到这里**（例如自定义投递通道）。
# 正常路径必须用 method.routing_key：兜底值会把评测消息投进执行队列，
# 消息不丢但被错误的消费者处理。
DEFAULT_ROUTING_KEY = "run.ready"


def retry_routing_key(tier: int) -> str:
    """该档位对应的重试 routing key。"""
    return f"{RETRY_KEY_PREFIX}{tier}"


def is_valid_retry_tier(tier: int) -> bool:
    """档位是否在 1..RETRY_TIER_COUNT 范围内。

    ``retry.t0``（等于首次投递，没有退避语义）与 ``retry.t4``（超出档位数）
    都**不该**被投递：前者让失败立刻回流形成热循环，
    后者没有对应队列会被静默丢弃。
    """
    return 1 <= tier <= RETRY_TIER_COUNT


class ConsumerError(Exception):
    """消费失败。

    ``retryable`` 决定消息去向：可重试的进入退避重试，
    不可重试的进入死信队列。区分两者是为了避免"契约错误被无限重投"——
    一条 schema 不认识的消息，重投一万次也不会变合法。
    """

    def __init__(self, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.retryable = retryable


@dataclass
class ConsumeSummary:
    """一轮消费的结果，供健康接口与测试断言。

    计数不变式：``delivered == acked + retried + dead_lettered + reject_failed``。
    少计任何一项都会让"已处理"对不上账，看起来像还有消息卡在队列里。
    """

    delivered: int = 0
    processed: int = 0
    acked: int = 0
    retried: int = 0
    dead_lettered: int = 0
    # 交给执行线程的唤醒次数。
    #
    # **不是"领到几个任务"**：领取发生在执行线程里，消息通道看不到，
    # 也不该看到——在回调里 claim 会给任务一个租约，排队等待期间就会过期。
    wakeups: int = 0
    duplicates: int = 0
    # 退避到期的消息被重新投递回业务交换机的次数。
    # 单列而不是并入 acked：它的入队来源是再发布队列而不是业务队列，
    # 混在一起会让人误以为"业务队列里有很多消息在被反复处理"。
    republished: int = 0
    # 拒绝动作本身失败（连接已断、签名不匹配等）。消息停留在未确认状态，
    # 连接关闭后由 broker 重投。不单独计数的话，它会伪装成"已处理"。
    reject_failed: int = 0
    errors: list[str] = field(default_factory=list)

    def as_dict(self) -> dict[str, Any]:
        return {
            "delivered": self.delivered,
            "processed": self.processed,
            "acked": self.acked,
            "retried": self.retried,
            "deadLettered": self.dead_lettered,
            "wakeups": self.wakeups,
            "duplicates": self.duplicates,
            "republished": self.republished,
            "rejectFailed": self.reject_failed,
            "errorCount": len(self.errors),
            "errors": self.errors[:10],
        }


def decode_envelope(body: bytes) -> dict[str, Any]:
    """把消息体解析成事件信封。

    解析失败算**不可重试**：同一段字节重投一万次结果一样。
    这类消息必须进死信队列让人来看，而不是在队列里空转。
    """
    try:
        envelope = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ConsumerError(f"消息体不是合法 JSON: {exc}", retryable=False) from exc
    if not isinstance(envelope, dict):
        raise ConsumerError("消息体不是 JSON 对象", retryable=False)
    return envelope


def validate_envelope(envelope: dict[str, Any]) -> None:
    """按事件契约校验信封的**结构性**字段。

    刻意不校验业务内容（runId 是否真的存在、任务是否已完成）：
    那是后端状态机的事，在这里重做一遍只会产生第二套真相。
    """
    required = ("schemaVersion", "eventId", "eventType", "aggregateId")
    missing = [field for field in required if not envelope.get(field)]
    if missing:
        raise ConsumerError(f"信封缺少必填字段: {', '.join(missing)}", retryable=False)
    # schemaVersion 未知 = 生产者升级了而本Worker 没跟上，是部署问题不是数据问题；
    # 但重投当前进程也不会变好，所以仍按不可重试处理，由人决定是否升级 Worker。
    if envelope["schemaVersion"] != 1:
        raise ConsumerError(
            f"不支持的信封版本 {envelope['schemaVersion']}，本 Worker 只支持 1",
            retryable=False)


def retry_tier_of(properties: Any) -> int:
    """读出消息当前处于第几档退避。

    首次投递没有 ``x-retry-tier`` 头，视为 0。
    """
    headers = getattr(properties, "headers", None) or {}
    raw = headers.get(HEADER_RETRY_TIER)
    if raw is None:
        return 0
    try:
        return int(raw)
    except (TypeError, ValueError):
        return 0


class RabbitConsumer:
    """在后台线程里消费 RabbitMQ 队列，每条消息触发一次领取。

    依赖 ``pika``。该库是同步的，所以放在独立线程里跑；
    消息处理（``on_message``）本身是同步的，符合pika 的用法。
    """

    def __init__(self, settings: Any, on_wakeup: Callable[[], Any],
                 register: Callable[[dict[str, Any]], Any] | None = None,
                 report_stats: Callable[[dict[str, int]], Any] | None = None) -> None:
        self.settings = settings
        self.on_wakeup = on_wakeup
        # Inbox 登记回调。默认 None 时跳过登记（仅用于不连后端的纯协议测试）。
        self.register = register
        # 消费统计上报回调。默认 None 时不上报（仅用于纯协议测试）。
        # 上报失败绝不冒泡：统计是观测手段，不该把业务处理带崩。
        self.report_stats = report_stats
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self.last_summary: ConsumeSummary | None = None
        self.connected = False
        self._lock = threading.Lock()
        # 累计计数：last_summary 只描述最近一轮，重启与多轮之间无法对账。
        # 累计值存在这里，每轮结束时与后一轮的差值上报给后端。
        self._totals = ConsumeSummary()
        # 已成功上报的累计值：用来算增量。
        # 上报失败时**不推进**它，于是下一轮会把同一批计数再报一次——
        # 宁可后端多计一次（幂等性由"这是计数不是状态"消化），
        # 也不要少计（少计会让"处理了多少"对不上账）。
        self._reported: dict[str, int] = {
            "delivered": 0, "processed": 0, "acked": 0, "retried": 0,
            "deadLettered": 0, "wakeups": 0, "duplicates": 0,
            "republished": 0, "rejectFailed": 0,
        }

    # ------------------------------------------------------------------ 生命周期

    def start(self) -> None:
        if self._thread is not None:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="rabbit-consumer", daemon=True)
        self._thread.start()
        log.info(
            "RabbitMQ 消费者已启动 queue=%s prefetch=%d",
            self.settings.rabbitmq_queue,
            self.settings.rabbitmq_prefetch,
        )

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=10)
            self._thread = None

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def status(self) -> dict[str, Any]:
        summary = self.last_summary.as_dict() if self.last_summary else None
        with self._lock:
            totals = self._totals.as_dict()
        return {
            "enabled": self.settings.rabbitmq_enabled,
            "connected": self.connected,
            "running": self.running,
            "queue": self.settings.rabbitmq_queue,
            "prefetch": self.settings.rabbitmq_prefetch,
            "lastSummary": summary,
            # 累计值：lastSummary 只描述最近一轮，多轮之间无法对账。
            # 排障时要问的是"这个进程一共处理了多少"，不是"上一轮处理了多少"。
            "totals": totals,
        }

    # ------------------------------------------------------------------ 主循环

    def _loop(self) -> None:
        """连接、订阅、消费，断线后退避重连。

        退避是必需的：broker 宕机期间如果立刻重连，会打空 CPU 并在broker
        恢复的瞬间被大量连接打垮。1~30 秒的指数退避 + 抖动让重连变成温和探测。
        """
        attempt = 0
        while not self._stop.is_set():
            try:
                self._consume_once()
                attempt = 0
            except Exception as exc:  # noqa: BLE001 - 连接层任何异常都要能自愈
                self.connected = False
                if self._stop.is_set():
                    return
                attempt += 1
                delay = min(2 ** min(attempt, 5), 30)
                log.warning("RabbitMQ 消费中断（第 %d 次），%.0fs 后重连: %s", attempt, delay, exc)
                if self._stop.wait(delay):
                    return

    def _consume_once(self) -> None:
        import pika  # 延迟导入：未启用 RabbitMQ 时不必安装该依赖

        params = pika.ConnectionParameters(
            host=self.settings.rabbitmq_host,
            port=self.settings.rabbitmq_port,
            credentials=pika.PlainCredentials(
                self.settings.rabbitmq_user, self.settings.rabbitmq_password
            ),
            virtual_host=self.settings.rabbitmq_vhost,
            heartbeat=int(self.settings.rabbitmq_heartbeat),
            blocked_connection_timeout=30,
            # 连接风暴时优先复用而不是新建，防止 broker 被自己的重连打垮
            connection_attempts=1,
            retry_delay=0,
        )
        connection = pika.BlockingConnection(params)
        try:
            channel = connection.channel()
            # prefetch=1：未处理的消息留在 broker，进程崩溃后自动回到队列。
            # prefetch 拉高只会让消息在客户端排队，断连时一起丢失。
            channel.basic_qos(prefetch_count=self.settings.rabbitmq_prefetch)
            # **消费 channel 必须开 confirm 模式**（实测依据见
            # docs/reviews/task-013-amqp-facts.md）：
            # pika 只有在 self._delivery_confirmation 为真时才检查 mandatory 与 NACK。
            # 不开confirm 的话，下面 _publish_to_retry() 里的
            # basic_publish(mandatory=True) 到没有 binding 的重试交换机会
            # **静默丢弃**，而原消息已经被我们 ACK —— 消息彻底消失，
            # 既没有进重试队列，也没有进死信队列，日志里一切正常。
            #
            # 回调里做 confirm 发布是安全的：confirm 模式的 basic_publish 只
            # poll ioloop 与处理超时，不派发 channel 事件，
            # 而嵌套的 channel 事件派发被 pika 的 _event_dispatch_suspend_depth 抑制。
            channel.confirm_delivery()
            channel.basic_consume(
                queue=self.settings.rabbitmq_queue,
                on_message_callback=self._on_message,
                auto_ack=False,
            )
            # 再发布队列：退避到期的消息落在这里，由本消费者复原 routing key
            # 后重新投递回业务交换机。必须订阅，否则退避链路在最后一步断裂——
            # 消息会一直堆在这里，既不重投也不进死信，看起来像"退避卡住了"。
            channel.basic_consume(
                queue=REPUBLISH_QUEUE,
                on_message_callback=self._on_republish,
                auto_ack=False,
            )
            self.connected = True
            log.info("已订阅队列 %s 与再发布队列 %s（manual ack, prefetch=%d）",
                     self.settings.rabbitmq_queue, REPUBLISH_QUEUE,
                     self.settings.rabbitmq_prefetch)
            while not self._stop.is_set():
                # timeout 让 stop() 能及时生效：没有它，pika 会一直阻塞在 IO 上
                connection.process_data_events(time_limit=1.0)
        finally:
            self.connected = False
            try:
                connection.close()
            except Exception:  # noqa: BLE001 - 关闭失败无需再处理
                pass

    # ------------------------------------------------------------------ 单条消息

    def _on_message(self, channel: Any, method: Any, properties: Any, body: bytes) -> None:
        summary = ConsumeSummary(delivered=1)
        try:
            self._handle(channel, method, properties, body, summary)
        finally:
            with self._lock:
                self.last_summary = summary
                self._accumulate(summary)
            self._flush_stats()

    def _on_republish(self, channel: Any, method: Any, properties: Any,
                      body: bytes) -> None:
        """再发布队列的回调入口。

        **不经过 Inbox 登记**：这条消息的业务内容在第一次投递时已经登记过了。
        再登记一次会命中 DUPLICATE，白白多一次 HTTP——
        而更重要的是，若此时后端不可用，重试链路会在"消息其实没问题"的地方断掉。
        """
        summary = ConsumeSummary(delivered=1)
        try:
            self.handle_republish(channel, method, properties, body, summary)
        except Exception as exc:  # noqa: BLE001 - 单条消息不能拖垮整个消费者
            log.exception("再发布处理异常")
            summary.errors.append(f"再发布异常: {exc}")
            # 不确认：消息留在再发布队列，连接关闭后由 broker 重投
            try:
                channel.basic_nack(method.delivery_tag, False, True)
                summary.reject_failed += 1
            except Exception:  # noqa: BLE001
                summary.reject_failed += 1
        finally:
            with self._lock:
                self.last_summary = summary
                self._accumulate(summary)
            self._flush_stats()

    def _accumulate(self, summary: ConsumeSummary) -> None:
        """把本轮计数累加到累计值里（调用方持有锁）。"""
        totals = self._totals
        totals.delivered += summary.delivered
        totals.processed += summary.processed
        totals.acked += summary.acked
        totals.retried += summary.retried
        totals.dead_lettered += summary.dead_lettered
        totals.wakeups += summary.wakeups
        totals.duplicates += summary.duplicates
        totals.republished += summary.republished
        totals.reject_failed += summary.reject_failed

    def _flush_stats(self) -> None:
        """把累计计数的变化量上报给后端。

        **上报的是增量而不是累计值**：后端把多次上报累加。
        传累计值的话，进程重启会让后端计数倒退——
        一个会倒退的计数器比没有计数器更危险，因为它看起来是对的。

        **失败绝不冒泡**：统计上报失败只记日志。观测手段坏了
        不该让业务处理跟着坏。
        """
        if self.report_stats is None:
            return
        with self._lock:
            totals = self._totals
            delta = {
                "delivered": totals.delivered - self._reported["delivered"],
                "processed": totals.processed - self._reported["processed"],
                "acked": totals.acked - self._reported["acked"],
                "retried": totals.retried - self._reported["retried"],
                "deadLettered": totals.dead_lettered - self._reported["deadLettered"],
                "wakeups": totals.wakeups - self._reported["wakeups"],
                "duplicates": totals.duplicates - self._reported["duplicates"],
                "republished": totals.republished - self._reported["republished"],
                "rejectFailed": totals.reject_failed - self._reported["rejectFailed"],
            }
            if not any(delta.values()):
                return
            try:
                self.report_stats(delta)
            except Exception as exc:  # noqa: BLE001 - 上报失败不影响业务
                log.warning("消费统计上报失败（不影响消费）: %s", exc)
                return
            for key, value in delta.items():
                self._reported[key] += value

    def _handle(self, channel: Any, method: Any, properties: Any, body: bytes,
                summary: ConsumeSummary) -> None:
        """处理单条消息并决定 ACK / 退避重试 / 死信。"""
        try:
            envelope = decode_envelope(body)
            validate_envelope(envelope)
            summary.processed += 1
            log.info("收到事件 %s (%s)", envelope["eventType"], envelope["eventId"])

            # 第一步：Inbox 登记（去重 + 契约校验 + 异内容检测）。
            # 这一步失败说明后端不可用，属于可重试的基础设施错误。
            if self.register is not None:
                outcome = self.register(envelope)
                if outcome == "DUPLICATE":
                    # 重复投递：正常现象，消息已经处理过。
                    # 仍然要触发一次唤醒——重复不代表上一轮的唤醒真的生效了
                    # （可能是 ACK 前崩溃），而 claim 是幂等的、最坏返回空。
                    summary.duplicates += 1
                    log.info("事件 {} 命中 Inbox 去重，仍触发一次唤醒", envelope["eventId"])

            # 第二步：把"有活干了"这件事交给执行线程，立即返回。
            #
            # **这里刻意不执行任务、也不 claim**：
            #   * 执行 Agent Run 可能几十分钟，放在回调里会把 AMQP 连接堵死，
            #     后续消息与再发布队列都停摆；
            #   * claim 会给任务一个租约，领完排在执行线程队列里等待，
            #     等轮到它时租约可能已过期，任务被别的 Worker 领走。
            # 交接失败（执行线程已停）按可重试处理，消息不 ACK。
            self.on_wakeup()
            summary.wakeups += 1
            channel.basic_ack(method.delivery_tag)
            summary.acked += 1
        except ConsumerError as exc:
            self._reject(channel, method, properties, body, summary, exc.retryable, str(exc))
        except Exception as exc:  # noqa: BLE001 - 单条消息不能拖垮整个消费者
            log.exception("处理消息异常")
            self._reject(channel, method, properties, body, summary, True, f"消费异常: {exc}")

    def _reject(self, channel: Any, method: Any, properties: Any, body: bytes,
                summary: ConsumeSummary, retryable: bool, reason: str) -> None:
        """按可重试性决定进入退避重试还是死信。

        可重试的失败**主动重新发布**到重试交换机，routing key 用
        ``retry.tN``（N = 当前档位 + 1），由带 TTL 的等待队列延迟后回���。
        不可重试的（或退避档位已用尽）则 ``basic_reject(requeue=False)``，
        由业务队列的 DLX 把它送进死信队列。

        **routing key 必须来自 ``method.routing_key``（``spec.Basic.Deliver``），
        不在 ``properties`` 上**——pika 的 ``BasicProperties`` 里没有这个字段。
        从 properties 取会恒为 None，于是评测队列（``evaluation.ready``）的消息
        会带着兜底值 ``run.ready`` 退避，重放后**进错队列**。
        这是真实 broker 测试抓出来的缺陷：消息不丢，但被投递给错误的消费者。
        """
        summary.errors.append(reason)
        tier = retry_tier_of(properties)
        routing_key = getattr(method, "routing_key", None) or DEFAULT_ROUTING_KEY
        next_tier = tier + 1

        if retryable and is_valid_retry_tier(next_tier):
            try:
                self._publish_to_retry(channel, properties, routing_key, body,
                                       next_tier, reason)
                # 重新发布成功后才能 ACK 原消息：顺序反了会在
                # "发布成功但 ACK 前崩溃"时丢消息（重试副本没有，原件被确认掉了）。
                channel.basic_ack(method.delivery_tag)
                # 原件确实已被确认，必须计入 acked：否则 acked 只统计正常路径，
                # 而运维的算式是 acked + retried + dead_lettered == delivered，
                # 少计会让"已处理"对不上账，看起来像还有消息卡在队列里。
                summary.acked += 1
                summary.retried += 1
                return
            except Exception:  # noqa: BLE001 - 重试发布失败，退回死信路径
                log.exception("投递到退避重试队列失败，改为直接进入死信")
                summary.reject_failed += 1

        try:
            channel.basic_reject(method.delivery_tag, False)
            summary.dead_lettered += 1
            log.error("消息进入死信队列（tier=%d）: %s", tier, reason)
        except Exception:  # noqa: BLE001 - 连接已断时拒绝也会失败
            # 拒绝失败意味着消息停留在未确认状态：连接还在时 broker 会在连接关闭后
            # 自动重投。所以这里**不能**当作已处理——把错误显式记进 summary，
            # 让运维与测试都能看见，而不是"看起来处理完了"。
            summary.reject_failed += 1
            log.exception("拒绝消息失败（连接可能已断开，消息将由 broker 重投）")

    def _publish_to_retry(self, channel: Any, properties: Any, routing_key: str, body: bytes,
                          next_tier: int, reason: str) -> None:
        """把消息重新发布到退避重试交换机（topic）。

        **routing key 换成合成键 ``retry.tN``**：等待队列要按档位分流，
        topic 交换机靠 routing key 匹配，而``x-match=all`` 的headers 方案
        在真实 broker 上会忽略 ``x-`` 前缀字段导致分档失效（实测）。

        **原 routing key 存进 ``x-original-routing-key`` 头**：
        TTL 到期后消息不会直接回业务交换机（那时 routing key 是``retry.tN``，
        业务交换机没有它的 binding，消息会被静默丢弃），
        而是先进再发布队列，由消费者读这个头复原 routing key 后重新投递。

        ``x-retry-tier`` 头**只用于诊断**，不再参与路由——
        让"头"和"实际进了哪个队列"可以互相印证，而不是互为因果。
        """
        import pika

        headers = dict(getattr(properties, "headers", None) or {})
        headers[HEADER_RETRY_TIER] = next_tier
        # 原 routing key 必须带上：再发布阶段靠它回到正确的业务队列
        headers[HEADER_ORIGINAL_ROUTING_KEY] = routing_key
        headers[HEADER_FIRST_FAILED_AT] = headers.get(
            HEADER_FIRST_FAILED_AT) or _now_iso()
        # 失败原因截断：信封与错误信息都可能很长，而头部有大小限制
        headers[HEADER_LAST_ERROR] = reason[:200]

        new_properties = pika.BasicProperties(
            content_type=getattr(properties, "content_type", None) or "application/json",
            delivery_mode=2,
            message_id=getattr(properties, "message_id", None),
            correlation_id=getattr(properties, "correlation_id", None),
            headers=headers,
        )
        # confirm 模式下 basic_publish 会在这两种情况下抛异常：
        #   * UnroutableError：mandatory=True 但重试交换机上没有匹配的 binding
        #     （例如等待队列被误删、routing key 打错）
        #   * NackError：broker 明确拒绝
        # 两种都必须冒泡到调用方——_reject 会退回死信路径，
        # 于是消息还有最后一线生机，而不是"以为重试成功了"然后 ACK 掉原件。
        channel.basic_publish(
            RETRY_EXCHANGE, retry_routing_key(next_tier),
            body=body, properties=new_properties, mandatory=True)

    # ------------------------------------------------------------------ 再发布

    def handle_republish(self, channel: Any, method: Any, properties: Any, body: bytes,
                         summary: ConsumeSummary) -> None:
        """把退避到期的消息重新投递回业务交换机。

        这一步是 topic 退避方案能成立的最后一环：
        等待队列 TTL 到期时 routing key 是 ``retry.republish``（死信改写的结果），
        原业务 routing key 只存在于 ``x-original-routing-key`` 头里，
        所以必须由消费者读出头、重新发布到业务交换机、再 ACK。

        **必须走 confirm**：确认成功才 ACK。顺序反了会在
        "发布成功但 ACK 前崩溃"时丢消息——再发布队列里没有副本了，
        业务交换机上也没有，而消息从未真正回到业务队列。

        **缺少原 routing key 头时不能猜**：宁可进死信让人来看，
        也不能随便挑一个 routing key——投给错误的消费者比不投更糟。
        """
        headers = getattr(properties, "headers", None) or {}
        original = headers.get(HEADER_ORIGINAL_ROUTING_KEY)
        tier = retry_tier_of(properties)

        if not original or not isinstance(original, str) or not original.strip():
            reason = ("消息缺少 x-original-routing-key 头，无法复原业务 routing key；"
                      "猜测 routing key 会把消息投给错误的消费者")
            log.error("再发布失败（tier=%d）: %s", tier, reason)
            summary.errors.append(reason)
            self._reject(channel, method, properties, body, summary, False, reason)
            return

        import pika

        new_properties = pika.BasicProperties(
            content_type=getattr(properties, "content_type", None) or "application/json",
            delivery_mode=2,
            message_id=getattr(properties, "message_id", None),
            correlation_id=getattr(properties, "correlation_id", None),
            headers=dict(headers),
        )
        try:
            channel.basic_publish(
                EVENTS_EXCHANGE, original, body=body,
                properties=new_properties, mandatory=True)
        except Exception as exc:  # noqa: BLE001
            # 发布失败**不能 ACK**：消息必须留在再发布队列里等下次。
            # 不确认就断开连接，broker 会自动重投，消息不会丢。
            reason = f"再发布到业务交换机失败: {exc}"
            log.error("再发布失败（routing_key=%s, tier=%d）: %s", original, tier, exc)
            summary.errors.append(reason)
            summary.reject_failed += 1
            try:
                channel.basic_nack(method.delivery_tag, False, True)
            except Exception:  # noqa: BLE001 - 连接已断时由 broker 重投
                summary.reject_failed += 1
            return

        channel.basic_ack(method.delivery_tag)
        summary.republished += 1
        log.info("退避到期消息已重新投递 routing_key=%s tier=%d", original, tier)

    # ------------------------------------------------------------------ 运维

    def drain_once(self, timeout: float = 5.0) -> dict[str, Any]:
        """手动跑一轮消费，供演示脚本与集成测试用。

        真实消费由后台线程完成；这个方法用于"启动一个连接、处理一条、再退出"，
        这样测试不需要跟后台线程抢时间。
        """
        import pika

        params = pika.ConnectionParameters(
            host=self.settings.rabbitmq_host,
            port=self.settings.rabbitmq_port,
            credentials=pika.PlainCredentials(
                self.settings.rabbitmq_user, self.settings.rabbitmq_password
            ),
            virtual_host=self.settings.rabbitmq_vhost,
        )
        connection = pika.BlockingConnection(params)
        try:
            channel = connection.channel()
            channel.basic_qos(prefetch_count=self.settings.rabbitmq_prefetch)
            # 与后台消费者一致：confirm 模式让 mandatory 真正生效，
            # 否则不可路由的重试消息会静默丢失而原消息已被 ACK
            channel.confirm_delivery()
            method, properties, body = channel.basic_get(
                queue=self.settings.rabbitmq_queue, auto_ack=False
            )
            if method is None:
                return {**ConsumeSummary().as_dict(), "empty": True}
            summary = ConsumeSummary(delivered=1)

            class _Method:
                # routing key 必须一起带过去：_reject 靠它把消息投回
                # 正确的重试档位与原业务队列
                delivery_tag = method.delivery_tag
                routing_key = method.routing_key

            # 直接复用 _on_message 的处理逻辑，但不让它覆写 last_summary：
            # last_summary 描述的是**后台线程**的最近一轮，
            # 让一次手动 drain 把它覆盖掉会让人误以为后台在正常工作。
            self._handle(channel, _Method, properties, body, summary)
            with self._lock:
                self.last_summary = summary
                self._accumulate(summary)
            self._flush_stats()
            return {**summary.as_dict(), "empty": False}
        finally:
            try:
                connection.close()
            except Exception:  # noqa: BLE001
                pass

    def republish_once(self) -> dict[str, Any]:
        """手动跑一轮再发布（处理一条退避到期的消息）。

        与 {@link drain_once} 分开是有意的：两者的队列与语义都不同，
        混在一个方法里会让调用方需要自己传"要处理哪个队列"，
        于是"忘了传"会变成一种静默的错误。
        """
        import pika

        params = pika.ConnectionParameters(
            host=self.settings.rabbitmq_host,
            port=self.settings.rabbitmq_port,
            credentials=pika.PlainCredentials(
                self.settings.rabbitmq_user, self.settings.rabbitmq_password
            ),
            virtual_host=self.settings.rabbitmq_vhost,
        )
        connection = pika.BlockingConnection(params)
        try:
            channel = connection.channel()
            channel.confirm_delivery()
            method, properties, body = channel.basic_get(
                queue=REPUBLISH_QUEUE, auto_ack=False)
            if method is None:
                return {**ConsumeSummary().as_dict(), "empty": True}
            summary = ConsumeSummary(delivered=1)
            self.handle_republish(channel, method, properties, body, summary)
            with self._lock:
                self.last_summary = summary
                self._accumulate(summary)
            self._flush_stats()
            return {**summary.as_dict(), "empty": False}
        finally:
            try:
                connection.close()
            except Exception:  # noqa: BLE001
                pass


def _now_iso() -> str:
    from datetime import datetime, timezone

    return datetime.now(tz=timezone.utc).isoformat(timespec="milliseconds").replace(
        "+00:00", "Z")
