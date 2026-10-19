"""路由汇总。"""

from fastapi import APIRouter

from app.api.routes import health, worker

api_router = APIRouter()
api_router.include_router(health.router)
api_router.include_router(worker.router)
