"""统一错误响应。

与 Java 服务保持一致的最小结构：
``{"code": "...", "message": "...", "requestId": "...", "timestamp": "..."}``

不返回异常堆栈或内部路径，5xx 详细原因只写入服务端日志。
"""

from __future__ import annotations

from datetime import datetime, timezone

from fastapi import FastAPI
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.requests import Request

from app.common.logging import get_logger, request_id_ctx

logger = get_logger("app.error")

_STATUS_CODE_NAMES = {
    400: "BAD_REQUEST",
    401: "UNAUTHORIZED",
    403: "FORBIDDEN",
    404: "NOT_FOUND",
    405: "METHOD_NOT_ALLOWED",
    422: "VALIDATION_ERROR",
    500: "INTERNAL_ERROR",
    503: "DEPENDENCY_UNAVAILABLE",
}


def _now_iso() -> str:
    return datetime.now(tz=timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _envelope(code: str, message: str) -> dict[str, str | None]:
    return {
        "code": code,
        "message": message,
        "requestId": request_id_ctx.get(),
        "timestamp": _now_iso(),
    }


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(StarletteHTTPException)
    async def handle_http_exception(_: Request, exc: StarletteHTTPException) -> JSONResponse:
        code = _STATUS_CODE_NAMES.get(exc.status_code, "HTTP_ERROR")
        return JSONResponse(status_code=exc.status_code, content=_envelope(code, str(exc.detail)))

    @app.exception_handler(Exception)
    async def handle_unexpected_exception(_: Request, exc: Exception) -> JSONResponse:
        logger.error("unhandled_exception", exc_info=exc)
        return JSONResponse(
            status_code=500,
            content=_envelope("INTERNAL_ERROR", "服务内部错误"),
        )
