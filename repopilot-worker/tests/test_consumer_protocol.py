"""RabbitMQ 消费者的**协议决策**（不需要真实broker）。

测的是"给定一条消息体，决定成 ack / 重投 / 死信、以及是否触发领取"，
与网络细节分开。真实 broker 的行为由 test_consumer_rabbit.py 覆盖。

单独拆一个文件的原因：这一类**永远**可以跑（无外部依赖），
而真实 broker 那类必须在有 RabbitMQ 时才跑。混在一起会让"没有 broker"
这件事把协议逻辑的覆盖也一起带走。
"""

from __future__ import annotations

import json
from typing import Any

import pytest

from app.config import Settings
from app.consumer import (ConsumeSummary, ConsumerError, RabbitConsumer, decode_envelope,
                          validate_envelope)


def make_settings(**overrides: Any) -> Settings:
    base = {
        "rabbitmq_host": "127.0.0.1",
        "rabbitmq_port": 5672,
        "rabbitmq_user": "repopilot",
        "rabbitmq_password": "secret",
        "rabbitmq_queue": "repopilot.run.ready",
    }
    base.update(overrides)
    return Settings(**base)


def valid_envelope(**overrides: Any) -> bytes:
    envelope = {
        "schemaVersion": 1,
        "eventId": "01M5BZZZZZZZZZZZZZZZZZZZZZ",
        "eventType": "run.ready",
        "aggregateId": "01M5BZZZZZZZZZZZZZZZZZZZZZ",
        "aggregateType": "RUN",
        "occurredAt": "2026-10-20T02:00:00Z",
        "correlationId": "01M5BZZZZZZZZZZZZZZZZZZZZZ",
        "payload": {"runId": "01M5BZZZZZZZZZZZZZZZZZZZZZ"},
    }
    envelope.update(overrides)
    return json.dumps(envelope).encode()


class FakeChannel:
    """记录 ack / nack / reject / publish 调用的假 channel。

    <b>签名必须与 pika 的Channel 一致</b>：{@code basic_nack} 是<b>三个</b>参数
    {@code (delivery_tag, multiple, requeue)}，{@code basic_reject} 是两个
    {@code (delivery_tag, requeue)}。第一版把 basic_nack 写成两参数，
    于是"允许 requeue"那条路径调用时抛 TypeError、被 except 吞掉、
    测试断言失败——看起来像产品缺陷，其实是替身签名写错了。

    这也说明<b>替身与真实库签名不一致时，它验证不了真实路径</b>：
    requeue 分支在真实 broker 上从未被这个替身覆盖过。
    真实 broker 的对应验证在 test_consumer_rabbit.py。

    {@code publish_error} 用来模拟"重试发布失败"：真实场景下 confirm 模式下
    不可路由会抛 {@code UnroutableError}，NACK 会抛 {@code NackError}。
    替身必须能复现这个失败，否则"发布失败退回死信"这条路径无法被覆盖。
    """

    def __init__(self, publish_error: Exception | None = None) -> None:
        self.acked: list[int] = []
        self.nacked: list[tuple[int, bool]] = []
        self.rejected: list[tuple[int, bool]] = []
        self.published: list[tuple[str, str, bytes, dict[str, Any]]] = []
        self.publish_error = publish_error

    def basic_ack(self, tag: int) -> None:
        self.acked.append(tag)

    def basic_nack(self, tag: int, multiple: bool, requeue: bool) -> None:
        self.nacked.append((tag, requeue))

    def basic_reject(self, tag: int, requeue: bool) -> None:
        self.rejected.append((tag, requeue))

    def basic_publish(self, exchange: str, routing_key: str, *, body: bytes,
                      properties: Any = None, mandatory: bool = False) -> None:
        if self.publish_error is not None:
            raise self.publish_error
        headers = dict(getattr(properties, "headers", None) or {})
        self.published.append((exchange, routing_key, body, headers))


class FakeProperties:
    """带headers 的消息属性替身（重试档位从properties.headers 读）。"""

    def __init__(self, headers: dict[str, Any] | None = None, routing_key: str = "run.ready") -> None:
        self.headers = headers or {}
        self.routing_key = routing_key
        self.content_type = "application/json"
        self.message_id = "msg-1"
        self.correlation_id = None


class FakeMethod:
    def __init__(self, tag: int = 7) -> None:
        self.delivery_tag = tag


