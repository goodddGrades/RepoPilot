# 分布式限流（TASK-014）

面向**跑这套系统的人**：限额是多少、为什么是这个数、被限流了怎么查、Redis 挂了会怎样。
跨模块的可靠性取舍见 [`../architecture/06-reliability-design.md`](../architecture/06-reliability-design.md)。

## 0. 三句话版本

1. **限流状态只有一份，在 Redis 里。** 两个 Java 实例、三个 Python Worker 看到的是同一个桶；
   没有进程内计数器，也不存在"每个实例各算一份"的退化路径。
2. **判定在 Lua 里原子完成。** 「读状态 → 补令牌 → 判断 → 扣减 → 写回」五步之间插不进别的客户端。
   写在 Java/Python 侧就会在并发下超额放行——`RateLimitIT` 与 `tests/test_ratelimit_redis.py`
   各有一条 64 并发用例，放行数必须**恰好等于容量**。
3. **429 与 503 是两回事。** 429 = 你太快了，按 `Retry-After` 退避；503 = 服务端依赖坏了（Redis 不可用），
   重试也没用。把 Redis 故障报成 429 会让监控把基础设施事故统计成业务限流。

## 1. 两层接入点

限流是**两处独立接入**，任何一层都不能代替另一层。

```
                    ┌──────────────────────── Redis（唯一状态） ────────────────────────┐
                    │  repopilot:ratelimit:{env}:{policy}:{sha256(subject)[:16]}      │
                    └───────▲──────────────────────────────────────────▲─────────────┘
                            │ EVAL token_bucket.lua                    │ EVAL（同一份字节）
        ┌───────────────────┴──────────────┐          ┌────────────────┴──────────────────┐
        │ Java：RateLimitInterceptor       │          │ Python：RateLimitedJudgeClient    │
        │ preHandle → limiter.acquire      │          │ complete() → bucket.acquire       │
        └───────────────────▲──────────────┘          └────────────────▲──────────────────┘
                            │                                          │
      POST /api/v1/experiments                     JudgeRubric → JudgeClient.complete
      POST /api/v1/experiments/*/start             （一次调用 = 一次 claude -p 进程）
      POST /api/v1/experiments/*/evaluation-batches
      POST /api/v1/imports, /api/v1/artifacts, …
```

| | A. API 入口（Java） | B. 模型调用（Python Worker） |
| --- | --- | --- |
| 类 | `common/ratelimit/RateLimitInterceptor` | `app/ratelimit/judge_guard.RateLimitedJudgeClient` |
| 触发点 | `preHandle`，在参数解析之前 | `JudgeClient.complete` 之前 |
| 主体（谁被限） | 客户端地址 `ip:<addr>` | 供应商 / 模型（默认 `provider:model`） |
| 策略名（默认） | `api-write`、`api-bulk` | `judge-model` |
| 上限维度 | 每客户端突发 N 次、长期 N 次/秒 | 所有 Worker 合计的模型调用次数 |
| 故障默认 | `FAIL_OPEN`（放行） | `FAIL_CLOSED`（拒绝，转可重试错误） |
| 被拒表现 | HTTP 429 + `Retry-After` | `JUDGE_RATE_LIMITED`（retryable），任务不判 FAIL |

**为什么入口限流不能代替模型调用限流**：入口拦的是"用户提交了几次实验"，而一个实验里可能有
几十条 Run、每条 Run 都要调模型。入口放行的请求照样能把上游配额打满。反过来，模型调用限流
也保护不了数据库写入和磁盘 IO。两层拦的是不同的资源。

### 1.1 边界声明（重要，不要误读）

`JudgeClient.complete` 是本仓库**唯一可观测**的模型调用边界。一次 `complete` 对应一次
`claude -p` 进程调用；CLI 内部是否又发了几次 HTTP、是否自带重试，RepoPilot 看不到。

