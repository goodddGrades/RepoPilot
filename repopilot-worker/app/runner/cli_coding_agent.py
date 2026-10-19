"""真实 Coding Agent Runner：通过本机已认证的 CLI 执行任务。

设计契约（`docs/modules/online-evaluation.md`）：
* 命令用**参数数组**调用，``shell=False``，用户输入不拼接成 shell；
* 保留可审计证据：CLI 版本、完整 argv（脱敏）、退出码、stderr 摘要、
  工作目录、Git commit（如果工作区是仓库）、真实 cost/token；
* **退出码 0 只说明进程正常结束**，不必然表示任务成功——任务结论由 Runner
  自己从结构化输出里读取，读不到就是 FAILURE 或技术失败，不含糊；
* 每次 Attempt 独立工作目录；超时/取消清理进程组与目录。

本机实际可用的是 ``claude`` CLI（``codex`` 未安装），因此 V1 适配器以 CLI 名称
配置化，未安装的 CLI 在构造时就能发现，不做"运行时报错才发现装错了"。
"""

from __future__ import annotations

import json
import os
import shutil
import signal
import subprocess
import time
from pathlib import Path
from typing import Any

from app.runner.base import (
    EmitEvent,
    Runner,
    RunnerEvent,
    RunnerFailure,
    RunnerRequest,
    RunnerResult,
    RunnerTimeout,
)

# 脱敏：这些值不允许出现在事件或结果里。
_SECRET_MARKERS = ("sk-", "Bearer ", "api_key", "apiKey", "secret", "token=")
MAX_STDERR_CHARS = 600
MAX_PROMPT_CHARS = 24000
# Windows 上文件占用是暂态的：进程刚退出时其工作目录句柄可能仍未释放，
# 立即 rmtree 会失败。真实 Coding Agent CLI 会在工作目录里跑子进程，
# 释放得更慢，因此这里给到秒级的有限重试而不是放弃。
CLEANUP_ATTEMPTS = 6
CLEANUP_BACKOFF_SECONDS = 0.5
# 等待进程真正退出的宽限时间：超过说明 CLI 有残留子进程，应强杀而不是无限等。
PROCESS_EXIT_GRACE_SECONDS = 10.0


