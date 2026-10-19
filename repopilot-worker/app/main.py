"""FastAPI 应用入口。

启动（显式触发一次执行）：
    uvicorn app.main:app --host 0.0.0.0 --port 8000
    curl -X POST http://127.0.0.1:8000/worker/run-once

TASK-004 提供 Mock Runner 与完整 HTTP 协议；真实 Codex 适配属于 TASK-008，
在此之前不会声称"已接入真实 Agent"。
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import AsyncIterator

from fastapi import FastAPI

from app.api.errors import register_exception_handlers
from app.api.middleware import RequestContextMiddleware
from app.api.router import api_router
from app.common.logging import configure_logging, get_logger
from app.config import get_settings


@asynccontextmanager
async def _lifespan(settings) -> AsyncIterator[None]:
    """按配置启停后台轮询；默认不启动。"""
    if settings.autostart:
        from app.runtime import get_runtime

        get_runtime().start()
    yield
    if settings.autostart:
        from app.runtime import reset_runtime

        reset_runtime()


def create_app() -> FastAPI:
    """构建应用实例（工厂函数，便于测试中独立构造）。"""
    settings = get_settings()
    configure_logging(
        level=settings.log_level,
        log_format=settings.log_format,
        service_name=settings.service_name,
    )

    app = FastAPI(
        title="RepoPilot Worker",
        version=settings.version,
        description="RepoPilot Agent 执行与评测 Worker（TASK-004：Mock Runner 与执行协议）",
        docs_url="/docs",
        openapi_url="/openapi.json",
        lifespan=lambda app: _lifespan(settings),
    )

    app.add_middleware(RequestContextMiddleware)
    register_exception_handlers(app)
    app.include_router(api_router)

    get_logger("app.startup").info(
        "worker_started",
        extra={
            "extra_fields": {
                "service": settings.service_name,
                "version": settings.version,
                "environment": settings.environment,
                "listen": f"{settings.host}:{settings.port}",
                "autostart": settings.autostart,
            }
        },
    )
    return app


app = create_app()
