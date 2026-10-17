"""配置加载测试。"""

from __future__ import annotations

import pytest

from app.config import Settings


def test_defaults_without_env(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in (
        "WORKER_PORT",
        "WORKER_HOST",
        "WORKER_ENVIRONMENT",
        "WORKER_LOG_LEVEL",
        "WORKER_LOG_FORMAT",
        "PORT",
        "HOST",
    ):
        monkeypatch.delenv(name, raising=False)

    settings = Settings(_env_file=None)

    assert settings.service_name == "repopilot-worker"
    assert settings.port == 8000
    assert settings.host == "0.0.0.0"
    assert settings.environment == "dev"
    assert settings.log_level == "INFO"
    assert settings.log_format == "json"


def test_env_override(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("WORKER_PORT", "9100")
    monkeypatch.setenv("WORKER_LOG_LEVEL", "DEBUG")
    monkeypatch.setenv("WORKER_LOG_FORMAT", "console")

    settings = Settings(_env_file=None)

    assert settings.port == 9100
    assert settings.log_level == "DEBUG"
    assert settings.log_format == "console"


def test_infrastructure_secrets_are_not_exposed_in_repr(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MYSQL_PASSWORD", "super-secret")
    monkeypatch.setenv("RABBITMQ_PASSWORD", "super-secret")
    monkeypatch.setenv("MINIO_ROOT_PASSWORD", "super-secret")

    settings = Settings(_env_file=None)

    assert "super-secret" not in repr(settings)


def test_invalid_log_level_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("WORKER_LOG_LEVEL", "VERBOSE")

    with pytest.raises(Exception):
        Settings(_env_file=None)