因此本任务实现的是 **「Worker 发起的模型调用次数」限流**，不是「上游 HTTP 请求数」限流。
若将来换成 SDK 直连（`HttpJsonJudge`），边界不变：一次 `complete` = 一次
`/v1/chat/completions`，一个令牌仍然对应一次上游请求。**在 CLI 模式下不要声称做到了逐次 API 限流。**

## 2. Lua 脚本

两端共用同一份脚本，逐字节相同（`tests/test_ratelimit_redis.py::test_lua_script_is_identical_on_both_sides`
会在任一端改动时报红）：

- `repopilot-server/src/main/resources/ratelimit/token_bucket.lua`
- `repopilot-worker/app/ratelimit/token_bucket.lua`

### 2.1 接口

| | 名称 | 说明 |
| --- | --- | --- |
| `KEYS[1]` | 桶 key | 见 §3 |
| `ARGV[1]` | `capacity` | 桶容量（令牌数） |
| `ARGV[2]` | `refill_per_sec` | 补充速率，**允许小数**（0.05 表示 20 秒补 1 个） |
| `ARGV[3]` | `cost` | 本次消耗 |
| `ARGV[4]` | `ttl_ms` | 每次写入后重置的空闲过期时间 |

返回四个元素：`{allowed, remaining, retry_after_ms, capacity}`。

- `allowed`：`1` 放行 / `0` 拒绝
- `remaining`：扣减后的剩余（向下取整，只用于响应头展示）
- `retry_after_ms`：拒绝时"还差多少毫秒能攒够一次消耗"；放行时为 `0`
- `capacity`：回显，便于响应头不依赖应用侧再算一遍
- **参数非法返回 `{-1, 0, -1, 0}`** —— 用哨兵值而不是 `error()`，因为 `error()` 在客户端表现为
  一个笼统的 `RedisSystemException`，分不清"我传错了"和"Redis 挂了"。

### 2.2 五个必须做对的细节

1. **用服务端时间。** `redis.call('TIME')` 而不是应用侧 `System.currentTimeMillis()`。
   多实例的本地时钟必有偏差，用应用时间等于让"时钟最快的机器"多补令牌，
   限流精度取决于最不准的那台。Redis 3.2–6.x 用 `TIME` 前需要 `replicate_commands()`，
   脚本用 `pcall` 包住，在 7.x（默认效果复制）上退化为 no-op。
2. **首次创建从满桶开始。** `HMGET` 返回 `nil` 时把 `tokens` 初始化为 `capacity`，
   否则新客户端第一次请求就被拒。
3. **时钟回拨按 0 处理。** `elapsed_ms < 0` 时只补 0，不扣已有令牌。NTP 校正不该惩罚用户。
4. **6 位小数存储。** 低速补充（0.05 个/秒）如果取整就永远补不上来。用
   `string.format('%.6f', tokens)` 写回；`remaining` 展示时才取整。
5. **每次写入都 `PEXPIRE`。** 空闲桶自动回收。TTL 短于"从 0 补到 capacity"的时间会让
   客户端周期性拿回满桶（等价于放大容量）——启动时 `ttlCoversFullRefill()` 会对此告警。

### 2.3 为什么不能在应用侧写

若写成 `GET → 本地算 → SET`，N 个并发请求会读到同一个旧值、各自认为有余量，超额放行量
随并发度线性增长。这不是理论问题：`RateLimitIT.concurrentRequestsMustNotExceedCapacity`
用 64 线程打容量 8 的桶，断言放行数**恰好 8**；同样一条在 Python 侧也有。
把它改成应用侧实现，这两条会立刻变红。

## 3. Key 设计

```
{keyPrefix}:{environment}:{policy}:{sha256(subject)[:16]}
例：repopilot:ratelimit:it:judge-model:426175e89fef3590
```

四段各有理由：

- **`keyPrefix`（业务命名空间）**：`repopilot:ratelimit`。同一个 Redis 上还可能放别的用途，
  前缀让 `SCAN`/清理能精确定位，不会误删别人的键。
