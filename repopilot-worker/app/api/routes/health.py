"""健康检查路由。"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Literal

from fastapi import APIRouter
from pydantic import BaseModel, Field

from app.config import Settings, get_settings

router = APIRouter(tags=["health"])


class ComponentStatus(BaseModel):
    """单个依赖组件的状态。"""

    name: str = Field(description="组件名，例如 mysql / rabbitmq / minio")
    status: Literal["UP", "DOWN", "UNKNOWN"] = Field(description="组件状态")
    detail: str | None = Field(default=None, description="可读说明，失败时为错误摘要")


class HealthResponse(BaseModel):
    """健康检查响应。"""

    status: Literal["UP", "DOWN", "DEGRADED"] = Field(description="聚合状态")
    service: str = Field(description="服务名")
    version: str = Field(description="服务版本")
    environment: str = Field(description="运行环境标识")
    timestamp: datetime = Field(description="采集时间（UTC）")
    components: list[ComponentStatus] = Field(
        default_factory=list,
        description="依赖组件状态；V1 尚未接入任何外部依赖，因此为空列表",
    )


@router.get(
    "/health",
    response_model=HealthResponse,
    summary="健康检查",
    description="返回 Worker 存活状态与版本信息。V1 不连接任何外部依赖，components 为空。",
)
def health() -> HealthResponse:
    settings: Settings = get_settings()
    return HealthResponse(
        status="UP",
        service=settings.service_name,
        version=settings.version,
        environment=settings.environment,
        timestamp=datetime.now(tz=timezone.utc),
        components=[],
    )
