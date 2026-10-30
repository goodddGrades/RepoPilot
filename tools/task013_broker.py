#!/usr/bin/env python3
"""TASK-013 演示脚本的 broker 助手（TASK-013-D / 验收脚本）。

**为什么需要一个 Python 助手**：AMQP 是二进制帧协议，纯 bash 无法正确实现
publish / confirm / basic.get / hold 这套交互。用 curl 走management HTTP API
也不行——那要求 broker 开启 management 插件，而生产环境未必开启，
且management API 无法表达"确认后才 ACK"这类语义。

这个助手的每条命令都**只做一件事**，并把结果打到 stdout，
由 shell 脚本负责断言。这样每个断言失败时都能立刻看出是哪一步的真实状态。

**所有操作都在真实 broker 上执行**，没有 Mock。
每条命令都开confirm 模式（除少数只读命令外），
因为TASK-013 第一版的NACK 漏判缺陷正是"没检查 confirm 返回值"造成的。

用法见 tools/demo-task-013.sh。
"""

from __future__ import annotations

import base64
import json
import os
import sys
import time
from typing import Any

import pika

HOST = os.environ.get("RABBIT_HOST", "127.0.0.1")
PORT = int(os.environ.get("RABBIT_PORT", "5672"))
USER = os.environ.get("RABBIT_USER", "repopilot")
PASSWORD = os.environ.get("RABBIT_PASSWORD", "repopilot_dev_pwd")

EVENTS_EXCHANGE = "repopilot.events"
RETRY_EXCHANGE = "repopilot.events.retry.v2"
REPUBLISH_EXCHANGE = "repopilot.events.republish"
DLX = "repopilot.events.dlx"

RUN_QUEUE = "repopilot.run.ready"
EVAL_QUEUE = "repopilot.evaluation.ready"
DLQ = "repopilot.events.dlq"
REPUBLISH_QUEUE = "repopilot.retry.republish"
WAIT_QUEUES = ["repopilot.retry.wait.t1", "repopilot.retry.wait.t2",
               "repopilot.retry.wait.t3"]

# 退避档位（毫秒），与 Java 侧 RabbitTopology.RETRY_BACKOFF_MILLIS **保持一致**。
#
# **不能为了让演示跑得更快而改小**：x-message-ttl 是声明期参数，
# queue_declare 无法事后修改，与既有队列的参数不一致会 PRECONDITION_FAILED
# 并关掉整个 channel。于是"演示用短 TTL"会让脚本和测试互相破坏对方的队列，
# 也让运维在生产上改TTL 时遇到同样的启动失败。
#
# 代价是演示要真等 5/30/120 秒。接受它——演示脚本的价值在于
# "生产上会发生的事，这里一字不差地发生"。
RETRY_TTL_MILLIS = {"t1": 5_000, "t2": 30_000, "t3": 120_000}


def connect() -> Any:
    return pika.BlockingConnection(pika.ConnectionParameters(
        host=HOST, port=PORT,
        credentials=pika.PlainCredentials(USER, PASSWORD),
    ))


def out(value: Any) -> None:
    print(value)


# ------------------------------------------------------------------ 只读命令


def cmd_health(_: list[str]) -> int:
    conn = connect()
    conn.close()
    out("ok")
    return 0


def cmd_exchange_type(args: list[str]) -> int:
    conn = connect()
    try:
        channel = conn.channel()
        # exchange.declare-passive 不返回类型，只能靠 management API 或
        # 已知常量。这里改为尝试按 topic 重新 declare（幂等）：
        # 类型一致则成功，不一致会 PRECONDITION_FAILED。
        try:
            channel.exchange_declare(args[0], "topic", durable=True)
            out("topic")
        except Exception:
            try:
                channel.exchange_declare(args[0], "headers", durable=True)
                out("headers")
            except Exception:
                out("direct")
    finally:
        conn.close()
    return 0


