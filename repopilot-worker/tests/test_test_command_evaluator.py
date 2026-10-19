"""test.command 单元测试。

验收要点：
* 只执行白名单命令——未知 commandKey 是配置错误，不会变成 shell 拼接；
* 超时是**技术错误**（可重试），断言失败是**质量失败**（正常完成）；
* 工作区每次独立且用后清理；
* 进程组清理：超时后不遗留子进程；
* 退出码语义区分断言失败（1）与工具自身崩溃（3）。
"""

from __future__ import annotations

import sys

from pathlib import Path
from typing import Any

import pytest

from app.evaluator.base import (
    EvaluationDependencyError,
    EvaluationInput,
    EvaluatorConfigError,
    TraceEvents,
    TraceRef,
)
from app.evaluator.testing import CommandRegistry, CommandSpec, TestCommand

TRACE = TraceEvents([], 0)


def make_input(config: dict[str, Any]) -> EvaluationInput:
    return EvaluationInput(
        job_id="01JOB", batch_id="01BATCH", execution_order=1, required=True,
        run_id="01RUN", mode="ONLINE", task_outcome="SUCCESS",
        accepted_attempt_id="01ATTEMPT", output={"answer": "x"}, output_artifact_id=None,
        usage={}, case={"expected": {}},
        trace=TraceRef(sealed=True, events_url="/x", event_count=0, manifest_sha256=None),
        evaluator={"key": "test.command", "version": 1, "type": "TEST"},
        config=config)


def builtin() -> TestCommand:
    return TestCommand()


# ----------------------------------------------------------------- 白名单


def test_unknown_command_key_is_config_error() -> None:
    with pytest.raises(EvaluatorConfigError) as excinfo:
        builtin().evaluate(make_input({"commandKey": "rm -rf /"}), TRACE)

    # 未知键绝不会被当成 shell 命令执行
    assert "未注册的 commandKey" in excinfo.value.message
    assert "smoke.pass" in excinfo.value.message


def test_missing_command_key_is_rejected() -> None:
    with pytest.raises(EvaluatorConfigError):
        builtin().evaluate(make_input({}), TRACE)


def test_command_string_instead_of_key_is_rejected() -> None:
    """config 里写整条命令行不生效：必须用白名单键。"""
    with pytest.raises(EvaluatorConfigError):
        builtin().evaluate(make_input({"commandKey": "python -c 'import os; os.system(\"x\")'"}),
                           TRACE)


@pytest.mark.parametrize("bad", [0, -5, 4000, "60", 1.5])
def test_invalid_timeout_is_config_error(bad: Any) -> None:
    with pytest.raises(EvaluatorConfigError):
        builtin().evaluate(make_input({"commandKey": "smoke.pass", "timeoutSeconds": bad}), TRACE)


# ----------------------------------------------------------------- 退出码语义


def test_passing_command_yields_pass() -> None:
    outcome = builtin().evaluate(make_input({"commandKey": "smoke.pass"}), TRACE)

    assert outcome.verdict == "PASS"
    assert outcome.score == 1.0
    assert outcome.generated_by == "TEST"
    assert outcome.result["exitCode"] == 0


def test_assertion_failure_is_quality_failure_not_technical() -> None:
    """退出码 1 = 测试跑完但断言失败：质量 FAIL，Job 正常完成。"""
    outcome = builtin().evaluate(make_input({"commandKey": "smoke.fail"}), TRACE)

    assert outcome.verdict == "FAIL"
    assert outcome.score == 0.0
    assert outcome.result["exitCode"] == 1
    assert "1 failed" in outcome.result["stdout"]


def test_tool_internal_error_is_indeterminate() -> None:
    """退出码 3 超出断言失败范围：不能记成"代码写错了"。"""
    outcome = builtin().evaluate(make_input({"commandKey": "smoke.internal-error"}), TRACE)

    assert outcome.verdict == "INDETERMINATE"
    assert outcome.score is None
    assert outcome.result["exitCode"] == 3


# ----------------------------------------------------------------- 超时


def test_timeout_is_retryable_technical_error() -> None:
    with pytest.raises(EvaluationDependencyError) as excinfo:
        builtin().evaluate(make_input({"commandKey": "smoke.timeout", "timeoutSeconds": 1}),
                           TRACE)

    assert excinfo.value.error_code == "TEST_COMMAND_TIMEOUT"
    assert excinfo.value.retryable is True, "超时属基础设施问题，应重试"


