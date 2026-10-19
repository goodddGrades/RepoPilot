"""test.command：在受控工作区里执行可信测试命令。

安全边界（对齐 `docs/modules/online-evaluation.md` 与任务边界）：

* **只认白名单键**：config 给的是 ``commandKey``，不是命令行字符串。
  命令与参数由 Worker 侧注册表固定，用户输入无法拼接成 shell；
* **参数数组、不经 shell**：``subprocess`` 传列表，``shell=False``；
* **进程组清理**：超时或取消时杀掉整个进程组，避免遗留孤儿进程占住工作区；
* **每次执行独立工作目录**：用完删除，不污染也不复用；
* **超时是技术错误，断言失败是质量失败**：进程超时 → :class:`EvaluationDependencyError`
  （可重试）；进程正常退出但退出码非零 → ``FAIL``，Job 正常完成。

不做的事：不声称这就是生产级沙箱。工具/网络白名单与资源限制依赖宿主机配置，
V1 只支持可信代码。
"""

from __future__ import annotations

import os
import shutil
import signal
import subprocess
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from app.evaluator.base import (
    EvaluationDependencyError,
    EvaluationInput,
    EvaluationOutcome,
    EvaluatorConfigError,
    TraceEvents,
)

MAX_OUTPUT_CHARS = 4000
DEFAULT_TIMEOUT_SECONDS = 120


@dataclass(frozen=True)
class CommandSpec:
    """白名单里的一条命令。"""

    key: str
    argv: tuple[str, ...]
    description: str
    #: 退出码是否算"断言失败"。有些测试工具用 1 表示测试失败、5 表示内部错误。
    failure_exit_codes: frozenset[int] = frozenset({1})
    #: 超过这个退出码上限视为工具自身崩溃（技术问题），不是断言失败。
    max_failure_code: int = 1


@dataclass
class CommandRegistry:
    """可信命令白名单。

    V1 内置三条**确定性、无网络、不写仓库外文件**的命令，全部基于标准库：
    它们足以验证"退出码语义、超时分离、进程组清理、输出脱敏"这条链路，
    而不需要在本机安装语言工具链。
    """

    specs: dict[str, CommandSpec] = field(default_factory=dict)

    def register(self, spec: CommandSpec) -> None:
        self.specs[spec.key] = spec

    def get(self, key: str) -> CommandSpec:
        spec = self.specs.get(key)
        if spec is None:
            raise EvaluatorConfigError(
                f"未注册的 commandKey: {key}；已注册: {', '.join(sorted(self.specs)) or '（空）'}")
        return spec


def _builtin_registry() -> CommandRegistry:
    """内置白名单。

    ``echo-pass``/``echo-fail``/``echo-slow`` 用 ``sys.executable`` 而不是 ``echo``：
    这样不依赖 shell 内建命令，也不受 PATH 劫持影响（路径在构造时固定为解释器绝对路径）。
    """
    import sys

    registry = CommandRegistry()
    interpreter = sys.executable
    registry.register(CommandSpec(
        key="smoke.pass",
        argv=(interpreter, "-c", "print('all assertions passed')"),
        description="冒烟：始终成功"))
    registry.register(CommandSpec(
        key="smoke.fail",
        argv=(interpreter, "-c",
               "import sys; print('3 passed, 1 failed'); sys.exit(1)"),
        description="冒烟：模拟测试断言失败（退出码 1）"))
    registry.register(CommandSpec(
        key="smoke.timeout",
        argv=(interpreter, "-c", "import time; time.sleep(600)"),
        description="冒烟：模拟长时间挂起，用于验证超时是技术错误"))
    registry.register(CommandSpec(
        key="smoke.internal-error",
        argv=(interpreter, "-c", "import sys; print('collection error'); sys.exit(3)"),
        description="冒烟：模拟测试工具自身崩溃（退出码 3，不算断言失败）",
        failure_exit_codes=frozenset(), max_failure_code=3))
    return registry


