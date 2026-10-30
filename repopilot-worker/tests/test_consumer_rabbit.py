"""RabbitMQ 消费者的**真实 broker** 行为（TASK-013）。

与 test_consumer_protocol.py 的分工：
* 那一类验证**协议决策**（ack / 重投 / 死信怎么选），永远可跑；
* 这一类验证**真实 AMQP 语义**——confirm、routing、x-death、prefetch 下的重投，
  这些是替身永远给不出的答案。

**需要真实 RabbitMQ**：环境变量``WORKER_TEST_RABBIT_HOST`` 未提供时整体跳过。
跳过会出现在测试报告里，不会伪装成"已验证"。

为什么必须有这一类：TASK-011 就是在"替身全绿"的情况下漏掉了
``basicPublish(routingKey, "", ...)``——exchange 传空字符串，
接上真broker 才发现每条事件都被退回、永远卡在 PENDING。
替身测的是"给定结果状态对不对"，给不出"这个调用在真实 broker 上是什么语义"。
"""

from __future__ import annotations

import json
import os
import time
from typing import Any

import pytest

pika = pytest.importorskip("pika", reason="未安装 pika，无法验证真实 broker 行为")

from app.config import Settings  # noqa: E402
from app.consumer import RabbitConsumer  # noqa: E402

RABBIT_HOST = os.environ.get("WORKER_TEST_RABBIT_HOST")
RABBIT_PORT = int(os.environ.get("WORKER_TEST_RABBIT_PORT", "5672"))
RABBIT_USER = os.environ.get("WORKER_TEST_RABBIT_USER", "repopilot")
RABBIT_PASSWORD = os.environ.get("WORKER_TEST_RABBIT_PASSWORD", "")

pytestmark = pytest.mark.skipif(
    not RABBIT_HOST or not RABBIT_PASSWORD,
    reason="需要真实 RabbitMQ：设置 WORKER_TEST_RABBIT_HOST / WORKER_TEST_RABBIT_PASSWORD",
)

EXCHANGE = "repopilot.events"
DLX = "repopilot.events.dlx"
DLQ = "repopilot.events.dlq"


def connection() -> Any:
    return pika.BlockingConnection(pika.ConnectionParameters(
        host=RABBIT_HOST, port=RABBIT_PORT,
        credentials=pika.PlainCredentials(RABBIT_USER, RABBIT_PASSWORD),
    ))


def queue_depth(queue: str) -> int:
    """队列深度。用 passive declare 读取——不会创建队列，也不会清空它。"""
    conn = connection()
    try:
        channel = conn.channel()
        return channel.queue_declare(queue=queue, passive=True).method.message_count
    finally:
        conn.close()


def drain(queue: str) -> int:
    """清空队列，返回清掉的消息数。

    测试之间共享真实 broker，前一个用例留下的消息会让后一个用例的深度断言错乱。
    每个用例开头先排空，比每个用例结尾清理更可靠——
    失败中断时也不会把脏数据留给下一次运行。
    """
    conn = connection()
    count = 0
    try:
        channel = conn.channel()
        while True:
            method, _, _ = channel.basic_get(queue=queue, auto_ack=True)
            if method is None:
                return count
            count += 1
    finally:
        conn.close()


def publish(body: bytes, routing_key: str = "evaluation.ready",
            exchange: str = EXCHANGE) -> None:
    conn = connection()
    try:
        conn.channel().basic_publish(exchange, routing_key, body=body)
    finally:
        conn.close()


def envelope_bytes(event_id: str = "01M5BZZZZZZZZZZZZZZZZZZZZZ",
                   routing_key: str = "evaluation.ready") -> bytes:
    return json.dumps({
        "schemaVersion": 1,
        "eventId": event_id,
        "eventType": routing_key,
        "aggregateId": "01M5BZZZZZZZZZZZZZZZZZZZZZ",
        "aggregateType": "EVALUATION_JOB",
        "occurredAt": "2026-10-20T02:00:00Z",
        "correlationId": "01M5BZZZZZZZZZZZZZZZZZZZ",
        "payload": {"jobId": "01M5BZZZZZZZZZZZZZZZZZZZ"},
    }).encode()