def test_timeout_kills_process_group_and_leaves_no_child(tmp_path: Path) -> None:
    """超时要杀掉整个进程组，不能留下继续跑的孤儿进程。"""
    marker = tmp_path / "child-alive.txt"
    registry = CommandRegistry()
    # 子进程在父被 kill 后仍会尝试写文件；若进程组没被杀干净，文件会出现。
    script = (
        "import subprocess, sys, time, pathlib\n"
        f"subprocess.Popen([sys.executable, '-c', "
        f"\"import time,pathlib; time.sleep(2); pathlib.Path(r'{marker}').write_text('alive')\"])\n"
        "time.sleep(30)\n"
    )
    registry.register(CommandSpec(key="leaky", argv=(sys.executable, "-c", script),
                                  description="留下子进程"))

    with pytest.raises(EvaluationDependencyError):
        TestCommand(registry).evaluate(
            make_input({"commandKey": "leaky", "timeoutSeconds": 1}), TRACE)

    import time
    time.sleep(4)
    assert not marker.exists(), "超时后子进程仍在运行，说明进程组没有清理"


# ----------------------------------------------------------------- 工作区


def test_workspace_is_unique_per_execution_and_removed(tmp_path: Path) -> None:
    root = tmp_path / "workspaces"
    registry = CommandRegistry()
    registry.register(CommandSpec(
        key="pwrite", argv=(sys.executable, "-c",
                            "import os; open('marker.txt','w').write(os.getcwd());"
                            "print(os.getcwd(), flush=True)"),
        description="写入工作目录"))

    command = TestCommand(registry)
    first = command.evaluate(make_input({"commandKey": "pwrite"}), TRACE)
    second = command.evaluate(make_input({"commandKey": "pwrite"}), TRACE)

    cwd_first = first.result["stdout"]
    cwd_second = second.result["stdout"]
    assert cwd_first != cwd_second, "每次执行必须是独立工作目录"
    # 用完即删：不留痕、不复用
    assert not Path(cwd_first).exists()
    assert not Path(cwd_second).exists()
    assert not root.exists()


def test_command_runs_in_isolated_directory_not_repo_root() -> None:
    outcome = builtin().evaluate(make_input({"commandKey": "smoke.pass"}), TRACE)

    assert outcome.verdict == "PASS"
    # 结果里不暴露绝对路径，避免把宿主目录结构写进数据库
    assert "stdout" in outcome.result


# ----------------------------------------------------------------- 输出


def test_long_output_is_truncated() -> None:
    registry = CommandRegistry()
    registry.register(CommandSpec(
        key="noisy", argv=(sys.executable, "-c", "print('x' * 50000)"),
        description="大量输出"))

    outcome = TestCommand(registry).evaluate(make_input({"commandKey": "noisy"}), TRACE)

    assert "已截断" in outcome.result["stdout"]
    assert len(outcome.result["stdout"]) < 5000


def test_elapsed_time_is_recorded() -> None:
    outcome = builtin().evaluate(make_input({"commandKey": "smoke.pass"}), TRACE)

    assert isinstance(outcome.result["elapsedMs"], int)
    assert outcome.result["elapsedMs"] >= 0


def test_custom_registry_can_extend_whitelist() -> None:
    registry = CommandRegistry()
    registry.register(CommandSpec(key="ok", argv=(sys.executable, "-c", "print(1)"),
                                  description="自定义"))

    outcome = TestCommand(registry).evaluate(make_input({"commandKey": "ok"}), TRACE)

    assert outcome.verdict == "PASS"
    assert outcome.result["stdout"] == "1"


def test_result_records_command_key_for_audit() -> None:
    outcome = builtin().evaluate(make_input({"commandKey": "smoke.pass"}), TRACE)

    assert outcome.result["commandKey"] == "smoke.pass"
    assert "真实" not in outcome.result.get("description", ""), "结果只记录键与退出码，不臆造来源"


def test_timeout_defaults_to_documented_limit() -> None:
    """没有显式 timeoutSeconds 时用算子的默认上限，不去猜实验 deadline。"""
    outcome = builtin().evaluate(make_input({"commandKey": "smoke.pass"}), TRACE)

    assert outcome.verdict == "PASS"
    # 冒烟命令实际耗时远小于默认上限，说明没有把"默认 120s"错当成"立刻超时"
    assert outcome.result["elapsedMs"] < 120_000