- **`environment`（环境隔离）**：`dev` / `it` / `prod`。多环境共用一台 Redis 时，
  测试会把开发环境的额度吃掉；这个字段是硬隔离，不是约定。
- **`policy`（策略隔离）**：同一主体在 `api-write` 用尽额度，不影响它的 `api-bulk`。
- **`sha256(subject)[:16]`（主体摘要）**：只存不可逆摘要前 16 位。
  - 固定长度：IPv6 地址里的冒号不会破坏 key 结构；
  - 不把客户端地址（乃至将来的用户标识）沉淀进键空间、慢查询日志、`MONITOR` 输出；
  - 16 位十六进制 = 64 bit，桶数量级的碰撞概率可以忽略。

**任何凭据都不进 key。** API Key、模型密钥、Worker token 一律不参与 key 生成，
也不写进日志。跨语言契约被两侧测试各自硬编码同一字面量锁死：
Java `RedisTokenBucketRateLimiterKeyTest.keyContractMatchesPythonImplementation` 与
Python `test_key_contract_matches_java_implementation`。改分隔符、哈希长度、编码任一端，两边都会红。

## 4. 主体选择与它的局限

本仓库**当前没有用户/租户身份体系**（无 Spring Security、无 `SecurityContext`、无 tenant 概念）。
入口限流因此以**客户端地址**为主体，这是本项目当前可用的最可靠主体，但它有明确局限：

| 局限 | 后果 | 缓解 |
| --- | --- | --- |
| NAT / 代理共用出口 | 同办公室所有人共用一个桶，一个人刷满全体被限 | 容量按"可信客户端数"放大，或部署到可信代理后打开 `trust-forwarded-for` |
| 换 IP 即可规避 | 限流不是安全边界，只能拦住"手滑连点"和"脚本小规模重试" | 真正的滥用防护需要身份体系 + 网关层配额，不在本任务范围 |
| `X-Forwarded-For` 可伪造 | 默认 `trust-forwarded-for: false`，忽略该头 | 只有确实位于可信代理之后才可打开；打开后取第一跳并做格式白名单校验 |

**不要为了"看起来更像限流"而伪造用户身份。** 在没有身份体系时编一个假的 user id，
只会让报表好看、实际拦不住任何东西。

模型调用侧的主体**刻意不含 `worker_id`**：把 worker 放进主体等于每个 Worker 各自限流，
N 个 Worker 的总量还是 N 倍，正是要消除的问题。默认维度 `PROVIDER_MODEL`
（`provider:model`），可按上游配额改成 `PROVIDER`（同一供应商所有模型共用一个池）
或 `MODEL`（同一模型跨供应商共用）。配置键：`WORKER_JUDGE_RATE_LIMIT_DIMENSION`。

## 5. 故障策略：FAIL_OPEN 与 FAIL_CLOSED

Redis 不可用（连不上、超时、脚本报错）时，两条链路的行为**不同且都可配置**：

| 链路 | 默认 | 含义 | 为什么 |
| --- | --- | --- | --- |
| API 入口 | `FAIL_OPEN` | 放行 + `X-RateLimit-Status: unavailable` + WARN 日志 + `unavailable` 指标 | 限流不是鉴权。Redis 抖一下就让整个写入面 503，代价远大于短暂失去节流 |
| 模型调用 | `FAIL_CLOSED` | 抛 `RATE_LIMIT_UNAVAILABLE`，任务转可重试 | 继续放行会打满上游配额，可能产生账单和封禁，损失不可逆 |

**两者都不是"退化成每实例各自计数"。** 这一点必须说清楚：FAIL_OPEN 是**明确不限流**，
并有指标和响应头标记；FAIL_CLOSED 是**明确拒绝**。任何"本地兜底计数器"都会重新引入
"看起来在限流实际没限"的问题，本实现不提供该选项。

配置键：`repopilot.rate-limit.policies.<name>.failure-mode` /
`WORKER_RATE_LIMIT_FAILURE_MODE`。

