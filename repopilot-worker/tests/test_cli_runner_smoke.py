"""真实 Coding Agent CLI 冒烟测试。

**这个测试会真实调用本机已认证的 Coding Agent CLI，产生真实费用。**

存在理由：桩 CLI 能验证适配器逻辑，但无法证明"真实工具的协议与我们的假设一致"。
契约要求"真实 Runner 冒烟有命令/版本/输出证据；缺访问条件时列为未验收"。
因此：

* 环境变量 ``WORKER_SMOKE_REAL_CLI=1`` 才运行；默认不运行，CI 不受影响；
* 未安装 CLI 或未认证时 **skip 并给出明确原因**，而不是静默通过；
* 通过时断言的是**可核查的证据**：CLI 版本、真实成本、真实输出、工作区清理。

运行方式::

    WORKER_SMOKE_REAL_CLI=1 WORKER_RUNNER_CLI=claude \
      pytest tests/test_cli_runner_smoke.py -v
"""

from __future__ import annotations

import os
import shutil
from datetime import datetime, timedelta, timezone
from typing import Any

import pytest

from app.runner.base import RunnerRequest, RunnerTimeout
from app.runner.cli_coding_agent import CliCodingAgentRunner

pytestmark = [
    pytest.mark.skipif(os.environ.get("WORKER_SMOKE_REAL_CLI") != "1",
                       reason="真实 CLI 冒烟需显式开启（会产生真实费用）"),
]


@pytest.fixture(scope="module")
def executable() -> str:
    name = os.environ.get("WORKER_RUNNER_CLI", "claude")
    resolved = shutil.which(name)
    if not resolved:
        pytest.skip(f"未安装 {name}：真实 CLI 执行无法验证")
    return resolved


def make_request(**overrides: Any) -> RunnerRequest:
    base: dict[str, Any] = {
        "run_id": "01SMOKETEST0000000000001",
        "attempt_id": "01SMOKETEST0000000000002",
        "attempt_no": 1,
        "runner_type": "CLI",
        "agent_config": {"outputPath": "answer"},
        "budget": {"timeoutSeconds": 180},
        "case": {"caseKey": "smoke-001",
                 "input": "用一句话回答：1 + 1 等于几？只回答数字。",
                 "expected": {"answer": "2"}},
        "deadline_at": datetime.now(tz=timezone.utc) + timedelta(minutes=5),
    }
    base.update(overrides)
    return RunnerRequest(**base)


def collect() -> tuple[Any, list[Any]]:
    from app.runner.mock import build_event_collector

    return build_event_collector()


def test_cli_version_is_discoverable(executable: str) -> None:
    """第一条验收证据：命令与版本。"""
    runner = CliCodingAgentRunner(executable=executable)

    version = runner.cli_version()

    assert version, "无法获取 CLI 版本，真实执行链路不可核查"
    assert len(version) > 3


def test_real_execution_produces_output_cost_and_events(executable: str) -> None:
    """真实冒烟：执行一次最小任务，核对输出、成本与轨迹。"""
    runner = CliCodingAgentRunner(executable=executable, max_turns=3)
    emit, events = collect()

    result = runner.run(make_request(), emit)

    # 1) 输出
    assert result.task_outcome in ("SUCCESS", "FAILURE")
    assert result.output is not None
    assert result.output["exitCode"] == 0
    assert result.output["answer"], "真实 CLI 应产出文本"
    assert result.output["runner"] == "CLI"

    # 2) 真实成本与 token（这是桩给不出来的证据）
    assert result.usage is not None, "真实调用必须回报用量"
    assert result.usage.get("costUsd", 0) > 0, "真实调用应有真实成本"
    assert result.usage.get("currency") == "USD"
    assert result.usage.get("totalTokens", 0) > 0

    # 3) CLI 版本与工作目录进轨迹，供事后审计
    started = [e for e in events if e.event_type == "RUN_STARTED"][0]
    assert started.payload["cliVersion"]
    workspace = started.payload["workingDirectory"]
    assert workspace

    # 4) 事件序列完整
    types = [e.event_type for e in events]
    assert types[0] == "RUN_STARTED"
    assert types[-1] == "RUN_COMPLETED"
    assert "MODEL_RESPONSE" in types
    assert "OUTPUT_PRODUCED" in types

    # 5) 模型自报结构化结果时可被算子取到（不强求：模型可能不照格式输出）
    print("真实输出：", result.output["answer"][:200])
    print("真实用量：", result.usage)


def test_real_workspace_is_cleaned(executable: str, tmp_path: Any) -> None:
    from pathlib import Path

    runner = CliCodingAgentRunner(executable=executable, max_turns=3,
                                  workdir_root=str(tmp_path / "ws"))
    emit, events = collect()

    runner.run(make_request(), emit)

    workspace = [e for e in events if e.event_type == "RUN_STARTED"][0].payload["workingDirectory"]
    assert workspace.startswith(str(tmp_path)), f"工作目录应落在配置的根下: {workspace}"
    assert not Path(workspace).exists(), "真实执行后工作目录必须清理"


def test_real_timeout_is_technical_error(executable: str) -> None:
    """真实 CLI 的超时也必须是技术错误，而不是被吞掉的失败。"""
    runner = CliCodingAgentRunner(executable=executable, max_turns=1)
    emit, events = collect()

    with pytest.raises(RunnerTimeout):
        runner.run(make_request(budget={"timeoutSeconds": 1}), emit)

    errors = [e for e in events if e.event_type == "ERROR"]
    assert errors and errors[0].payload["errorCode"] == "RUNNER_TIMEOUT"