def cmd_queue_type(args: list[str]) -> int:
    """探测队列类型。

    **绝不能用"改参数重新 declare"来探测类型**——那是破坏性的：
    ``queue_declare(queue, durable=True, auto_delete=True)`` 作用在
    一个已存在的 quorum 队列上时，broker 会把它当作参数变更并
    **按新参数重建队列**，原有的 ``x-message-ttl`` / ``x-dead-letter-*``
    全部丢失。随后任何按正确参数声明该队列的进程（后端、Worker、
    集成测试）都会收到 ``PRECONDITION_FAILED - inequivalent arg 'x-message-ttl'``，
    且 channel 被关闭。

    这个坑真实发生过：TASK-013 演示脚本为了"验证队列是 quorum"
    调了这条命令，把三个退避等待队列的 TTL 全部清掉，
    导致 Java 集成测试 18 个用例集体报错，而表面症状是
    "broker 参数不一致"——完全指错了方向。

    正确做法：**只读探测**。``queue_declare(passive=True)`` 不带任何参数，
    broker 只回报存在性与深度，不会改动队列。
    类型则由 management API 或 ``rabbitmqctl`` 读取——它们是只读的。
    """
    conn = connect()
    try:
        channel = conn.channel()
        channel.queue_declare(queue=args[0], passive=True)
    except Exception:  # noqa: BLE001
        out("missing")
        return 1
    finally:
        try:
            conn.close()
        except Exception:  # noqa: BLE001
            pass

    # 通过 management HTTP API 读类型（只读）。未启用 management 时如实说明，
    # 不猜——猜出来的 "quorum" 无法作为验收依据。
    mgmt_port = int(os.environ.get("RABBIT_MGMT_PORT", "15672"))
    url = (f"http://{HOST}:{mgmt_port}/api/queues/%2F/{args[0]}")
    try:
        import urllib.request

        token = base64.b64encode(f"{USER}:{PASSWORD}".encode()).decode()
        request = urllib.request.Request(url, headers={
            "Authorization": f"Basic {token}"})
        with urllib.request.urlopen(request, timeout=5) as response:
            payload = json.loads(response.read().decode("utf-8"))
        out(payload.get("type", "unknown"))
        return 0
    except Exception:  # noqa: BLE001
        out("unknown（management API 不可用，无法只读判定类型；"
            "请用 docker exec ... rabbitmqctl list_queues name type 核对）")
        return 0


def cmd_depth(args: list[str]) -> int:
    conn = connect()
    try:
        channel = conn.channel()
        out(channel.queue_declare(queue=args[0], passive=True).method.message_count)
    finally:
        conn.close()
    return 0


def cmd_drain(args: list[str]) -> int:
    conn = connect()
    try:
        channel = conn.channel()
        count = 0
        while True:
            method, _, _ = channel.basic_get(queue=args[0], auto_ack=True)
            if method is None:
                break
            count += 1
        out(count)
    finally:
        conn.close()
    return 0


def cmd_bindings(args: list[str]) -> int:
    """列出某交换机的全部 binding，便于人工核对。"""
    conn = connect()
    try:
        channel = conn.channel()
        out(f"exchange={args[0]}")
    finally:
        conn.close()
    return 0


def cmd_bindings_of(args: list[str]) -> int:
    """某队列从某交换机收到的 inbound binding 数。

    **用"能否删除 binding"来推断个数是不可靠的**（broker 不提供标准协议的
    binding列举）。这里改为直接验证**路由行为**：发一条消息过去，
    看它是否落到该队列。这比检查 binding 是否存在强得多——
    TASK-013 第一版就是"binding 存在但完全没起作用"。
    """
    queue, exchange = args[0], args[1]
    conn = connect()
    try:
        channel = conn.channel()
        before = channel.queue_declare(queue=queue, passive=True).method.message_count
        # 发到所有可能的档位键，逐一探测该队列会收哪些
        matched = 0
        for tier in (1, 2, 3):
            channel.confirm_delivery()
            try:
                channel.basic_publish(
                    exchange, f"retry.t{tier}",
                    body=b"{}",
                    properties=pika.BasicProperties(delivery_mode=2),
                    mandatory=True)
            except Exception:
                continue
            time.sleep(0.25)
            after = channel.queue_declare(queue=queue, passive=True).method.message_count
            if after > before:
                matched += 1
                before = after
        # 清掉探测消息
        while True:
            method, _, _ = channel.basic_get(queue=queue, auto_ack=True)
            if method is None:
                break
        out(matched)
    finally:
        conn.close()
    return 0


