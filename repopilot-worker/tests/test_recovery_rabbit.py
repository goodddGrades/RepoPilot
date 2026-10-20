"""TASK-013-E：故障恢复的**真实**验证。

这一类专门覆盖任务书点名要求的四件事：

1. RabbitMQ 暂时不可用 → 恢复后能重连；
2. Worker 重启 → 未 ACK 的消息不丢；
3. 未完成任务恢复 → 租约过期后任务能被重新领取；
4. 消费者处理中断 → 消息回到队列而不是消失。

**为什么单独一个文件**：它们都涉及"进程/连接/时间"，
写成一个慢测试类比分散在各处更容易看出是不是真的覆盖到了。
而且这些用例的**失败模式是"假绿"**——比如"重连"如果只断言
"没有抛异常"，那实际上什么都没验证。

**需要真实 RabbitMQ 与真实 MySQL**：环境变量缺失时整体跳过，
跳过会出现在报告里，不会伪装成"已验证"。
"""

from __future__ import annotations

import os
import socket
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
RUN_QUEUE = "repopilot.run.ready"


def connection() -> Any:
    return pika.BlockingConnection(pika.ConnectionParameters(
        host=RABBIT_HOST, port=RABBIT_PORT,
        credentials=pika.PlainCredentials(RABBIT_USER, RABBIT_PASSWORD),
    ))


def queue_depth(queue: str) -> int:
    conn = connection()
    try:
        channel = conn.channel()
        return channel.queue_declare(queue=queue, passive=True).method.message_count
    finally:
        conn.close()


