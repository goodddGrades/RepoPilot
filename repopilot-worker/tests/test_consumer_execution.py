"""执行线程模型：长任务不占用消费者（TASK-013 修复）。

这一组回答的是**架构问题**，不是协议问题：

    一个跑十分钟的 Agent Run，会不会把 RabbitMQ 消费者堵死？

修复前 ``on_wakeup`` 接的是 ``runtime.run_once``，在 AMQP 回调线程里同步跑完
claim → 执行 → 回传 → ACK。任务执行期间这条连接不取新消息，再发布队列一起停摆。
修复后回调只做三件短事：Inbox 登记 → ``Event.set()`` → ACK；
真正的执行在 ``runtime`` 里唯一的 ``worker-exec`` 线程里串行进行。

分两层验证：

* **不依赖 broker 的部分**（唤醒合并、线程有界、异常后线程存活、优雅关闭）
  用假的 poller，永远可跑；
* **依赖真实 broker 的部分**（长任务期间仍能消费、重试、重复投递）
  需要 ``WORKER_TEST_RABBIT_*``，没有就跳过——跳过会出现在报告里。
"""

from __future__ import annotations

import json
import os
import threading
import time
from typing import Any, Callable

import pytest

from app.config import Settings
from app.consumer import ConsumerError
from app.poller import PollSummary
from app.runtime import WorkerRuntime

RABBIT_HOST = os.environ.get("WORKER_TEST_RABBIT_HOST")
RABBIT_PORT = int(os.environ.get("WORKER_TEST_RABBIT_PORT", "5672"))
RABBIT_USER = os.environ.get("WORKER_TEST_RABBIT_USER", "repopilot")
RABBIT_PASSWORD = os.environ.get("WORKER_TEST_RABBIT_PASSWORD", "")

broker_required = pytest.mark.skipif(
    not RABBIT_HOST or not RABBIT_PASSWORD,
    reason="需要真实 RabbitMQ：设置 WORKER_TEST_RABBIT_HOST / WORKER_TEST_RABBIT_PASSWORD",
)

EVENT_ID_A = "01M5C0000000000000000000AA"
EVENT_ID_B = "01M5C0000000000000000000BB"


# ---------------------------------------------------------------- 测试替身


class BlockingPoller:
    """第一轮卡住不返回，模拟"跑十分钟的 Agent Run"。

    ``release`` 不置位就一直阻塞，测试因此可以确定性地观察
    "执行还没结束"这个状态，而不是靠 sleep 猜。
    """

    def __init__(self, work: int = 1) -> None:
        self.calls = 0
        self.remaining = work
        self.entered = threading.Event()
        self.release = threading.Event()
        self._lock = threading.Lock()

    def run_once(self) -> PollSummary:
        with self._lock:
            self.calls += 1
            call = self.calls
            if self.remaining > 0:
                self.remaining -= 1
                claimed = 1
            else:
                claimed = 0
        self.entered.set()
        # 第一轮一直卡住；后续轮次立即返回，避免测试收尾时挂住。
        if call == 1:
            self.release.wait(timeout=15)
        return PollSummary(claimed=claimed)


class CountingPoller:
    """有 ``work`` 个任务可领，领完之后返回 0。"""

    def __init__(self, work: int) -> None:
        self.remaining = work
        self.calls = 0
        self._lock = threading.Lock()

    def run_once(self) -> PollSummary:
        with self._lock:
            self.calls += 1
            if self.remaining > 0:
                self.remaining -= 1
                return PollSummary(claimed=1)
        return PollSummary(claimed=0)


class FlakyPoller:
    """第一次执行抛异常，第二次正常返回。"""

    def __init__(self) -> None:
        self.calls = 0
        self.entered = threading.Event()

    def run_once(self) -> PollSummary:
        self.calls += 1
        if self.calls == 1:
            raise RuntimeError("模拟执行崩溃")
        self.entered.set()
        return PollSummary()