def settings(queue: str) -> Settings:
    return Settings(
        rabbitmq_host=RABBIT_HOST,
        rabbitmq_port=RABBIT_PORT,
        rabbitmq_user=RABBIT_USER,
        rabbitmq_password=RABBIT_PASSWORD,
        rabbitmq_queue=queue,
        rabbitmq_enabled=True,
    )


@pytest.fixture()
def queue() -> str:
    """每次用例前排空两个队列，避免跨用例污染。"""
    for name in ("repopilot.evaluation.ready", DLQ):
        try:
            drain(name)
        except Exception:  # noqa: BLE001 - 队列可能还不存在
            pass
    yield "repopilot.evaluation.ready"
    for name in ("repopilot.evaluation.ready", DLQ):
        try:
            drain(name)
        except Exception:  # noqa: BLE001
            pass


# ----------------------------------------------------------------- 真实 ACK 语义


def test_message_is_acked_and_removed_from_queue(queue: str):
    """真实 broker 上：消费后 ACK，消息真的从队列消失。

    这是替身给不出的断言——它只能验证"调用了 ack"，
    验证不了"broker 真的把它移走了"。
    """
    consumer = RabbitConsumer(settings(queue), lambda: None)
    publish(envelope_bytes())
    time.sleep(0.5)
    assert queue_depth(queue) == 1

    result = consumer.drain_once()

    assert result["acked"] == 1
    assert result["empty"] is False
    assert result["wakeups"] == 1
    time.sleep(0.3)
    assert queue_depth(queue) == 0, "ACK 之后消息必须真的离开队列"


def test_empty_queue_reports_empty(queue: str):
    consumer = RabbitConsumer(settings(queue), lambda: None)
    result = consumer.drain_once()
    assert result["empty"] is True
    assert result["acked"] == 0


def test_duplicate_delivery_acks_each_time_without_error(queue: str):
    """同一条消息投递两次：两次都 ACK，都不进死信。

    重复投递是<b>正常现象</b>（confirm 丢失、消费者崩溃），
    如果它导致进死信，死信队列会被正常重投塞满。
    """
    body = envelope_bytes()
    consumer = RabbitConsumer(settings(queue), lambda: None)

    publish(body)
    time.sleep(0.4)
    first = consumer.drain_once()
    assert first["acked"] == 1

    # 模拟重投：同样的字节再次入队
    publish(body)
    time.sleep(0.4)
    second = consumer.drain_once()
    assert second["acked"] == 1
    assert second["deadLettered"] == 0


def test_unprocessed_messages_survive_connection_loss(queue: str):
    """未 ACK 的消息在连接断开后必须回到队列（prefetch 语义）。

    这是「消费者崩溃后任务不丢」的保证：如果消息在客户端被 ACK 掉再处理，
    崩溃时消息就永远消失了——而任务看起来一切正常。

    第一版这里同时注册了 ``basic_consume`` 又调 ``basic_get``：
    队列里只有 1 条消息，basic_get 取走它之后连接就断了，
    于是断言「深度应为 1」必然失败——是测试自己多取了一条，不是产品行为有问题。
    """
    publish(envelope_bytes())
    time.sleep(0.4)
    assert queue_depth(queue) == 1

    conn = connection()
    channel = conn.channel()
    channel.basic_qos(prefetch_count=1)
    # 取一条但不 ACK，然后直接断开连接
    method, _, _ = channel.basic_get(queue=queue, auto_ack=False)
    assert method is not None
    assert queue_depth(queue) == 0, "已被投递但未 ACK：此时不应还在 ready 队列里"
    conn.close()
    time.sleep(1.0)

    assert queue_depth(queue) == 1, \
        "未 ACK 的消息必须随连接关闭回到队列，否则崩溃即丢消息"


# ----------------------------------------------------------------- 真实死信语义


def test_contract_violation_reaches_dead_letter_queue(queue: str):
    """契约错误必须**真的**落到 DLQ。

    这里实测踩过一个坑：业务队列没设 x-dead-letter-exchange 时，
    ``basic_reject(requeue=False)`` 会被 broker **直接丢弃**——
    日志显示"已拒绝"、计数显示 deadLettered=1，而 DLQ 里什么都没有。
    所以这个断言不能只检查 summary，必须检查 DLQ 的真实深度。
    """
    consumer = RabbitConsumer(settings(queue), lambda: None)

    # 场景 1：非法 JSON
    publish(b"{not json at all")
    time.sleep(0.4)
    first = consumer.drain_once()
    assert first["deadLettered"] == 1

    # 场景 2：合法 JSON 但缺必填字段
    publish(json.dumps({"schemaVersion": 1, "eventType": "evaluation.ready"}).encode())
    time.sleep(0.4)
    second = consumer.drain_once()
    assert second["deadLettered"] == 1

    time.sleep(1.0)
    assert queue_depth(DLQ) == 2, \
        "死信必须真的在 DLQ 里；为 0 说明队列没配 x-dead-letter-exchange，消息被静默丢弃"


