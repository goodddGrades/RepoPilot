"""真实 Runner（CLI 适配器）测试。

策略：用**桩 CLI**（``tests/fixtures/stub_coding_agent.py``）代替真实 Coding Agent，
让 CI 可重复、零外部依赖、零费用。测试聚焦适配器自身的正确性——argv 构造、
不经 shell、退出码语义、脱敏、超时与进程组清理、工作区隔离、真实用量透传——
这些正是"接了真实工具"最容易出错的地方。

真实 CLI 冒烟在 ``test_cli_runner_smoke.py``：需要本机已认证的 Coding Agent，
缺失时按 ``skip`` 明确标注为"未验证"，而不是让 CI 假装通过。
"""

from __future__ import annotations

import json
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import pytest

from app.runner.base import RunnerFailure, RunnerRequest, RunnerTimeout
from app.runner.cli_coding_agent import CliCodingAgentRunner

FIXTURES = Path(__file__).parent / "fixtures"
STUB_CLI = FIXTURES / "stub_coding_agent.py"

pytestmark = pytest.mark.skipif(not STUB_CLI.exists(), reason=f"桩 CLI 不存在: {STUB_CLI}")


def make_request(case_input: Any = "2 + 2 = ?", **overrides: Any) -> RunnerRequest:
    base: dict[str, Any] = {
        "run_id": "01RUN0000000000000000TEST",
        "attempt_id": "01ATT0000000000000000TEST",
        "attempt_no": 1,
        "runner_type": "CLI",
        "agent_config": {},
        "budget": {},
        "case": {"caseKey": "case-001", "input": case_input, "expected": {"answer": "4"}},
        "deadline_at": datetime.now(tz=timezone.utc) + timedelta(minutes=10),
    }
    base.update(overrides)
    return RunnerRequest(**base)


def collect() -> tuple[Any, list[Any]]:
    from app.runner.mock import build_event_collector

    return build_event_collector()


def make_runner(*, workdir_root: str | None = None,
                allowed_tools: str | None = None) -> CliCodingAgentRunner:
    """Runner 的 ``_resolve`` 指向桩脚本，其余全部是生产代码路径。

    桩脚本自己 ``#!/usr/bin/env python``；这里用当前解释器显式启动它，
    避免依赖可执行位在 Windows 上的行为差异。
    """
    runner = CliCodingAgentRunner(executable=sys.executable, extra_args=[],
                                  workdir_root=workdir_root, allowed_tools=allowed_tools)
    runner._resolve = lambda: sys.executable  # type: ignore[method-assign]
    runner.extra_args = [str(STUB_CLI)]
    return runner


def run_stub(emit: Any, *, request: RunnerRequest | None = None,
             workdir_root: str | None = None,
             allowed_tools: str | None = None) -> Any:
    runner = make_runner(workdir_root=workdir_root, allowed_tools=allowed_tools)
    runner.cli_version = lambda: "stub-coding-agent 9.9.9"  # type: ignore[method-assign]
    return runner.run(request or make_request(), emit)