def handle(consumer: RabbitConsumer, body: bytes, channel: FakeChannel,
           properties: FakeProperties | None = None) -> Any:
    from app.consumer import ConsumeSummary

    summary = ConsumeSummary(delivered=1)
    consumer._handle(channel, FakeMethod(), properties, body, summary)
    return summary


# ----------------------------------------------------------------- 解码


def test_decode_rejects_non_json_as_non_retryable():
    # 同一段字节重投一万次也不会变合法 ⇒ 必须判为不可重试，
    # 否则它会在队列里空转到死为止
    with pytest.raises(ConsumerError) as exc:
        decode_envelope(b"not json")
    assert exc.value.retryable is False


def test_decode_rejects_non_object_json():
    with pytest.raises(ConsumerError) as exc:
        decode_envelope(b"[1,2,3]")
    assert exc.value.retryable is False


def test_decode_accepts_valid_envelope():
    envelope = decode_envelope(valid_envelope())
    assert envelope["eventType"] == "run.ready"


# ----------------------------------------------------------------- 校验


def test_validate_requires_core_fields():
    with pytest.raises(ConsumerError) as exc:
        validate_envelope({"schemaVersion": 1, "eventType": "run.ready"})
    assert "eventId" in str(exc.value)
    assert "aggregateId" in str(exc.value)


def test_validate_rejects_unknown_schema_version_as_non_retryable():
    # 部署问题：重投当前进程也不会变好，只能靠升级 Worker
    with pytest.raises(ConsumerError) as exc:
        validate_envelope(json.loads(valid_envelope(schemaVersion=99)))
    assert exc.value.retryable is False
    assert "99" in str(exc.value)


def test_validate_accepts_v1():
    validate_envelope(json.loads(valid_envelope()))


# ----------------------------------------------------------------- 处理决策


def test_valid_message_is_acked_and_triggers_wakeup():
    calls: list[int] = []
    consumer = RabbitConsumer(make_settings(), lambda: calls.append(1))
    channel = FakeChannel()

    summary = handle(consumer, valid_envelope(), channel)

    assert channel.acked == [7]
    assert channel.rejected == []
    assert summary.acked == 1
    assert summary.wakeups == 1
    # 关键不变式：消息本身不携带执行能力，必须经 claim 才能干活
    assert len(calls) == 1


def test_malformed_message_goes_to_dead_letter_not_requeue():
    consumer = RabbitConsumer(make_settings(), lambda: None)
    channel = FakeChannel()

    summary = handle(consumer, b"garbage", channel)

    assert channel.acked == []
    assert channel.nacked == []
    assert channel.rejected == [(7, False)]
    assert summary.dead_lettered == 1
    assert summary.acked == 0


def test_duplicate_delivery_is_idempotent_via_claim():
    """同一条消息投两次：两次都 ACK，但只有真正领到任务的那次才有业务效果。

    这里无法验证"不重复执行"——那取决于后端 claim 的竞争语义。
    本测试锁定的是**消费者不会因为重复投递而报错进死信**，
    否则重复投递会把队列塞满死信。
    """
    consumer = RabbitConsumer(make_settings(), lambda: None)
    for _ in range(3):
        channel = FakeChannel()
        summary = handle(consumer, valid_envelope(), channel)
        assert channel.acked == [7]
        assert summary.acked == 1
        assert summary.dead_lettered == 0


def test_wakeup_failure_enters_bounded_backoff_not_hot_loop():
    """唤醒失败意味着"这次没干活"，必须重投而不是丢弃。

    反过来做的话：领取临时失败一次，任务就永远不会被唤醒，
    而它看起来一切正常（消息被 ACK 了、业务没发生）。

    **处置方式是"带 TTL 的退避重试"而不是 basic_nack(requeue=true)**：
    requeue 会把消息立刻放回队首并被立刻取出，在后端持续失败时形成热循环，
    把 CPU 和 broker 一起打满（实测：5 条消息循环 get+nack 会绕回队首反复命中同一条）。
    退避重试让消息**留在 broker 里等**，消费者可以立刻处理下一条。
    """
    def boom() -> dict[str, int]:
        raise RuntimeError("后端暂时不可用")

    consumer = RabbitConsumer(make_settings(), boom)
    channel = FakeChannel()

    summary = handle(consumer, valid_envelope(), channel)

    assert summary.acked == 1, "重试副本已确认投递，原消息才可以 ACK"
    assert summary.retried == 1
    assert summary.dead_lettered == 0
    # 绝不能 requeue：那是热循环的根源
    assert channel.nacked == [], "requeue=true 会形成取出→失败→立刻取回的热循环"
    assert channel.rejected == []
    # 重试消息进了重试交换机的第 1 档
    assert len(channel.published) == 1
    exchange, routing_key, body, headers = channel.published[0]
    assert exchange == "repopilot.events.retry.v2"
    # **routing key 是合成键 retry.t1**，原 routing key 放进header。
    # 这样 topic 交换机才能按档位分流（原方案用 headers 绑定分档，
    # 但 x-match=all 会忽略 x- 前缀字段，实测分档完全失效）。
    assert routing_key == "retry.t1", "退避路由必须用 retry.tN 合成键"
    assert headers["x-original-routing-key"] == "run.ready", \
        "原 routing key 必须放进头里，再发布阶段靠它回到正确的业务队列"
    assert headers["x-retry-tier"] == 1
    # 失败原因与首次失败时间被记录下来，供排查与 DLQ 摘要使用
    assert "后端暂时不可用" in headers["x-last-error"]
    assert headers["x-first-failed-at"]


