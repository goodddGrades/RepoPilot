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

    # ------------------------------------------------------------------ 手动

    def run_once(self) -> dict[str, Any]:
        with self._lock:
            summary = self.poller.run_once()
            self.last_summary = asdict(summary)
            return self.last_summary

    # ------------------------------------------------------------------ 后台

    def start(self) -> None:
        if self._thread is not None:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="worker-poll", daemon=True)
        self._thread.start()
        log.info("后台轮询已启动 interval=%.1fs", self.settings.poll_interval)

    def stop(self) -> None:
        self._stop.set()
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
