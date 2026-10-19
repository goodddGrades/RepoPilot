"""Worker 控制接口。

这些接口**不**属于 Java 后端协议，只用于本地演示与运维：
* ``GET /worker/status`` 查看当前 Runner 与上次领取结果；
* ``POST /worker/run-once`` 显式触发一次"领取—执行—回传"，便于复现与演示。
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter

from app.runtime import get_runtime

router = APIRouter(prefix="/worker", tags=["worker"])


@router.get("/status")
def status() -> dict[str, Any]:
    return get_runtime().status()


@router.post("/run-once")
def run_once() -> dict[str, Any]:
    return get_runtime().run_once()
