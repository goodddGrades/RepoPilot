#!/usr/bin/env python3
"""TASK-013 重试拓扑的受控迁移工具（headers v1 → topic v2）。

**为什么需要这个工具**
--------------------------------------------------------------------------------
TASK-013 第一版用 headers 交换机 + ``x-match=all`` + ``x-retry-tier`` 分档。
实测证明这个方案**完全失效**：``x-match=all`` 的语义是"binding 中所有
**非 ``x-`` 前缀**的字段都必须匹配"，而 ``x-retry-tier`` 带 ``x-`` 前缀，
因此**从未参与匹配**。headers 交换机表现得像 fanout，所有档位队列全部命中——
三级指数退避实际退化成"全部消息按第一档（5 秒）并发回流"，
而 ``retry.tier`` 计数、指标与日志全都正常，监控完全看不见。
（完整排查过程见 ``docs/reviews/task-013-amqp-facts.md`` 第 8 条。）

修复方案改用 **topic 交换机 + 精确 routing key + 再发布中转层**
（见 ``RabbitTopology``）。但滚动发布期间新旧实例并存，
旧的 headers 交换机与它绑定的队列会继续存在，且**里面可能压着消息**。
直接 ``queue_delete`` 会把积压消息一起删掉——那是静默丢消息。

**迁移的核心原则：先保全，再切换，最后清理**

--------------------------------------------------------------------------------
顺序错了就会丢消息或重复投递，因此本工具**强制**按三阶段执行，
且每一步都有前置条件检查：

1. **drain（保全）**：把旧队列里的消息逐条读出来，按 ``x-retry-tier``
   换算成新拓扑的精确 routing key（``retry.tN``），
   带完整诊断头重新发布到新重试交换机；**确认成功后才 ACK 旧消息**。
   任一条发布失败就**停止并放回**，绝不把消息丢在原地。
2. **verify（校验）**：确认旧队列深度为 0，且新拓扑确实收到了对应数量的消息。
3. **purge（清理）**：只有前两阶段都通过，才删除旧队列与旧交换机。

阶段之间需要人工确认（``--yes`` 跳过），因为这是一次**不可逆**的删除操作。

**幂等性**：重复执行是安全的。旧实体已不存在时，drain 与 purge 都会跳过；
``--verify-only`` 只报告不修改。

用法::

    # 只看现状，不改任何东西
    python tools/migrate_retry_topology.py --verify-only

    # 分阶段执行，每步人工确认
    python tools/migrate_retry_topology.py --phase drain
    python tools/migrate_retry_topology.py --phase verify
    python tools/migrate_retry_topology.py --phase purge

    # 无人值守（仅建议在已确认无积压时使用）
    python tools/migrate_retry_topology.py --phase all --yes
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any

import pika

HOST = os.environ.get("RABBIT_HOST", "127.0.0.1")
PORT = int(os.environ.get("RABBIT_PORT", "5672"))
USER = os.environ.get("RABBIT_USER", "repopilot")
PASSWORD = os.environ.get("RABBIT_PASSWORD", "repopilot_dev_pwd")

# 新拓扑（必须与 RabbitTopology / repopilot-worker/app/consumer.py 一致）
RETRY_EXCHANGE_V2 = "repopilot.events.retry.v2"
REPUBLISH_EXCHANGE = "repopilot.events.republish"
RETRY_TIER_COUNT = 3
HEADER_RETRY_TIER = "x-retry-tier"
HEADER_ORIGINAL_ROUTING_KEY = "x-original-routing-key"
HEADER_FIRST_FAILED_AT = "x-first-failed-at"
HEADER_LAST_ERROR = "x-last-error"

# 旧拓扑（headers v1）。这里硬编码而不从 Java侧导入：
# 迁移工具必须在**旧代码仍在线**时也能独立运行，那时Java 常量可能已不存在。
LEGACY_RETRY_EXCHANGE = "repopilot.events.retry"
LEGACY_WAIT_QUEUES = ["repopilot.retry.wait.t1",
                      "repopilot.retry.wait.t2",
                      "repopilot.retry.wait.t3"]

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_NEEDS_CONFIRM = 2


def connect() -> Any:
    return pika.BlockingConnection(pika.ConnectionParameters(
        host=HOST, port=PORT,
        credentials=pika.PlainCredentials(USER, PASSWORD),
    ))


def log(message: str) -> None:
    print(message, flush=True)


def err(message: str) -> None:
    print(message, file=sys.stderr, flush=True)


def queue_depth(channel: Any, queue: str) -> int:
    """队列深度；队列不存在时返回 -1。

    用 passive declare探测存在性——这是协议层唯一可靠的方式。
    """
    try:
        return channel.queue_declare(queue=queue, passive=True).method.message_count
    except Exception:  # noqa: BLE001 - 队列不存在会抛 ChannelClosedByBroker
        # channel 已被 broker 关闭，必须换一条才能继续
        return -1


def exchange_exists(channel_factory: Any, name: str) -> bool:
    conn = channel_factory()
    try:
        conn.channel().exchange_declare(exchange=name, passive=True)
        return True
    except Exception:  # noqa: BLE001
        return False
    finally:
        try:
            conn.close()
        except Exception:  # noqa: BLE001
            pass


def tier_of(headers: dict[str, Any], body: bytes) -> int:
    """从旧消息里判定它应该去新拓扑的哪一档。

    优先读 ``x-retry-tier``（旧消费者写过）。读不到时**保守地归到第1 档**：
    宁可早重试，也不要因为判定不出档位就把消息丢进 DLQ。
    """
    raw = headers.get(HEADER_RETRY_TIER)
    tier = None
    if raw is not None:
        try:
            tier = int(raw)
        except (TypeError, ValueError):
            tier = None
    if tier is None:
        # 旧拓扑下所有消息都进了同一批队列，头里的档位是唯一线索。
        # 头都没有的（极端情况）按第 1 档处理，并在日志里标出来。
        tier = 1
    if not 1 <= tier <= RETRY_TIER_COUNT:
        # 越界的档位说明是脏数据：钳到有效范围而不是丢弃
        tier = max(1, min(RETRY_TIER_COUNT, tier))
    return tier


def republish_one(channel: Any, queue: str) -> str:
    """从旧队列取一条、按新拓扑重新发布，成功才 ACK。

    返回 'migrated' / 'empty' / 'failed'。
    """
    method, props, body = channel.basic_get(queue=queue, auto_ack=False)
    if method is None:
        return "empty"

    headers = dict((props.headers or {}) if props else {})
    tier = tier_of(headers, body)
    original_rk = headers.get(HEADER_ORIGINAL_ROUTING_KEY)

    # 旧 headers 拓扑下拿不到原始 routing key（它当时是 fanout 行为），
    # 这时**不猜**：交给 run.ready 是最保守的选择，且必须在日志里说明。
    if not original_rk:
        original_rk = "run.ready"
        headers["x-migration-inferred-routing-key"] = "true"

    headers[HEADER_RETRY_TIER] = tier
    headers[HEADER_ORIGINAL_ROUTING_KEY] = original_rk
    headers.setdefault(HEADER_FIRST_FAILED_AT, "")
    headers.setdefault(HEADER_LAST_ERROR, "由 headers→topic 迁移工具补写")
    headers["x-migrated-from"] = LEGACY_RETRY_EXCHANGE

    try:
        channel.basic_publish(
            RETRY_EXCHANGE_V2, f"retry.t{tier}", body=body,
            properties=pika.BasicProperties(
                content_type=(props.content_type if props else None) or "application/json",
                delivery_mode=2, headers=headers),
            mandatory=True)
    except Exception as exc:  # noqa: BLE001
        # 发布失败**必须放回**：ACK 了就等于丢消息
        try:
            channel.basic_nack(method.delivery_tag, False, True)
        except Exception:  # noqa: BLE001
            pass
        err(f"[drain] 重新发布失败（消息已放回原队列，未丢失）：{exc}")
        return "failed"

    # confirm 模式下 basic_publish 成功返回即代表已被 broker 接收；
    # publish 抛异常（含 UnroutableError）才是失败。
    channel.basic_ack(method.delivery_tag)
    return "migrated"


def phase_drain(conn: Any, assume_yes: bool) -> int:
    channel = conn.channel()
    channel.confirm_delivery()

    log("=== 阶段 1/3：drain（保全旧队列积压）===")
    total_before = 0
    total_after = 0
    failed = False

    for queue in LEGACY_WAIT_QUEUES:
        probe_conn = connect()
        try:
            depth = queue_depth(probe_conn.channel(), queue)
        finally:
            probe_conn.close()
        if depth < 0:
            log(f"  {queue}: 不存在，跳过")
            continue
        if depth == 0:
            log(f"  {queue}: 深度 0，无需迁移")
            continue

        total_before += depth
        log(f"  {queue}: 深度 {depth}，开始逐条迁移")
        if not assume_yes and depth > 0:
            # 明确告知影响面后再等确认——这是会改变生产状态的操作
            answer = input(f"  确认迁移 {queue} 的 {depth} 条消息？输入 yes 继续: ")
            if answer.strip().lower() != "yes":
                err(f"  {queue}: 用户取消，停止迁移")
                failed = True
                continue

        migrated = 0
        while True:
            result = republish_one(channel, queue)
            if result == "empty":
                break
            if result == "failed":
                failed = True
                break
            migrated += 1
        total_after += migrated
        log(f"  {queue}: 已迁移 {migrated} 条")

    if failed:
        err("\ndrain 阶段失败：部分消息未迁移。**没有删除任何旧实体**，"
            "修复后重跑本工具即可继续。")
        return EXIT_FAILED

    log(f"\ndrain 完成：共迁移 {total_after} 条（原队列合计 {total_before} 条）")
    log("提示：迁移只把消息投到新拓扑的等待队列，退避与再发布仍由消费者完成。")
    return EXIT_OK


def phase_verify(conn: Any) -> int:
    log("=== 阶段 2/3：verify（确认旧队列已排空、新拓扑已就位）===")
    ok = True

    new_conn = connect()
    try:
        new_channel = new_conn.channel()
        for queue in LEGACY_WAIT_QUEUES:
            # 换连接探测：一次 passive 失败会关掉 channel
            probe = connect()
            try:
                depth = queue_depth(probe.channel(), queue)
            finally:
                probe.close()
            if depth < 0:
                log(f"  {queue}: 已不存在")
            elif depth == 0:
                log(f"  [OK] {queue}: 深度 0，积压已清空")
            else:
                log(f"  [FAIL] {queue}: 仍有 {depth} 条未迁移")
                ok = False

        for tier in range(1, RETRY_TIER_COUNT + 1):
            queue = f"repopilot.retry.wait.t{tier}"
            probe = connect()
            try:
                depth = queue_depth(probe.channel(), queue)
            finally:
                probe.close()
            if depth < 0:
                log(f"  [FAIL] {queue} 不存在：新拓扑未声明，无法接收迁移消息")
                ok = False
            else:
                log(f"  [OK] {queue}: 深度 {depth}")
    finally:
        new_conn.close()

    log("")
    if ok:
        log("verify 通过：旧队列已排空，新拓扑就位，可以执行 purge。")
    else:
        err("verify 未通过：**不要执行 purge**，否则会删掉仍带消息的旧队列。")
    return EXIT_OK if ok else EXIT_FAILED


def phase_purge(conn: Any, assume_yes: bool) -> int:
    log("=== 阶段 3/3：purge（删除旧实体）===")

    if not assume_yes:
        answer = input("  即将删除旧队列与旧 headers 交换机（不可逆）。输入 yes 继续: ")
        if answer.strip().lower() != "yes":
            err("  用户取消，未删除任何实体")
            return EXIT_NEEDS_CONFIRM

    deleted_queues = []
    skipped_queues = []
    for queue in LEGACY_WAIT_QUEUES:
        probe = connect()
        try:
            depth = queue_depth(probe.channel(), queue)
        finally:
            probe.close()
        if depth < 0:
            skipped_queues.append(queue)
            continue
        if depth != 0:
            err(f"  [拒绝] {queue} 仍有 {depth} 条消息，拒绝删除"
                "（删除会静默丢消息）")
            return EXIT_FAILED
        # 空队列才删
        purge_conn = connect()
        try:
            purge_conn.channel().queue_delete(queue=queue)
            deleted_queues.append(queue)
            log(f"  已删除队列 {queue}")
        finally:
            purge_conn.close()

    exchange_deleted = False
    probe = connect()
    try:
        exists = True
        try:
            probe.channel().exchange_declare(exchange=LEGACY_RETRY_EXCHANGE,
                                             passive=True)
        except Exception:  # noqa: BLE001
            exists = False
    finally:
        probe.close()

    if exists:
        del_conn = connect()
        try:
            del_conn.channel().exchange_delete(exchange=LEGACY_RETRY_EXCHANGE)
            exchange_deleted = True
            log(f"  已删除交换机 {LEGACY_RETRY_EXCHANGE}（headers v1）")
        except Exception as exc:  # noqa: BLE001
            err(f"  删除旧交换机失败（可稍后重试，不影响消息安全）：{exc}")
        finally:
            del_conn.close()
    else:
        log(f"  旧交换机 {LEGACY_RETRY_EXCHANGE} 不存在，跳过")

    log("")
    log(json.dumps({
        "deletedQueues": deleted_queues,
        "alreadyAbsentQueues": skipped_queues,
        "legacyExchangeDeleted": exchange_deleted,
    }, ensure_ascii=False, indent=2))
    log("\n迁移完成。滚动发布策略：新代码实例启动即声明 v2 拓扑，"
        "旧实例停止消费后即可下线——两者不需要同时在线。")
    return EXIT_OK


def phase_report(conn: Any) -> int:
    log("=== 现状报告（只读，不修改任何实体）===")
    for queue in LEGACY_WAIT_QUEUES:
        probe = connect()
        try:
            depth = queue_depth(probe.channel(), queue)
        finally:
            probe.close()
        if depth < 0:
            log(f"  {queue}: 不存在")
        else:
            log(f"  {queue}: 深度 {depth}")

    probe = connect()
    exists = True
    try:
        probe.channel().exchange_declare(exchange=LEGACY_RETRY_EXCHANGE,
                                         passive=True)
    except Exception:  # noqa: BLE001
        exists = False
    finally:
        probe.close()
    log(f"  旧交换机 {LEGACY_RETRY_EXCHANGE}: {'存在' if exists else '不存在'}")

    v2 = connect()
    try:
        try:
            v2.channel().exchange_declare(exchange=RETRY_EXCHANGE_V2,
                                          passive=True)
            log(f"  新交换机 {RETRY_EXCHANGE_V2}: 存在")
        except Exception:  # noqa: BLE001
            log(f"  新交换机 {RETRY_EXCHANGE_V2}: **不存在**（需先启动新版本后端）")
    finally:
        v2.close()

    pending = sum(
        max(0, _safe_depth(queue)) for queue in LEGACY_WAIT_QUEUES)
    log("")
    if pending == 0 and not exists:
        log("结论：迁移已完成，无需操作。")
        return EXIT_OK
    log(f"结论：旧拓扑仍有 {pending} 条积压且旧实体仍在，"
        "按 drain → verify → purge 顺序执行。")
    return EXIT_OK


def _safe_depth(queue: str) -> int:
    probe = connect()
    try:
        return max(0, queue_depth(probe.channel(), queue))
    except Exception:  # noqa: BLE001
        return 0
    finally:
        try:
            probe.close()
        except Exception:  # noqa: BLE001
            pass


def main() -> int:
    parser = argparse.ArgumentParser(
        description="TASK-013 重试拓扑受控迁移工具（headers v1 → topic v2）")
    parser.add_argument("--phase", choices=["drain", "verify", "purge", "all",
                                            "report"],
                        default="report",
                        help="要执行的阶段（默认 report，只读）")
    parser.add_argument("--verify-only", action="store_true",
                        help="等价于 --phase report")
    parser.add_argument("--yes", action="store_true",
                        help="跳过人工确认（无人值守场景）")
    args = parser.parse_args()

    phase = "report" if args.verify_only else args.phase

    log(f"RabbitMQ {HOST}:{PORT}  用户={USER}")
    conn = connect()
    try:
        if phase == "report":
            return phase_report(conn)
        if phase == "drain":
            result = phase_drain(conn, args.yes)
            if result != EXIT_OK:
                return result
            return phase_verify(connect()) if phase == "all" else result
        if phase == "verify":
            return phase_verify(conn)
        if phase == "purge":
            return phase_purge(conn, args.yes)
        if phase == "all":
            result = phase_drain(conn, args.yes)
            if result != EXIT_OK:
                return result
            result = phase_verify(connect())
            if result != EXIT_OK:
                return result
            return phase_purge(connect(), args.yes)
        return EXIT_FAILED
    finally:
        try:
            conn.close()
        except Exception:  # noqa: BLE001
            pass


if __name__ == "__main__":
    sys.exit(main())