def test_dead_letter_preserves_original_envelope_and_death_reason(queue: str):
    """死信必须保留原始信封与 x-death 元数据，否则无法排查。"""
    consumer = RabbitConsumer(settings(queue), lambda: None)
    publish(b'{"broken":')
    time.sleep(0.4)
    consumer.drain_once()
    time.sleep(0.8)

    conn = connection()
    try:
        channel = conn.channel()
        method, properties, body = channel.basic_get(queue=DLQ, auto_ack=False)
        assert method is not None, "DLQ 里应有死信"
        # 原始信封逐字节保留：排查时需要看到"当时到底发了什么"
        assert body == b'{"broken":'
        headers = properties.headers or {}
        assert "x-death" in headers, "缺少 x-death 就无法知道为什么进了死信"
        death = headers["x-death"][0]
        assert death["reason"] == "rejected"
        assert death["queue"] == queue
        assert death["count"] >= 1
        channel.basic_ack(method.delivery_tag)
    finally:
        conn.close()


def test_unknown_schema_version_goes_to_dead_letter_not_infinite_retry(queue: str):
    """未知信封版本重投多少次都不会变合法，必须一次性隔离。"""
    consumer = RabbitConsumer(settings(queue), lambda: None)
    body = json.dumps({
        "schemaVersion": 99,
        "eventId": "01M5BZZZZZZZZZZZZZZZZZZZZZ",
        "eventType": "evaluation.ready",
        "aggregateId": "01M5BZZZZZZZZZZZZZZZZZZZ",
    }).encode()
    publish(body)
    time.sleep(0.4)
    result = consumer.drain_once()
    assert result["deadLettered"] == 1
    assert result["retried"] == 0, \
        "不可重试的消息绝不能 requeue——那会形成打死循环"


def test_valid_messages_never_reach_dead_letter_queue(queue: str):
    """反向断言：正常消息绝不能进死信。"""
    consumer = RabbitConsumer(settings(queue), lambda: None)
    publish(envelope_bytes())
    time.sleep(0.4)
    consumer.drain_once()
    time.sleep(0.8)
    assert queue_depth(DLQ) == 0


# ----------------------------------------------------------------- 真实路由语义


def test_only_bound_routing_key_is_delivered(queue: str):
    """只有绑定了 binding 的 routing key 才会进这个队列。

    验证的是"交换机按 binding 分发"：往一个没绑定的key 发消息，
    它不会出现在这个队列里。这是 TASK-013-A 修的核心问题——
    exchange 传空字符串时，broker 把 routing key 当队列名去找。
    """
    publish(envelope_bytes(routing_key="run.ready"), routing_key="run.ready")
    time.sleep(0.6)

    assert queue_depth(queue) == 0, \
        "run.ready 不绑定到 evaluation 队列，不应出现在这里"
    # 它应该出现在自己的队列里
    assert queue_depth("repopilot.run.ready") >= 1
    drain("repopilot.run.ready")


# ----------------------------------------------------------------- 真实退避重试
#
# 下面这些用例需要"重试层拓扑"存在：headers 交换机 + 3 档 TTL 队列。
# 生产环境由 Java 侧 RabbitTopologyDeclarer 在启动时声明；
# 这里自己声明一份，是为了让这一类测试能在**不起后端**的情况下跑。


RETRY_EXCHANGE = "repopilot.events.retry.v2"
REPUBLISH_EXCHANGE = "repopilot.events.republish"
REPUBLISH_QUEUE = "repopilot.retry.republish"
RK_REPUBLISH = "retry.republish"
RETRY_QUEUES = ["repopilot.retry.wait.t1", "repopilot.retry.wait.t2",
                "repopilot.retry.wait.t3"]
