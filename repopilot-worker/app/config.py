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
    # Worker 内部接口的 Bearer 凭证；与 Java 服务读取同一个环境变量名。
    # repr=False：避免异常日志或调试输出把凭证带出来。
    worker_token: str = Field(default="", validation_alias="REPOPILOT_WORKER_TOKEN", repr=False)

    # ---- 执行协议 ----
    worker_id: str = Field(default="mock-worker-1", validation_alias="WORKER_ID")
    capabilities_raw: str = Field(default="RUN:MOCK", validation_alias="WORKER_CAPABILITIES")
    max_tasks: int = Field(default=1, validation_alias="WORKER_MAX_TASKS")
    heartbeat_interval: float = Field(default=15.0, validation_alias="WORKER_HEARTBEAT_INTERVAL")
    poll_interval: float = Field(default=1.0, validation_alias="WORKER_POLL_INTERVAL")
    # 是否在进程启动后自动开始轮询；默认关闭，演示/测试用 /worker/run-once 显式触发。
    autostart: bool = Field(default=False, validation_alias="WORKER_AUTOSTART")

    # ---- Mock Runner ----
    mock_scenario: str = Field(default="SUCCESS", validation_alias="WORKER_MOCK_SCENARIO")
    mock_fail_first_attempt: bool = Field(
        default=False, validation_alias="WORKER_MOCK_FAIL_FIRST_ATTEMPT")
    # true 时把 output 作为 Artifact 上传，完成回调只带 outputArtifactId；
    # false 时内联 output。两条路径都被 TASK-004 支持，默认内联便于演示。
    upload_artifact: bool = Field(default=False, validation_alias="WORKER_UPLOAD_ARTIFACT")

    # ---- 真实 Runner（TASK-008）----
    # 本机实际可用的 Coding Agent CLI 名称；未安装时声明 RUN:CLI 会在装配阶段失败。
    runner_cli: str = Field(default="claude", validation_alias="WORKER_RUNNER_CLI")
    # CLI 允许使用的工具白名单；留空表示不额外限制（仍受 CLI 自身权限模式约束）。
    runner_cli_allowed_tools: str = Field(
        default="", validation_alias="WORKER_RUNNER_CLI_ALLOWED_TOOLS")
    runner_cli_max_turns: int = Field(default=8, validation_alias="WORKER_RUNNER_CLI_MAX_TURNS")
    # 每次 Attempt 的工作目录根；留空表示用系统临时目录，每次 Attempt 独立且用后删除。
    runner_workdir_root: str = Field(default="", validation_alias="WORKER_RUNNER_WORKDIR_ROOT")

    # ---- Judge（TASK-008）----
    # Judge 用的 CLI。Judge 与被测 Agent 分开配置：这里只影响 EVAL:judge.rubric。
    judge_cli: str = Field(default="claude", validation_alias="WORKER_JUDGE_CLI")
    # Judge 单次调用超时上限的兜底值；plan item config 里的 timeoutSeconds 更优先。
    judge_timeout_seconds: float = Field(
        default=90.0, validation_alias="WORKER_JUDGE_TIMEOUT_SECONDS")
    # "claude"=真实模型调用；"stub"=确定性测试桩（CI 用，结果带 provider=stub 不冒充真实调用）
    judge_provider: str = Field(default="claude", validation_alias="WORKER_JUDGE_PROVIDER")
    judge_stub_verdict: str = Field(default="PASS", validation_alias="WORKER_JUDGE_STUB_VERDICT")
    judge_stub_inject: str = Field(default="ok", validation_alias="WORKER_JUDGE_STUB_INJECT")

    @property
    def capabilities(self) -> list[str]:
        """能力字符串列表；空串被忽略。"""
        return [item.strip() for item in self.capabilities_raw.split(",") if item.strip()]

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
