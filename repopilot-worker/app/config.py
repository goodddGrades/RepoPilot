"""Worker 运行配置。

约定：
* 所有可变配置来自环境变量或 .env 文件，代码中不出现任何凭据；
* 仓库根目录 .env 优先于 worker 目录下的 .env（后者仅用于独立启动时的覆盖）；
* 环境变量名称与 .env.example 保持一致，避免同一配置出现多种写法。
"""

from functools import lru_cache
from typing import Literal

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """进程级配置（不可变，进程启动时读入一次）。"""

    model_config = SettingsConfigDict(
        env_file=("../.env", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # ---- 服务标识 ----
    service_name: str = "repopilot-worker"
    version: str = "0.1.0"
    environment: str = Field(
        default="dev",
        validation_alias=AliasChoices("WORKER_ENVIRONMENT", "ENVIRONMENT"),
    )

    # ---- 日志 ----
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"] = Field(
        default="INFO",
        validation_alias=AliasChoices("WORKER_LOG_LEVEL", "LOG_LEVEL"),
    )
    log_format: Literal["json", "console"] = Field(
        default="json",
        validation_alias=AliasChoices("WORKER_LOG_FORMAT", "LOG_FORMAT"),
    )

    # ---- HTTP 监听 ----
    host: str = Field(default="0.0.0.0", validation_alias=AliasChoices("WORKER_HOST", "HOST"))
    port: int = Field(default=8000, validation_alias=AliasChoices("WORKER_PORT", "PORT"))

    # ---- 上游 Java 服务 ----
    server_base_url: str = Field(
        default="http://127.0.0.1:8080",
        validation_alias=AliasChoices("WORKER_SERVER_BASE_URL", "SERVER_BASE_URL"),
    )

    # ---- 基础设施登记项（V1 不建立连接，供后续任务使用）----
    mysql_host: str = Field(default="127.0.0.1", validation_alias="MYSQL_HOST")
    mysql_port: int = Field(default=13306, validation_alias="MYSQL_PORT")
    mysql_database: str = Field(default="repopilot", validation_alias="MYSQL_DATABASE")
    mysql_user: str = Field(default="repopilot", validation_alias="MYSQL_USER")
    mysql_password: str = Field(default="", validation_alias="MYSQL_PASSWORD", repr=False)

    rabbitmq_host: str = Field(default="127.0.0.1", validation_alias="RABBITMQ_HOST")
    rabbitmq_port: int = Field(default=5672, validation_alias="RABBITMQ_PORT")
    rabbitmq_user: str = Field(default="repopilot", validation_alias="RABBITMQ_USER")
    rabbitmq_password: str = Field(default="", validation_alias="RABBITMQ_PASSWORD", repr=False)

    minio_host: str = Field(default="127.0.0.1", validation_alias="MINIO_HOST")
    minio_api_port: int = Field(default=9000, validation_alias="MINIO_API_PORT")
    minio_root_user: str = Field(default="repopilot", validation_alias="MINIO_ROOT_USER")
    minio_root_password: str = Field(default="", validation_alias="MINIO_ROOT_PASSWORD", repr=False)
    minio_bucket: str = Field(default="repopilot", validation_alias="MINIO_BUCKET")


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """返回进程内单例配置。测试中可通过 get_settings.cache_clear() 重置。"""
    return Settings()