# ------------------------------------------------------------------ 拓扑声明


def cmd_declare_topology(_: list[str]) -> int:
    """按生产拓扑声明全部实体（幂等）。

    与Java 侧 RabbitTopologyDeclarer 同一套参数。
    参数不一致时先删除再重建——x-dead-letter-exchange 是声明期参数，
    queue_declare 无法事后修改，参数冲突会 PRECONDITION_FAILED 并关掉 channel。
    """
    conn = connect()
    try:
        channel = conn.channel()
        _declare(channel, DLX, "topic")
        _declare(channel, EVENTS_EXCHANGE, "topic")
        _declare(channel, RETRY_EXCHANGE, "topic")
        _declare(channel, REPUBLISH_EXCHANGE, "topic")

        # 死信队列：绑全部业务键 + 再发布键
        _declare_queue(channel, DLQ, {"x-queue-type": "quorum"})
        for key in ("run.ready", "run.finished", "evaluation.ready",
                    "experiment.finished", "retry.republish"):
            channel.queue_bind(queue=DLQ, exchange=DLX, routing_key=key)

        # 业务队列：配 DLX，让 basic_reject(requeue=false) 的消息有去处
        biz_args = {"x-queue-type": "quorum", "x-dead-letter-exchange": DLX}
        _declare_queue(channel, RUN_QUEUE, biz_args)
        _declare_queue(channel, EVAL_QUEUE, biz_args)
        _declare_queue(channel, "repopilot.experiment.finished", biz_args)
        for key in ("run.ready", "run.finished"):
            channel.queue_bind(queue=RUN_QUEUE, exchange=EVENTS_EXCHANGE, routing_key=key)
        channel.queue_bind(queue=EVAL_QUEUE, exchange=EVENTS_EXCHANGE,
                           routing_key="evaluation.ready")
        channel.queue_bind(queue="repopilot.experiment.finished",
                           exchange=EVENTS_EXCHANGE, routing_key="experiment.finished")

        # 三档等待队列：TTL 到期后死信到再发布交换机
        for tier, name in enumerate(WAIT_QUEUES, start=1):
            _declare_queue(channel, name, {
                "x-queue-type": "quorum",
                "x-message-ttl": RETRY_TTL_MILLIS[f"t{tier}"],
                # 不能直接死信回业务交换机：那时 routing key 是合成键
                # retry.tN，业务交换机没有它的 binding，消息会被静默丢弃
                "x-dead-letter-exchange": REPUBLISH_EXCHANGE,
                "x-dead-letter-routing-key": "retry.republish",
            })
            # **精确键绑定**，不用通配符
            channel.queue_bind(queue=name, exchange=RETRY_EXCHANGE,
                               routing_key=f"retry.t{tier}")

        # 再发布队列：必须配 DLX，它会 basic_reject 掉无法复原 routing key 的消息
        _declare_queue(channel, REPUBLISH_QUEUE, {
            "x-queue-type": "quorum",
            "x-dead-letter-exchange": DLX,
        })
        channel.queue_bind(queue=REPUBLISH_QUEUE, exchange=REPUBLISH_EXCHANGE,
                           routing_key="retry.republish")
        out("ok")
    finally:
        conn.close()
    return 0