def test_retry_uses_original_routing_key_from_method_not_properties():
    """原 routing key 必须取自 ``method.routing_key``，不是 ``properties``。

    pika 的 ``BasicProperties`` **没有** ``routing_key`` 字段——
    它在 ``spec.Basic.Deliver`` 上。从 properties 取会恒为 None，
    于是评测消息（``evaluation.ready``）会带着兜底值 ``run.ready`` 退避，
    重放后**进错队列**：消息不丢，但被错误的消费者处理。
    这是真实 broker 测试抓出来的缺陷。
    """
    def boom() -> dict[str, int]:
        raise RuntimeError("后端暂时不可用")

    consumer = RabbitConsumer(make_settings(), boom)
    channel = FakeChannel()

    # properties 刻意带上 routing_key：模拟"从错误位置取值"的实现
    summary = handle(consumer, valid_envelope(), channel,
                     FakeProperties(routing_key="WRONG.placeholder"))

    assert channel.published[0][3]["x-original-routing-key"] == "run.ready", \
        "必须用 method.routing_key，不能用 properties.routing_key"


def test_retry_tier_escalates_until_exhausted_then_dead_letters():
    """退避档位逐级递增，用尽后进死信队列——不能无限重投。

    这是"有上限的指数退避"的完整验证：
    档位 0→1→2→3 各进对应 TTL 的重试队列，第 4 次失败时tier 已用尽，
    必须进 DLQ。再往后就没有"第 5 档"了。
    """
    def boom() -> dict[str, int]:
        raise RuntimeError("持续失败")

    consumer = RabbitConsumer(make_settings(), boom)

    for expected_tier in (1, 2, 3):
        channel = FakeChannel()
        summary = handle(consumer, valid_envelope(), channel,
                         FakeProperties({"x-retry-tier": expected_tier - 1}))
        assert summary.retried == 1, f"第 {expected_tier} 档应当仍然重试"
        assert channel.published[0][3]["x-retry-tier"] == expected_tier

    # tier 已经等于档位数：不再重试，直接死信
    channel = FakeChannel()
    summary = handle(consumer, valid_envelope(), channel,
                     FakeProperties({"x-retry-tier": 3}))

    assert channel.published == [], "档位用尽后不该再投重试队列"
    assert channel.rejected == [(7, False)], "应basic_reject(requeue=False) 由 DLX 送进死信"
    assert summary.dead_lettered == 1
    assert summary.retried == 0


def test_retry_publish_failure_falls_back_to_dead_letter():
    """重试副本发布失败时不能 ACK 原消息。

    否则就是"原件被确认掉、副件没投出去"——消息彻底消失，
    既不在重试队列也不在死信队列，日志里一切正常。
    这里必须退回死信路径，消息还有最后一线生机。
    """
    def boom() -> dict[str, int]:
        raise RuntimeError("后端暂时不可用")

    consumer = RabbitConsumer(make_settings(), boom)
    # 模拟 confirm 模式下的 UnroutableError：重试交换机上没有匹配的 binding
    channel = FakeChannel(publish_error=RuntimeError("UnroutableError"))

    summary = handle(consumer, valid_envelope(), channel)

    assert summary.retried == 0
    assert channel.acked == [], "重试副本没投出去就不能 ACK 原件"
    assert channel.rejected == [(7, False)], "应退回死信路径"
    assert summary.dead_lettered == 1
    # 发布失败必须被如实计数，不能伪装成"已处理"
    assert summary.reject_failed == 1


