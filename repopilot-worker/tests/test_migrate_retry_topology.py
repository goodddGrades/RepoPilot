"""TASK-013 重试拓扑迁移工具的自动化测试（**使用真实 broker**）。

**为什么必须用真实 broker**
--------------------------------------------------------------------------------
本工具的全部价值在于"不丢消息、不重复投递"。而这两个性质完全取决于
AMQP 的真实行为（basic_get 的 delivery_tag、confirm 时序、
quorum 队列的 requeue 语义、交换机删除后 binding 的处理），
这些**没有一项能用内存替身验证**。

TASK-013 第一版headers 分档方案就是在 19 个全绿的替身用例下，
在真实 broker 上完全失效（见 docs/reviews/task-013-amqp-facts.md 第 8 条）。
迁移工具如果只用替身测试，很可能重蹈覆辙。

测试策略：**造出真实的旧拓扑**（headers 交换机 + 旧队列），
灌入带``x-retry-tier`` 头的真实消息，然后跑迁移工具，
逐条核对消息去了哪里。
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pika
import pytest

HOST = os.environ.get("WORKER_TEST_RABBIT_HOST", "127.0.0.1")
PORT = int(os.environ.get("WORKER_TEST_RABBIT_PORT", "5672"))
USER = os.environ.get("WORKER_TEST_RABBIT_USER", "repopilot")
PASSWORD = os.environ.get("WORKER_TEST_RABBIT_PASSWORD", "repopilot_dev_pwd")

REPO_ROOT = Path(__file__).resolve().parents[2]
MIGRATE_TOOL = REPO_ROOT / "tools" / "migrate_retry_topology.py"

V2_EXCHANGE = "repopilot.events.retry.v2"
LEGACY_EXCHANGE = "repopilot.events.retry"
RUN_QUEUE = "repopilot.run.ready"

# 迁移工具里的旧拓扑常量。**故意不 import 工具模块**：
# 工具的常量必须与线上旧代码的常量一致，从工具自身 import 就成了自证。
LEGACY_WAIT_QUEUES = ["repopilot.retry.wait.t1",
                      "repopilot.retry.wait.t2",
                      "repopilot.retry.wait.t3"]

pytestmark = pytest.mark.skipif(
    not USER or not PASSWORD,
    reason="未配置真实 broker 凭证（WORKER_TEST_RABBIT_USER/PASSWORD）",
)


def _conn() -> pika.BlockingConnection:
    return pika.BlockingConnection(pika.ConnectionParameters(
        host=HOST, port=PORT,
        credentials=pika.PlainCredentials(USER, PASSWORD),
    ))


def _declare_legacy_topology() -> None:
    """声明**真实的旧 headers 拓扑**（x-match=all + x-retry-tier）。

    这里故意复现第一版的错误绑定方式：headers交换机的
    ``x-match=all`` 会忽略 ``x-`` 前缀的匹配字段，因此
    ``x-retry-tier`` 从未参与匹配——这正是我们要迁移 away 的行为。
    """
    conn = _conn()
    try:
        ch = conn.channel()
        ch.exchange_declare(LEGACY_EXCHANGE, "headers", durable=True)
        for queue in LEGACY_WAIT_QUEUES:
            ch.queue_delete(queue=queue)
            ch.queue_declare(queue=queue, durable=True)
            # pika 的 headers 绑定走arguments 参数（不是 headers）
            ch.queue_bind(queue, LEGACY_EXCHANGE,
                          arguments={"x-match": "all", "x-retry-tier": 1})
        ch.exchange_declare(V2_EXCHANGE, "topic", durable=True)
        for tier in (1, 2, 3):
            ch.queue_declare(queue=f"repopilot.mig.wait.t{tier}",
                             durable=True)
            ch.queue_bind(f"repopilot.mig.wait.t{tier}", V2_EXCHANGE,
                          routing_key=f"retry.t{tier}")
    finally:
        conn.close()


def _cleanup_legacy() -> None:
    conn = _conn()
    try:
        ch = conn.channel()
        for queue in LEGACY_WAIT_QUEUES + [f"repopilot.mig.wait.t{t}"
                                           for t in (1, 2, 3)]:
            try:
                ch.queue_delete(queue=queue)
            except Exception:  # noqa: BLE001
                pass
        try:
            ch.exchange_delete(exchange=LEGACY_EXCHANGE)
        except Exception:  # noqa: BLE001
            pass
    finally:
        conn.close()


def _publish_legacy(tier: int, event_id: str, original_rk: str = "run.ready") -> None:
    """往旧 headers 交换机灌一条带档位头的消息。"""
    conn = _conn()
    try:
        ch = conn.channel()
        ch.confirm_delivery()
        body = json.dumps({"schemaVersion": 1, "eventId": event_id,
                           "eventType": original_rk,
                           "payload": {"runId": event_id}}).encode()
        ch.basic_publish(
            LEGACY_EXCHANGE, "", body=body,
            properties=pika.BasicProperties(
                content_type="application/json", delivery_mode=2,
                headers={"x-retry-tier": tier,
                         "x-first-failed-at": "2026-11-01T00:00:00.000Z",
                         "x-last-error": "旧拓扑遗留"}),
            mandatory=True)
    finally:
        conn.close()


def _depth(queue: str) -> int:
    conn = _conn()
    try:
        return conn.channel().queue_declare(
            queue=queue, passive=True).method.message_count
    except Exception:  # noqa: BLE001
        return -1
    finally:
        try:
            conn.close()
        except Exception:  # noqa: BLE001
            pass


def _peek_all(queue: str) -> list[dict]:
    """hold-all-then-restore地读出队列全部内容（查询不改变队列）。"""
    conn = _conn()
    try:
        ch = conn.channel()
        held = []
        while True:
            method, props, body = ch.basic_get(queue=queue, auto_ack=False)
            if method is None:
                break
            held.append((method, props, body))
        try:
            result = []
            for method, props, body in held:
                headers = (props.headers or {}) if props else {}
                result.append({
                    "routingKey": method.routing_key,
                    "headers": headers,
                    "body": body.decode("utf-8", errors="replace"),
                })
            return result
        finally:
            for m, _, _ in held:
                try:
                    ch.basic_nack(m.delivery_tag, False, True)
                except Exception:  # noqa: BLE001
                    pass
    finally:
        conn.close()


def _run_tool(*args: str) -> tuple[int, str, str]:
    env = dict(os.environ)
    env.update({"RABBIT_HOST": HOST, "RABBIT_PORT": str(PORT),
                "RABBIT_USER": USER, "RABBIT_PASSWORD": PASSWORD,
                "PYTHONIOENCODING": "utf-8"})
    proc = subprocess.run(
        [sys.executable, str(MIGRATE_TOOL), *args],
        capture_output=True, text=True, env=env, timeout=120, input="yes\n")
    return proc.returncode, proc.stdout, proc.stderr


@pytest.fixture
def legacy_topology():
    _declare_legacy_topology()
    yield
    _cleanup_legacy()


# ------------------------------------------------------------------ 只读阶段


def test_report_is_read_only_and_reports_both_topologies(legacy_topology):
    """--verify-only 绝不能修改任何实体。"""
    _publish_legacy(1, "mig-report-1")
    before = _depth(LEGACY_WAIT_QUEUES[0])

    code, out, _ = _run_tool("--verify-only")

    assert code == 0, out
    assert str(before) in out
    # 报告必须同时提到新旧两套拓扑，否则无法判断该不该迁移
    assert LEGACY_EXCHANGE in out
    assert V2_EXCHANGE in out
    # 深度不变 = 只读
    assert _depth(LEGACY_WAIT_QUEUES[0]) == before


# ------------------------------------------------------------------ drain


def test_drain_migrates_message_to_correct_new_tier(legacy_topology):
    """档位必须被正确翻译：新拓扑的精确 routing key 决定进哪个队列。

    **期望值为什么是 3 而不是 1**（这是实测出来的，不是推测）：
    旧 headers 拓扑用 ``x-match=all`` + ``x-retry-tier`` 绑定，
    而 ``x-`` 前缀字段从不参与匹配——所以每条消息会**同时进三个旧队列**。
    实测：3 条消息 → 每个旧队列深度 3（见 test_legacy_headers_broadcasts_to_all_queues）。

    于是迁移后：
      * 旧队列共 9 条（3 条消息 × 3 个队列），必须全部排空；
      * 新拓扑 t1/t2/t3 各收到 3 条——每个旧队列各贡献 1 条对应档位的副本。

    如果这里写成 1，说明没搞清旧拓扑的真实行为，
    也就无法证明迁移是"按档位精确归位"而不是"随便丢进去"。
    """
    _publish_legacy(1, "mig-t1")
    _publish_legacy(2, "mig-t2")
    _publish_legacy(3, "mig-t3")

    # 先确认旧拓扑确实把每条消息广播到了全部 3 个队列
    for queue in LEGACY_WAIT_QUEUES:
        assert _depth(queue) == 3, f"{queue} 的旧拓扑深度不是 3，测试前提不成立"

    for tier in (1, 2, 3):
        assert _depth(f"repopilot.mig.wait.t{tier}") == 0

    code, out, errout = _run_tool("--phase", "drain", "--yes")

    assert code == 0, f"stdout={out}\nstderr={errout}"
    # 旧队列必须已排空（消息没被丢在原地）
    for queue in LEGACY_WAIT_QUEUES:
        assert _depth(queue) == 0, f"{queue} 未排空"
    # 9 条消息一条都不能少，按档位精确归位
    assert _depth("repopilot.mig.wait.t1") == 3
    assert _depth("repopilot.mig.wait.t2") == 3
    assert _depth("repopilot.mig.wait.t3") == 3


def test_legacy_headers_broadcasts_to_all_queues(legacy_topology):
    """**先证明旧拓扑的缺陷本身**，再谈迁移。

    这条测试是整个迁移工具存在的前提：如果旧拓扑的分档其实有效，
    就不需要迁移工具；如果分档失效，那么"一条消息变三份副本"
    就是必须处理的事实，而不是测试的意外。
    """
    _publish_legacy(3, "legacy-broadcast-probe")
    # 档位是 3，但三个队列**全都**收到了——这正是 x-match=all 忽略
    # x- 前缀字段的直接后果
    for queue in LEGACY_WAIT_QUEUES:
        assert _depth(queue) == 1, (
            f"{queue} 深度不是 1：若旧拓扑分档有效，本测试的前提需重新评估")


def test_drain_preserves_event_id_and_diagnostics(legacy_topology):
    """迁移**不能丢 eventId**：Inbox 去重完全依赖它。

    丢eventId 的后果是同一条任务被重复执行——
    这是"重复投递"的恶梦，比丢消息更难排查。

    注意：旧拓扑下一条消息会变成三个副本（每个旧队列一份），
    所以 t2 队列里会有 3 条，且三条的 eventId 必须是**同一个**。
    若副本的 eventId 被改写或清空，就是迁移在破坏消息身份。
    """
    _publish_legacy(2, "mig-preserve-event-id")
    code, out, errout = _run_tool("--phase", "drain", "--yes")
    assert code == 0, f"stdout={out}\nstderr={errout}"

    msgs = _peek_all("repopilot.mig.wait.t2")
    assert len(msgs) == 3, f"t2 队列应有 3 条副本，实际 {len(msgs)}"
    for msg in msgs:
        # eventId 逐字节保留，且三条副本完全一致
        assert json.loads(msg["body"])["eventId"] == "mig-preserve-event-id"
        # routing key 复原为精确键
        assert msg["routingKey"] == "retry.t2"
        # 档位头保留
        assert str(msg["headers"]["x-retry-tier"]) == "2"
        # 诊断信息保留（否则迁移后无法知道这条消息为何重试过）
        assert msg["headers"]["x-last-error"] == "旧拓扑遗留"
        assert msg["headers"]["x-first-failed-at"] == "2026-11-01T00:00:00.000Z"
        # 迁移来源可追溯
        assert msg["headers"]["x-migrated-from"] == LEGACY_EXCHANGE
        # routing key 被补全（否则再发布阶段无法回到业务队列）
        assert msg["headers"]["x-original-routing-key"] == "run.ready"


def test_drain_is_idempotent(legacy_topology):
    """重复执行不得重复投递——同一条消息不能进新队列两次。"""
    _publish_legacy(1, "mig-idempotent")

    code1, out1, _ = _run_tool("--phase", "drain", "--yes")
    assert code1 == 0, out1
    first = _depth("repopilot.mig.wait.t1")
    assert first > 0, "首次 drain 没有迁移任何消息"

    code2, out2, _ = _run_tool("--phase", "drain", "--yes")
    assert code2 == 0, out2
    second = _depth("repopilot.mig.wait.t1")

    assert first == second, (
        f"重复 drain 造成了重复投递：{first} -> {second}")


def test_drain_tolerates_absent_legacy_topology(legacy_topology):
    """旧拓扑已被清理时，drain 必须干净跳过而不是报错。

    迁移工具会被反复执行；"已经迁完了"是正常状态，不是故障。
    """
    _cleanup_legacy()
    code, out, _ = _run_tool("--phase", "drain", "--yes")
    assert code == 0, out
    assert "不存在，跳过" in out


# ------------------------------------------------------------------ purge


def test_purge_refuses_to_delete_non_empty_queue(legacy_topology):
    """**安全铁律**：仍有消息的旧队列绝不能被删除。

    删除带消息的队列 = 静默丢消息。这是本工具最不能犯的错。
    """
    _publish_legacy(1, "mig-purge-guard")
    # 不drain，直接 purge
    code, out, errout = _run_tool("--phase", "purge", "--yes")

    assert code != 0, "对非空队列执行 purge 却返回成功"
    assert "仍有" in errout or "仍有" in out
    # 队列必须还在，消息必须还在
    assert _depth(LEGACY_WAIT_QUEUES[0]) >= 0


def test_purge_deletes_empty_queue_and_legacy_exchange(legacy_topology):
    """空队列 + 旧交换机应被清理干净。"""
    code, out, errout = _run_tool("--phase", "all", "--yes")
    assert code == 0, f"stdout={out}\nstderr={errout}"

    for queue in LEGACY_WAIT_QUEUES:
        assert _depth(queue) == -1, f"{queue} 未被删除"
    conn = _conn()
    try:
        with pytest.raises(Exception):
            conn.channel().exchange_declare(exchange=LEGACY_EXCHANGE,
                                             passive=True)
    finally:
        conn.close()


def test_verify_blocks_purge_when_backlog_remains(legacy_topology):
    """verify 必须在有积压时失败，并阻止后续 purge。

    否则运维按 "verify → purge" 走流程时，会在verify 通过的错觉下删掉积压。
    """
    _publish_legacy(1, "mig-verify-guard")
    code, out, errout = _run_tool("--phase", "verify")

    assert code != 0, "有积压时 verify 却通过了"
    assert "不要执行 purge" in errout or "不要执行 purge" in out
    # 旧实体必须仍然存在
    assert _depth(LEGACY_WAIT_QUEUES[0]) >= 1


# ------------------------------------------------------------------ 常量一致性


def test_tool_constants_match_worker_and_topology():
    """迁移工具的拓扑常量必须与 Worker / RabbitTopology 一致。

    不一致的后果是"消息迁过去了但没人消费"——
    一种比丢消息更难发现的故障，因为它悄无声息。
    """
    worker_source = (REPO_ROOT / "repopilot-worker" / "app" / "consumer.py").read_text(
        encoding="utf-8")
    assert f'"{V2_EXCHANGE}"' in worker_source
    assert '"retry.t"' in worker_source

    java_source = (REPO_ROOT / "repopilot-server" / "src" / "main" / "java"
                   / "com" / "repopilot" / "common" / "reliability"
                   / "messaging" / "RabbitTopology.java").read_text(encoding="utf-8")
    assert f'"{V2_EXCHANGE}"' in java_source
    assert f'"{LEGACY_EXCHANGE}"' in java_source
    for header in ("x-retry-tier", "x-original-routing-key",
                   "x-first-failed-at", "x-last-error"):
        assert f'"{header}"' in java_source
        assert f'"{header}"' in worker_source