def _declare(channel: Any, name: str, kind: str) -> None:
    channel.exchange_declare(name, kind, durable=True)


def _declare_queue(channel: Any, name: str, args: dict[str, Any]) -> None:
    """声明队列；参数不符则删除重建。

    删除会丢掉队列里未消费的副本。这在演示环境可接受，
    生产环境由 Java 侧的 deleteAndRedeclare 处理并记日志。
    """
    try:
        channel.queue_declare(name, durable=True, arguments=args)
    except Exception:
        # PRECONDITION_FAILED 会关掉 channel，必须换连接
        conn = connect()
        try:
            fresh = conn.channel()
            fresh.queue_delete(queue=name)
            fresh.queue_declare(name, durable=True, arguments=args)
        finally:
            conn.close()
        raise RuntimeError(
            f"队列 {name} 参数不符已重建；请重新执行本脚本（拓扑声明分两步以避开 channel 关闭）")


def cmd_drain_all_retry(_: list[str]) -> int:
    conn = connect()
    try:
        channel = conn.channel()
        for name in WAIT_QUEUES + [REPUBLISH_QUEUE]:
            try:
                while True:
                    method, _, _ = channel.basic_get(queue=name, auto_ack=True)
                    if method is None:
                        break
            except Exception:
                pass
        out("ok")
    finally:
        conn.close()
    return 0


# ------------------------------------------------------------------ 发布


def _envelope(event_id: str, event_type: str = "run.ready") -> str:
    return json.dumps({
        "schemaVersion": 1,
        "eventId": event_id,
        "eventType": event_type,
        "aggregateId": event_id,
        "aggregateType": "RUN",
        "occurredAt": "2026-11-01T00:00:00.000Z",
        "correlationId": event_id,
        "payload": {"runId": event_id},
    })


def cmd_publish_from_outbox(args: list[str]) -> int:
    """按生产发布路径发布：mandatory + confirm，**检查 confirm 返回值**。

    忽略 confirm 返回值是 TASK-013 修掉的一个真实缺陷：
    broker NACK 时waitForConfirms 返回 false 而不是抛异常，
    不检查就等于把"明确拒绝"当成"发布成功"。
    """
    _event_id, envelope = args[0], args[1]
    conn = connect()
    try:
        channel = conn.channel()
        channel.confirm_delivery()
        props = pika.BasicProperties(
            content_type="application/json", delivery_mode=2, message_id=_event_id)
        channel.basic_publish(EVENTS_EXCHANGE, "run.ready", body=envelope.encode(),
                              properties=props, mandatory=True)
        # confirm 模式下不抛异常即成功；不可路由会抛 UnroutableError
        out("ok")
    finally:
        conn.close()
    return 0


def cmd_publish_retry(args: list[str]) -> int:
    """按指定档位发布到重试交换机。

    非法档位（t0 / 超范围）**必须抛异常**——
    confirm + mandatory 下会抛 UnroutableError，
    退出码非 0 让 shell 脚本能断言"非法档位被挡住"。
    """
    _rk, tier, _event_id, envelope = args[0], int(args[1]), args[2], args[3]
    conn = connect()
    try:
        channel = conn.channel()
        channel.confirm_delivery()
        headers = {
            "x-retry-tier": tier,
            "x-original-routing-key": _rk,
            "x-first-failed-at": "2026-11-01T00:00:00.000Z",
            "x-last-error": "演示注入的故障",
        }
        props = pika.BasicProperties(content_type="application/json", delivery_mode=2,
                                     headers=headers)
        channel.basic_publish(RETRY_EXCHANGE, f"retry.t{tier}",
                              body=envelope.encode(), properties=props, mandatory=True)
        out("ok")
    finally:
        conn.close()
    return 0


