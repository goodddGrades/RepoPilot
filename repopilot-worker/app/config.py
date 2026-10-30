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
    """进程级配置（不可变，进程启动时读入一次）。

    关于 ``validation_alias`` 与构造参数的关系（TASK-013 实测踩到的坑）：

    pydantic-settings 里，一旦字段声明了 ``validation_alias``，
    **构造参数必须用别名**（即 ``WORKER_RABBITMQ_ENABLED``）才生效；
    用字段名传参会被静默忽略、退回默认值。
    实测：``Settings(autostart=True).autostart`` 仍是 ``False``，
    而 ``Settings(worker_id="x")``（该字段没有 alias）正常。
    也就是说"看起来传进去了、实际没生效"，不报错、不告警。

    测试里要覆盖配置，必须走环境变量（本仓库的 ``make_settings`` 夹具
    正是这么做的），或者用 ``populate_by_name=True`` 让字段名也能用。
    这里显式开启 ``populate_by_name``：它让两种写法都有效，
    消除"必须记住该用哪个名字"的隐性负担。
    """

    model_config = SettingsConfigDict(
        env_file=("../.env", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
        # 允许用字段名构造，而不仅仅是别名。没有这一行时
        # Settings(autostart=True) 会被静默忽略——配置看起来设了、实际没设。
        populate_by_name=True,
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
    # 优雅关闭时等待"正在执行的那一轮"结束的上限（秒）。
    #
    # 关闭流程是"先停消费 → 再等执行线程"，执行线程只在两轮之间检查停止标志，
    # 所以正在跑的 Run 不会被腰斩；等不到就放弃，让租约到期后由回收器接手。
    # 设 0 表示不等待（测试用）。
    shutdown_timeout: float = Field(default=30.0, validation_alias="WORKER_SHUTDOWN_TIMEOUT")

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

    # ---- Redis 分布式限流（TASK-014）----
    #
    # 与 Java 侧共用同一台 Redis 的同一套命名空间规则：
    #   {key_prefix}:{environment}:{policy}:{subjectHash}
    # environment 必须与后端保持一致（同环境同值），否则同一个模型
    # 在 Java 侧和 Python 侧会各有一份额度，"共享限额"就不成立了。

    redis_host: str = Field(default="127.0.0.1", validation_alias="REDIS_HOST")
    redis_port: int = Field(default=16379, validation_alias="REDIS_PORT")
    redis_password: str = Field(default="", validation_alias="REDIS_PASSWORD", repr=False)
    redis_database: int = Field(default=0, validation_alias="REDIS_DATABASE")
    # 限流在模型调用主链路上：连接/读写的超时必须短，卡住等于把 Worker 拖死。
    redis_timeout_seconds: float = Field(
        default=2.0, validation_alias="WORKER_REDIS_TIMEOUT_SECONDS")

    # 总开关。关闭后 JudgeClient 保持原样，不做任何 Redis 调用。
    rate_limit_enabled: bool = Field(default=True, validation_alias="WORKER_RATE_LIMIT_ENABLED")
    rate_limit_key_prefix: str = Field(
        default="repopilot:ratelimit", validation_alias="WORKER_RATE_LIMIT_KEY_PREFIX")
    rate_limit_environment: str = Field(default="dev", validation_alias="WORKER_RATE_LIMIT_ENV")
    # 模型调用保护默认**失败关闭**：Redis 不可用时宁可让本次调用失败并可重试，
    # 也不能放行到打满上游配额（那部分损失不可逆）。
    rate_limit_failure_mode: Literal["FAIL_CLOSED", "FAIL_OPEN"] = Field(
        default="FAIL_CLOSED", validation_alias="WORKER_RATE_LIMIT_FAILURE_MODE")

    # Judge 限流的具体阈值。默认值是开发环境的保守起点，不是生产推荐值。
    judge_rate_limit_capacity: int = Field(
        default=10, validation_alias="WORKER_JUDGE_RATE_LIMIT_CAPACITY")
    judge_rate_limit_refill_per_second: float = Field(
        default=0.2, validation_alias="WORKER_JUDGE_RATE_LIMIT_REFILL")
    judge_rate_limit_cost: int = Field(
        default=1, validation_alias="WORKER_JUDGE_RATE_LIMIT_COST")
    judge_rate_limit_ttl_seconds: int = Field(
        default=600, validation_alias="WORKER_JUDGE_RATE_LIMIT_TTL_SECONDS")
    # 限流维度：PROVIDER=按提供方、MODEL=按模型、PROVIDER_MODEL=两者组合（默认）。
    # 上游配额按账号算时用 PROVIDER；按模型算时用 MODEL。
    judge_rate_limit_dimension: Literal["PROVIDER", "MODEL", "PROVIDER_MODEL"] = Field(
        default="PROVIDER_MODEL", validation_alias="WORKER_JUDGE_RATE_LIMIT_DIMENSION")

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

    # ---- RabbitMQ 消费（TASK-013）----
    #
    # 默认**关闭**：Worker 靠 HTTP 轮询也能完整工作，消息只是加速器。
    # 默认打开会让"没配 RabbitMQ"的部署直接起不来，而那本来是个合法配置。
    rabbitmq_enabled: bool = Field(default=False, validation_alias="WORKER_RABBITMQ_ENABLED")
    # 队列名必须与 Java 侧 RabbitTopology 声明的一致。不同的 Worker 实例
    # 应订阅不同队列（run.ready / evaluation.ready），靠 binding 分流而不是
    # 抢同一条消息——抢同一条会让"一个实例只能干一种活"变成隐式约束。
    rabbitmq_queue: str = Field(
        default="repopilot.run.ready", validation_alias="WORKER_RABBITMQ_QUEUE")
    rabbitmq_vhost: str = Field(default="/", validation_alias="WORKER_RABBITMQ_VHOST")
    # prefetch=1：未处理的消息留在 broker 而不是客户端，进程崩溃才能自动回队。
    # 调高它会让消息在客户端排队，断连时一起丢失——看起来"已投递"实际永不处理。
    rabbitmq_prefetch: int = Field(default=1, validation_alias="WORKER_RABBITMQ_PREFETCH")
    rabbitmq_heartbeat: int = Field(default=30, validation_alias="WORKER_RABBITMQ_HEARTBEAT")

    # ---- Inbox 登记（TASK-013-C）----
    #
    # Inbox 的去重键是 (consumer_name, event_id)，**不是** Worker 实例 ID。
    # 用实例 ID 会让每个 Worker 实例都把同一条事件当成新事件登记一次，
    # 去重形同虚设；用固定名字才能保证"一条事件在一个逻辑消费者上只处理一次"。
    #
    # 与后端 OpenAPI 里示例值（worker-run / worker-eval）保持一致：
    # 执行 Worker 与评测 Worker 消费同一事件是两次正常投递，不是重复。
    inbox_consumer_name: str = Field(
        default="worker-run", validation_alias="WORKER_INBOX_CONSUMER_NAME")

    minio_host: str = Field(default="127.0.0.1", validation_alias="MINIO_HOST")
    minio_api_port: int = Field(default=9000, validation_alias="MINIO_API_PORT")
    minio_root_user: str = Field(default="repopilot", validation_alias="MINIO_ROOT_USER")
    minio_root_password: str = Field(default="", validation_alias="MINIO_ROOT_PASSWORD", repr=False)
    minio_bucket: str = Field(default="repopilot", validation_alias="MINIO_BUCKET")


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """返回进程内单例配置。测试中可通过 get_settings.cache_clear() 重置。"""
    return Settings()