class TestCommand:
    """test.command 算子。"""

    __test__ = False  # 这是算子实现，不是 pytest 测试类

    key = "test.command"
    generated_by = "TEST"

    def __init__(self, registry: CommandRegistry | None = None) -> None:
        self.registry = registry or _builtin_registry()

    def evaluate(self, data: EvaluationInput, trace: TraceEvents) -> EvaluationOutcome:
        config = data.config if isinstance(data.config, dict) else {}
        if not config:
            raise EvaluatorConfigError("test.command 需要 plan item config")
        command_key = config.get("commandKey")
        if not isinstance(command_key, str) or not command_key.strip():
            raise EvaluatorConfigError("config.commandKey 必须是非空字符串")
        spec = self.registry.get(command_key.strip())

        timeout = config.get("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS)
        if isinstance(timeout, bool) or not isinstance(timeout, int) or not 1 <= timeout <= 3600:
            raise EvaluatorConfigError("config.timeoutSeconds 必须是 1~3600 的整数")

        workspace = Path(tempfile.mkdtemp(prefix="repopilot-test-"))
        started = time.monotonic()
        try:
            completed = self._run(spec, workspace, timeout)
        finally:
            # 无论成功、失败还是超时，工作区都不留痕。
            shutil.rmtree(workspace, ignore_errors=True)
        elapsed_ms = int((time.monotonic() - started) * 1000)

        return self._verdict(spec, completed, elapsed_ms)

    # ------------------------------------------------------------------ 进程

    def _run(self, spec: CommandSpec, workspace: Path, timeout: int) -> subprocess.CompletedProcess:
        env = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1", "REPOPILOT_TEST_KEY": spec.key}
        popen_kwargs: dict[str, Any] = {"shell": False, "cwd": str(workspace), "env": env}
        if os.name == "nt":
            # 独立进程组：超时时可以连带杀掉子孙进程。
            creationflags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
            popen_kwargs["creationflags"] = creationflags
        else:
            popen_kwargs["start_new_session"] = True

        process = subprocess.Popen(  # noqa: S603 - 参数数组，无 shell
            list(spec.argv), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding="utf-8", errors="replace", **popen_kwargs)
        try:
            stdout, stderr = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            _kill_process_tree(process)
            stdout, stderr = process.communicate()
            raise EvaluationDependencyError(
                "TEST_COMMAND_TIMEOUT",
                f"测试命令 {spec.key} 超过 {timeout}s 未结束，已终止进程组",
                retryable=True) from None
        return subprocess.CompletedProcess(spec.argv, process.returncode, stdout, stderr)

    # ------------------------------------------------------------------ 判定

    def _verdict(self, spec: CommandSpec, completed: subprocess.CompletedProcess,
                 elapsed_ms: int) -> EvaluationOutcome:
        code = completed.returncode
        stdout = _clip(completed.stdout)
        stderr = _clip(completed.stderr)
        result: dict[str, Any] = {
            "commandKey": spec.key,
            "exitCode": code,
            "elapsedMs": elapsed_ms,
            "stdout": stdout,
            "stderr": stderr,
        }

        if code == 0:
            return EvaluationOutcome(verdict="PASS", score=1.0,
                                     reason=f"测试命令 {spec.key} 全部通过",
                                     evidence=[], result=result, generated_by="TEST")

        if code in spec.failure_exit_codes and code <= spec.max_failure_code:
            # 测试跑完了、有断言失败：这是质量失败，Job 正常完成。
            return EvaluationOutcome(verdict="FAIL", score=0.0,
                                     reason=f"测试命令 {spec.key} 断言失败（退出码 {code}）",
                                     evidence=[], result=result, generated_by="TEST")

        # 退出码超出断言失败范围：测试工具本身崩了，不该记成"代码写错了"。
        return EvaluationOutcome(
            verdict="INDETERMINATE", score=None,
            reason=f"测试命令 {spec.key} 以非预期退出码 {code} 结束，"
                   "无法区分断言失败与工具故障",
            evidence=[], result=result, generated_by="TEST")


def _kill_process_tree(process: subprocess.Popen) -> None:
    """终止整个进程组；组已经退出时不抛异常。"""
    try:
        if os.name == "nt":
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(process.pid)],
                           capture_output=True, check=False, timeout=30)
        else:
            os.killpg(os.getpgid(process.pid), signal.SIGKILL)
    except (OSError, subprocess.SubprocessError):
        # 清理失败不能掩盖原始结果：进程可能已经自己退出了。
        pass
    try:
        process.kill()
    except OSError:
        pass


def _clip(text: str | None, limit: int = MAX_OUTPUT_CHARS) -> str:
    value = (text or "").strip()
    if len(value) <= limit:
        return value
    return value[:limit] + f"\n…(已截断，原始长度 {len(value)} 字符)"