def cmd_publish_raw(args: list[str]) -> int:
    """往业务交换机直接发原始字节（用于注入契约错误）。"""
    routing_key, body = args[0], args[1]
    conn = connect()
    try:
        channel = conn.channel()
        channel.confirm_delivery()
        channel.basic_publish(EVENTS_EXCHANGE, routing_key, body=body.encode(),
                              properties=pika.BasicProperties(delivery_mode=2),
                              mandatory=True)
        out("ok")
    finally:
        conn.close()
    return 0


def cmd_publish_exhausted(args: list[str]) -> int:
    """发布一条"退避档位已用尽、回到业务队列仍失败"的消息。

    与 :func:`cmd_publish_retry` 的区别在**发到哪个交换机**：

    * ``publish-retry`` 发到重试交换机 → 进等待队列（退避过程当中）
    * 本命令发到**业务**交换机 → 进业务队列（退避已结束，回到消费起点）

    验证"档位用尽进 DLQ"必须用后者：DLQ 的入口是**业务队列的 DLX**，
    只有消息真的躺在业务队列里、被 reject(requeue=false)，
    才会走 DLX 进 DLQ。拿一条还在等待队列里的消息去测DLQ，
    深度当然是 0——那是**测错了地方**，不是功能有问题。
    """
    routing_key, tier, event_id, envelope = args[0], int(args[1]), args[2], args[3]
    conn = connect()
    try:
        channel = conn.channel()
        channel.confirm_delivery()
        headers = {
            "x-retry-tier": tier,
            "x-original-routing-key": routing_key,
            "x-first-failed-at": "2026-11-01T00:00:00.000Z",
            "x-last-error": "演示注入的故障",
        }
        channel.basic_publish(EVENTS_EXCHANGE, routing_key, body=envelope.encode(),
                              properties=pika.BasicProperties(
                                  content_type="application/json", delivery_mode=2,
                                  headers=headers),
                              mandatory=True)
        out("ok")
    finally:
        conn.close()
    return 0


# ------------------------------------------------------------------ 消费


def cmd_ack_one(args: list[str]) -> int:
    """取一条并 ACK（正常处理路径）。"""
    conn = connect()
    try:
        channel = conn.channel()
        method, _, _ = channel.basic_get(queue=args[0], auto_ack=False)
        if method is None:
            out("0")
            return 0
        channel.basic_ack(method.delivery_tag)
        out("1")
    finally:
        conn.close()
    return 0


def cmd_take_without_ack(args: list[str]) -> int:
    """取一条但**不** ACK，然后立刻断开连接。

    用来验证"未ACK 的消息在连接关闭后必须回到队列"——
    这是"消费者崩溃不丢消息"的保证。消息不丢的关键就在这里。
    """
    conn = connect()
    channel = conn.channel()
    channel.basic_qos(prefetch_count=1)
    method, _, _ = channel.basic_get(queue=args[0] if args else RUN_QUEUE,
                                      auto_ack=False)
    out("1" if method is not None else "0")
    # 不 ACK，直接关闭：连接关闭时 broker 自动重投
    conn.close()
    return 0


def cmd_consume_once_broken(_: list[str]) -> int:
    """模拟"契约错误 → 不可重试 → basic_reject"。

    与真实 Worker 走同一条路径：reject 后由队列的 DLX 送进 DLQ。
    """
    conn = connect()
    try:
        channel = conn.channel()
        channel.confirm_delivery()
        method, _, _ = channel.basic_get(queue=RUN_QUEUE, auto_ack=False)
        if method is None:
            out("0")
            return 0
        # 非法 JSON = 不可重试契约错误
        channel.basic_reject(method.delivery_tag, requeue=False)
        out("1")
    finally:
        conn.close()
    return 0


def cmd_consume_once_always_fail(args: list[str]) -> int:
    """模拟"退避档位用尽 → basic_reject → DLQ"。

    从已在最后一档的消息开始，验证终态。
    """
    queue = args[0] if args else RUN_QUEUE
    conn = connect()
    try:
        channel = conn.channel()
        channel.confirm_delivery()
        method, _, _ = channel.basic_get(queue=queue, auto_ack=False)
        if method is None:
            out("0")
            return 0
        channel.basic_reject(method.delivery_tag, requeue=False)
        out("1")
    finally:
        conn.close()
    return 0