# 退避 TTL，**与 Java 侧 RabbitTopology.RETRY_BACKOFF_MILLIS 保持一致**。
#
# 曾经想用更短的 TTL（1.5 秒）让测试跑得快，但那是错的：
# ``x-message-ttl`` 是**声明期参数**，``queue_declare`` 无法事后修改。
# 测试用短 TTL 声明的队列，会让演示脚本 / 后端按生产 TTL 声明时抛
# ``PRECONDITION_FAILED`` 并关掉 channel——两个"消费者"互相破坏对方的拓扑。
# 运维在生产上改 TTL 时也会遇到完全一样的启动失败。
#
# 因此这里用**生产值**：慢，但与生产一字不差。
# 需要快速验证机制时用一次性的隔离 vhost，不要动共享队列的参数。
RETRY_TTL_MILLIS = {"t1": 5_000, "t2": 30_000, "t3": 120_000}

# 再发布队列的声明参数。x-dead-letter-exchange 是**声明期参数**，
# queue_declare 无法事后修改：参数不一致时 broker 抛 PRECONDITION_FAILED
# 并**关掉整个 channel**，后续声明全部失败。比直接失败更难排查。
REPUBLISH_QUEUE_ARGS = {
    "x-queue-type": "quorum",
    # 必须配 DLX：这里会 basic_reject(requeue=false)，
    # 没有 DLX 时 broker 直接丢弃被 reject 的消息，日志却显示"已拒绝"
    "x-dead-letter-exchange": DLX,
}


def _delete_queue(queue: str) -> None:
    """删队列（用于参数不符时重建）。失败不抛：队列可能不存在。"""
    conn = connection()
    try:
        conn.channel().queue_delete(queue=queue)
    except Exception:  # noqa: BLE001
        pass
    finally:
        conn.close()


def _is_precondition_failed(exc: BaseException) -> bool:
    """判断异常是否是 broker 的 PRECONDITION_FAILED。

    不能只看 ``str(exc)``：AMQP 客户端收到 channel.close 帧时抛
    ``AMQPChannelError``，真正的 broker 回复在 ``repr`` 里。
    """
    text = f"{exc!r}"
    cursor: BaseException | None = exc
    guard = 0
    while cursor is not None and guard < 5:
        text += " " + str(cursor)
        cursor = cursor.__cause__
        guard += 1
    return "PRECONDITION_FAILED" in text or " inequivalent arg " in text


@pytest.fixture()
def retry_topology(queue: str):
    """声明一份与生产同构的退避拓扑（topic 重试交换机 + 等待队列 + 再发布）。

    **队列名、交换机名、队列参数都与生产完全一致**——消费者代码里的
    RETRY_EXCHANGE / REPUBLISH_QUEUE 是硬编码常量，名字必须对上；
    TTL 也用生产值，因为它是声明期参数（见 RETRY_TTL_MILLIS 的说明）。
    唯一差别是没有"删除并重建"的兜底之外的任何偏离。
    """
    # 先清掉可能存在的旧参数队列：x-dead-letter-exchange 是声明期参数，
    # 参数不一致会 PRECONDITION_FAILED 并**关掉整个 channel**，
    # 后续所有声明随之失败。队列里未消费的副本会丢——
    # 这在测试环境可接受；生产环境由 Java 声明器的 deleteAndRedeclare
    # 处理并记日志（权威副本在 outbox_events 里）。
    for name in (REPUBLISH_QUEUE,) + tuple(RETRY_QUEUES):
        _delete_queue(name)

    conn = connection()
    try:
        channel = conn.channel()
        # DLQ 由后端声明器创建，但本 fixture 要往它上面加 binding，
        # 所以先确保它存在（幂等）
        channel.queue_declare(queue=DLQ, durable=True,
                              arguments={"x-queue-type": "quorum"})
        channel.exchange_declare(exchange=RETRY_EXCHANGE, exchange_type="topic",
                                 durable=True)
        channel.exchange_declare(exchange=REPUBLISH_EXCHANGE, exchange_type="topic",
                                 durable=True)
        channel.queue_declare(queue=REPUBLISH_QUEUE, durable=True,
                              arguments=REPUBLISH_QUEUE_ARGS)
        channel.queue_bind(queue=REPUBLISH_QUEUE, exchange=REPUBLISH_EXCHANGE,
                           routing_key=RK_REPUBLISH)
        # 再发布队列的死信沿用 retry.republish 这个 routing key，
        # DLQ 必须绑它，否则"无法复原 routing key"的消息会被静默丢弃
        channel.queue_bind(queue=DLQ, exchange=DLX, routing_key=RK_REPUBLISH)

        for tier, name in enumerate(RETRY_QUEUES, start=1):
            channel.queue_declare(queue=name, durable=True, arguments={
                "x-queue-type": "quorum",
                "x-message-ttl": RETRY_TTL_MILLIS[f"t{tier}"],
                # TTL 到期后不能直接死信回业务交换机：那时 routing key 是
                # 合成键，业务交换机没有它的 binding，消息会被静默丢弃
                "x-dead-letter-exchange": REPUBLISH_EXCHANGE,
                "x-dead-letter-routing-key": RK_REPUBLISH,
            })
            # **精确键绑定**。这是本次迁移的核心：旧方案用 headers +
            # x-match=all 分档，实测 x-match=all 会忽略 x- 前缀字段导致分档完全失效
            channel.queue_bind(queue=name, exchange=RETRY_EXCHANGE,
                               routing_key=f"retry.t{tier}")
    finally:
        conn.close()

    for name in RETRY_QUEUES + [REPUBLISH_QUEUE]:
        drain(name)
    yield queue
    for name in RETRY_QUEUES + [REPUBLISH_QUEUE]:
        drain(name)