def test_retry_preserves_first_failed_at_across_tiers():
    """x-first-failed-at 在多档重试之间**保留首次**时间，不被覆盖。

    覆盖掉的话，DLQ 摘要里的"首次失败时间"会变成最后一次的时间，
    运维看到的故障持续时长会被系统性低估。
    """
    def boom() -> dict[str, int]:
        raise RuntimeError("持续失败")

    consumer = RabbitConsumer(make_settings(), boom)
    channel = FakeChannel()
    handle(consumer, valid_envelope(), channel,
           FakeProperties({"x-retry-tier": 1, "x-first-failed-at": "2026-10-20T02:00:00.000Z"}))

    assert channel.published[0][3]["x-first-failed-at"] == "2026-10-20T02:00:00.000Z"


def test_consumer_does_not_log_payload():
    """死信日志不得输出完整载荷：信封里可能有业务数据。"""
    consumer = RabbitConsumer(make_settings(), lambda: None)
    channel = FakeChannel()
    secret = "s3cr3t-business-payload"
    summary = handle(consumer, json.dumps({"payload": secret}).encode(), channel)
    # 错误信息里只该有"缺少哪些字段"，不该有载荷内容
    for message in summary.errors:
        assert secret not in message


# ----------------------------------------------------------------- Inbox 登记


def test_event_is_registered_in_inbox_before_wakeup():
    """Inbox 登记必须在 claim **之前**，且顺序不可颠倒。

    颠倒的后果：claim 已经把任务领走并开始执行，之后登记失败 →
    消息被当作失败重投 → 再次 claim → 又一次执行。
    去重登记若在副作用之后，就再也拦不住重复副作用了。
    """
    order: list[str] = []

    def register(envelope: dict[str, Any]) -> str:
        order.append("register")
        return "ACCEPTED"

    def wakeup() -> None:
        order.append("wakeup")

    consumer = RabbitConsumer(make_settings(), wakeup, register=register)
    channel = FakeChannel()

    summary = handle(consumer, valid_envelope(), channel)

    assert order == ["register", "wakeup"], f"顺序错误：{order}"
    assert summary.duplicates == 0


def test_inbox_duplicate_still_triggers_wakeup():
    """Inbox 命中重复时**仍然**触发一次唤醒，并计入 duplicates。

    不触发是错的：重复可能来自"上一轮 ACK 前崩溃"，
    那次唤醒其实从未真正生效。claim 是幂等的、最坏返回空，
    多唤醒一次的代价是零，漏唤醒的代价是任务挂到下一个轮询周期。
    """
    calls: list[int] = []

    consumer = RabbitConsumer(make_settings(),
                              lambda: calls.append(1),
                              register=lambda envelope: "DUPLICATE")
    channel = FakeChannel()

    summary = handle(consumer, valid_envelope(), channel)

    assert len(calls) == 1, "重复投递也必须尝试唤醒"
    assert summary.duplicates == 1
    assert channel.acked == [7], "重复是正常现象，不该进死信"
    assert summary.dead_lettered == 0


def test_inbox_registration_failure_is_retryable():
    """Inbox 登记失败（后端不可用）必须走退避重试，不能静默继续。

    静默继续等于绕过 Inbox：消息被 ACK，Inbox 里没有记录，
    而"重放不产生重复副作用"这个保证正是靠Inbox 建立的。
    """
    def register(envelope: dict[str, Any]) -> str:
        raise RuntimeError("后端 503")

    consumer = RabbitConsumer(make_settings(), lambda: None, register=register)
    channel = FakeChannel()

    summary = handle(consumer, valid_envelope(), channel)

    # 关键：登记失败时**不能**继续 claim——那会造成未登记就产生副作用
    assert summary.wakeups == 0
    assert summary.retried == 1, "登记失败应进入退避重试"
    assert channel.published[0][3]["x-retry-tier"] == 1


def test_inbox_payload_conflict_goes_to_dead_letter():
    """同eventId 异载荷（数据损坏）不可重试，必须进死信。

    重投当前进程不会让载荷变回原样；而静默 ACK 等于把数据损坏藏起来。
    """
    def register(envelope: dict[str, Any]) -> str:
        raise ConsumerError("同 eventId 出现不同载荷", retryable=False)

    consumer = RabbitConsumer(make_settings(), lambda: None, register=register)
    channel = FakeChannel()

    summary = handle(consumer, valid_envelope(), channel)

    assert channel.rejected == [(7, False)]
    assert summary.dead_lettered == 1
    assert summary.wakeups == 0