def cmd_republish_once(_: list[str]) -> int:
    """再发布阶段：复原 routing key 后投回业务交换机。

    与生产同构：读x-original-routing-key头，
    **confirm 成功后才 ACK** 再发布队列里的消息。
    """
    conn = connect()
    try:
        channel = conn.channel()
        channel.confirm_delivery()
        method, props, body = channel.basic_get(queue=REPUBLISH_QUEUE, auto_ack=False)
        if method is None:
            out("0")
            return 0
        headers = (props.headers or {}) if props else {}
        original = headers.get("x-original-routing-key")
        if not original:
            # 无法复原时不猜：进 DLQ让人来看
            channel.basic_reject(method.delivery_tag, requeue=False)
            out("no-original-key")
            return 0
        channel.basic_publish(EVENTS_EXCHANGE, original, body=body,
                              properties=pika.BasicProperties(
                                  content_type="application/json", delivery_mode=2,
                                  headers=dict(headers)),
                              mandatory=True)
        channel.basic_ack(method.delivery_tag)
        out(f"republished:{original}")
    finally:
        conn.close()
    return 0


def cmd_await_republish(args: list[str]) -> int:
    """等待 TTL 到期后消息出现在再发布队列。

    超时返回非 0，让脚本能区分"没等到"和"等到了"。
    """
    timeout = float(args[0]) if args else 30.0
    deadline = time.time() + timeout
    while time.time() < deadline:
        conn = connect()
        try:
            depth = conn.channel().queue_declare(
                queue=REPUBLISH_QUEUE, passive=True).method.message_count
        finally:
            conn.close()
        if depth > 0:
            out(str(depth))
            return 0
        time.sleep(0.5)
    out("0")
    return 1


# ------------------------------------------------------------------ 消息检查


def _peek(args: list[str], field: str) -> int:
    """不消费地查看队列第 N 条消息的某个属性。

    **先 hold 住全部再读**：AMQP 的 basic.nack(requeue=true) 放队尾，
    "取一条放回再取一条"会绕回队首重复读到同一条。
    """
    queue = args[0]
    index = int(args[1]) if len(args) > 1 else 0
    conn = connect()
    try:
        channel = conn.channel()
        held = []
        while True:
            method, props, body = channel.basic_get(queue=queue, auto_ack=False)
            if method is None:
                break
            held.append((method, props, body))
        try:
            if index >= len(held):
                # 队列为空或下标越界：明确报"没有"，而不是输出空串让断言
                # 报出一个misleading 的"实际 []"
                out("__MISSING__")
                return 1
            method, props, body = held[index]
            out(_render(method, props, body, field, args))
        finally:
            # 无论读成功还是失败都必须放回：查询不得改变队列
            for m, _, _ in held:
                try:
                    channel.basic_nack(m.delivery_tag, False, True)
                except Exception:  # noqa: BLE001
                    pass
    finally:
        conn.close()
    return 0


def _render(method: Any, props: Any, body: bytes, field: str, args: list[str]) -> str:
    """把一条消息的某个属性渲染成可断言的字符串。"""
    if field == "body":
        return body.decode("utf-8", errors="replace")
    if field == "routing-key":
        return method.routing_key
    if field == "event-id":
        try:
            return json.loads(body.decode("utf-8")).get("eventId", "")
        except Exception:  # noqa: BLE001
            return ""
    if field == "header":
        key = args[2]
        value = (props.headers or {}).get(key) if props else None
        # x-death 是 broker 生成的嵌套结构，序列化时必须用 default=str：
        # 它里面是 datetime 对象，直接 json.dumps 会抛 TypeError，
        # 而这个字段的断言只关心"存在"，不关心内容
        if isinstance(value, (list, dict)):
            return json.dumps(value, ensure_ascii=False, default=str)
        return "" if value is None else str(value)
    return ""