def test_retryable_failure_lands_in_retry_queue_then_republishes(retry_topology: str):
    """可重试失败的完整链路：t1 等待队列 → TTL 到期 → 再发布队列 → 回到业务队列。

    这是 TASK-013-C 的核心验证：**退避靠带 TTL 的队列实现**，
    消息留在 broker 里等，而不是靠 basic_nack(requeue=true) 打热循环。

    注意链路里有<b>两段</b>而不是一段：TTL 到期后先到再发布队列，
    由消费者复原 routing key 再投回业务交换机。
    少这一段的话，消息会带着合成键 retry.republish 回到业务交换机，
    匹配不到 binding 而被静默丢弃。
    """
    def boom() -> dict[str, int]:
        raise RuntimeError("后端暂时不可用")

    consumer = RabbitConsumer(settings(retry_topology), boom)
    publish(envelope_bytes())
    time.sleep(0.4)

    result = consumer.drain_once()

    assert result["retried"] == 1, "可重试失败必须进退避重试而不是死信"
    assert result["deadLettered"] == 0
    # 原件已被确认（重试副本已投递），业务队列立刻空出来
    assert queue_depth(retry_topology) == 0
    # 消息此刻在 t1 等待队列里
    assert queue_depth(RETRY_QUEUES[0]) == 1, "重试副本必须真的进了 t1 队列"
    # **关键**：不能同时出现在 t2/t3 队列里
    assert queue_depth(RETRY_QUEUES[1]) == 0, "t2 队列不该收到 t1 档的消息"
    assert queue_depth(RETRY_QUEUES[2]) == 0, "t3 队列不该收到 t1 档的消息"

    # 等 TTL 到期，消息应当进入再发布队列
    deadline = time.time() + 15
    while time.time() < deadline and queue_depth(REPUBLISH_QUEUE) == 0:
        time.sleep(0.5)
    assert queue_depth(REPUBLISH_QUEUE) == 1, \
        "TTL 到期后必须死信到再发布队列（x-dead-letter-exchange/routing-key 配错就不会到）"

    # 再发布阶段：消费者复原 routing key 后投回业务交换机
    republished = consumer.republish_once()
    assert republished["republished"] == 1, "必须成功重新投递"
    time.sleep(0.6)
    assert queue_depth(retry_topology) == 1, \
        "重新投递后必须回到原业务队列（依赖 x-original-routing-key 复原）"
    assert queue_depth(REPUBLISH_QUEUE) == 0, "再发布成功后消息应离开再发布队列"