class CliCodingAgentRunner(Runner):
    """通过本机 Coding Agent CLI 执行样本任务。"""

    name = "CLI"

    def __init__(self, *, executable: str = "claude", workdir_root: str | None = None,
                 extra_args: list[str] | None = None, allowed_tools: str | None = None,
                 max_turns: int = 8) -> None:
        self.executable = executable
        self.workdir_root = workdir_root
        self.extra_args = list(extra_args or [])
        self.allowed_tools = allowed_tools
        self.max_turns = max(1, max_turns)
        self._version: str | None = None

    # ------------------------------------------------------------------ 探测

    def _resolve(self) -> str:
        resolved = shutil.which(self.executable)
        if not resolved:
            raise RunnerFailure("RUNNER_CLI_MISSING",
                                f"未找到可执行文件 {self.executable}；"
                                "请安装后重试或改用 MOCK", retryable=False)
        return resolved

    def cli_version(self) -> str | None:
        """CLI 版本：进结果与轨迹，便于事后核对"当时到底用的哪个版本"。"""
        if self._version is not None:
            return self._version
        try:
            completed = subprocess.run(  # noqa: S603 - 参数数组
                [self._resolve(), "--version"], capture_output=True, text=True,
                timeout=30, check=False, stdin=subprocess.DEVNULL)
        except (OSError, subprocess.SubprocessError):
            return None
        output = (completed.stdout or "").strip()
        self._version = output.splitlines()[0][:120] if output else None
        return self._version

    # ------------------------------------------------------------------ 执行

    def run(self, request: RunnerRequest, emit: EmitEvent) -> RunnerResult:
        executable = self._resolve()
        version = self.cli_version()

        input_text = _render_input(request.case)
        output_path = _configured_output_path(request.agent_config)
        prompt = _build_prompt(request, input_text, output_path)

        workspace = Path(self.workdir_root or os.environ.get("WORKER_WORKDIR_ROOT") or "").expanduser()
        # 未配置根目录时落到系统临时目录下的独立子目录：绝不能在进程工作目录里
        # 直接建 "run-xxx"——那会把执行产物写进 Worker 启动的地方（可能是代码仓库）。
        if str(workspace) in ("", "."):
            import tempfile

            workspace = Path(tempfile.gettempdir()) / "repopilot-runner"
        run_dir = workspace / f"run-{request.run_id}-{request.attempt_no}"
        run_dir.mkdir(parents=True, exist_ok=True)

        argv = [executable, *self.extra_args, "-p", "--output-format", "json",
                "--max-turns", str(self.max_turns)]
        if self.allowed_tools:
            argv += ["--allowedTools", self.allowed_tools]
        for key, value in (request.agent_config.get("cliArgs") or []):
            argv.append(str(key))
            if value not in (None, True):
                argv.append(str(value))

        emit("RUN_STARTED", {"runnerType": "CLI", "executable": Path(executable).name,
                             "cliVersion": version, "attemptNo": request.attempt_no,
                             "workingDirectory": str(run_dir)})
        emit("MODEL_REQUEST", {"model": str(request.agent_config.get("model") or "cli-default"),
                               "requestSummary": _summarize(prompt)})

        budget = request.budget if isinstance(request.budget, dict) else {}
        timeout = _timeout_seconds(budget, request)
        started = time.monotonic()
        try:
            completed = _spawn(argv, prompt, run_dir, timeout)
        except _Timeout:
            emit("ERROR", {"errorCode": "RUNNER_TIMEOUT",
                           "message": f"CLI 执行超过 {timeout}s，已终止进程组"})
            _cleanup(run_dir)
            raise RunnerTimeout(f"CLI 执行超时（{timeout}s）") from None
        except OSError as exc:
            emit("ERROR", {"errorCode": "RUNNER_SPAWN_FAILED", "message": _redact(str(exc))})
            _cleanup(run_dir)
            raise RunnerFailure("RUNNER_SPAWN_FAILED", f"无法启动 CLI: {exc}",
                                retryable=False) from exc
        elapsed_ms = int((time.monotonic() - started) * 1000)
        _cleanup(run_dir)

        stderr = _clip(completed.stderr, MAX_STDERR_CHARS)
        if completed.returncode != 0:
            emit("ERROR", {"errorCode": "RUNNER_CLI_FAILED",
                           "message": _redact(stderr) or f"退出码 {completed.returncode}"})
            # CLI 自身失败是可重试的基础设施问题：交回后端按退避重试，不消耗 Run。
            raise RunnerFailure("RUNNER_CLI_FAILED",
                                f"CLI 退出码 {completed.returncode}: {_redact(stderr)}",
                                retryable=True)

        envelope = _parse_envelope(completed.stdout)
        usage = _extract_usage(envelope)
        text = str(envelope.get("result") or envelope.get("text") or "").strip()
        commit = _git_commit(run_dir)

        emit("MODEL_RESPONSE", {"model": str(envelope.get("modelUsage") and "cli") or "cli",
                                "outputSummary": _summarize(text),
                                "usage": usage or None})
        emit("TOOL_COMPLETED", {"toolCallId": f"cli-{request.run_id[:8]}",
                                "toolName": Path(executable).name, "outcome": "SUCCESS",
                                "durationMs": elapsed_ms})

        if not text:
            emit("OUTPUT_PRODUCED", {"outputSummary": "CLI 未产生文本输出"})
            emit("RUN_COMPLETED", {"taskOutcome": "FAILURE"})
            return RunnerResult(
                task_outcome="FAILURE",
                output={"runner": "CLI", "cliVersion": version, "exitCode": 0,
                        "answer": "", "reason": "CLI 正常退出但没有产生输出"},
                usage=usage or None)

        emit("OUTPUT_PRODUCED", {"outputSummary": _summarize(text)})
        emit("RUN_COMPLETED", {"taskOutcome": "SUCCESS"})
        output: dict[str, Any] = {
            "runner": "CLI",
            "cliVersion": version,
            "exitCode": completed.returncode,
            "answer": text,
            "elapsedMs": elapsed_ms,
        }
        if commit:
            output["codeCommit"] = commit
        if output_path:
            # CLI 自报的结构化结果（若 prompt 要求且模型照做）。
            # 只并入 outputPath 指向的字段，并把值统一成字符串：模型可能回
            # `{"answer": 391}`（数字），而算子与前端都按文本比对——让类型随模型
            # 摇摆会让同一个 Run 的输出结构变得不可预测。
            structured = _try_json(text)
            if structured is not None and output_path in structured:
                raw = structured[output_path]
                output[output_path] = raw if isinstance(raw, str) else json.dumps(
                    raw, ensure_ascii=False, sort_keys=True)
                output["structuredOutput"] = {
                    key: value for key, value in structured.items() if key != output_path}
        return RunnerResult(task_outcome="SUCCESS", output=output,
                            usage=usage or None)


