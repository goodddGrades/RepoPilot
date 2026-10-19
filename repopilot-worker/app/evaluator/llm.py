"""Judge 的模型调用适配层。

设计边界（对齐 `docs/modules/evaluation-engine.md`）：

* Judge 与被测 Agent 是**两套配置**：本模块只接收 plan item config 里显式声明的
  model / promptVersion / temperature / maxTokens，不从环境里"猜"模型；
* 被测文本用不可混淆的数据边界包裹，且系统提示明确禁止采纳被测文本里的指令——
  提示注入不能改变评分规则；
* 输出按固定 JSON Schema 校验。**非法 JSON、限流、超时是技术错误**，
  不是质量 FAIL：它们会让 Worker 重试或标记技术失败，而不是把基础设施抖动
  记成"模型认为答案不好"；
* 每次调用（含被重试的）都记录 token 与成本。未知成本字段保持缺失，不写 0。

供应商接入方式
--------------
真实调用通过 :class:`ClaudeCliJudge` —— 调用本机已认证的 ``claude -p`` CLI，
不引入第三方 SDK，也不假设某个不存在的 HTTP 网关。CI 使用
:class:`StubJudge`：确定性桩，用于验证协议、校验与重试逻辑，不冒充真实模型调用。
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Protocol

# 供应商返回里可能裹着 markdown 代码块；先剥掉再校验 JSON。
_FENCE_RE = re.compile(r"```(?:json)?\s*(.*?)\s*```", re.DOTALL)


class JudgeCallError(Exception):
    """模型调用层失败。

    ``retryable`` 区分限流/超时（值得重试）与鉴权失败、响应不合规
    （重试没有意义，应尽快暴露配置问题）。
    """

    def __init__(self, error_code: str, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.error_code = error_code
        self.message = message
        self.retryable = retryable


@dataclass(frozen=True)
class JudgeRequest:
    """一次 Judge 调用所需的全部输入（已脱敏、已截断）。"""

    system_prompt: str
    user_prompt: str
    json_schema: dict[str, Any]
    model: str
    temperature: float
    max_tokens: int
    timeout_seconds: float


@dataclass
class JudgeResponse:
    """模型原始返回：正文 + 用量。``usage`` 缺失就是缺失，不补 0。"""

    text: str
    usage: dict[str, Any] = field(default_factory=dict)

    @property
    def has_usage(self) -> bool:
        return bool(self.usage)


class JudgeClient(Protocol):
    """Judge 供应商适配接口。"""

    name: str

    def complete(self, request: JudgeRequest) -> JudgeResponse:
        ...

    def describe(self) -> dict[str, Any]:
        """可审计的调用来源：供应商名、模型、版本。写入 result 供人工核查。"""
        ...


class ClaudeCliJudge:
    """通过本机已认证的 ``claude -p`` CLI 调用模型。

    为什么用 CLI 而不是 SDK：本机实际可用的就是 CLI（``codex`` 不存在），
    任务契约要求"不假定不存在的 SDK"。CLI 走自身的鉴权与计费，
    RepoPilot 不持有模型密钥。

    ``--json-schema`` 让供应商做结构化输出约束；本模块**仍然独立校验**，
    不信任"我传了 schema 所以一定合法"。
    """

    name = "claude-cli"

    def __init__(self, executable: str = "claude", *, extra_args: list[str] | None = None,
                 version: str | None = None) -> None:
        self.executable = executable
        self.extra_args = list(extra_args or [])
        self.version = version

    def _resolve(self) -> str:
        resolved = shutil.which(self.executable) or self.executable
        return resolved

    def _probe_version(self) -> str | None:
        if self.version:
            return self.version
        try:
            completed = subprocess.run(  # noqa: S603 - 参数数组，不经 shell
                [self._resolve(), "--version"],
                capture_output=True, text=True, timeout=30, check=False,
                stdin=subprocess.DEVNULL)
        except (OSError, subprocess.SubprocessError):
            return None
        output = (completed.stdout or "").strip()
        return output.splitlines()[0][:120] if output else None

    def complete(self, request: JudgeRequest) -> JudgeResponse:
        # extra_args 放在固定参数之前：某些 CLI 的可执行脚本参数必须紧跟 argv[0]
        # （例如把 Python 桩脚本接进来时），放末尾会被当成未知选项。
        argv = [
            self._resolve(), *self.extra_args,
            "-p", "--output-format", "json",
            "--model", request.model,
            "--json-schema", json.dumps(request.json_schema, ensure_ascii=False),
        ]
        # 提示词走 stdin：命令行参数会出现在进程列表里，长文本与换行都不可靠。
        payload = request.user_prompt
        started = time.monotonic()
        try:
            completed = subprocess.run(  # noqa: S603 - 参数数组，禁止 shell 拼接
                argv, input=payload, capture_output=True, text=True,
                timeout=request.timeout_seconds, check=False,
                env={**_clean_env(), "CLAUDE_CODE_MAX_OUTPUT_TOKENS": str(request.max_tokens)},
            )
        except subprocess.TimeoutExpired as exc:
            raise JudgeCallError("JUDGE_TIMEOUT",
                                 f"模型调用超过 {request.timeout_seconds}s 未返回",
                                 retryable=True) from exc
        except OSError as exc:
            raise JudgeCallError("JUDGE_CLI_UNAVAILABLE",
                                 f"无法启动模型 CLI: {exc}", retryable=False) from exc

        elapsed_ms = int((time.monotonic() - started) * 1000)
        if completed.returncode != 0:
            stderr = (completed.stderr or "")[:400]
            lowered = stderr.lower()
            if "rate limit" in lowered or "429" in lowered:
                raise JudgeCallError("JUDGE_RATE_LIMITED",
                                     f"模型限流: {stderr}", retryable=True)
            raise JudgeCallError("JUDGE_CLI_FAILED",
                                 f"模型 CLI 退出码 {completed.returncode}: {stderr}",
                                 retryable=False)
        return _parse_cli_json(completed.stdout or "", elapsed_ms)


def _clean_env() -> dict[str, str]:
    """透传必要的环境变量，同时剔除凭据类变量。

    CLI 自带的会话凭据（如 ``ANTHROPIC_API_KEY``）由 CLI 自己从其配置读取；
    这里只保证 PATH/HOME 等运行环境可用，不额外注入 RepoPilot 侧密钥。
    """
    import os

    keep = ("PATH", "HOME", "USERPROFILE", "SYSTEMROOT", "TEMP", "TMP",
            "APPDATA", "LOCALAPPDATA", "HOMEDRIVE", "HOMEPATH", "LANG")
    return {key: value for key, value in os.environ.items() if key.upper() in keep}


def _parse_cli_json(raw: str, elapsed_ms: int) -> JudgeResponse:
    try:
        envelope = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise JudgeCallError("JUDGE_RESPONSE_MALFORMED",
                             f"模型 CLI 输出不是合法 JSON: {exc}", retryable=False) from exc

    if isinstance(envelope, dict) and envelope.get("is_error"):
        message = str(envelope.get("result") or envelope.get("error") or "")[:400]
        lowered = message.lower()
        retryable = "rate limit" in lowered or "429" in lowered or "overloaded" in lowered
        raise JudgeCallError("JUDGE_UPSTREAM_ERROR", message or "模型返回错误",
                             retryable=retryable)

    text = ""
    if isinstance(envelope, dict):
        text = str(envelope.get("result") or envelope.get("text") or "")
    if not text:
        raise JudgeCallError("JUDGE_RESPONSE_EMPTY", "模型返回空内容", retryable=False)

    usage = _extract_usage(envelope)
    usage["elapsedMs"] = elapsed_ms
    return JudgeResponse(text=text, usage=usage)


def _extract_usage(envelope: dict[str, Any]) -> dict[str, Any]:
    """从 CLI 信封里提取 token 与成本。缺失就缺失——不写 0 冒充已知。"""
    usage: dict[str, Any] = {}
    raw_usage = envelope.get("usage")
    if isinstance(raw_usage, dict):
        for key in ("input_tokens", "output_tokens", "cache_read_input_tokens",
                    "cache_creation_input_tokens"):
            value = raw_usage.get(key)
            if isinstance(value, (int, float)) and not isinstance(value, bool):
                usage[key] = value
    total_tokens = (usage.get("input_tokens") or 0) + (usage.get("output_tokens") or 0)
    if total_tokens:
        usage["totalTokens"] = total_tokens

    cost = envelope.get("total_cost_usd")
    if isinstance(cost, (int, float)) and not isinstance(cost, bool):
        usage["costUsd"] = float(cost)
        usage["currency"] = "USD"
    return usage


class HttpJsonJudge:
    """通用 HTTP JSON 端点适配（公司网关等）。

    V1 默认不启用：契约要求"以本地实际可用接口实现"。保留实现是为了让
    真实网关接入时不需要改动算子逻辑，但没配置 base_url 就不构造实例，
    因此不会冒充"已验证的外部调用"。
    """

    name = "http-json"

    def __init__(self, base_url: str, *, secret_ref_env: str = "REPOPILOT_JUDGE_API_KEY",
                 timeout: float = 60.0, model: str = "") -> None:
        if not base_url:
            raise ValueError("base_url 不能为空")
        self.base_url = base_url.rstrip("/")
        self.secret_ref_env = secret_ref_env
        self.timeout = timeout
        self.model = model

    def complete(self, request: JudgeRequest) -> JudgeResponse:
        import os

        api_key = os.environ.get(self.secret_ref_env, "")
        body = json.dumps({
            "model": request.model or self.model,
            "temperature": request.temperature,
            "max_tokens": request.max_tokens,
            "system": request.system_prompt,
            "messages": [{"role": "user", "content": request.user_prompt}],
            "response_format": {"type": "json_schema",
                                "json_schema": {"name": "judge_result",
                                                "schema": request.json_schema}},
        }, ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if api_key:
            headers["Authorization"] = f"Bearer {api_key}"
        http_request = urllib.request.Request(self.base_url + "/v1/chat/completions",
                                             data=body, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(http_request, timeout=self.timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            retryable = exc.code == 429 or exc.code >= 500
            raise JudgeCallError("JUDGE_UPSTREAM_ERROR",
                                 f"模型网关返回 {exc.code}", retryable=retryable) from exc
        except (urllib.error.URLError, TimeoutError) as exc:
            raise JudgeCallError("JUDGE_TIMEOUT", f"模型网关不可达: {exc}",
                                 retryable=True) from exc

        choices = payload.get("choices") or []
        if not choices:
            raise JudgeCallError("JUDGE_RESPONSE_EMPTY", "模型网关没有返回候选",
                                 retryable=False)
        text = str((choices[0].get("message") or {}).get("content") or "")
        usage_raw = payload.get("usage") or {}
        usage: dict[str, Any] = {}
        for source, target in (("prompt_tokens", "input_tokens"),
                               ("completion_tokens", "output_tokens")):
            value = usage_raw.get(source)
            if isinstance(value, (int, float)) and not isinstance(value, bool):
                usage[target] = value
        return JudgeResponse(text=text, usage=usage)

    def describe(self) -> dict[str, Any]:
        return {"provider": self.name, "baseUrl": self.base_url,
                "credentialEnv": self.secret_ref_env, "verified": False}


class StubJudge:
    """确定性 Judge 桩，供 CI 与单元测试使用。

    它**不是**真实模型调用：返回的 usage 明确带 ``stub=true``，``describe()``
    声明 ``verified=False``，算子会把 ``provider`` 写进 result。因此报告里能
    区分"真 Judge 跑出来的分"和"CI 里跑通协议的桩分"。

    桩按被评文本里的关键词给结论，用于验证四类语义确实被正确路由：
    限流→技术错误可重试；非法 JSON→技术错误；``INDETERMINATE`` → 证据不足；
    其余按 ``judge_fixture`` 指定的结论。
    """

    name = "stub"

    def __init__(self, *, verdict: str = "PASS", score: float = 1.0,
                 inject: str = "ok") -> None:
        self.verdict = verdict
        self.score = score
        self.inject = inject
        self.calls: list[JudgeRequest] = []

    def complete(self, request: JudgeRequest) -> JudgeResponse:
        self.calls.append(request)
        if self.inject == "ratelimit":
            raise JudgeCallError("JUDGE_RATE_LIMITED", "桩：模拟限流", retryable=True)
        if self.inject == "malformed":
            return JudgeResponse(text="这不是 JSON", usage={"stub": 1})
        if self.inject == "no-score":
            return JudgeResponse(text=json.dumps(
                {"verdict": "PASS", "score": None, "reason": "无分数", "evidence_quotes": []},
                ensure_ascii=False), usage={"stub": 1})
        payload = {
            "verdict": self.verdict,
            "score": self.score if self.verdict != "INDETERMINATE" else None,
            "reason": f"桩 Judge（{self.inject}）",
            "evidence_quotes": [],
        }
        return JudgeResponse(text=json.dumps(payload, ensure_ascii=False),
                             usage={"stub": 1, "elapsedMs": 1})

    def describe(self) -> dict[str, Any]:
        return {"provider": self.name, "verified": False,
                "note": "确定性测试桩，不是真实模型调用"}


def strip_code_fence(text: str) -> str:
    """剥掉 markdown 代码围栏；没有围栏时原样返回。"""
    match = _FENCE_RE.search(text)
    return match.group(1).strip() if match else text.strip()