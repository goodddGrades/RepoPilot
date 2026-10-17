"""pytest 全局夹具。

原则：
* 测试不依赖外部基础设施（MySQL / RabbitMQ / MinIO）；
* 每个用例都从干净的环境变量与配置缓存开始，避免相互污染。
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config import get_settings


@pytest.fixture(autouse=True)
def reset_settings_cache() -> None:
    """清空配置单例，保证用例读取到当前环境变量。"""
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


@pytest.fixture()
def client() -> TestClient:
    """构造独立的应用实例（不依赖模块级 app 的初始化时机）。"""
    from app.main import create_app

    with TestClient(create_app()) as test_client:
        yield test_client