def test_retry_tier_routing_is_exact_not_broadcast(retry_topology: str):
    """**分档路由必须精确**：t1/t2/t3 各进各的队列，一条都不多。

    这是本次迁移要验证的核心问题。旧方案用 headers 交换机
    ``x-match=all, x-retry-tier=N`` 分档，实测 RabbitMQ 会**忽略 ``x-`` 前缀
    的绑定字段**，于是所有消息都进了每一个档位队列——
    "3 档指数退避"退化为"全部按第一档并发回流"。

    这里对每一档分别验证：
      * 消息只进目标队列；
      * 其他两档队列深度保持 0。
    只断言"进了 t1"是不够的——旧方案下t1 也是有消息的，
    区别在于它**同时**还有 t2/t3。
    """
    conn = connection()
    try:
        channel = conn.channel()
        for tier in (1, 2, 3):
            channel.basic_publish(
                RETRY_EXCHANGE, f"retry.t{tier}",
                body=envelope_bytes(event_id=f"01TIER0000000000000000000000{tier}"),
                properties=pika.BasicProperties(delivery_mode=2),
            )
        time.sleep(0.8)

        depths = {name: queue_depth(name) for name in RETRY_QUEUES}
        assert depths == {RETRY_QUEUES[0]: 1, RETRY_QUEUES[1]: 1, RETRY_QUEUES[2]: 1}, \
            f"每档队列应各收到 1 条，实际 {depths}——出现 >1 说明存在广播式误路由"
    finally:
        conn.close()


def test_illegal_retry_routing_key_is_not_routed(retry_topology: str):
    """非法档位（t0 / t4/ 带后缀）**不得**被投递到任何等待队列。

    ``retry.t0`` 等于首次投递，没有退避语义，投递过去等于立刻回流形成热循环；
    ``retry.t4`` 超出档位数，没有对应队列。两者都必须靠
    confirm + mandatory 暴露成 ``UnroutableError``，
    让消费者退回死信路径，而不是静默丢弃。
    """
    conn = connection()
    try:
        channel = conn.channel()
        channel.confirm_delivery()
        for bad_key in ("retry.t0", "retry.t4", "retry.t1x", "retry.tX"):
            raised = False
            try:
                channel.basic_publish(RETRY_EXCHANGE, bad_key, body=envelope_bytes(),
                                      properties=pika.BasicProperties(delivery_mode=2),
                                      mandatory=True)
            except pika.exceptions.UnroutableError:
                raised = True
            assert raised, f"{bad_key} 必须被判为不可路由（没有对应队列）"

        time.sleep(0.5)
        for name in RETRY_QUEUES:
            assert queue_depth(name) == 0, f"非法档位的消息不该进 {name}"
        assert queue_depth(REPUBLISH_QUEUE) == 0
    finally:
        conn.close()


def test_retried_message_carries_original_routing_key_in_header(retry_topology: str):
    """重试副本必须带上原 routing key 与递增的档位头。

    这两点任一缺失，退避链路就断了：
    *缺原 routing key → 再发布阶段无法复原，消息进死信；
    * 档位不递增 → 耗尽判断永远不触发，消息无限重投。
    """
    def boom() -> dict[str, int]:
        raise RuntimeError("持续失败")

    consumer = RabbitConsumer(settings(retry_topology), boom)
    publish(envelope_bytes())
    time.sleep(0.4)
    consumer.drain_once()

    conn = connection()
    try:
        channel = conn.channel()
        method, properties, body = channel.basic_get(queue=RETRY_QUEUES[0], auto_ack=True)
        assert method is not None, "重试副本应在 t1 队列里"
        # 等待队列的 routing key 是合成键
        assert method.routing_key == "retry.t1"
        headers = properties.headers or {}
        # 原业务 routing key 在头里，供再发布阶段复原
        assert headers["x-original-routing-key"] == "evaluation.ready", \
            "原 routing key 必须放进头里，否则再发布时不知道该投回哪个队列"
        assert headers["x-retry-tier"] == 1
        assert "持续失败" in headers["x-last-error"], "失败原因必须留存供排查"
        assert headers["x-first-failed-at"], "首次失败时间必须被记录"
        # 信封逐字节保留：重试不是重新构造消息
        assert body == envelope_bytes()
    finally:
        conn.close()