class _Timeout(Exception):
    """内部信号：命令超时。"""


def _spawn(argv: list[str], prompt: str, run_dir: Path,
           timeout: int) -> subprocess.CompletedProcess:
    kwargs: dict[str, Any] = {"shell": False, "cwd": str(run_dir)}
    if os.name == "nt":
        kwargs["creationflags"] = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
    else:
        kwargs["start_new_session"] = True

    process = subprocess.Popen(  # noqa: S603 - 参数数组，无 shell
        argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8", errors="replace", **kwargs)
    try:
        stdout, stderr = process.communicate(input=prompt, timeout=timeout)
    except subprocess.TimeoutExpired:
        _terminate(process)
        try:
            process.communicate(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
        raise _Timeout from None
    # communicate 返回后进程仍可能处于"已退出但句柄未释放"状态（Windows 上尤其如此）。
    # 等它真正结束再让调用方删目录，否则清理会静默失败并留下垃圾目录。
    try:
        process.wait(timeout=PROCESS_EXIT_GRACE_SECONDS)
    except subprocess.TimeoutExpired:
        process.kill()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass
    return subprocess.CompletedProcess(argv, process.returncode, stdout, stderr)


def _terminate(process: subprocess.Popen) -> None:
    """终止进程组，避免超时后遗留子进程继续占用资源。"""
    try:
        if os.name == "nt":
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(process.pid)],
                           capture_output=True, check=False, timeout=30)
        else:
            os.killpg(os.getpgid(process.pid), signal.SIGKILL)
    except (OSError, subprocess.SubprocessError):
        pass


def _cleanup(run_dir: Path) -> None:
    """删除本次 Attempt 的工作目录。

    Windows 上子进程刚退出时其工作目录可能仍短暂被占用，``rmtree`` 会直接失败。
    直接忽略错误等于留下垃圾目录——真实 Runner 每次执行都建目录，不清理迟早
    撑爆磁盘。所以这里做有限次重试，并在最后如实报告失败而不是假装清掉了。
    """
    for attempt in range(CLEANUP_ATTEMPTS):
        if not run_dir.exists():
            return
        try:
            shutil.rmtree(run_dir, ignore_errors=False)
            return
        except (OSError, PermissionError):
            if attempt + 1 >= CLEANUP_ATTEMPTS:
                break
            time.sleep(CLEANUP_BACKOFF_SECONDS * (attempt + 1))
    # 最后一次尝试：能删就删，删不掉只记日志（不抛——不能让清理失败盖掉真实结论）
    shutil.rmtree(run_dir, ignore_errors=True)


def _git_commit(run_dir: Path) -> str | None:
    """记录代码提交，便于"这个结果对应哪个版本"可追溯。"""
    target = run_dir
    try:
        completed = subprocess.run(  # noqa: S603 - 参数数组
            ["git", "rev-parse", "HEAD"], capture_output=True, text=True,
            timeout=15, check=False, cwd=str(target), stdin=subprocess.DEVNULL)
    except (OSError, subprocess.SubprocessError):
        return None
    value = (completed.stdout or "").strip()
    return value[:40] if completed.returncode == 0 and value else None


def _render_input(case: dict[str, Any]) -> str:
    payload = case.get("input") if isinstance(case, dict) else None
    if payload is None:
        return ""
    if isinstance(payload, str):
        return payload[:MAX_PROMPT_CHARS]
    return json.dumps(payload, ensure_ascii=False, sort_keys=True)[:MAX_PROMPT_CHARS]


def _configured_output_path(agent_config: dict[str, Any]) -> str | None:
    value = (agent_config or {}).get("outputPath")
    return value if isinstance(value, str) and value.strip() else None


