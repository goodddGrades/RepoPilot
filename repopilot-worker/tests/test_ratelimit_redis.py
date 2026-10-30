"""真实 Redis 上的令牌桶验收。

这些用例**必须**连真实 Redis：Lua 的原子性、TTL、服务端时间都无法用替身证明。
替身只能验证"我写的分支会执行"，验证不了"64 个并发恰好放行 N 个"。

未设置 ``WORKER_TEST_REDIS_HOST`` 时整组跳过，跳过会写进交付报告。
"""

from __future__ import annotations

import os
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest
import redis

from app.ratelimit.bucket import (
    RateLimitUnavailable,
    RedisTokenBucket,
    _LUA_SOURCE,
)
from app.ratelimit.policy import RateLimitPolicy

REDIS_HOST = os.environ.get("WORKER_TEST_REDIS_HOST")
REDIS_PORT = int(os.environ.get("WORKER_TEST_REDIS_PORT", "16379"))
REDIS_PASSWORD = os.environ.get("WORKER_TEST_REDIS_PASSWORD") or None
REDIS_DB = int(os.environ.get("WORKER_TEST_REDIS_DATABASE", "0"))

redis_required = pytest.mark.skipif(
    not REDIS_HOST,
    reason="需要真实 Redis：设置 WORKER_TEST_REDIS_HOST / WORKER_TEST_REDIS_PORT / WORKER_TEST_REDIS_PASSWORD",
)

PREFIX = "repopilot:ratelimit"


def make_client() -> "redis.Redis":
    return redis.Redis(host=REDIS_HOST or "127.0.0.1", port=REDIS_PORT, password=REDIS_PASSWORD,
                       db=REDIS_DB, decode_responses=True, socket_timeout=2,
                       socket_connect_timeout=2)


def unique_env() -> str:
    """每个用例一个独立环境段：用例之间不共享桶，也不依赖清理顺序。"""
    return "test-" + uuid.uuid4().hex[:12]


def make_bucket(environment: str, client: "redis.Redis | None" = None) -> RedisTokenBucket:
    return RedisTokenBucket(client or make_client(), key_prefix=PREFIX, environment=environment)


def policy(**overrides) -> RateLimitPolicy:
    params = {"name": "judge-model", "capacity": 3, "refill_per_second": 0.0001,
              "cost_per_request": 1, "ttl_seconds": 60, "failure_mode": "FAIL_CLOSED"}
    params.update(overrides)
    return RateLimitPolicy(**params)


@redis_required
def test_initial_bucket_is_full_then_rejects() -> None:
    env = unique_env()
    bucket = make_bucket(env)
    pol = policy(capacity=3)

    decisions = [bucket.acquire(pol, "provider:claude-cli/model:m1") for _ in range(3)]
    assert [d.allowed for d in decisions] == [True, True, True]
    # 剩余量递减且不为负：首次创建时桶必须是满的（否则新客户端一上来就被拒）
    assert decisions[0].remaining == 2
    assert decisions[-1].remaining == 0

    denied = bucket.acquire(pol, "provider:claude-cli/model:m1")
    assert denied.allowed is False
    assert denied.retry_after_ms > 0
    assert denied.retry_after_seconds > 0


@redis_required
def test_refill_restores_capacity_over_time() -> None:
    env = unique_env()
    bucket = make_bucket(env)
    pol = policy(capacity=1, refill_per_second=20)  # 50ms 补一个

    assert bucket.acquire(pol, "provider:x/model:m").allowed is True
    assert bucket.acquire(pol, "provider:x/model:m").allowed is False

    deadline = time.monotonic() + 3
    recovered = False
    while time.monotonic() < deadline:
        if bucket.acquire(pol, "provider:x/model:m").allowed:
            recovered = True
            break
        time.sleep(0.05)
    assert recovered, "令牌应按服务端时间补充，3 秒内必须恢复"


@redis_required
def test_two_workers_share_the_same_limit() -> None:
    # 两个独立的 RedisTokenBucket（各自一条连接）= 两个 Worker 进程。
    # 共享限额只能来自 Redis 里的同一个 key，不能来自进程内计数。
    env = unique_env()
    worker_a = make_bucket(env)
    worker_b = make_bucket(env)
    pol = policy(capacity=4)

    allowed = 0
    for bucket in (worker_a, worker_b):
        for _ in range(4):
            if bucket.acquire(pol, "provider:claude-cli/model:shared").allowed:
                allowed += 1

    assert allowed == 4, "两个 Worker 合计只能放行 capacity 次，否则就是各算各的"


