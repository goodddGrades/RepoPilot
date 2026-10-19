#!/usr/bin/env python
"""桩 Coding Agent CLI。

存在意义：让"真实 Runner 适配器"的测试在 CI 上**可重复、零外部依赖、零费用**。
它模拟一个 Coding Agent CLI 的外部行为（读 stdin、写 JSON 信封、按模式失败），
被测的是 RepoPilot 侧适配器的正确性，不是 CLI 本身。

行为由环境变量 ``STUB_MODE`` 选择：

===============  ==========================================================
ok               正常返回，带 token 与成本
structured        返回带 ```json 代码块的结构化答案
empty             退出码 0 但内容为空
crash             退出码 2，stderr 里含疑似凭据（验证脱敏）
notjson           输出不是 JSON（验证协议错误处理）
upstream-error    is_error 信封（验证上游错误可重试）
hang              长时间挂起（验证超时）
child             派生子进程后挂起（验证进程组清理）
===============  ==========================================================
"""

from __future__ import annotations

import json
import os
import sys
import time

MODE = os.environ.get("STUB_MODE", "ok")


def emit(payload: dict) -> None:
    print(json.dumps(payload, ensure_ascii=False))


def main() -> int:
    argv = sys.argv[1:]
    stdin_text = sys.stdin.read()

    if "--version" in argv:
        print("stub-coding-agent 9.9.9")
        return 0

    if MODE == "hang":
        time.sleep(300)
        return 0

    if MODE == "child":
        import subprocess
        marker = os.environ.get("STUB_CHILD_MARKER", "survivor.txt")
        # 用 json.dumps 传路径：Windows 反斜杠不能直接进代码字符串
        code = ("import time, pathlib; time.sleep(2); "
                f"pathlib.Path({json.dumps(marker)}).write_text('alive')")
        subprocess.Popen([sys.executable, "-c", code])
        time.sleep(300)
        return 0

    if MODE == "crash":
        # 故意包含疑似密钥：适配器必须脱敏，不能让它进事件或错误消息
        print("boom: api_key=SECRET-VALUE token=abc123", file=sys.stderr)
        return 2

    if MODE == "notjson":
        print("this is definitely not json")
        return 0

    if MODE == "upstream-error":
        emit({"is_error": True, "result": "upstream overloaded"})
        return 0

    if MODE == "empty":
        emit({"is_error": False, "result": "",
              "usage": {"input_tokens": 3, "output_tokens": 0}})
        return 0

    base = {
        "is_error": False,
        "modelUsage": {"stub-model": {}},
        "usage": {"input_tokens": 11, "output_tokens": 7},
        "total_cost_usd": 0.0021,
        "stubStdinLength": len(stdin_text),
    }

    if MODE == "structured":
        base["result"] = ('答案是 4。\n\n```json\n'
                          '{"answer": "4", "confidence": 0.9}\n```')
        emit(base)
        return 0

    if MODE == "structured-number":
        # 模型回数字而非字符串：适配器必须归一化，否则输出结构会随模型摇摆
        base["result"] = '391\n\n```json\n{"answer": 391}\n```'
        emit(base)
        return 0

    base["result"] = f"stub answer（收到 {len(stdin_text)} 字符输入）"
    emit(base)
    return 0


if __name__ == "__main__":
    sys.exit(main())