"""FastAPI 应用入口。

启动：
    uvicorn app.main:app --host 0.0.0.0 --port 8000

V1 只提供健康检查，不包含任何 Agent Runner 或 Judge 的真实实现。
"""

from __future__ import annotations

from fastapi import FastAPI

from app.api.errors import register_exception_handlers
from app.api.middleware import RequestContextMiddleware
from app.api.router import api_router
from app.common.logging import configure_logging, get_logger
from app.config import get_settings


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
        description="RepoPilot Agent 执行与评测 Worker（V1 仅提供健康检查）",
        docs_url="/docs",
        openapi_url="/openapi.json",
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
            }
        },
    )
    return app


app = create_app()
