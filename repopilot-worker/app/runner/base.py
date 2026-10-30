"""Runner 抽象。

Runner 只负责"把一个样本跑成一次执行结果"，不负责领取、租约、重试或持久化——
那些是 Java 后端的职责。这样替换真实 Runner（Codex/Custom）时不需要改协议代码。

约定：
* ``run`` 返回 :class:`RunnerResult` 表示**执行完成**，即使任务本身失败
  （``task_outcome=FAILURE``）也属于"完成"，仍需封口并进入评测；
* 只有无法产出可信结果的**技术失败**才抛 :class:`RunnerFailure`，
  由 Worker 调用 ``/fail`` 交给后端决定是否重试。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Callable, Protocol


@dataclass(frozen=True)
class RunnerRequest:
    """一次执行输入（来自后端 WorkItem.input）。"""

    run_id: str
    attempt_id: str
    attempt_no: int
    runner_type: str
    agent_config: dict[str, Any]
    budget: dict[str, Any]
    case: dict[str, Any]
    deadline_at: datetime


@dataclass(frozen=True)
class RunnerEvent:
    """待上报的轨迹事件。``event_id`` 由 Worker 首次生成并保持不变（重发时不换 ID）。"""

    event_id: str
    event_type: str
    payload: dict[str, Any]
    sequence_no: int
    occurred_at: datetime


@dataclass
class RunnerResult:
    """正常结束（含业务失败）的结果。"""

    task_outcome: str
    output: dict[str, Any] | None = None
    usage: dict[str, Any] | None = None
    events: list[RunnerEvent] = field(default_factory=list)


class RunnerFailure(Exception):
    """技术失败：没有可信结果，交给后端决定重试。"""

    def __init__(self, error_code: str, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.error_code = error_code
        self.message = message
        self.retryable = retryable


class RunnerTimeout(RunnerFailure):
    """硬超时。错误码固定为 TIMED_OUT，后端据此写 TIMED_OUT 终态。"""

    def __init__(self, message: str = "runner timed out") -> None:
        super().__init__("TIMED_OUT", message, retryable=False)


EmitEvent = Callable[[str, dict[str, Any]], None]


class Runner(Protocol):
    """可替换的执行器接口。"""

    name: str

    def run(self, request: RunnerRequest, emit: EmitEvent) -> RunnerResult:
        """执行一次并返回结果；``emit`` 用于在执行过程中追加轨迹事件。"""
        ...