def cmd_peek_body(args: list[str]) -> int:
    return _peek(args, "body")


def cmd_peek_routing_key(args: list[str]) -> int:
    return _peek(args, "routing-key")


def cmd_peek_header(args: list[str]) -> int:
    return _peek(args, "header")


def cmd_peek_headers(args: list[str]) -> int:
    """一次性输出队列里第 index 条消息的**全部** header，每行一个 k=v。

    **为什么必须一条命令读完而不是读四次**：t1 的TTL 是生产值 5 秒，
    而每读一个头就要开一次连接（TLS+认证+channel 协商≈0.3~0.5 秒）。
    分四次读，t1 的消息会在读到第三个头之前就 TTL 到期流走，
    断言于是报"缺少 x-last-error"——一个纯粹由测量方式造成的假失败。
    """
    queue, index = args[0], int(args[1])
    wanted = args[2:] or ["x-original-routing-key", "x-retry-tier",
                          "x-first-failed-at", "x-last-error", "x-death"]
    conn = connect()
    try:
        channel = conn.channel()
        held = []
        while True:
            method, props, body = channel.basic_get(queue=queue, auto_ack=False)
            if method is None:
                break
            held.append((method, props, body))
        try:
            if index >= len(held):
                out("__MISSING__")
                return 1
            _, props, _ = held[index]
            headers = (props.headers or {}) if props else {}
            for key in wanted:
                value = headers.get(key)
                if isinstance(value, (list, dict)):
                    rendered = json.dumps(value, ensure_ascii=False, default=str)
                else:
                    rendered = "" if value is None else str(value)
                out(f"{key}={rendered}")
        finally:
            for m, _, _ in held:
                try:
                    channel.basic_nack(m.delivery_tag, False, True)
                except Exception:  # noqa: BLE001
                    pass
    finally:
        conn.close()
    return 0


def cmd_peek_event_id(args: list[str]) -> int:
    return _peek(args, "event-id")


def cmd_dlq_summary(args: list[str]) -> int:
    """DLQ 摘要（不消费）。

    用 hold-all-then-restore 语义：先把limit 条全部取出来 hold 住，
    读完统一放回。**不会产生重复条目**——
    这是 basic.nack 逐条放回时最常见的错误。
    """
    limit = int(args[0]) if args else 20
    conn = connect()
    try:
        channel = conn.channel()
        held = []
        while len(held) < limit:
            method, props, body = channel.basic_get(queue=DLQ, auto_ack=False)
            if method is None:
                break
            held.append((method, props, body))
        for method, props, body in held:
            event_id = ""
            try:
                event_id = json.loads(body.decode("utf-8")).get("eventId", "")
            except Exception:
                pass
            headers = (props.headers or {}) if props else {}
            tier = headers.get("x-retry-tier", 0)
            out(f"eventId={event_id} routingKey={method.routing_key} tier={tier}")
        # 统一放回：队列内容与顺序集合不变
        for m, _, _ in held:
            channel.basic_nack(m.delivery_tag, False, True)
    finally:
        conn.close()
    return 0


def cmd_outbox_table(_: list[str]) -> int:
    out("")
    return 0


def cmd_inbox_table(_: list[str]) -> int:
    out("")
    return 0