def runtime_settings(**overrides: Any) -> Settings:
    base: dict[str, Any] = {
        "worker_id": "test-worker",
        "capabilities_raw": "RUN:MOCK",
        "autostart": False,
        "poll_interval": 0.2,
        "shutdown_timeout": 5.0,
    }
    base.update(overrides)
    return Settings(**base)


def loop_settings(**overrides: Any) -> Settings:
    """带执行线程但不主动轮询的配置。

    ``poll_interval`` 设得足够大：自动轮询在 1 小时后才会发生，
    测试期间的所有执行都由 ``schedule_wakeup`` 驱动，结果确定。
    """
    base: dict[str, Any] = {"autostart": True, "poll_interval": 3600.0}
    base.update(overrides)
    return runtime_settings(**base)


def wait_until(predicate: Callable[[], bool], timeout: float = 3.0,
               interval: float = 0.02) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return predicate()


# ---------------------------------------------------------------- 真实 broker 辅助


def connection() -> Any:
    import pika

    return pika.BlockingConnection(pika.ConnectionParameters(
        host=RABBIT_HOST, port=RABBIT_PORT,
        credentials=pika.PlainCredentials(RABBIT_USER, RABBIT_PASSWORD),
    ))


def drain(queue: str) -> int:
    conn = connection()
    try:
        channel = conn.channel()
        count = 0
        while True:
            method, _, _ = channel.basic_get(queue=queue, auto_ack=True)
            if method is None:
                return count
            count += 1
    finally:
        conn.close()


def queue_depth(queue: str) -> int:
    conn = connection()
    try:
        return conn.channel().queue_declare(queue=queue, passive=True).method.message_count
    finally:
        conn.close()


def publish(body: bytes, routing_key: str = "evaluation.ready") -> None:
    conn = connection()
    try:
        conn.channel().basic_publish("repopilot.events", routing_key, body=body)
    finally:
        conn.close()


def envelope_bytes(event_id: str, routing_key: str = "evaluation.ready") -> bytes:
    return json.dumps({
        "schemaVersion": 1,
        "eventId": event_id,
        "eventType": routing_key,
        "aggregateId": "01M5C0000000000000000000CC",
        "aggregateType": "EVALUATION_JOB",
        "occurredAt": "2026-10-20T02:00:00Z",
        "correlationId": "01M5C0000000000000000000DD",
        "payload": {"jobId": "01M5C0000000000000000000CC"},
    }).encode()


QUEUE = "repopilot.evaluation.ready"
RETRY_QUEUE_T1 = "repopilot.retry.wait.t1"


@pytest.fixture()
def broker_runtime() -> Any:
    """真实 broker 上的 runtime：消费者 + 执行线程都真启动。"""
    for name in (QUEUE, RETRY_QUEUE_T1, "repopilot.events.dlq"):
        try:
            drain(name)
        except Exception:  # noqa: BLE001 - 队列可能还不存在
            pass

    settings = runtime_settings(
        rabbitmq_enabled=True,
        rabbitmq_host=RABBIT_HOST,
        rabbitmq_port=RABBIT_PORT,
        rabbitmq_user=RABBIT_USER,
        rabbitmq_password=RABBIT_PASSWORD,
        rabbitmq_queue=QUEUE,
        rabbitmq_prefetch=1,
        inbox_consumer_name="worker-test",
    )
    runtime = WorkerRuntime(settings)
    yield runtime
    runtime.stop()
    for name in (QUEUE, RETRY_QUEUE_T1, "repopilot.events.dlq"):
        try:
            drain(name)
        except Exception:  # noqa: BLE001
            pass


# ---------------------------------------------------------------- 核心场景


