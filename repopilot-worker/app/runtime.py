"""Worker 运行时装配。

把"客户端 + Runner + 轮询器"组装成一个对象，供 HTTP 接口与后台线程共用。
后台轮询默认关闭：本地演示与集成测试通常希望**显式**触发一次领取，
而不是让一个后台线程在没人注意时改变数据库状态。
"""

from __future__ import annotations

import threading
from dataclasses import asdict
from typing import Any

from app.client import WorkerClient
from app.common.logging import get_logger
from app.config import Settings, get_settings
from app.consumer import RabbitConsumer
from app.evaluator import registry
from app.evaluator.base import Evaluator
from app.evaluator.judge import JudgeRubric
from app.poller import WorkerPoller
from app.runner.base import Runner
from app.runner.cli_coding_agent import CliCodingAgentRunner
from app.runner.mock import MockRunner

log = get_logger("app.runtime")


def build_runner(settings: Settings) -> Runner | None:
    """按能力声明构建 Runner；声明了执行能力却没有实现时直接失败，不静默降级。

    纯评测 Worker（只声明 ``EVAL:*``）没有 Runner，这是合法配置：
    它领取评测任务，不参与 Agent 执行。

    ``RUN:CLI`` 在**装配阶段**就检查 CLI 是否可用：与其领到任务后才发现
    进程起不来，不如让 Worker 启动失败、运维立刻看到配置问题。
    """
    capabilities = {item.upper() for item in settings.capabilities}
    run_capabilities = sorted(item for item in capabilities if item.startswith("RUN:"))
    if "RUN:MOCK" in capabilities:
        return MockRunner(settings.mock_scenario,
                          fail_first_attempt=settings.mock_fail_first_attempt)
    if any(item in ("RUN:CLI", "RUN:CODEX", "RUN:CUSTOM") for item in run_capabilities):
        import shutil

        executable = shutil.which(settings.runner_cli)
        if not executable:
            raise RuntimeError(
                f"声明了真实 Runner 能力，但未找到可执行文件 {settings.runner_cli!r}；"
                "请安装该 CLI 或改用 RUN:MOCK")
        runner = CliCodingAgentRunner(
            executable=settings.runner_cli,
            workdir_root=settings.runner_workdir_root or None,
            allowed_tools=settings.runner_cli_allowed_tools or None,
            max_turns=settings.runner_cli_max_turns,
        )
        version = runner.cli_version()
        log.info("真实 Runner 已装配: executable=%s version=%s", executable, version)
        return runner
    if run_capabilities:
        raise RuntimeError(f"没有可用的 Runner 实现，capabilities={settings.capabilities}")
    return None


def build_evaluators(settings: Settings) -> dict[str, Evaluator]:
    """把 ``EVAL:<key>`` 能力解析成算子实现；未实现的 key 让启动失败。"""
    keys = [capability.split(":", 1)[1] for capability in settings.capabilities
            if capability.upper().startswith("EVAL:")]
    return registry.resolve(keys, settings) if keys else {}