@redis_required
def test_subjects_and_policies_are_isolated() -> None:
    env = unique_env()
    bucket = make_bucket(env)
    pol = policy(capacity=2)

    assert bucket.acquire(pol, "provider:a/model:m").allowed
    assert bucket.acquire(pol, "provider:a/model:m").allowed
    assert not bucket.acquire(pol, "provider:a/model:m").allowed

    # 另一个主体、另一个策略各有独立的桶
    assert bucket.acquire(pol, "provider:b/model:m").allowed
    other_policy = policy(name="judge-other", capacity=2)
    assert bucket.acquire(other_policy, "provider:a/model:m").allowed


@redis_required
def test_concurrent_acquires_never_exceed_capacity() -> None:
    # 核心证据：并发下"读—判—扣"必须原子。若在进程内计算，
    # 64 个并发会放行远多于 8 个。
    env = unique_env()
    bucket = make_bucket(env)
    capacity = 8
    concurrency = 64
    pol = policy(name="concurrent", capacity=capacity, refill_per_second=0.000001)

    barrier = threading.Barrier(concurrency)

    def attempt() -> bool:
        barrier.wait(timeout=10)
        return bucket.acquire(pol, "provider:c/model:m").allowed

    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        results = list(pool.map(lambda _: attempt(), range(concurrency)))

    assert sum(results) == capacity


@redis_required
def test_idle_bucket_expires_by_ttl() -> None:
    env = unique_env()
    bucket = make_bucket(env)
    pol = policy(capacity=1, ttl_seconds=0.3)

    assert bucket.acquire(pol, "provider:t/model:m").allowed
    assert not bucket.acquire(pol, "provider:t/model:m").allowed

    time.sleep(0.9)

    # key 已过期 → 重新建立时是满桶（不是"额度被补满"，是状态被回收）
    assert bucket.acquire(pol, "provider:t/model:m").allowed
    client = make_client()
    remaining_keys = client.keys(f"{PREFIX}:{env}:*")
    assert remaining_keys, "重新放行后应存在一个刚创建的 key"
    # 再等一个 TTL，key 必须自己消失：不能永久占用 Redis 内存
    time.sleep(1.2)
    assert client.keys(f"{PREFIX}:{env}:*") == []


@redis_required
def test_script_rejects_non_positive_arguments() -> None:
    # 脚本自己兜底：参数非法时返回 allowed=-1，而不是"宽松放行"。
    env = unique_env()
    bucket = make_bucket(env)
    client = make_client()

    raw = client.register_script(_LUA_SOURCE)(
        keys=[f"{PREFIX}:{env}:bad:deadbeef"],
        args=[0, "1.0", 1, 60000],
        client=client,
    )

    assert list(raw)[0] == -1


@redis_required
def test_key_contains_no_raw_subject() -> None:
    env = unique_env()
    bucket = make_bucket(env)
    subject = "provider:claude-cli/model:secret-ish-model-name"

    key = bucket.key(policy(), subject)

    assert key.startswith(f"{PREFIX}:{env}:judge-model:")
    assert subject not in key
    assert "secret-ish-model-name" not in key


@redis_required
def test_unavailable_redis_raises_dedicated_error() -> None:
    # 连一个必然拒绝连接的端口：证明真实客户端异常被映射成 RateLimitUnavailable，
    # 而不是漏成 redis.ConnectionError 让上层按"模型失败"处理。
    dead = redis.Redis(host="127.0.0.1", port=1, socket_timeout=0.5,
                       socket_connect_timeout=0.5, decode_responses=True)
    bucket = RedisTokenBucket(dead, key_prefix=PREFIX, environment=unique_env())

    with pytest.raises(RateLimitUnavailable):
        bucket.acquire(policy(), "provider:x/model:m")


def test_lua_script_is_identical_on_both_sides() -> None:
    """Java 与 Python 必须运行同一份脚本。

    两份副本一旦漂移，同一个模型配额在两端会算出不同的结果，
    而"为什么 Java 说还能调、Python 说不行"几乎无法排查。
    """
    java_lua = (Path(__file__).resolve().parents[2] / "repopilot-server" /
                "src" / "main" / "resources" / "ratelimit" / "token_bucket.lua")
    if not java_lua.exists():  # 只安装 worker 包时没有兄弟目录，跳过而不是误报
        pytest.skip("未找到 Java 侧 Lua 脚本（当前环境只有 worker）")

    assert java_lua.read_text(encoding="utf-8") == _LUA_SOURCE


@redis_required
def test_key_contract_matches_java_implementation() -> None:
    """跨语言 Key 契约：同一主体在 Java 与 Python 下必须落在同一个 key 上。

    值不是现算的——它是两端测试各自硬编码的**同一个字面量**。
    任何一侧改了分隔符、哈希截断长度或编码，这条用例就会红，
    而不是等到生产上出现"两端各有一份额度"才发现。
    """
    bucket = make_bucket("it")
    subject = "provider:claude-cli/model:claude-3-5-sonnet"

    assert bucket.key(policy(name="judge-model"), subject) == \
        "repopilot:ratelimit:it:judge-model:426175e89fef3590"