@broker_required
def test_long_run_does_not_block_consumer(broker_runtime: WorkerRuntime) -> None:
    """一个 Run 卡住时，消费者仍能登记并 ACK 后续消息。

    这是本次修复的**主断言**。修复前第二条消息会一直卡在回调里，
    直到第一个 Run 结束——表现就是"消息进来了但没人处理"。
    """
    slow = BlockingPoller()
    broker_runtime.poller = slow
    registered: list[str] = []

    def register(envelope: dict[str, Any]) -> str:
        registered.append(envelope["eventId"])
        return "ACCEPTED"

    broker_runtime.consumer.register = register  # type: ignore[union-attr]
    broker_runtime.start()
    try:
        assert wait_until(lambda: broker_runtime.consumer.connected, 10), "消费者未连上 broker"

        publish(envelope_bytes(EVENT_ID_A))
        assert slow.entered.wait(5), "执行线程应开始跑第一轮"

        publish(envelope_bytes(EVENT_ID_B))

        assert wait_until(lambda: len(registered) == 2, 3), (
            f"长任务执行期间消费者必须继续处理消息，实际只登记了 {registered}")
        assert not slow.release.is_set(), "第一轮仍在执行——后续消息是在执行期间被处理的"

        # 两条消息都应已 ACK（队列深度归零），而不是挂在 unacked 上。
        assert wait_until(lambda: queue_depth(QUEUE) == 0, 3), (
            f"消息必须已被确认，队列深度={queue_depth(QUEUE)}")

        totals = broker_runtime.consumer.status()["totals"]  # type: ignore[union-attr]
        assert totals["wakeups"] >= 2
        assert totals["retried"] == 0
        assert totals["deadLettered"] == 0
    finally:
        slow.release.set()
        broker_runtime.stop()


@broker_required
def test_retryable_failure_still_retries_while_run_executes(
        broker_runtime: WorkerRuntime) -> None:
    """Run 执行期间，后来的消息若登记失败，仍要进退避队列而不是被吞掉。"""
    slow = BlockingPoller()
    broker_runtime.poller = slow

    def register(envelope: dict[str, Any]) -> str:
        if envelope["eventId"] == EVENT_ID_B:
            raise ConsumerError("后端暂时不可用", retryable=True)
        return "ACCEPTED"

    broker_runtime.consumer.register = register  # type: ignore[union-attr]
    broker_runtime.start()
    try:
        assert wait_until(lambda: broker_runtime.consumer.connected, 10)
        publish(envelope_bytes(EVENT_ID_A))
        assert slow.entered.wait(5)

        publish(envelope_bytes(EVENT_ID_B))

        # t1 的 TTL 是 5 秒：必须在它到期前看到消息落在等待队列里。
        assert wait_until(lambda: queue_depth(RETRY_QUEUE_T1) >= 1, 3), (
            "可重试失败必须进入退避队列，即使执行线程正忙")
        assert not slow.release.is_set()
    finally:
        slow.release.set()
        broker_runtime.stop()


@broker_required
def test_duplicate_delivery_during_long_run_is_idempotent(
        broker_runtime: WorkerRuntime) -> None:
    """执行期间重复投递同一条消息：都 ACK、都不进死信、唤醒被合并。"""
    slow = BlockingPoller()
    broker_runtime.poller = slow
    seen: list[str] = []

    def register(envelope: dict[str, Any]) -> str:
        # 第一次 ACCEPTED，之后同 eventId 一律 DUPLICATE（真实 Inbox 的唯一键语义）。
        if envelope["eventId"] in seen:
            return "DUPLICATE"
        seen.append(envelope["eventId"])
        return "ACCEPTED"

    broker_runtime.consumer.register = register  # type: ignore[union-attr]
    broker_runtime.start()
    try:
        assert wait_until(lambda: broker_runtime.consumer.connected, 10)
        body = envelope_bytes(EVENT_ID_A)
        publish(body)
        assert slow.entered.wait(5)

        publish(body)
        publish(body)

        assert wait_until(lambda: len(seen) == 1, 3), "重复投递必须命中 Inbox 去重"
        assert wait_until(lambda: queue_depth(QUEUE) == 0, 3), "重复消息也必须被 ACK"

        totals = broker_runtime.consumer.status()["totals"]  # type: ignore[union-attr]
        assert totals["duplicates"] == 2
        assert totals["deadLettered"] == 0, "重复投递是正常现象，不该进死信"

        slow.release.set()
        # 三次投递只应触发"一次执行 + 一次合并后的补跑"，不会有第三次。
        assert wait_until(lambda: slow.calls <= 2, 3)
        assert slow.calls <= 2, f"唤醒应被合并，实际执行了 {slow.calls} 次"
    finally:
        slow.release.set()
        broker_runtime.stop()


