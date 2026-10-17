"""结构化日志。

两种输出格式：
* ``json``     —— 一行一条 JSON，便于采集与检索（默认，容器环境使用）；
* ``console``  —— 便于本地阅读的可读格式。

每条日志自动包含 ``timestamp`` / ``level`` / ``logger`` / ``message`` / ``service`` /
``request_id``，业务附加字段通过 ``extra={"extra_fields": {...}}`` 传入。
"""

from __future__ import annotations

import json
import logging
import sys
from contextvars import ContextVar
from datetime import datetime, timezone
from typing import Any, Literal

# 当前请求的追踪 ID；由中间件写入，日志格式化器读取
request_id_ctx: ContextVar[str | None] = ContextVar("request_id", default=None)

_SERVICE_NAME: str = "repopilot-worker"
_CONFIGURED = False

_CONSOLE_FORMAT = "%(asctime)s %(levelname)-5s [%(name)s] [%(request_id)s] %(message)s"


class JsonFormatter(logging.Formatter):
    """把 LogRecord 序列化为单行 JSON。"""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "timestamp": datetime.fromtimestamp(record.created, tz=timezone.utc)
            .isoformat(timespec="milliseconds")
            .replace("+00:00", "Z"),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "service": _SERVICE_NAME,
            "request_id": request_id_ctx.get(),
        }
        extra_fields = getattr(record, "extra_fields", None)
        if isinstance(extra_fields, dict):
            payload.update(extra_fields)
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False, default=str)


class ConsoleFormatter(logging.Formatter):
    """可读输出，同时把 request_id 注入日志记录。"""

    def format(self, record: logging.LogRecord) -> str:
        if not hasattr(record, "request_id"):
            record.request_id = request_id_ctx.get() or "-"
        return super().format(record)


def configure_logging(
    level: str = "INFO",
    log_format: Literal["json", "console"] = "json",
    service_name: str = "repopilot-worker",
) -> None:
    """初始化根日志器（幂等，重复调用不会叠加 handler）。"""
    global _SERVICE_NAME, _CONFIGURED

    _SERVICE_NAME = service_name
    root = logging.getLogger()

    for handler in list(root.handlers):
        root.removeHandler(handler)

    handler = logging.StreamHandler(stream=sys.stdout)
    if log_format == "json":
        handler.setFormatter(JsonFormatter())
    else:
        handler.setFormatter(ConsoleFormatter(_CONSOLE_FORMAT))

    root.addHandler(handler)
    root.setLevel(level.upper())

    # 统一第三方库日志格式，避免非结构化日志混入
    for noisy in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        logger = logging.getLogger(noisy)
        logger.handlers = [handler]
        logger.propagate = False

    _CONFIGURED = True


def is_configured() -> bool:
    """供测试断言使用。"""
    return _CONFIGURED


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(name)