class WorkerRuntime:
    # 待处理唤醒的上限。
    #
    # 这不是"队列长度"，只是一个整数计数器：消息再多也只会让计数停在这里。
    # 上限的作用是防止长时间卡住后"追账"追几百轮——那些轮次里绝大多数 claim 都是空的。
    MAX_PENDING_WAKEUPS = 1024

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.client = WorkerClient(settings.server_base_url, settings.worker_token)
        self.runner = build_runner(settings)
        self.evaluators = build_evaluators(settings)
        if self.runner is None and not self.evaluators:
            raise RuntimeError(f"没有声明任何可用能力，capabilities={settings.capabilities}")
        self.poller = WorkerPoller(self.client, self.runner, settings, evaluators=self.evaluators)
        # 执行线程只有一个：Run 串行执行（一轮领几个由 WORKER_MAX_TASKS 决定）。
        # 并发执行会改变租约、心跳与"同一任务只被领一次"的语义，不在本任务范围。
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        # 唤醒信号：消息通道只累加计数 + set 这个 Event，真正干活的是执行线程。
        #
        # **为什么是"计数 + Event"而不是队列**：队列在"任务跑得慢 + 消息来得快"时
        # 会无界增长；但只用布尔 Event 又会把 N 条消息合并成 1 次唤醒，
        # 纯消息模式（WORKER_AUTOSTART=false）下剩下的任务要等下一条消息才动。
        # 计数只占一个整数，上限 MAX_PENDING_WAKEUPS；执行线程按计数跑，
        # 一旦某一轮 claim 为空就提前收敛，不会空转几百次。
        self._wakeup = threading.Event()
        self._wakeup_lock = threading.Lock()
        self._pending_wakeups = 0
        self._lock = threading.Lock()
        self.last_summary: dict[str, Any] | None = None
        # 消费者默认不创建：未启用时连pika 都不必import。
        self.consumer: RabbitConsumer | None = None
        if settings.rabbitmq_enabled:
            if not settings.rabbitmq_password:
                # 留空密码会在 broker 上被当成"用 guest 身份连"，
                # 报出来的是认证失败而不是"你忘了配密码"，很难排查。
                raise RuntimeError(
                    "已启用 WORKER_RABBITMQ_ENABLED 但 RABBITMQ_PASSWORD 为空；"
                    "请配置与后端一致的凭据")
            self.consumer = RabbitConsumer(settings, self.schedule_wakeup,
                                           register=self.register_event,
                                           report_stats=self.report_consumer_stats)
            log.info("RabbitMQ 消费已启用 queue=%s（HTTP 轮询同时保留为降级通道）",
                     settings.rabbitmq_queue)

    # ------------------------------------------------------------------ 消费统计

    def report_consumer_stats(self, delta: dict[str, int]) -> None:
        """把消费统计增量上报给后端（TASK-013-E）。

        **异常在这里被吞掉**：统计上报是观测手段。
        让它把消费流程带崩，等于用"监控坏了"换来"业务也坏了"——
        而观测失败本该是最不该影响业务的一类故障。
        """
        try:
            self.client.report_consumer_stats(self.settings.inbox_consumer_name, delta)
        except Exception:  # noqa: BLE001 - 上报失败不影响消费
            log.warning("消费统计上报失败（已忽略，不影响消费）", exc_info=True)

    # ------------------------------------------------------------------ Inbox

    def register_event(self, envelope: dict[str, Any]) -> str:
        """把消息登记进 Inbox（去重 + 契约校验 + 异内容检测）。

        **登记在 claim 之前**，而不是"处理成功之后再登记"：
        登记的目的是让"同一条消息被投递 N 次"这件事有据可查，
        如果放在处理之后，处理失败的消息就不在 Inbox 里，
        重试会绕过所有去重与校验直接打后端。

        **不在这里catch 异常**：HTTP 失败必须冒泡到消费者，
        由它按"可重试基础设施故障"投递到退避重试队列。
        在这里吞掉异常等于把后端故障伪装成"消息处理成功"。
        """
        return self.client.accept_notification(self.settings.inbox_consumer_name, envelope)

    # ------------------------------------------------------------------ 手动

    def run_once(self) -> dict[str, Any]:
        """跑一轮"领取 + 执行"。

        ``self._lock`` 让手动触发（``/worker/run-once``）、执行线程与测试
        彼此串行：同一个 Worker 不会同时领两批任务。
        """
        with self._lock:
            summary = self.poller.run_once()
            self.last_summary = asdict(summary)
            return self.last_summary

    # ------------------------------------------------------------------ 唤醒

    def schedule_wakeup(self) -> dict[str, Any]:
        """消息通道的唤醒入口：**只记账并置位信号，立即返回**。

        这就是"长任务不占用消费者"的实现点：回调里不再跑 ``run_once``，
        所以 AMQP 回调线程不会被一个跑了十分钟的 Agent Run 堵住，
        后续消息（以及再发布队列）能继续被消费。

        **为什么不在回调里 claim**：claim 会给任务一个租约。
        领完排在执行线程的队列里等上十分钟，租约先过期，任务被别的 Worker 领走，
        这里就成了"领了但没执行"的僵尸。claim 必须发生在真正要跑的那一刻。

        **为什么计数而不是纯布尔信号**：纯布尔会把 N 条消息合并成 1 次唤醒。
        在 ``WORKER_AUTOSTART=false``（纯消息驱动）的部署里，剩下的任务要等
        下一条消息才会被领——这就是"异步化引入的任务搁置"。
        计数保证"最多 N 条消息触发 N 轮"，而空轮次会被提前收敛，不会空转。

        **停止中拒绝接活**：返回异常会让消费者按可重试失败处理（不 ACK），
        连接关闭后消息由 broker 重投，不会被静默丢掉。
        """
        with self._wakeup_lock:
            if self._stop.is_set():
                raise RuntimeError("执行线程已停止，拒绝接收新的唤醒")
            self._pending_wakeups = min(self._pending_wakeups + 1,
                                        self.MAX_PENDING_WAKEUPS)
        self._wakeup.set()
        return {"scheduled": True}

    def _take_pending_wakeups(self) -> int:
        """取走并清零待处理计数（调用方已确认被唤醒）。"""
        with self._wakeup_lock:
            pending = self._pending_wakeups
            self._pending_wakeups = 0
        return pending

    # ------------------------------------------------------------------ 后台

    def start(self) -> None:
        if self.consumer is not None:
            self.consumer.start()
        # 两条通道同时开是有意为之，不是重复劳动：
        #   * RabbitMQ = 快速唤醒（消息到了就干活）
        #   * HTTP 轮询 = 兜底（消息丢了、broker 挂了、消息被误入死信都不影响任务最终执行）
        # 两者都收敛到同一个 run_once -> claim，而 claim 才是唯一决定"谁执行"的入口，
        # 所以不存在双重执行：同一任务只有一个 Worker 能领到。
        if not self.settings.autostart and self.consumer is None:
            # 没有轮询也没有消息源：不启执行线程，只能手动触发。
            log.info("后台执行未启动（WORKER_AUTOSTART=false 且未启用 RabbitMQ），"
                     "只能通过 /worker/run-once 手动触发")
            return
        if self._thread is not None:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="worker-exec", daemon=True)
        self._thread.start()
        if self.settings.autostart:
            log.info("执行线程已启动：HTTP 轮询每 %.1fs + 消息唤醒（串行执行）",
                     self.settings.poll_interval)
        else:
            log.info("HTTP 轮询未启动（WORKER_AUTOSTART=false），由消息唤醒执行线程")

    def stop(self) -> None:
        """优雅关闭：先停消费，再等正在执行的那一轮结束。

        执行线程只在**两轮之间**检查停止标志，所以正在跑的 Run 不会被腰斩；
        等不到就放弃（``WORKER_SHUTDOWN_TIMEOUT``），让租约到期后由回收器接手。
        """
        self._stop.set()
        # 先停消费：不再有新消息进来，避免"边关边接活"。
        if self.consumer is not None:
            self.consumer.stop()
        # 叫醒可能正阻塞在 wait() 上的执行线程。
        self._wakeup.set()
        thread = self._thread
        if thread is not None:
            timeout = max(0.0, float(self.settings.shutdown_timeout))
            thread.join(timeout=timeout)
            if thread.is_alive():
                log.warning("执行线程在 %.1fs 内未结束：当前 Run 仍在执行，"
                            "租约到期后会被回收器接手", timeout)
            else:
                self._thread = None

    def _loop(self) -> None:
        """执行线程：等唤醒 / 按间隔轮询，**串行**跑 ``run_once``。

        串行是刻意的：一个 Worker 同时跑多个 Agent Run 会改变
        ``WORKER_MAX_TASKS``、心跳与租约的语义，CLI 本身也吃资源。

        每次被唤醒后按"待处理唤醒计数"跑相应轮次；**某一轮 claim 为空就提前收敛**：
        那说明活已经被别人领走了（或本来就没了），继续跑只是空转。
        """
        autostart = self.settings.autostart
        interval = max(0.05, float(self.settings.poll_interval))
        while not self._stop.is_set():
            # autostart 时按间隔轮询；纯消息模式下无限等，靠 stop() 置位唤醒退出。
            signaled = self._wakeup.wait(timeout=interval if autostart else None)
            self._wakeup.clear()
            if self._stop.is_set():
                break
            if not autostart and not signaled:
                continue
            # 被唤醒：按计数跑（多条消息 → 最多多轮）；纯轮询超时则跑一轮。
            budget = max(1, self._take_pending_wakeups()) if signaled else 1
            for _ in range(budget):
                if self._stop.is_set():
                    break
                try:
                    summary = self.run_once()
                except Exception:  # noqa: BLE001 - 单轮失败不能让线程退出
                    log.exception("执行轮次失败（线程继续）")
                    break
                if int(summary.get("claimed", 0) or 0) <= 0:
                    # 没领到任务：剩下的唤醒已无意义。计数不再清理——
                    # 若此刻真有新消息进来，它会把 Event 重新置位，下一轮照常处理。
                    break

    def status(self) -> dict[str, Any]:
        runner_detail: dict[str, Any] = {}
        if self.runner is not None:
            runner_detail = {"type": getattr(self.runner, "name", None),
                             "scenario": getattr(self.runner, "scenario", None)}
            if isinstance(self.runner, CliCodingAgentRunner):
                # 如实报告真实执行器身份：CLI 名称与版本可核对，不含糊成"某种 runner"
                runner_detail.update({"type": "CLI", "executable": self.settings.runner_cli,
                                      "cliVersion": self.runner.cli_version(),
                                      "realExecution": True})
            else:
                runner_detail["realExecution"] = False

        judge_detail: dict[str, Any] = {}
        judge = self.evaluators.get("judge.rubric")
        if isinstance(judge, JudgeRubric):
            describe = getattr(judge.client, "describe", None)
            judge_detail = {"provider": judge.client.name,
                            "rubrics": sorted(judge.rubrics),
                            "realInvocation": judge.client.name != "stub"}
            if callable(describe):
                judge_detail.update(describe())

        return {
            "workerId": self.settings.worker_id,
            "capabilities": self.settings.capabilities,
            "runner": runner_detail.get("type"),
            "runnerDetail": runner_detail,
            "scenario": runner_detail.get("scenario"),
            "evaluators": sorted(self.evaluators),
            "judge": judge_detail,
            # 限流状态：阈值 + 三类计数。不做成独立监控系统，直接挂在状态接口上，
            # 演示与排障都能看到"放行了多少、拒了多少、Redis 故障几次"。
            "rateLimit": self._rate_limit_status(),
            "backgroundRunning": self._thread is not None and self._thread.is_alive(),
            # 消费状态如实汇报：enabled=false 不是故障，不能显示成"连接正常"。
            "consumer": self.consumer.status() if self.consumer is not None else {
                "enabled": False, "connected": False, "running": False},
            "lastSummary": self.last_summary,
        }

    def _rate_limit_status(self) -> dict[str, Any]:
        """限流策略与计数快照。"""
        from app.ratelimit.metrics import METRICS

        settings = self.settings
        enabled = bool(getattr(settings, "rate_limit_enabled", False))
        detail: dict[str, Any] = {
            "enabled": enabled,
            "environment": getattr(settings, "rate_limit_environment", None),
            "failureMode": getattr(settings, "rate_limit_failure_mode", None),
            "dimension": getattr(settings, "judge_rate_limit_dimension", None),
            "counters": METRICS.snapshot(),
        }
        if enabled:
            detail["policy"] = {
                "name": "judge-model",
                "capacity": getattr(settings, "judge_rate_limit_capacity", None),
                "refillPerSecond": getattr(settings, "judge_rate_limit_refill_per_second", None),
                "costPerRequest": getattr(settings, "judge_rate_limit_cost", None),
                "ttlSeconds": getattr(settings, "judge_rate_limit_ttl_seconds", None),
            }
        return detail


_runtime: WorkerRuntime | None = None
_runtime_lock = threading.Lock()


def get_runtime() -> WorkerRuntime:
    """进程级单例（配置与 Runner 都不应每个请求重建）。"""
    global _runtime
    with _runtime_lock:
        if _runtime is None:
            _runtime = WorkerRuntime(get_settings())
        return _runtime


def reset_runtime() -> None:
    """测试用：清空单例。"""
    global _runtime
    with _runtime_lock:
        if _runtime is not None:
            _runtime.stop()
        _runtime = None