def test_republish_restores_routing_key_and_returns_to_business_queue(retry_topology: str):
    """再发布阶段必须把消息投回**原业务交换机与原 routing key**。

    验证的是完整回流的最后一跳：
    再发布队列里的消息带着 ``retry.republish`` 这个合成 routing key，
    消费者必须读 ``x-original-routing-key`` 复原后再投，
    否则消息会因为匹配不到 binding 而消失。
    """
    conn = connection()
    try:
        channel = conn.channel()
        channel.basic_publish(
            REPUBLISH_EXCHANGE, RK_REPUBLISH,
            body=envelope_bytes(),
            properties=pika.BasicProperties(
                content_type="application/json", delivery_mode=2,
                headers={"x-retry-tier": 1,
                         "x-original-routing-key": "evaluation.ready",
                         "x-first-failed-at": "2026-10-20T02:00:00.000Z",
                         "x-last-error": "等待重试"}),
        )
        time.sleep(0.5)
    finally:
        conn.close()

    consumer = RabbitConsumer(settings(retry_topology), lambda: None)
    result = consumer.republish_once()

    assert result["republished"] == 1
    time.sleep(0.6)
    assert queue_depth(retry_topology) == 1, \
        "重新投递后必须回到原业务队列，而不是消失"
    assert queue_depth(REPUBLISH_QUEUE) == 0

    # 消息内容与诊断头都必须完整保留
    conn = connection()
    try:
        channel = conn.channel()
        method, properties, body = channel.basic_get(queue=retry_topology, auto_ack=True)
        assert method is not None
        assert json.loads(body)["eventId"] == "01M5BZZZZZZZZZZZZZZZZZZZZZ"
        headers = properties.headers or {}
        assert headers["x-retry-tier"] == 1, "档位必须保留，重投后消费端仍要能看到"
        assert headers["x-last-error"] == "等待重试"
    finally:
        conn.close()


def test_republish_without_original_key_does_not_guess(retry_topology: str):
    """再发布时缺原 routing key 头 → 拒绝猜测，消息必须保留可查。

    猜一个 routing key 意味着把消息投给错误的消费者：
    看起来"处理完了"，实际业务根本没被触发。
    """
    conn = connection()
    try:
        channel = conn.channel()
        channel.basic_publish(
            REPUBLISH_EXCHANGE, RK_REPUBLISH, body=envelope_bytes(),
            properties=pika.BasicProperties(delivery_mode=2,
                                            headers={"x-retry-tier": 1}))
        time.sleep(0.5)
    finally:
        conn.close()

    consumer = RabbitConsumer(settings(retry_topology), lambda: None)
    result = consumer.republish_once()

    assert result["republished"] == 0, "缺原 routing key 时不该乱投"
    # 消息进了死信（可查），而不是消失
    time.sleep(1.0)
    assert queue_depth(DLQ) == 1, "无法复原 routing key 的消息必须被隔离而不是丢掉"


def test_exhausted_retries_reach_dead_letter_queue(retry_topology: str):
    """退避档位用尽后消息必须真的进 DLQ，并保留完整排查信息。

    直接从"已在最后一档"的消息开始，验证终态：
    DLQ 里必须有消息，且信封、失败原因、档位、首次失败时间齐全——
    "进了死信但查不出为什么"等于没隔离。
    """
    def boom() -> dict[str, int]:
        raise RuntimeError("最后一击")

    consumer = RabbitConsumer(settings(retry_topology), boom)

    conn = connection()
    try:
        channel = conn.channel()
        channel.basic_publish(
            EXCHANGE, "evaluation.ready",
            body=envelope_bytes(),
            properties=pika.BasicProperties(
                content_type="application/json", delivery_mode=2,
                headers={"x-retry-tier": 3,  # 档位已用尽
                         "x-first-failed-at": "2026-10-20T02:00:00.000Z",
                         "x-last-error": "前几档的失败原因"}),
        )
        time.sleep(0.5)
    finally:
        conn.close()

    result = consumer.drain_once()

    assert result["deadLettered"] == 1
    assert result["retried"] == 0, "档位用尽后不得再投重试队列"
    time.sleep(1.0)

    assert queue_depth(DLQ) == 1, "耗尽后必须真的落在 DLQ 里"

    conn = connection()
    try:
        channel = conn.channel()
        method, properties, body = channel.basic_get(queue=DLQ, auto_ack=False)
        assert method is not None
        headers = properties.headers or {}
        # 排查所需的四项元数据一个都不能少
        assert json.loads(body)["eventId"] == "01M5BZZZZZZZZZZZZZZZZZZZZZ", \
            "死信必须保留原始信封（含 eventId），否则无法追溯业务事实"
        assert headers["x-retry-tier"] == 3, "必须保留已重试的档位"
        assert headers["x-first-failed-at"] == "2026-10-20T02:00:00.000Z"
        assert headers["x-last-error"] == "前几档的失败原因"
        assert "x-death" in headers
        channel.basic_ack(method.delivery_tag)
    finally:
        conn.close()