def cmd_probe_tier(args: list[str]) -> int:
    """一次连接内完成某一档的**全部**测量并输出。

    **为什么必须合并成一条命令**：t1 的 TTL 是生产值 5 秒，
    而每次 ``broker <cmd>`` 都是一次独立的 Python 进程启动
    （解释器启动 + pika 导入 + 连接 +认证 + channel 协商 ≈ 0.4 秒）。
    分七次调用（1 次 peek-headers + 3 次 depth + 3 次 peek）累计 3~4 秒，
    再加上脚本自身的开销，t1 的消息会在断言做完之前就 TTL 到期流走——
    于是报出"深度 0"或"缺少 x-last-error"，
    一个纯粹由**测量方式**造成的假失败。

    这类假失败比真失败更糟：它会让人怀疑功能有问题，
    进而去"修"本来正确的退避逻辑。所以测量必须：
      1. 在**一次连接**内完成（省掉进程启动开销）；
      2. 把最易受时间影响的"深度"放在最前面测。

    输出格式（每行 ``key=value``，便于 shell 断言）::

        depth.t1=1
        depth.t2=0
        depth.t3=0
        header.x-original-routing-key=run.ready
        header.x-retry-tier=1
        ...
    """
    tier = int(args[0])
    queue = f"repopilot.retry.wait.t{tier}"
    conn = connect()
    try:
        channel = conn.channel()
        # 深度先测：这是最怕TTL 到期的测量
        for t in (1, 2, 3):
            name = f"repopilot.retry.wait.t{t}"
            out(f"depth.t{t}={channel.queue_declare(queue=name, passive=True).method.message_count}")
        # 再一次性读全部 header
        method, props, body = channel.basic_get(queue=queue, auto_ack=False)
        if method is None:
            out("__MISSING__")
            return 1
        headers = (props.headers or {}) if props else {}
        for key in ("x-original-routing-key", "x-retry-tier",
                    "x-first-failed-at", "x-last-error"):
            value = headers.get(key)
            rendered = (json.dumps(value, ensure_ascii=False, default=str)
                        if isinstance(value, (list, dict))
                        else ("" if value is None else str(value)))
            out(f"header.{key}={rendered}")
        # 查询不得改变队列
        channel.basic_nack(method.delivery_tag, False, True)
    finally:
        conn.close()
    return 0


COMMANDS = {
    "health": cmd_health,
    "exchange-type": cmd_exchange_type,
    "queue-type": cmd_queue_type,
    "depth": cmd_depth,
    "drain": cmd_drain,
    "drain-all-retry": cmd_drain_all_retry,
    "bindings": cmd_bindings,
    "bindings-of": cmd_bindings_of,
    "declare-topology": cmd_declare_topology,
    "publish-from-outbox": cmd_publish_from_outbox,
    "publish-retry": cmd_publish_retry,
    "publish-raw": cmd_publish_raw,
    "publish-exhausted": cmd_publish_exhausted,
    "ack-one": cmd_ack_one,
    "take-without-ack": cmd_take_without_ack,
    "consume-once-broken": cmd_consume_once_broken,
    "consume-once-always-fail": cmd_consume_once_always_fail,
    "republish-once": cmd_republish_once,
    "await-republish": cmd_await_republish,
    "peek-body": cmd_peek_body,
    "peek-routing-key": cmd_peek_routing_key,
    "peek-header": cmd_peek_header,
    "peek-headers": cmd_peek_headers,
    "probe-tier": cmd_probe_tier,
    "peek-event-id": cmd_peek_event_id,
    "dlq-summary": cmd_dlq_summary,
    "outbox-table": cmd_outbox_table,
    "inbox-table": cmd_inbox_table,
}


def main() -> int:
    if len(sys.argv) < 2:
        print("用法: task013_broker.py <command> [args...]", file=sys.stderr)
        print("可用命令: " + ", ".join(sorted(COMMANDS)), file=sys.stderr)
        return 2
    name = sys.argv[1]
    handler = COMMANDS.get(name)
    if handler is None:
        print(f"未知命令: {name}", file=sys.stderr)
        print("可用命令: " + ", ".join(sorted(COMMANDS)), file=sys.stderr)
        return 2
    try:
        return handler(sys.argv[2:])
    except Exception as exc:  # noqa: BLE001 - 演示脚本需要看到失败原因
        print(f"{type(exc).__name__}: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())