`FAIL_OPEN` 时 Redis 不进 readiness 组：它挂了不该把这个实例摘出负载均衡。
**若改成 `FAIL_CLOSED`，应同时把 redis 加入 readiness 组**（`application.yml` 里有对应注释）。
`/api/health` 中 Redis 由真实 `PING` 探测，返回 `UP` / `DOWN` 与版本号，不再是固定 `UNKNOWN`。

## 6. 被限流之后：语义不能被污染

### 6.1 API：429

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 46
X-RateLimit-Policy: api-write
X-RateLimit-Limit: 30
X-RateLimit-Remaining: 0
```
响应体是项目统一信封（`code: RATE_LIMITED`、`message`、`requestId`、`timestamp`），
客户端不需要为 429 写第二套解析分支。

`Retry-After` 是**算出来的**，不是常量：`ceil((cost - tokens) * 1000 / refill_per_sec)`，
向上取整且至少 1 秒。返回 `0` 会让客户端立刻重试，一次 429 变成一串 429。

### 6.2 模型调用：可重试错误，不是 FAIL

被限流时 `RateLimitedJudgeClient` 抛 `JudgeCallError("JUDGE_RATE_LIMITED", retryable=True,
retry_after_seconds=...)`。由此产生两个效果：

1. **不写 verdict、不写分数。** `tests/test_ratelimit_judge_semantics.py` 用真实的
   `JudgeRubric` 链路证明：被限流的算子结果是错误，不会产出 `PASS`/`FAIL` 或任何分数。
   被限流是"没评上"，不是"评失败了"。
2. **跳过算子内快速重试。** 算子内重试预算是 1s/3s，而配额恢复常需几十秒，硬重试必然全败、
   还把重试预算烧光。`retry_after_seconds` 非 `None` 时 `judge.py` 直接转
   `EvaluationDependencyError`，交给任务级 5s/15s 退避。

## 7. 阈值怎么定（示例值不是生产最佳值）

`application.yml` / `.env.example` 里的数字是**开发环境的保守起点**，用于让本地能跑、
让测试有非零限额。生产必须按下面两个公式重算，不要直接抄。

**入口限流（每客户端）**

```
capacity        ≈ 一个正常用户在一次操作里可能连点的最大次数（含误触）
                  例：创建实验多为 1 次，手滑 3 次 → capacity = 5~10
refill_per_sec  ≈ 该客户端可长期维持的请求频率 × 安全系数(0.5~1)
                  例：一个用户平均 1 次/分钟 → 0.0167 × 0.5 ≈ 0.01
```

**模型调用限流（全局，所有 Worker 共用）**

```
refill_per_sec  ≈ 上游配额（RPM / 60）÷ 安全系数(1.2~2)
                  例：上游 60 RPM → 1 次/秒 ÷ 1.5 ≈ 0.67
capacity        ≈ 允许的突发量。取 refill_per_sec × 一次批次的规模，
                  但要小于上游的 burst 上限