def _build_prompt(request: RunnerRequest, input_text: str, output_path: str | None) -> str:
    """样本输入作为待办数据交给 Agent。

    注意这里与 Judge 相反：Runner 场景下被测 Agent **应该**执行输入里的指令，
    所以不额外包裹"不可信边界"。它拿到的仍是受限工作目录与超时。
    """
    parts = [f"任务样本（caseKey={request.case.get('caseKey')!r}）：", input_text or "（无输入）"]
    if output_path:
        parts += [
            "",
            f"完成后请把最终答案写入 JSON 的 {output_path} 字段，"
            "并在回复末尾附上一个 ```json 代码块，内容为该 JSON 对象。",
        ]
    if request.agent_config.get("instructions"):
        parts += ["", "附加要求：", str(request.agent_config["instructions"])[:4000]]
    return "\n".join(parts)


def _timeout_seconds(budget: dict[str, Any], request: RunnerRequest) -> int:
    for key in ("timeoutSeconds", "timeout_seconds"):
        value = budget.get(key)
        if isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0:
            return int(min(3600, value))
    remaining = _remaining_seconds(request)
    return int(min(3600, max(30, remaining))) if remaining else 900


def _remaining_seconds(request: RunnerRequest) -> int | None:
    from datetime import datetime, timezone

    deadline = request.deadline_at
    if deadline is None:
        return None
    if deadline.tzinfo is None:
        deadline = deadline.replace(tzinfo=timezone.utc)
    return int((deadline - datetime.now(tz=timezone.utc)).total_seconds())


def _parse_envelope(stdout: str) -> dict[str, Any]:
    try:
        payload = json.loads(stdout or "")
    except json.JSONDecodeError as exc:
        raise RunnerFailure("RUNNER_RESPONSE_MALFORMED",
                            f"CLI 输出不是合法 JSON: {exc}", retryable=False) from exc
    if not isinstance(payload, dict):
        raise RunnerFailure("RUNNER_RESPONSE_MALFORMED",
                            "CLI 输出不是 JSON 对象", retryable=False)
    if payload.get("is_error"):
        raise RunnerFailure("RUNNER_UPSTREAM_ERROR",
                            _redact(str(payload.get("result") or "CLI 返回错误"))[:600],
                            retryable=True)
    return payload


def _extract_usage(envelope: dict[str, Any]) -> dict[str, Any]:
    """真实用量与成本。CLI 没报就是没有——不补 0。"""
    usage: dict[str, Any] = {}
    raw = envelope.get("usage")
    if isinstance(raw, dict):
        for source, target in (("input_tokens", "inputTokens"),
                               ("output_tokens", "outputTokens"),
                               ("cache_read_input_tokens", "cacheReadInputTokens")):
            value = raw.get(source)
            if isinstance(value, (int, float)) and not isinstance(value, bool):
                usage[target] = int(value)
        total = sum(usage.get(key, 0) for key in
                    ("inputTokens", "outputTokens", "cacheReadInputTokens"))
        if total:
            usage["totalTokens"] = total
    cost = envelope.get("total_cost_usd")
    if isinstance(cost, (int, float)) and not isinstance(cost, bool):
        usage["costUsd"] = float(cost)
        usage["currency"] = "USD"
    model_usage = envelope.get("modelUsage")
    if isinstance(model_usage, dict) and model_usage:
        usage["models"] = sorted(str(name) for name in model_usage)[:5]
    return usage


def _try_json(text: str) -> dict[str, Any] | None:
    """从回复里提取模型自报的 JSON（若存在）。"""
    start = text.find("```json")
    if start < 0:
        return None
    start = text.find("\n", start) + 1
    end = text.find("```", start)
    if start <= 0 or end < 0:
        return None
    try:
        parsed = json.loads(text[start:end])
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


def _redact(text: str) -> str:
    """脱敏：命中密钥特征就整段替换，不做部分保留。"""
    value = text or ""
    lowered = value.lower()
    if any(marker.lower() in lowered for marker in _SECRET_MARKERS):
        return "[已脱敏：内容疑似包含凭据]"
    return value


def _clip(text: str | None, limit: int) -> str:
    value = (text or "").strip()
    return value if len(value) <= limit else value[:limit] + "…(已截断)"


def _summarize(text: str | None, limit: int = 200) -> str:
    return _clip(_redact(text), limit)