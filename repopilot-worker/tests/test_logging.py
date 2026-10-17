"""结构化日志测试。"""

from __future__ import annotations

import json
import logging

import pytest

from app.common.logging import (
    ConsoleFormatter,
    JsonFormatter,
    configure_logging,
    request_id_ctx,
)


@pytest.fixture()
def restore_root_logger():
    root = logging.getLogger()
    saved_handlers = list(root.handlers)
    saved_level = root.level
    yield
    for handler in list(root.handlers):
        root.removeHandler(handler)
    for handler in saved_handlers:
        root.addHandler(handler)
    root.setLevel(saved_level)


def _make_record(message: str, args: tuple = ()) -> logging.LogRecord:
    return logging.LogRecord(
        name="app.test",
        level=logging.INFO,
        pathname=__file__,
        lineno=10,
        msg=message,
        args=args,
        exc_info=None,
    )


def test_json_formatter_emits_required_fields() -> None:
    record = _make_record("run %s finished", ("run_001",))
    record.extra_fields = {"duration_ms": 12.5, "status": "COMPLETED"}

    token = request_id_ctx.set("req-123")
    try:
        payload = json.loads(JsonFormatter().format(record))
    finally:
        request_id_ctx.reset(token)

    assert payload["level"] == "INFO"
    assert payload["logger"] == "app.test"
    assert payload["message"] == "run run_001 finished"
    assert payload["request_id"] == "req-123"
    assert payload["duration_ms"] == 12.5
    assert payload["status"] == "COMPLETED"
    assert payload["timestamp"].endswith("Z")
    assert "service" in payload


def test_json_formatter_includes_exception_traceback() -> None:
    try:
        raise ValueError("judge provider 超时")
    except ValueError:
        import sys

        record = logging.LogRecord(
            name="app.test",
            level=logging.ERROR,
            pathname=__file__,
            lineno=20,
            msg="evaluation_failed",
            args=(),
            exc_info=sys.exc_info(),
        )

    payload = json.loads(JsonFormatter().format(record))

    assert "ValueError" in payload["exception"]


def test_console_formatter_injects_request_id() -> None:
    record = _make_record("hello")

    token = request_id_ctx.set("req-console")
    try:
        line = ConsoleFormatter("%(levelname)s [%(request_id)s] %(message)s").format(record)
    finally:
        request_id_ctx.reset(token)

    assert "req-console" in line
    assert "hello" in line


def test_configure_logging_is_idempotent(restore_root_logger) -> None:
    configure_logging(level="DEBUG", log_format="json")
    configure_logging(level="INFO", log_format="console")

    root = logging.getLogger()
    assert len(root.handlers) == 1
    assert root.level == logging.INFO
    assert isinstance(root.handlers[0].formatter, ConsoleFormatter)
