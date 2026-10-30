"""Redis 令牌桶：唯一的 IO 点。

原子性由 Lua 保证（见同目录 ``token_bucket.lua``）：脚本在 Redis 内一次性完成
"补令牌 → 判断 → 扣减 → 写回"，多个 Worker 进程并发调用同一个 key 时，
不会出现"都读到有余量、各自放行"的超额放行。

Java 侧 ``RedisTokenBucketRateLimiter`` 用的是**同一份脚本文件**
（``repopilot-server/src/main/resources/ratelimit/token_bucket.lua``）。
两边的阈值单位、key 结构与返回语义必须一致，否则同一个模型配额会出现
"Java 算 30 次/分、Python 算 12 次/分"的隐性分歧；测试里有一条
"两份 Lua 必须逐字节相同"的守卫。
"""

from __future__ import annotations

import hashlib
import threading
from pathlib import Path
from typing import Any

import redis

from app.ratelimit.policy import RateLimitDecision, RateLimitPolicy

_LUA_PATH = Path(__file__).with_name("token_bucket.lua")
_LUA_SOURCE = _LUA_PATH.read_text(encoding="utf-8")
SUBJECT_HASH_LENGTH = 16


class RateLimitUnavailable(RuntimeError):
    """限流依赖（Redis）不可用。

    与"超过限额"严格区分：前者是基础设施故障，后者是业务节奏问题。
    合用一个异常会让调用方无法决定"该退避还是该报障"。
    """

    def __init__(self, policy: str, cause: BaseException) -> None:
        super().__init__(f"限流依赖不可用（策略 {policy}）: {cause}")
        self.policy = policy
        self.cause = cause


class RedisTokenBucket:
    """令牌桶的 Redis 实现。

    构造时不建立连接（``redis.Redis`` 本身是惰性的）：只声明了 Judge 能力
    却从未真正调用模型的 Worker，不应该因为 Redis 没开就起不来。
    连接失败要到第一次 ``acquire`` 才暴露，并按策略的 failure_mode 处置。
    """

    def __init__(self, client: "redis.Redis", *, key_prefix: str, environment: str) -> None:
        if not key_prefix or not key_prefix.strip():
            raise ValueError("rate_limit_key_prefix 不能为空")
        if not environment or not environment.strip():
            raise ValueError("rate_limit_environment 不能为空")
        self._client = client
        self._key_prefix = key_prefix.strip()
        self._environment = environment.strip()
        self._script = client.register_script(_LUA_SOURCE)
        # 状态接口可能在执行线程之外读取，用锁保证"读到的是一致快照"。
        self._lock = threading.Lock()

    # ------------------------------------------------------------------ 构造

    @classmethod
    def from_settings(cls, settings: Any) -> "RedisTokenBucket":
        """按配置建立客户端。密码为空表示无鉴权（本地开发/CI 常见）。"""
        timeout = float(getattr(settings, "redis_timeout_seconds", 2.0) or 2.0)
        password = getattr(settings, "redis_password", "") or None
        client = redis.Redis(
            host=str(getattr(settings, "redis_host", "127.0.0.1")),
            port=int(getattr(settings, "redis_port", 16379)),
            password=password,
            db=int(getattr(settings, "redis_database", 0)),
            # 限流在模型调用主链路上：一个卡住的 Redis 不能把整条链拖死。
            # 宁可快速判定"依赖不可用"，也不要无限等待。
            socket_timeout=timeout,
            socket_connect_timeout=timeout,
            decode_responses=True,
            health_check_interval=30,
        )
        return cls(
            client,
            key_prefix=str(getattr(settings, "rate_limit_key_prefix", "repopilot:ratelimit")),
            environment=str(getattr(settings, "rate_limit_environment", "dev")),
        )

    # ------------------------------------------------------------------ Key

    def key(self, policy: RateLimitPolicy, subject: str) -> str:
        """``{prefix}:{env}:{policy}:{subjectHash}``。

        主体只存 SHA-256 前 16 位：既是固定长度（IPv6 带冒号也不会破坏结构），
        也避免把客户端地址/模型名原样沉淀进 Redis 键空间。
        任何凭据（API Key、模型密钥）都不允许进入 key。
        """
        digest = hashlib.sha256(subject.encode("utf-8")).hexdigest()[:SUBJECT_HASH_LENGTH]
        return f"{self._key_prefix}:{self._environment}:{policy.name}:{digest}"

    # ------------------------------------------------------------------ 执行

    def acquire(self, policy: RateLimitPolicy, subject: str, cost: int | None = None) -> RateLimitDecision:
        """申请一次配额；超过限额时返回 ``allowed=False``，不抛异常。"""
        tokens = int(cost if cost is not None else policy.cost_per_request)
        key = self.key(policy, subject)
        try:
            raw = self._script(
                keys=[key],
                args=[policy.capacity, repr(policy.refill_per_second), tokens,
                      int(policy.ttl_seconds * 1000)],
                client=self._client,
            )
        except (redis.RedisError, OSError) as exc:
            raise RateLimitUnavailable(policy.name, exc) from exc

        return self._to_decision(policy, subject, raw)

    def ping(self) -> bool:
        """真实 PING。健康检查与测试用它确认"连得上"，不用来推断限流是否生效。"""
        try:
            return bool(self._client.ping())
        except (redis.RedisError, OSError) as exc:
            raise RateLimitUnavailable("ping", exc) from exc

    def close(self) -> None:
        try:
            self._client.close()
        except Exception:  # noqa: BLE001 - 关闭失败不应影响退出流程
            pass

    # ------------------------------------------------------------------ 解析

    def _to_decision(self, policy: RateLimitPolicy, subject: str, raw: Any) -> RateLimitDecision:
        if not isinstance(raw, (list, tuple)) or len(raw) < 4:
            raise RateLimitUnavailable(
                policy.name, ValueError(f"限流脚本返回值格式不正确: {raw!r}"))
        allowed, remaining, retry_after_ms, capacity = (int(item) for item in raw[:4])
        if allowed < 0:
            # 脚本显式拒绝参数：capacity/refill/cost/ttl 里有非正值。
            # 这类问题本应在 RateLimitPolicy 构造时被拦下；走到这里意味着
            # 有人绕过了校验，按配置错误处理而不是悄悄放行。
            raise ValueError(
                f"限流脚本拒绝参数，策略 {policy.name}：capacity/refill/cost/ttl 必须为正数")
        return RateLimitDecision(
            allowed=allowed == 1,
            remaining=remaining,
            capacity=capacity,
            retry_after_ms=0 if allowed == 1 else retry_after_ms,
            policy=policy.name,
            subject=subject,
        )
