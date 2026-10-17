"""请求上下文中间件：生成/透传 request id 并记录访问日志。"""

from __future__ import annotations

import time
import uuid

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

from app.common.logging import get_logger, request_id_ctx

REQUEST_ID_HEADER = "X-Request-Id"
_MAX_REQUEST_ID_LENGTH = 64

logger = get_logger("app.access")


def _resolve_request_id(raw: str | None) -> str:
    """复用合法的上游 request id，否则生成新的。"""
    if raw and len(raw) <= _MAX_REQUEST_ID_LENGTH and all(c.isalnum() or c in "-_" for c in raw):
        return raw
    return str(uuid.uuid4())


class RequestContextMiddleware(BaseHTTPMiddleware):
    """为每个请求绑定 request_id，并输出结构化访问日志。"""

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        request_id = _resolve_request_id(request.headers.get(REQUEST_ID_HEADER))
        token = request_id_ctx.set(request_id)
        started = time.perf_counter()
        try:
            response = await call_next(request)
            duration_ms = round((time.perf_counter() - started) * 1000, 2)
            logger.info(
                "http_request",
                extra={
                    "extra_fields": {
                        "method": request.method,
                        "path": request.url.path,
                        "status_code": response.status_code,
                        "duration_ms": duration_ms,
                    }
                },
            )
            response.headers[REQUEST_ID_HEADER] = request_id
            return response
        finally:
            request_id_ctx.reset(token)