# ---------------------------------------------------------------- 不依赖 broker 的保证


def test_many_wakeups_stay_bounded_and_drain_all_work() -> None:
    """200 次唤醒：线程数仍只多一个，且**不丢活**——有多少任务就领多少轮。

    这里同时锁住两件事：
    * 有界：不因为消息多就开线程、也不建无界队列；
    * 不丢：唤醒计数保证"最多 N 条消息触发 N 轮"，不会被合并成一轮导致任务搁置。
    领完之后的那一轮返回 0，循环立即收敛，不会空转 200 次。
    """
    runtime = WorkerRuntime(loop_settings())
    poller = CountingPoller(work=5)
    runtime.poller = poller
    baseline = threading.active_count()

    runtime.start()
    try:
        for _ in range(200):
            runtime.schedule_wakeup()

        assert wait_until(lambda: poller.remaining == 0, 5), (
            f"5 个任务必须全部被领走，剩余 {poller.remaining}")
        assert threading.active_count() <= baseline + 1, (
            f"线程数应只多一个执行线程，实际 {threading.active_count()}（基线 {baseline}）")

        # 5 次领取 + 1 次空轮次用于收敛；远小于 200 次唤醒。
        assert poller.calls <= 7, f"空轮次应提前收敛，实际跑了 {poller.calls} 轮"
    finally:
        runtime.stop()


def test_execution_exception_does_not_kill_execution_thread() -> None:
    """一轮执行抛异常，线程必须活着并在下一次唤醒继续干活。"""
    runtime = WorkerRuntime(loop_settings())
    flaky = FlakyPoller()
    runtime.poller = flaky

    runtime.start()
    try:
        runtime.schedule_wakeup()
        assert wait_until(lambda: flaky.calls >= 1, 3)
        # 第一次抛异常后线程仍在
        assert runtime._thread is not None and runtime._thread.is_alive()

        runtime.schedule_wakeup()
        assert flaky.entered.wait(3), "异常之后的下一次唤醒仍应被执行"
        assert flaky.calls >= 2
    finally:
        runtime.stop()


def test_graceful_shutdown_waits_for_inflight_run() -> None:
    """stop() 必须等在跑的 Run 结束，而不是把它腰斩。

    执行线程只在两轮之间检查停止标志；``stop`` 通过 join 等待当前轮次。
    """
    runtime = WorkerRuntime(loop_settings(shutdown_timeout=5.0))
    slow = BlockingPoller()
    runtime.poller = slow

    runtime.start()
    try:
        runtime.schedule_wakeup()
        assert slow.entered.wait(3)

        stopper = threading.Thread(target=runtime.stop, name="stopper")
        stopper.start()
        time.sleep(0.3)
        assert stopper.is_alive(), "正在执行的 Run 未结束前，stop() 不应返回"

        slow.release.set()
        stopper.join(timeout=5)
        assert not stopper.is_alive(), "Run 结束后 stop() 必须返回"
        assert runtime._thread is None, "执行线程应在 stop() 后退出"
    finally:
        slow.release.set()
        runtime.stop()


def test_shutdown_rejects_new_wakeups() -> None:
    """停止后不再接活：唤醒会失败，让消息保持未 ACK 由 broker 重投。"""
    runtime = WorkerRuntime(loop_settings(shutdown_timeout=0.0))
    runtime.start()
    runtime.stop()

    with pytest.raises(RuntimeError):
        runtime.schedule_wakeup()
