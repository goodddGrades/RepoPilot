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
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.client = WorkerClient(settings.server_base_url, settings.worker_token)
        self.runner = build_runner(settings)
        self.evaluators = build_evaluators(settings)
        if self.runner is None and not self.evaluators:
            raise RuntimeError(f"没有声明任何可用能力，capabilities={settings.capabilities}")
        self.poller = WorkerPoller(self.client, self.runner, settings, evaluators=self.evaluators)
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
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
            self.consumer = RabbitConsumer(settings, self.run_once,
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
        with self._lock:
            summary = self.poller.run_once()
            self.last_summary = asdict(summary)
            return self.last_summary

    # ------------------------------------------------------------------ 后台

    def start(self) -> None:
        if self.consumer is not None:
            self.consumer.start()
        # 两条通道同时开是有意为之，不是重复劳动：
        #   * RabbitMQ = 快速唤醒（消息到了就干活）
        #   * HTTP 轮询 = 兜底（消息丢了、broker 挂了、消息被误入死信都不影响任务最终执行）
        # 两者都走同一个 run_once -> claim，而 claim 才是唯一决定"谁执行"的入口，
        # 所以不存在双重执行：同一任务只有一个 Worker 能领到。
        if not self.settings.autostart:
            # autostart=false 时不启轮询线程，只由消息驱动。
            # 这让演示与测试能确定性地控制"什么时候执行"，不被后台线程抢先。
            if self.consumer is not None:
                log.info("HTTP 轮询未启动（WORKER_AUTOSTART=false），由 RabbitMQ 消息驱动执行")
            else:
                log.info("后台执行未启动（WORKER_AUTOSTART=false 且未启用 RabbitMQ），"
                         "只能通过 /worker/run-once 手动触发")
            return
        if self._thread is not None:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="worker-poll", daemon=True)
        self._thread.start()
        log.info("HTTP 轮询已启动 interval=%.1fs（降级通道，与消息消费并存）",
                 self.settings.poll_interval)

    def stop(self) -> None:
        self._stop.set()
        if self.consumer is not None:
            self.consumer.stop()
        if self._thread is not None:
            self._thread.join(timeout=5)
            self._thread = None

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                self.run_once()
            except Exception:  # noqa: BLE001 - 单轮失败不能让线程退出
                log.exception("轮询失败")
            self._stop.wait(self.settings.poll_interval)

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
            "backgroundRunning": self._thread is not None and self._thread.is_alive(),
            # 消费状态如实汇报：enabled=false 不是故障，不能显示成"连接正常"。
            "consumer": self.consumer.status() if self.consumer is not None else {
                "enabled": False, "connected": False, "running": False},
            "lastSummary": self.last_summary,
        }


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