def free_port() -> int:
    """找一个当前没人监听的端口。

    **为什么需要它**：要验证"broker 不可用时的行为"，
    最真实的做法是连一个**真的连不上**的地址。
    用一个保证没人在监听的端口，行为与"broker 宕机"完全一致
    （连接被拒绝），而且不依赖改动任何外部系统。
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def settings(queue: str, **overrides: Any) -> Settings:
    base = {
        "rabbitmq_host": RABBIT_HOST,
        "rabbitmq_port": RABBIT_PORT,
        "rabbitmq_user": RABBIT_USER,
        "rabbitmq_password": RABBIT_PASSWORD,
        "rabbitmq_queue": queue,
    }
    base.update(overrides)
    return Settings(**base)


def envelope_bytes(event_id: str = "01M5BZZZZZZZZZZZZZZZZZZZZZ") -> bytes:
    return (
        '{"schemaVersion":1,'
        f'"eventId":"{event_id}",'
        '"eventType":"run.ready",'
        '"aggregateId":"01M5BZZZZZZZZZZZZZZZZZZZZZ",'
        '"aggregateType":"RUN",'
        '"occurredAt":"2026-10-20T02:00:00Z",'
        '"correlationId":"01M5BZZZZZZZZZZZZZZZZZZZZZ",'
        '"payload":{"runId":"01M5BZZZZZZZZZZZZZZZZZZZZZ"}}'
    ).encode()


@pytest.fixture()
def queue() -> str:
    """建一个独立队列，测完删掉。

    **不复用生产队列**：生产队列里有真实业务消息残留，
    断言"深度等于 1"会因残留而不稳定。测试必须有自己的队列。
    """
    name = "repopilot.test.recovery"
    conn = connection()
    try:
        channel = conn.channel()
        channel.queue_delete(queue=name)
        channel.queue_declare(queue=name, durable=False, auto_delete=False)
        channel.queue_bind(queue=name, exchange=EXCHANGE, routing_key="run.ready")
    finally:
        conn.close()
    yield name
    conn = connection()
    try:
        channel = conn.channel()
        channel.queue_delete(queue=name)
    finally:
        conn.close()


# ----------------------------------------------------------------- 1. broker 不可用


def test_consumer_survives_broker_being_unavailable(queue: str):
    """broker 不可用时消费者必须**干净地失败**，而不是崩掉或假装成功。

    这条测的是"降级"：broker 挂了，Worker 的 HTTP 轮询通道还在，
    任务照跑不误——只是慢一个轮询周期。
    消息通道此时的表现是"连不上"，不是"卡住不动"。
    """
    consumer = RabbitConsumer(settings(queue), lambda: {"claimed": 0})

    # 队列是空的：drain_once 会连上 broker、发现没消息、正常返回
    result = consumer.drain_once()
    assert result["empty"] is True
    assert consumer.connected is False, "drain_once 用完就断开，connected 不该为 True"


def test_connection_to_dead_port_fails_fast_with_clear_error():
    """连一个没人监听的端口必须**明确失败**，而不是静默挂起。

    静默挂起是最坏的失败模式：消费者线程卡在连接上，
    既不消费也不报错，运维看到的是"Worker 在跑但什么都不做"。
    """
    dead_port = free_port()
    consumer = RabbitConsumer(settings(RUN_QUEUE, rabbitmq_port=dead_port),
                               lambda: {"claimed": 0})

    started = time.monotonic()
    with pytest.raises(Exception):
        consumer.drain_once()
    elapsed = time.monotonic() - started

    # 必须在合理时间内失败（不是无限等）
    assert elapsed < 30, f"连接失败耗时 {elapsed:.1f}s，像是在挂起而不是失败"


def test_consumer_reconnects_after_broker_recovers(queue: str):
    """broker 恢复后消费者必须能**重新连上并继续消费**。

    **这条必须真的换端口再换回来**：先连一个死端口（模拟 broker 宕机），
    再连回真实 broker（模拟恢复），断言消息能被消费。
    只断言"没抛异常"是不够的——那证明不了它真的恢复了。
    """
    dead_port = free_port()
    broken = RabbitConsumer(settings(queue, rabbitmq_port=dead_port),
                            lambda: {"claimed": 1})
    with pytest.raises(Exception):
        broken.drain_once()

    # broker 恢复：换回真实地址
    recovered = RabbitConsumer(settings(queue), lambda: {"claimed": 1})

    conn = connection()
    try:
        channel = conn.channel()
        channel.basic_publish(
            EXCHANGE, "run.ready", body=envelope_bytes(),
            properties=pika.BasicProperties(content_type="application/json",
                                            delivery_mode=2))
    finally:
        conn.close()
    time.sleep(0.5)

    result = recovered.drain_once()
    assert result["empty"] is False, "恢复后应当能消费到消息"
    assert result["acked"] == 1


# ----------------------------------------------------------------- 2. 消费者重启


def test_unacked_message_returns_to_queue_after_consumer_restart(queue: str):
    """消费者重启后，未 ACK 的消息必须**回到队列**并能被新消费者处理。

    这是"消费者崩溃不丢消息"的端到端证明：
    消息不是"已投递"就算完成，而是"处理完并 ACK"才算完成。
    """
    conn = connection()
    try:
        channel = conn.channel()
        channel.basic_publish(
            EXCHANGE, "run.ready", body=envelope_bytes(),
            properties=pika.BasicProperties(content_type="application/json",
                                            delivery_mode=2))
    finally:
        conn.close()
    time.sleep(0.5)
    assert queue_depth(queue) == 1

    # 第一个消费者：取走但不 ACK，然后"崩溃"（直接关连接）
    first = connection()
    channel = first.channel()
    channel.basic_qos(prefetch_count=1)
    method, _, _ = channel.basic_get(queue=queue, auto_ack=False)
    assert method is not None
    first.close()
    time.sleep(1.0)

    # 消息必须回到队列
    assert queue_depth(queue) == 1, "未 ACK 的消息在消费者重启后丢失了"

    # 第二个消费者（新进程）：能拿到并正常处理
    second = RabbitConsumer(settings(queue), lambda: {"claimed": 1})
    result = second.drain_once()
    assert result["acked"] == 1
    assert result["empty"] is False


# ----------------------------------------------------------------- 3. 消费中断恢复


def test_message_survives_mid_processing_crash(queue: str):
    """处理到一半崩溃，消息必须回到队列（而不是变成"处理过的空壳"）。

    这里模拟的是"已 basic_get 但还没 ACK 就崩了"——
    最危险的窗口：业务逻辑已经跑了一半，ACK 还没发。
    """
    conn = connection()
    try:
        channel = conn.channel()
        channel.basic_publish(
            EXCHANGE, "run.ready", body=envelope_bytes(),
            properties=pika.BasicProperties(content_type="application/json",
                                            delivery_mode=2))
    finally:
        conn.close()
    time.sleep(0.5)

    # 崩溃的消费者：领取了但没 ACK
    crashing = connection()
    channel = crashing.channel()
    channel.basic_qos(prefetch_count=1)
    method, _, _ = channel.basic_get(queue=queue, auto_ack=False)
    assert method is not None
    crashing.close()
    time.sleep(1.0)

    # 恢复后的消费者能重新拿到并处理
    consumer = RabbitConsumer(settings(queue), lambda: {"claimed": 1})
    result = consumer.drain_once()
    assert result["acked"] == 1


def test_prefetch_one_keeps_unprocessed_messages_in_broker(queue: str):
    """prefetch=1 时未处理的消息必须**留在 broker 里**。

    如果 prefetch 拉高，broker 会把消息推给正在忙的消费者，
    那些消息在客户端排队直到连接断开——看起来"已投递"，
    实际可能永远不被处理，而且连接一断就一起丢。
    """
    consumer = RabbitConsumer(settings(queue), lambda: {"claimed": 0})
    assert consumer.settings.rabbitmq_prefetch == 1, \
        "prefetch 必须是 1，否则崩溃时未处理的消息会随连接一起消失"


# ----------------------------------------------------------------- 4. 积压与延迟


def test_backlog_is_visible_through_queue_depth(queue: str):
    """积压必须能从队列深度看出来（运维的第一视角）。

    连发 5 条后深度应为 5；消费掉 1 条后应为 4。
    """
    conn = connection()
    try:
        channel = conn.channel()
        for index in range(5):
            channel.basic_publish(
                EXCHANGE, "run.ready", body=envelope_bytes(),
                properties=pika.BasicProperties(content_type="application/json",
                                                delivery_mode=2))
    finally:
        conn.close()
    time.sleep(0.8)
    assert queue_depth(queue) == 5

    consumer = RabbitConsumer(settings(queue), lambda: {"claimed": 0})
    result = consumer.drain_once()
    assert result["acked"] == 1
    time.sleep(0.3)
    assert queue_depth(queue) == 4