# ----------------------------------------------------------------- 常量一致性


def test_topology_constants_match_java_side():
    """与Java 侧 RabbitTopology 的常量必须逐一一致。

    Worker 是独立进程，不能引用 Java 的类，两侧只能靠约定保持一致。
    一旦分叉，后果是**静默**的：消息发到不存在的交换机，
    或 routing key匹配不到 binding，消息消失且日志正常。
    所以这里把每一项都钉死。
    """
    from app.consumer import (DLX_EXCHANGE, EVENTS_EXCHANGE, HEADER_FIRST_FAILED_AT,
                              HEADER_LAST_ERROR, HEADER_ORIGINAL_ROUTING_KEY,
                              HEADER_RETRY_TIER, REPUBLISH_EXCHANGE, REPUBLISH_QUEUE,
                              RETRY_EXCHANGE, RETRY_KEY_PREFIX, RETRY_TIER_COUNT,
                              RK_REPUBLISH, is_valid_retry_tier, retry_routing_key)

    assert EVENTS_EXCHANGE == "repopilot.events"
    # v2 后缀是刻意的：headers 版本不能原地改类型（PRECONDITION_FAILED 会关channel），
    # 滚动发布期间新旧实例并存会让启动直接失败
    assert RETRY_EXCHANGE == "repopilot.events.retry.v2"
    assert REPUBLISH_EXCHANGE == "repopilot.events.republish"
    assert REPUBLISH_QUEUE == "repopilot.retry.republish"
    assert DLX_EXCHANGE == "repopilot.events.dlx"
    assert RETRY_TIER_COUNT == 3
    assert RETRY_KEY_PREFIX == "retry.t"
    assert RK_REPUBLISH == "retry.republish"
    assert HEADER_RETRY_TIER == "x-retry-tier"
    assert HEADER_ORIGINAL_ROUTING_KEY == "x-original-routing-key"
    assert HEADER_FIRST_FAILED_AT == "x-first-failed-at"
    assert HEADER_LAST_ERROR == "x-last-error"
    # routing key 与档位的映射：档位是 1 起的，0 没有退避语义
    assert retry_routing_key(1) == "retry.t1"
    assert retry_routing_key(3) == "retry.t3"
    assert not is_valid_retry_tier(0), "tier=0 等于首次投递，没有退避语义"
    assert not is_valid_retry_tier(4), "超出档位数，投递过去没有对应队列会被丢弃"
    assert all(is_valid_retry_tier(t) for t in (1, 2, 3))


# ----------------------------------------------------------------- 再发布阶段


def test_republish_restores_original_routing_key_and_acks():
    """退避到期的消息必须被重新投递到**原业务交换机与原 routing key**。

    这是 topic 退避方案的最后一环：TTL 到期时 routing key 是合成键
    ``retry.republish``，业务交换机上没有它的 binding。
    消费者读 ``x-original-routing-key`` 复原后才投得回去。
    """
    consumer = RabbitConsumer(make_settings(), lambda: None)
    channel = FakeChannel()
    summary = ConsumeSummary(delivered=1)

    consumer.handle_republish(
        channel, FakeMethod(), FakeProperties({
            "x-retry-tier": 1,
            "x-original-routing-key": "evaluation.ready",
        }), valid_envelope(), summary)

    assert channel.published, "必须重新发布到业务交换机"
    exchange, routing_key, _, headers = channel.published[0]
    assert exchange == "repopilot.events", "重投必须走业务交换机而不是重试交换机"
    assert routing_key == "evaluation.ready", "必须复原成原业务 routing key"
    # 档位等诊断头必须保留：重投成功后消费端仍要能看到它重试过
    assert headers["x-retry-tier"] == 1
    assert channel.acked == [7], "确认成功后才ACK"
    assert summary.republished == 1


def test_republish_without_original_routing_key_goes_to_dead_letter():
    """缺少原 routing key 时**不能猜**，必须进死信。

    猜一个 routing key 等于把消息投给错误的消费者：
    看起来"处理完了"，实际业务根本没被触发。
    """
    consumer = RabbitConsumer(make_settings(), lambda: None)
    channel = FakeChannel()
    summary = ConsumeSummary(delivered=1)

    consumer.handle_republish(channel, FakeMethod(),
                              FakeProperties({"x-retry-tier": 1}),
                              valid_envelope(), summary)

    assert channel.published == [], "没有原 routing key 就不该乱投"
    assert channel.rejected == [(7, False)], "应进死信让人来看"
    assert summary.republished == 0