def spy_argv(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    """拦截 Popen，记录真实 argv 与关键 kwargs。"""
    recorded: dict[str, Any] = {}
    real_popen = subprocess.Popen

    def spy(argv: Any, **kwargs: Any) -> Any:
        if isinstance(argv, list) and "--output-format" in argv:
            recorded["argv"] = list(argv)
            recorded["shell"] = kwargs.get("shell")
            recorded["stdin"] = kwargs.get("stdin")
        return real_popen(argv, **kwargs)

    monkeypatch.setattr("app.runner.cli_coding_agent.subprocess.Popen", spy)
    return recorded


# ----------------------------------------------------------------- 基础执行


def test_successful_run_returns_output_and_real_usage() -> None:
    emit, _ = collect()

    result = run_stub(emit)

    assert result.task_outcome == "SUCCESS"
    assert result.output is not None
    assert result.output["runner"] == "CLI"
    assert result.output["exitCode"] == 0
    assert "stub answer" in result.output["answer"]
    assert "mock" not in result.output, "真实执行不能带 mock 标记冒充演练数据"


def test_real_cost_and_tokens_are_passed_through() -> None:
    """真实用量必须透传：写 0 会让 cost.budget 假通过。"""
    emit, _ = collect()

    result = run_stub(emit)

    assert result.usage is not None
    assert result.usage["inputTokens"] == 11
    assert result.usage["outputTokens"] == 7
    assert result.usage["totalTokens"] == 18
    assert result.usage["costUsd"] == pytest.approx(0.0021)
    assert result.usage["currency"] == "USD"


def test_run_started_event_records_cli_identity() -> None:
    emit, events = collect()

    run_stub(emit)

    started = [e for e in events if e.event_type == "RUN_STARTED"]
    assert len(started) == 1
    payload = started[0].payload
    assert payload["runnerType"] == "CLI"
    assert payload["cliVersion"] == "stub-coding-agent 9.9.9", "CLI 版本要可审计"


def test_events_cover_full_lifecycle_with_sequential_numbers() -> None:
    emit, events = collect()

    run_stub(emit)

    types = [e.event_type for e in events]
    assert types[0] == "RUN_STARTED"
    assert types[-1] == "RUN_COMPLETED"
    assert {"MODEL_REQUEST", "MODEL_RESPONSE", "OUTPUT_PRODUCED"} <= set(types)
    assert [e.sequence_no for e in events] == list(range(1, len(events) + 1))


def test_event_ids_are_unique() -> None:
    emit, events = collect()

    run_stub(emit)

    ids = [e.event_id for e in events]
    assert len(ids) == len(set(ids)), "事件 ID 重复会被后端判为重放"


# ----------------------------------------------------------------- 失败语义


def test_nonzero_exit_is_retryable_infrastructure_failure(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """CLI 崩溃是可重试问题：不能因此消耗一个 Run。"""
    monkeypatch.setenv("STUB_MODE", "crash")
    emit, _ = collect()

    with pytest.raises(RunnerFailure) as excinfo:
        run_stub(emit)

    assert excinfo.value.error_code == "RUNNER_CLI_FAILED"
    assert excinfo.value.retryable is True


def test_stderr_with_credentials_is_redacted(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("STUB_MODE", "crash")
    emit, events = collect()

    with pytest.raises(RunnerFailure) as excinfo:
        run_stub(emit)

    assert "SECRET-VALUE" not in str(excinfo.value), "密钥绝不能进错误消息"
    assert "已脱敏" in str(excinfo.value)
    for event in events:
        assert "SECRET-VALUE" not in json.dumps(event.payload, ensure_ascii=False)


def test_malformed_response_is_non_retryable(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("STUB_MODE", "notjson")
    emit, _ = collect()

    with pytest.raises(RunnerFailure) as excinfo:
        run_stub(emit)

    assert excinfo.value.error_code == "RUNNER_RESPONSE_MALFORMED"
    assert excinfo.value.retryable is False, "协议不兼容重试没有意义"


def test_upstream_error_envelope_is_retryable(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("STUB_MODE", "upstream-error")
    emit, _ = collect()

    with pytest.raises(RunnerFailure) as excinfo:
        run_stub(emit)

    assert excinfo.value.error_code == "RUNNER_UPSTREAM_ERROR"
    assert excinfo.value.retryable is True


def test_empty_output_is_business_failure_not_crash(monkeypatch: pytest.MonkeyPatch) -> None:
    """退出码 0 但没内容：执行完成、任务失败，仍进入评测。"""
    monkeypatch.setenv("STUB_MODE", "empty")
    emit, _ = collect()

    result = run_stub(emit)

    assert result.task_outcome == "FAILURE"
    assert result.output is not None
    assert result.output["answer"] == ""
    assert "没有产生输出" in result.output["reason"]


def test_missing_cli_is_non_retryable_failure() -> None:
    runner = CliCodingAgentRunner(executable="definitely-not-installed-xyz-123")
    emit, _ = collect()

    with pytest.raises(RunnerFailure) as excinfo:
        runner.run(make_request(), emit)

    assert excinfo.value.error_code == "RUNNER_CLI_MISSING"
    assert excinfo.value.retryable is False


# ----------------------------------------------------------------- 超时与进程组


def test_timeout_raises_and_records_error_event(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("STUB_MODE", "hang")
    emit, events = collect()

    with pytest.raises(RunnerTimeout):
        run_stub(emit, request=make_request(budget={"timeoutSeconds": 1}))

    errors = [e for e in events if e.event_type == "ERROR"]
    assert errors and errors[0].payload["errorCode"] == "RUNNER_TIMEOUT"
    assert any(e.event_type == "RUN_STARTED" for e in events), "失败轨迹同样保留"


def test_grandchild_process_does_not_survive_timeout(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """超时杀的是进程组：孙进程不能在父被杀后继续运行。"""
    monkeypatch.setenv("STUB_MODE", "child")
    monkeypatch.setenv("STUB_CHILD_MARKER", str(tmp_path / "survivor.txt"))
    # 孙进程在 CHILD_DELAY 秒后才写标记；测试必须等过这个时间点才能断言"它没活下来"，
    # 否则一个真正幸存的孙进程也可能因为"还没轮到它写"而让用例假通过。
    # 等待窗口取 CHILD_DELAY + 余量：既留足 kill 完成的时间，又保证标记本该出现时一定出现过。
    child_delay = 8.0
    monkeypatch.setenv("STUB_CHILD_DELAY_SECONDS", str(int(child_delay)))
    emit, _ = collect()

    with pytest.raises(RunnerTimeout):
        run_stub(emit, request=make_request(budget={"timeoutSeconds": 1}))

    deadline = time.monotonic() + child_delay + 4.0
    while time.monotonic() < deadline:
        if (tmp_path / "survivor.txt").exists():
            break
        time.sleep(0.25)
    assert not (tmp_path / "survivor.txt").exists(), "超时后孙进程仍在运行"


def test_budget_timeout_is_honoured(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("STUB_MODE", "hang")
    emit, _ = collect()

    started = time.monotonic()
    with pytest.raises(RunnerTimeout):
        run_stub(emit, request=make_request(budget={"timeoutSeconds": 2}))
    elapsed = time.monotonic() - started

    assert elapsed < 25, f"应在配置的 2s 附近超时，实际 {elapsed:.1f}s"


# ----------------------------------------------------------------- 工作区隔离


def test_workspace_is_recorded_then_removed(tmp_path: Path) -> None:
    emit, events = collect()

    run_stub(emit, workdir_root=str(tmp_path / "workspaces"))

    workspace = [e for e in events if e.event_type == "RUN_STARTED"][0].payload["workingDirectory"]
    assert workspace, "工作目录必须进轨迹供审计"
    assert not Path(workspace).exists(), "执行结束后必须清理"


def test_each_attempt_uses_a_distinct_workspace(tmp_path: Path) -> None:
    root = str(tmp_path / "workspaces")
    emit1, events1 = collect()
    emit2, events2 = collect()

    run_stub(emit1, request=make_request(attempt_no=1), workdir_root=root)
    run_stub(emit2, request=make_request(attempt_no=2), workdir_root=root)

    first = [e for e in events1 if e.event_type == "RUN_STARTED"][0].payload["workingDirectory"]
    second = [e for e in events2 if e.event_type == "RUN_STARTED"][0].payload["workingDirectory"]
    assert first != second, "不同 Attempt 不得共用工作目录"


def test_without_workdir_root_a_temp_directory_is_used() -> None:
    """未配置根目录时必须落在系统临时目录下，不能在进程工作目录里建 run-xxx。"""
    import tempfile

    emit, events = collect()

    run_stub(emit)

    workspace = [e for e in events if e.event_type == "RUN_STARTED"][0].payload["workingDirectory"]
    assert workspace, "无配置时也要落在临时目录"
    assert not Path(workspace).exists(), "执行完必须清理"
    # 关键：不能是相对路径（那会写进 Worker 启动目录，可能是代码仓库）
    assert Path(workspace).is_absolute()
    assert str(Path(tempfile.gettempdir())) in workspace


# ----------------------------------------------------------------- 安全性


def test_shell_is_never_used_and_argv_is_a_list(monkeypatch: pytest.MonkeyPatch) -> None:
    recorded = spy_argv(monkeypatch)
    emit, _ = collect()

    run_stub(emit)

    assert recorded["shell"] is False, "永远不经 shell"
    assert isinstance(recorded["argv"], list)
    assert recorded["stdin"] is subprocess.PIPE, "提示走 stdin，不进命令行"


def test_user_input_travels_through_stdin_not_argv(monkeypatch: pytest.MonkeyPatch) -> None:
    """用户输入（含换行与 shell 元字符）不得出现在 argv 中。"""
    recorded = spy_argv(monkeypatch)
    hostile = "line1\nline2 --max-turns 999 $(whoami) && echo pwned"
    emit, _ = collect()

    run_stub(emit, request=make_request(case_input=hostile))

    assert recorded["argv"], "应记录到 argv"
    assert not any("$(whoami)" in str(arg) for arg in recorded["argv"])
    assert not any("pwned" in str(arg) for arg in recorded["argv"])
    assert "--max-turns" in recorded["argv"], "只有平台自己的固定参数进 argv"


def test_allowed_tools_flag_is_passed_when_configured(monkeypatch: pytest.MonkeyPatch) -> None:
    recorded = spy_argv(monkeypatch)
    emit, _ = collect()

    run_stub(emit, allowed_tools="Read,Grep")

    argv = recorded["argv"]
    assert argv[argv.index("--allowedTools") + 1] == "Read,Grep"


def test_max_turns_flag_is_always_present(monkeypatch: pytest.MonkeyPatch) -> None:
    recorded = spy_argv(monkeypatch)
    emit, _ = collect()

    run_stub(emit)

    argv = recorded["argv"]
    assert argv[argv.index("--max-turns") + 1] == "8"


def test_agent_config_cli_args_are_forwarded(monkeypatch: pytest.MonkeyPatch) -> None:
    recorded = spy_argv(monkeypatch)
    request = make_request(agent_config={"cliArgs": [["--model", "some-model"]]})
    emit, _ = collect()

    run_stub(emit, request=request)

    assert recorded["argv"][recorded["argv"].index("--model") + 1] == "some-model"


# ----------------------------------------------------------------- 输出解析


def test_model_reported_json_is_merged_into_output(monkeypatch: pytest.MonkeyPatch) -> None:
    """模型按要求自报结构化结果时，平台把它并入 output 供算子取字段。"""
    monkeypatch.setenv("STUB_MODE", "structured")
    request = make_request(agent_config={"outputPath": "answer"})
    emit, _ = collect()

    result = run_stub(emit, request=request)

    assert result.output is not None
    assert result.output["answer"] == "4", "outputPath 字段被结构化结果覆盖"
    # 其余字段归入 structuredOutput，不污染顶层 output 的形状
    assert result.output["structuredOutput"]["confidence"] == pytest.approx(0.9)
    assert "confidence" not in {k for k in result.output if k in ("confidence",)}


def test_structured_answer_is_normalised_to_text(monkeypatch: pytest.MonkeyPatch) -> None:
    """模型回数字时也要变成字符串：输出结构不能随模型摇摆。"""
    monkeypatch.setenv("STUB_MODE", "structured-number")
    request = make_request(agent_config={"outputPath": "answer"})
    emit, _ = collect()

    result = run_stub(emit, request=request)

    assert result.output is not None
    assert isinstance(result.output["answer"], str)
    assert result.output["answer"] == "391"


def test_raw_text_is_preserved_when_model_ignores_structure(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """模型没按格式输出时，保留原始文本，并如实标注未取到结构化字段。"""
    monkeypatch.setenv("STUB_MODE", "ok")
    request = make_request(agent_config={"outputPath": "answer"})
    emit, _ = collect()

    result = run_stub(emit, request=request)

    assert result.output is not None
    assert "stub answer" in result.output["answer"]
    assert "structuredOutput" not in result.output, "没有结构化结果就不该有这个键"


def test_plain_text_answer_is_used_when_no_structure_requested() -> None:
    emit, _ = collect()

    result = run_stub(emit)

    assert result.output is not None
    assert "stub answer" in result.output["answer"]
    assert "structuredOutput" not in result.output


def test_unknown_cost_is_not_invented() -> None:
    """桩报的成本必须原样透传，不能被改写或补 0。"""
    emit, _ = collect()

    result = run_stub(emit)

    assert result.usage is not None
    assert result.usage.get("costUsd") == pytest.approx(0.0021)
    assert result.usage.get("costUsd", 0) > 0