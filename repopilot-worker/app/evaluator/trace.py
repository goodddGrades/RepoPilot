"""封口轨迹读取。

评测算子看到的轨迹必须与后端封口时的清单一致：
* 只读 ``accepted Attempt``（离线 Run 没有 Attempt，按 Run 读取）；
* 逐页读取直到 ``hasMore=false``，用 ``eventCount`` 与 ``manifestSha256`` 双重校验；
* 校验失败抛技术错误，**不**拿残缺轨迹去判"没有工具错误"。

为什么不用固定延时等事件到齐：那只是把不确定性藏起来。封口清单是唯一权威。
"""

from __future__ import annotations

import hashlib
import urllib.parse
from typing import Any

from app.client import WorkerApiError
from app.evaluator.base import EvaluationDependencyError, TraceEvents, TraceRef

MAX_PAGES = 200


class TraceReader:
    def __init__(self, client: Any) -> None:
        self.client = client

    def load(self, ref: TraceRef) -> TraceEvents:
        if not ref.sealed:
            raise EvaluationDependencyError("TRACE_NOT_SEALED",
                                            "Run 尚未封口，不能作为评测输入", retryable=False)
        if not ref.events_url:
            raise EvaluationDependencyError("TRACE_MISSING",
                                            "评测输入没有提供轨迹地址", retryable=False)

        events: list[dict[str, Any]] = []
        cursor: str | None = None
        pages = 0
        while True:
            url = ref.events_url
            if cursor:
                joiner = "&" if "?" in url else "?"
                url = f"{url}{joiner}cursor={urllib.parse.quote(cursor)}"
            try:
                page = self.client.get(url)
            except WorkerApiError as exc:
                retryable = exc.status >= 500 or exc.status == 429
                raise EvaluationDependencyError("TRACE_FETCH_FAILED",
                                                f"读取轨迹失败: {exc.status} {exc.message}",
                                                retryable=retryable) from exc
            except Exception as exc:  # noqa: BLE001 - 网络异常统一归为可重试依赖错误
                raise EvaluationDependencyError("TRACE_FETCH_FAILED",
                                                f"读取轨迹失败: {exc}", retryable=True) from exc

            items = page.get("items") or []
            events.extend(items)
            pages += 1
            if not page.get("hasMore"):
                break
            cursor = page.get("nextCursor")
            if not cursor:
                # 服务端说还有下一页却不给游标：契约被破坏，不能自行猜测
                raise EvaluationDependencyError("TRACE_CURSOR_MISSING",
                                                "轨迹分页缺少 nextCursor", retryable=False)
            if pages >= MAX_PAGES:
                raise EvaluationDependencyError("TRACE_TOO_LARGE",
                                                f"轨迹超过 {MAX_PAGES} 页，拒绝继续读取",
                                                retryable=False)

        expected_count = ref.event_count
        if expected_count and len(events) != expected_count:
            raise EvaluationDependencyError(
                "TRACE_MANIFEST_MISMATCH",
                f"轨迹条数与封口清单不一致：读到 {len(events)}，清单 {expected_count}",
                retryable=False)
        if ref.manifest_sha256:
            actual = manifest_sha256([str(event.get("eventId", "")) for event in events])
            if actual != ref.manifest_sha256.lower():
                raise EvaluationDependencyError("TRACE_MANIFEST_MISMATCH",
                                                "轨迹清单摘要与封口值不一致", retryable=False)
        return TraceEvents(events, expected_count)


def manifest_sha256(event_ids: list[str]) -> str:
    """与后端一致：eventId 按 ASCII 升序、LF 连接、末尾无 LF、UTF-8 求 SHA-256。"""
    joined = "\n".join(sorted(event_ids))
    return hashlib.sha256(joined.encode("utf-8")).hexdigest()