def test_republish_failure_keeps_message_unacked():
    """再发布失败时**不能 ACK**，消息必须留在再发布队列里等下次。

    ACK 掉就等于丢消息：消息不在再发布队列里，也从未回到业务队列，
    而两边都没有它的副本。不确认则连接关闭后 broker 自动重投。
    """
    consumer = RabbitConsumer(make_settings(), lambda: None)
    channel = FakeChannel(publish_error=RuntimeError("UnroutableError"))
    summary = ConsumeSummary(delivered=1)

    consumer.handle_republish(
        channel, FakeMethod(),
        FakeProperties({"x-retry-tier": 1, "x-original-routing-key": "run.ready"}),
        valid_envelope(), summary)

    assert channel.acked == [], "发布失败却ACK = 丢消息"
    assert channel.nacked == [(7, True)], "应 requeue 留待下次"
    assert summary.republished == 0
    assert summary.reject_failed >= 1


# ----------------------------------------------------------------- 消费统计上报（TASK-013-E）


def test_consumer_reports_incremental_stats_to_backend():
    """消费结果必须**增量**上报：后端累加，传累计值会让计数在重启后倒退。"""
    reported: list[dict[str, int]] = []

    consumer = RabbitConsumer(make_settings(), lambda: None,
                               report_stats=reported.append)
    channel = FakeChannel()

    # 第一条：正常处理
    consumer._on_message(channel, FakeMethod(), FakeProperties(), valid_envelope())
    # 第二条：正常处理
    consumer._on_message(channel, FakeMethod(), FakeProperties(), valid_envelope())

    assert len(reported) == 2, "每处理一条应上报一次增量"
    assert reported[0]["acked"] == 1
    assert reported[1]["acked"] == 1
    # 关键：第二次上报的仍然是 1（增量），不是 2（累计）
    assert reported[1]["acked"] == 1, "上报的必须是增量而不是累计值"


def test_consumer_stats_failure_does_not_break_consumption():
    """统计上报失败**不能**影响消费。

    观测手段坏了不该让业务跟着坏——否则一次监控抖动
    就变成了消息处理的批量失败。
    """
    def broken_report(_delta: dict[str, int]) -> None:
        raise RuntimeError("后端不可用")

    consumer = RabbitConsumer(make_settings(), lambda: None,
                               report_stats=broken_report)
    channel = FakeChannel()

    consumer._on_message(channel, FakeMethod(), FakeProperties(), valid_envelope())

    # 消息仍然被 ACK 了：业务路径没有被统计上报带崩
    assert channel.acked == [7]


def test_consumer_keeps_totals_across_rounds():
    """累计值跨轮次保留：lastSummary 只描述最近一轮，多轮之间无法对账。"""
    consumer = RabbitConsumer(make_settings(), lambda: None,
                               report_stats=lambda _d: None)
    channel = FakeChannel()

    consumer._on_message(channel, FakeMethod(), FakeProperties(), valid_envelope())
    consumer._on_message(channel, FakeMethod(), FakeProperties(), valid_envelope())

    status = consumer.status()
    assert status["totals"]["acked"] == 2
    assert status["totals"]["delivered"] == 2


def test_consumer_stats_reports_dead_letter_and_retry_separately():
    """进死信与进重试是两种不同的结局，必须能分别计数。

    合在一起就看不出"是重试次数不够"还是"消息本身不可重试"，
    而这两种的处置完全不同。
    """
    reported: list[dict[str, int]] = []
    consumer = RabbitConsumer(make_settings(), lambda: None,
                               report_stats=reported.append)

    # 契约错误 → 不可重试 → 直接进死信
    channel = FakeChannel()
    consumer._on_message(channel, FakeMethod(), FakeProperties(), b"not json")
    assert reported[-1]["deadLettered"] == 1
    assert reported[-1]["retried"] == 0

    # 唤醒失败 → 可重试 → 进退避重试
    failing = RabbitConsumer(make_settings(),
                              lambda: (_ for _ in ()).throw(RuntimeError("后端不可用")),
                              report_stats=reported.append)
    channel2 = FakeChannel()
    failing._on_message(channel2, FakeMethod(), FakeProperties(), valid_envelope())
    assert reported[-1]["retried"] == 1
    assert reported[-1]["deadLettered"] == 0
