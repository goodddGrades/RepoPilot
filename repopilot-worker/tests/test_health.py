"""健康检查接口测试。"""

from __future__ import annotations

from datetime import datetime

from fastapi.testclient import TestClient


def test_health_returns_service_status(client: TestClient) -> None:
    response = client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["service"] == "repopilot-worker"
    assert body["version"] == "0.1.0"
    assert body["components"] == []
    # 时间戳必须是可解析的 UTC 时间
    assert datetime.fromisoformat(body["timestamp"]).tzinfo is not None


def test_health_generates_request_id_header(client: TestClient) -> None:
    response = client.get("/health")

    request_id = response.headers.get("X-Request-Id")
    assert request_id is not None
    assert len(request_id) == 36  # UUID4


def test_health_reuses_inbound_request_id(client: TestClient) -> None:
    response = client.get("/health", headers={"X-Request-Id": "trace-abc-001"})

    assert response.headers["X-Request-Id"] == "trace-abc-001"


def test_health_rejects_invalid_request_id(client: TestClient) -> None:
    response = client.get("/health", headers={"X-Request-Id": "bad id with spaces"})

    assert response.headers["X-Request-Id"] != "bad id with spaces"


def test_unknown_route_returns_unified_error_envelope(client: TestClient) -> None:
    response = client.get("/not-exist")

    assert response.status_code == 404
    body = response.json()
    assert body["code"] == "NOT_FOUND"
    assert "requestId" in body
    assert "timestamp" in body


def test_openapi_schema_is_available(client: TestClient) -> None:
    response = client.get("/openapi.json")

    assert response.status_code == 200
    assert "/health" in response.json()["paths"]