```

**TTL**：必须 ≥ `capacity / refill_per_sec`（补满时间），否则空闲客户端会周期性拿回满桶。
`ttlCoversFullRefill()` 在启动时检查并 WARN。`api-bulk` 的 5 / 0.05 = 100s，配 30m TTL 是刻意的宽裕。

改阈值只改配置，不改代码：`REPOPILOT_RATE_LIMIT_API_WRITE_CAPACITY`、
`REPOPILOT_RATE_LIMIT_API_WRITE_REFILL`、`WORKER_JUDGE_RATE_LIMIT_CAPACITY`、
`WORKER_JUDGE_RATE_LIMIT_REFILL_PER_SECOND` 等，完整清单见 `.env.example`。

## 8. 可观测性

| 指标 | 类型 | 标签 | 位置 |
| --- | --- | --- | --- |
| `repopilot.ratelimit.requests` | Counter | `policy`、`outcome`(allowed/denied/unavailable) | Java，Micrometer |
| `rateLimit.counters` | 快照 | 每策略 × 三 outcome | Python，`GET /worker/status` 的 `rateLimit` 段 |

标签只有 `policy` 与 `outcome`，**不把 subject 做成标签**——那样时间序列会随客户端数量增长，
最终把监控打爆。看"谁被限得最多"应该查日志/访问记录，不是查指标。

Worker 的 `GET /worker/status` 额外暴露 `rateLimit.enabled` / `environment` / `failureMode` /
`dimension` / `counters` / `policy`（当前容量、速率、TTL），排障时先看这一段确认配置真的生效了。

## 9. 排查清单

| 症状 | 先看什么 |
| --- | --- |
| 用户说"一直 429" | 响应头 `X-RateLimit-Policy` 是哪条策略；`X-RateLimit-Limit` 是不是被配小了；该 IP 是否 NAT 出口 |
| 429 但额度明显没用完 | `environment` 是否与其它环境撞了；是否有多个策略匹配同一路径（各自扣各自的桶） |
| 日志里 `FAIL_OPEN` 告警刷屏 | Redis 连不上。`docker ps` 看容器；`redis-cli -p 16379 -a ... ping`；确认没连到本机 6379 的另一个实例 |
| `X-RateLimit-Status: unavailable` | 同一条链路已降级为不限流，此时**没有任何限流保护**，要尽快修 Redis |
| 评测任务大量 `JUDGE_RATE_LIMITED` | 上游配额与 `WORKER_JUDGE_RATE_LIMIT_REFILL_PER_SECOND` 不匹配；或维度选错（多个模型挤在一个 `PROVIDER` 池里） |
| 所有 Worker 加起来还是超配额 | 检查是否有人把 `worker_id` 加进主体，或各 Worker 的 `WORKER_RATE_LIMIT_ENV` 不一致导致桶被拆开 |
| 桶数量暴涨 | `environment` 配错成每实例唯一值；或 TTL 过大而客户端 IP 池很大 |

## 10. 本地启动与验证

```bash
# 1. Redis（compose 里已有 7.2.5，映射到 16379）
docker compose -f deploy/docker-compose.yml up -d redis
redis-cli -p 16379 -a repopilot_dev_pwd ping        # PONG

# 2. 后端（限流默认开启）
cd repopilot-server && ./mvnw spring-boot:run
curl -i -X POST localhost:8080/api/v1/experiments -H 'Content-Type: application/json' -d '{}'
# 连打 30 次后：429 + Retry-After

# 3. Worker
cd repopilot-worker && ./.venv/Scripts/python.exe -m uvicorn app.main:app --port 8000
curl -s localhost:8000/worker/status | jq .rateLimit
```

限流相关的测试分三层，都需要**真实 Redis**：

```bash
# Java 单测（不需要 Redis）
./mvnw -o test -Dtest='RateLimit*Test,RedisTokenBucketRateLimiter*Test'

# Java 集成测试（真实 MySQL + 真实 Redis）：含 64 并发恰放 8、429+Retry-After、TTL 回收
REPOPILOT_IT_REDIS_HOST=127.0.0.1 REPOPILOT_IT_REDIS_PORT=16379 \
REPOPILOT_IT_REDIS_PASSWORD=repopilot_dev_pwd \
./mvnw -o verify -Dit.test=RateLimitIT

# Python：含双 Worker 共享限额、64 并发、跨语言键契约、脚本一致性
WORKER_TEST_REDIS_HOST=127.0.0.1 WORKER_TEST_REDIS_PORT=16379 \
WORKER_TEST_REDIS_PASSWORD=repopilot_dev_pwd \
./.venv/Scripts/python.exe -m pytest tests/test_ratelimit_redis.py -v
```

**缺 Redis 时这些用例整类跳过**（`@EnabledIfEnvironmentVariable` / pytest skip），
跳过数量必须如实写进交付报告，不能把"跳过"当"通过"。CI 里 Redis 是 service 容器，
先用 `redis-cli ping` 轮询确认服务真的起来了，再跑测试——否则"服务没起来"会被伪装成"跳过"。