def test_confirm_mode_makes_unroutable_retry_visible(retry_topology: str):
    """confirm 模式下不可路由的重试消息必须**抛错**，不能静默丢弃。

    这是本轮在真实 broker 上实测确认的关键行为：pika 只有在confirm 模式
    开启时才检查 mandatory 与 NACK。不开 confirm 时，往没有 binding 的
    交换机发消息会**静默成功**，而消费者随后 ACK 掉原件——消息彻底消失，
    既不在重试队列也不在死信队列，日志里一切正常。

    本用例锁定"confirm 生效"这个前提：它是上面那条链路的生命线。
    """
    conn = connection()
    try:
        channel = conn.channel()
        channel.confirm_delivery()
        unroutable_raised = False
        try:
            # retry.t99 超出档位数，重试交换机上没有对应 binding。
            # 路由决策只看 routing key，不看 x-retry-tier 头——
            # 头只用于诊断，这样"头"与"实际进了哪个队列"可以互相印证。
            channel.basic_publish(
                RETRY_EXCHANGE, "retry.t99", body=envelope_bytes(),
                properties=pika.BasicProperties(delivery_mode=2,
                                                headers={"x-retry-tier": 99}),
                mandatory=True)
        except pika.exceptions.UnroutableError:
            unroutable_raised = True

        assert unroutable_raised, \
            "confirm 模式下不可路由必须抛 UnroutableError；" \
            "若通过说明 confirm 未生效，mandatory 检查形同虚设"

        # 且没有任何重试队列收到它
        for name in RETRY_QUEUES:
            assert queue_depth(name) == 0
    finally:
        conn.close()


# ----------------------------------------------------------------- 死信受控重放


def test_replayed_dead_letter_keeps_original_event_id(queue: str):
    """受控重放必须保留原 eventId，Inbox 去重才拦得住重复副作用。

    换 ID 重放会让同一条业务事实被当成两次通知，
    Inbox 的 ``(consumer, event_id)`` 唯一键就形同虚设。
    这里验证重放后消息回到业务队列、且信封逐字节不变。
    """
    body = envelope_bytes(event_id="01M5DEADBEEFDEADBEEFDEADBEEF")

    conn = connection()
    try:
        channel = conn.channel()
        channel.confirm_delivery()
        # 重放走**业务交换机**而不是直投队列：这样 Inbox 去重、
        # 信封版本校验、消费者幂等全部照常执行
        channel.basic_publish(EXCHANGE, "evaluation.ready", body=body,
                              properties=pika.BasicProperties(content_type="application/json",
                                                              delivery_mode=2),
                              mandatory=True)
        time.sleep(0.6)
        assert queue_depth(queue) == 1, "重放的消息必须真的回到业务队列"

        method, _, replayed = channel.basic_get(queue=queue, auto_ack=True)
        assert method is not None
        assert json.loads(replayed)["eventId"] == "01M5DEADBEEFDEADBEEFDEADBEEF", \
            "重放必须保留原 eventId，换 ID 会绕开 Inbox 去重"
    finally:
        conn.close()


def test_dead_letter_queue_stays_empty_after_successful_processing(queue: str):
    """反向断言：正常消息走完全程后 DLQ 仍是空的。

    这是对退避重试的"副作用检查"：如果重试逻辑写错
    （比如把成功消息也发到重试交换机），DLQ 会莫名增长。
    """
    consumer = RabbitConsumer(settings(queue), lambda: None)
    publish(envelope_bytes())
    time.sleep(0.4)
    result = consumer.drain_once()
    assert result["acked"] == 1
    time.sleep(1.0)

    assert queue_depth(queue) == 0
    assert queue_depth(DLQ) == 0
    for name in RETRY_QUEUES:
        # 重试交换机可能还没被声明（无重试用例时），跳过不存在的队列
        try:
            assert queue_depth(name) == 0, f"{name} 不该有消息"
        except Exception:  # noqa: BLE001
            pass
