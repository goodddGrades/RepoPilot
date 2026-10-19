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
from app.poller import WorkerPoller
from app.runner.base import Runner
from app.runner.mock import MockRunner

log = get_logger("app.runtime")


def build_runner(settings: Settings) -> Runner:
    """按能力声明构建 Runner；没有可用实现时直接失败，不静默降级。"""
    if "RUN:MOCK" in settings.capabilities:
        return MockRunner(settings.mock_scenario,
                          fail_first_attempt=settings.mock_fail_first_attempt)
    raise RuntimeError(f"没有可用的 Runner 实现，capabilities={settings.capabilities}")


class WorkerRuntime:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.client = WorkerClient(settings.server_base_url, settings.worker_token)
        self.runner = build_runner(settings)
        self.poller = WorkerPoller(self.client, self.runner, settings)
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
        return {
            "workerId": self.settings.worker_id,
            "capabilities": self.settings.capabilities,
            "runner": self.runner.name,
            "scenario": getattr(self.runner, "scenario", None),
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
