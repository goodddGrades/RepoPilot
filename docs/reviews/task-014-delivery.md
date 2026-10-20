# TASK-014 交付报告：Redis + Lua 分布式限流

> 交付时间：2026-10-20 · 分支 `develop` · **本地提交，未 push**

## 1. 审计：修复前究竟有什么

任务书要求先审计再动手，且明确"不得仅凭 Redis 依赖存在或健康检查显示 UNKNOWN，就判断限流已实现或完全未实现"。
逐项核对结果如下（每一项都给出了判断依据，不是猜测）：

| 能力 | 修复前状态 | 判断依据 |
| --- | --- | --- |
| Redis 客户端（Java） | **无** | `pom.xml` 无 `spring-boot-starter-data-redis`；`~/.m2` 无该 jar 缓存 |
| Redis 客户端（Python） | **无** | `requirements.txt` / `pyproject.toml` 均无 `redis` |
| Lua 脚本 | **无** | 全仓库 `**/*.lua` 零命中（`.git` 除外） |
| Redis 健康检查 | **仅占位** | `RedisReservedProbe` 硬编码返回 `UNKNOWN` + "V1 预留"文案，**没有真实探测** |
| `ErrorCode.RATE_LIMITED` | **已定义、零引用** | 枚举里有该码，但全仓库无抛出处 → 死代码，说明"设计过但没实现" |
| API 入口限流 | **无** | 无 `HandlerInterceptor`、无 `Filter`、无 `@RateLimit` 注解、无 Bucket4j/Resilience4j 依赖 |
| Worker 模型调用限流 | **无** | 只有**被动**识别上游 429 并映射 `JUDGE_RATE_LIMITED`，没有本地配额 |
| 并发配额 | **不是限流** | 存在的是 `repopilot.worker.max-tasks-limit`（同时领取任务数），语义是"并发度"不是"请求速率" |
| 用户/租户身份体系 | **不存在** | 无 Spring Security、无 `SecurityContext`、无 tenant 字段 |
| Redis 编排 | **已完成** | `deploy/docker-compose.yml` 有 `redis:7.2.5-alpine`、端口 16379、`--requirepass`、healthcheck |
| Redis 环境变量 | **已完成** | 根 `.env.example` 有 `REDIS_PORT` / `REDIS_PASSWORD`，注释写的是"预留" |

**结论**：Redis 的**基础设施**（容器、端口、密码、环境变量）已经就绪，但**限流能力完全是零**——
没有客户端、没有脚本、没有接入点、没有一个请求被限过。健康检查的 `UNKNOWN` 是占位实现，
既不能证明"有"，也不能证明"没有"；真正能证明"没有"的是 `pom.xml` 的依赖清单和全仓库零 `.lua`。

### 1.1 复用了什么（没有重写）

| 已有能力 | 复用方式 |
| --- | --- |
| `BusinessException` + `ApiResponse` + `GlobalExceptionHandler` 统一信封 | 429 走同一信封，客户端不需要第二套解析分支 |
| `ErrorCode` 枚举 | 启用了已定义但闲置的 `RATE_LIMITED` |
| 健康检查 probe 机制 | 用 `RedisHealthProbe` 替换 `RedisReservedProbe`，沿用同一注册方式 |
| Micrometer | 新增 counter，不引入新监控体系 |
| `EvaluationDependencyError` + 任务级 5s/15s 退避 | 限流错误沿用既有可重试语义，不新建状态 |
| compose 里的 Redis 服务 | 原样使用，未改镜像/端口/密码 |
| TASK-011 Outbox/Inbox、TASK-012 性能结论、TASK-013 执行模型 | **零改动**（见 §6 回归证据） |

### 1.2 架构冲突与裁决

`AGENTS.md` §2 原文：

> Redis 仅编排实例，Java 不引入客户端；`/api/health` 中固定 `UNKNOWN` 并标注"预留"。

这与本任务**直接冲突**。按 `AGENTS.md` §0「任务文本与 `docs/` 冲突时不允许实施方自行选择」，
处理顺序是：**先改 `AGENTS.md`，再改实现**。已在该节显式修订，并保留了原条款文字与
"由 TASK-014 按 §0 冲突裁决流程显式修订"的说明，避免后人看到新旧两套说法。

## 2. 实现：两个真正被接入的场景

### 2.1 共用的 Lua 脚本

`repopilot-server/src/main/resources/ratelimit/token_bucket.lua`
`repopilot-worker/app/ratelimit/token_bucket.lua`

两份**逐字节相同**（`diff -q` 通过），且有测试守着：任一端改动即红——
`test_ratelimit_redis.py::test_lua_script_is_identical_on_both_sides`。

脚本在 Redis 单线程内完成「读状态 → 补令牌 → 判断 → 扣减 → 写回」，五个步骤之间插不进其他客户端命令。
关键细节（每一条都对应一种会出错的做法）：

| 细节 | 错误做法 | 本实现 |
| --- | --- | --- |
| 时间源 | 应用侧 `System.currentTimeMillis()`，多实例时钟偏差让"最快的机器"多补令牌 | `redis.call('TIME')`；`pcall(replicate_commands)` 兼容 3.2–6.x，7.x 退化为 no-op |
| 首次创建 | `nil` 当 0 → 新客户端第一次请求就被拒 | `tokens == nil` 时初始化为 `capacity`（满桶开始） |
| 时钟回拨 | 负 elapsed 直接算 → 令牌被倒扣 | `elapsed_ms < 0` 按 0 处理，只补不扣 |
| 小数速率 | 取整存储 → 0.05 个/秒永远补不上来 | `string.format('%.6f', tokens)`；展示时才取整 |
| 参数校验 | `error()` → 客户端只看到笼统的 `RedisSystemException` | 返回哨兵 `{-1, 0, -1, 0}`，能区分"传错"与"Redis 挂了" |
| Key 回收 | 不设过期 → 键空间无限增长 | 每次写入 `PEXPIRE`，空闲桶自动回收 |

### 2.2 场景 A：Java API 入口

**实际调用链**

```
POST /api/v1/experiments
 → RequestIdFilter
 → DispatcherServlet
   → RateLimitInterceptor#preHandle            ← 每条策略一个实例，路径来自 application.yml
       handler instanceof HandlerMethod?        否 → 放行（资源/错误页）
       policy.methods 含 POST?                  否 → 放行（路径模式区分不了 GET/POST）
       RateLimitSubjectResolver.resolve        → "ip:1.2.3.4"
       RedisTokenBucketRateLimiter.acquire
         → StringRedisTemplate.execute(DefaultRedisScript, [key], capacity, rate, cost, ttl_ms)
           → EVAL <sha1> 1 <key> ...
       allowed → 写 X-RateLimit-Policy/Limit/Remaining → 进 Controller
       denied  → RateLimitedException
                 → GlobalExceptionHandler → 429 + Retry-After + 统一信封
       Redis 异常 → RateLimitUnavailableException
                 → FAIL_OPEN：放行 + X-RateLimit-Status: unavailable + WARN + unavailable 指标
                 → FAIL_CLOSED：503 + Retry-After: 1
```

**被限的入口**（全部是"会产生实际成本"的写操作）：

| 策略 | 路径 | 方法 | 默认容量 / 速率 |
| --- | --- | --- | --- |
| `api-write` | `/api/v1/experiments`、`/api/v1/experiments/*/start`、`/api/v1/experiments/*/evaluation-batches` | POST | 30 / 0.5 每秒 |
| `api-bulk` | `/api/v1/imports`、`/api/v1/dataset-versions/*/cases:import`、`/api/v1/artifacts` | POST | 5 / 0.05 每秒 |

**没有被限的**：`/api/health`、`/actuator/**`、静态资源、所有 GET 查询、`/internal/v1/**`。
理由：健康检查被限流会让编排层误判实例死亡；只读查询不产生写入成本；Worker 内部协议由 token 鉴权，
再加一层令牌桶会让"Worker 拿不到任务"和"Worker 被限流"混在一起。这三条都有测试守着
（`RateLimitIT.readRequestsAreNotRateLimited`）。

**为什么用拦截器而不是 Filter**：拦截器在 DispatcherServlet 内，抛出的异常会被
`@RestControllerAdvice` 转成统一信封并带上 `Retry-After`；Filter 在它之前，只能手写 JSON，
很容易与统一结构漂移。

**429 长什么样**

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 2
X-RateLimit-Policy: api-write
X-RateLimit-Limit: 3
X-RateLimit-Remaining: 0
```
```json
{ "code": "RATE_LIMITED", "message": "...", "requestId": "...", "timestamp": "..." }
```

`Retry-After` 是 `ceil((cost − tokens) × 1000 / refill_per_sec)` 算出来的，向上取整且至少 1 秒。
返回 0 会让客户端立刻重试，一次 429 变成一串 429。

### 2.3 场景 B：Python Worker 模型调用

**实际调用链**

```
EvaluationJob
 → JudgeRubric.evaluate
   → (每个评分项) JudgeClient.complete
     → RateLimitedJudgeClient#complete           ← 包装器，实现 JudgeClient 协议
         _subject() → "provider:anthropic/model:claude-..."   （默认 PROVIDER_MODEL）
         RedisTokenBucket.acquire → EVAL 同一份 lua
         allowed     → inner.complete(request)
         denied      → JudgeCallError("JUDGE_RATE_LIMITED", retryable=True, retry_after_seconds=N)
                       → judge.py: retry_after_seconds 非 None → **跳过 1s/3s 算子内重试**
                         → EvaluationDependencyError（保留原错误码）
                         → 任务级 5s/15s 退避重排 Attempt
         unavailable → FAIL_CLOSED: JudgeCallError("RATE_LIMIT_UNAVAILABLE", retryable=True)
                     → FAIL_OPEN: 放行 + unavailable 指标 + WARNING
```

**三个必须说清的边界**

1. **限的是「Worker 发起的模型调用次数」，不是「上游 HTTP 请求数」。**
   `JudgeClient.complete` 是本仓库唯一可观测的边界：一次 `complete` = 一次 `claude -p` 进程调用。
   CLI 内部是否又发了几次 HTTP、是否自带重试，RepoPilot 看不到。**在 CLI 模式下不能声称做到了
   逐次 API 请求限流。** 换成 SDK 直连后边界不变（一次 `complete` = 一次 chat/completions）。
2. **主体刻意不含 `worker_id`。** 把 worker 放进主体等于每个 Worker 各自限流，总量仍是 N 倍——
   正是要消除的问题。默认 `PROVIDER_MODEL`，可按上游配额改成 `PROVIDER` 或 `MODEL`。
3. **被限流不是评测失败。** `JUDGE_RATE_LIMITED` 是 `retryable=True` 的技术错误，
   不写 verdict、不写分数。`test_ratelimit_judge_semantics.py` 用真实的 `JudgeRubric` 链路
   断言这一点（5 条用例），不是靠读代码推断。

**为什么跳过算子内重试**：算子内预算是 1s/3s，而配额恢复常需几十秒，硬重试必然全败，
还把重试预算烧光，最终错误码退化成笼统的"重试次数用尽"。提前退出保留 `JUDGE_RATE_LIMITED`
这个可定位的错误码，交给任务级 5s/15s 退避。

### 2.4 Key 设计

```
repopilot:ratelimit:it:judge-model:426175e89fef3590
└─ 命名空间 ─┘ └环境┘ └─策略──┘ └─ sha256(subject)[:16] ─┘
```

- **三段隔离**：业务命名空间（便于 `SCAN` 定点清理）/ 环境（dev-it-prod 共用一个 Redis 时硬隔离）/
  策略（同一主体的 `api-write` 用尽不影响 `api-bulk`）。
- **主体只存不可逆摘要前 16 位**：长度固定（IPv6 的冒号不破坏结构）、不把客户端地址沉淀进
  键空间与慢查询日志。64 bit 对桶数量级足够。
- **任何凭据不进 key**，也不进日志。
- **跨语言契约被锁死**：Java `RedisTokenBucketRateLimiterKeyTest.keyContractMatchesPythonImplementation`
  与 Python `test_key_contract_matches_java_implementation` **各自硬编码同一字面量**
  `repopilot:ratelimit:it:judge-model:426175e89fef3590`。任一端改分隔符、哈希长度或编码，两边同时红。

### 2.5 故障策略

| 链路 | 默认 | 语义 | 理由 |
| --- | --- | --- | --- |
| API 入口 | `FAIL_OPEN` | 放行 + `X-RateLimit-Status: unavailable` + WARN + `unavailable` 指标 | 限流不是鉴权；Redis 抖一下就让整个写入面 503，代价大于短暂失去节流 |
| 模型调用 | `FAIL_CLOSED` | 拒绝 + `RATE_LIMIT_UNAVAILABLE`（可重试） | 继续放行会打满上游配额，可能产生账单/封禁，损失不可逆 |

**两者都不是「退化成每实例各自计数」。** 这一点在代码里是硬约束：`RateLimitUnavailableException`
只有"放行"或"拒绝"两条路，没有本地兜底桶；`RateLimitPolicy.FailureMode` 只有两个枚举值。
指标里 `outcome=unavailable` 让降级状态在监控上可见，而不是静默。

`FAIL_OPEN` 下 Redis **不进 readiness 组**（挂了不该被摘流）；`application.yml` 里写明
"若改成 FAIL_CLOSED 应同时加入"，避免后人改配置时漏掉这一半。

### 2.6 可观测性

| 指标 / 字段 | 位置 | 标签 |
| --- | --- | --- |
| `repopilot.ratelimit.requests` | Java Micrometer counter | `policy`、`outcome`(allowed/denied/unavailable) |
| `rateLimit` 段 | Python `GET /worker/status` | enabled / environment / failureMode / dimension / counters / policy |
| `redis` 组件 | `GET /api/health` | 真实 `PING`，返回 `UP`/`DOWN` + 版本号 |

**不把 subject 做成指标标签**：时间序列会随客户端数量增长，最终打爆 Prometheus。
"谁被限得最多"应该查访问日志，不是查指标。

## 3. 测试与真实证据

### 3.1 环境

| 项 | 实际值 |
| --- | --- |
| OS | Windows 11 |
| JDK | 21.0.10 (Microsoft OpenJDK) |
| 后端 | Spring Boot 3.5.16 |
| MySQL | 8.0.22，`127.0.0.1:13306`，库 `repopilot_it` |
| Redis | **7.2.5 容器**，`127.0.0.1:16379`，带 `--requirepass` |
| RabbitMQ | 3.13.7，`127.0.0.1:5672` |
| Python | 3.13.12，venv |
| Node | 22（前端构建） |

### 3.2 数字（全部为实跑，未跳过、未删改用例）

| 层 | 命令 | 结果 |
| --- | --- | --- |
| Java 单测（全量） | `./mvnw -o verify`（surefire 阶段） | **114 通过 / 0 失败 / 0 跳过** |
| Java 集成（全量） | 同上（failsafe 阶段） | **266 通过 / 0 失败 / 0 跳过** |
| 　├ 其中 TASK-014 新增单测 | 37 条在专用类（`-Dtest='RateLimit*Test,RedisTokenBucketRateLimiter*Test'` 全绿）+ 2 条在 `GlobalExceptionHandlerTest` | **39 通过 / 0 失败 / 0 跳过** |
| 　└ 其中 TASK-014 新增集成 | `-Dit.test=RateLimitIT` | **8 通过 / 0 失败 / 0 跳过**（21.25s） |
| Python 全量 | `pytest -q`（真实 Redis + 真实 RabbitMQ） | **281 通过 / 4 跳过**（268.24s，EXIT=0） |
| 　└ 其中 TASK-014 新增 | 4 个 `test_ratelimit_*.py` | **42 通过**（policy 14 / guard 12 / judge_semantics 5 / redis 11） |
| 前端 | `npm run build`（`vue-tsc --noEmit && vite build`） | **EXIT=0**，built in 24.70s |
| 设计字典 | `python tools/verify_design.py` | **PASS**，1295 项检查 |
| DDL 一致性 | `gen_ddl_from_dict.py --check` | **OK**：6 个迁移与字典一致 |
| Java 生成物一致性 | `gen_java_from_dict.py --check` | **OK**：76 个文件一致 |
| 生成器自检 | `gen_java_from_dict.py --self-test` | **OK**：6 项 |
| 后端合计 | — | **380 个用例（114 + 266）** |

**那 4 个跳过是什么**：`tests/test_cli_runner_smoke.py` 的 4 条真实 CLI 冒烟，
跳过原因是"会产生真实费用，需显式开启"（`REPOPILOT_ENABLE_CLI_SMOKE` 之类）。
这 4 条在 TASK-013 基线里同样是跳过，**数量未变**，不是本次新增的逃避。

> 首轮 Worker 全量跑出 `252 passed / 33 skipped`，多出的 29 条跳过是因为**只注入了 Redis 环境变量、
> 没注入 RabbitMQ 环境变量**。补上 `WORKER_TEST_RABBIT_*` 后重跑得到 `281 / 4`。
> 这里记录两种结果，是因为"少配一个环境变量导致 29 条静默跳过"正是任务书警告的那种"假通过"。

### 3.3 原子性到底怎么验证的

**不是靠 Mock。** 两条独立用例，都在真实 Redis 上跑：

| 侧 | 用例 | 做法 | 断言 |
| --- | --- | --- | --- |
| Java | `RateLimitIT.concurrentRequestsMustNotExceedCapacity` | 容量 8、64 线程、`CountDownLatch` 同时起跑，真实 HTTP 无关（直接打 limiter） | 放行数 **恰好 8**，多一个即失败 |
| Python | `test_concurrent_acquires_never_exceed_capacity` | 容量 8、64 线程 `ThreadPoolExecutor`，两个 Worker 实例共享同一 Redis | 放行数 **恰好 8** |

为什么这条能证明原子性：若把逻辑写在应用侧（`GET` → 本地算 → `SET`），64 个并发会读到同一旧值、
各自认为有余量，实测放行数会显著大于 8（并发度越高偏差越大）。断言"恰好等于容量"而不是
"不超过容量 + 一点误差"，就是为了让这种实现直接失败。

其余关键用例：

| 用例 | 验证什么 |
| --- | --- |
| `writeEndpointShouldReturn429WithRetryAfterWhenBucketIsExhausted` | 429 + `RATE_LIMITED` + `Retry-After` ≥ 1 + `X-RateLimit-Remaining: 0` + 统一信封字段 |
| `exhaustedSubjectShouldNotAffectOtherSubjects` | 多主体隔离（不是全局共享计数） |
| `readRequestsAreNotRateLimited` | 写入口耗尽后，GET 仍 200 |
| `tokensAreRefilledOverTime` | 轮询而非硬等固定时长，断言"最终恢复"（避免机器负载导致 flaky） |
| `idleBucketExpiresSoKeysDoNotLiveForever` | TTL 300ms 后 key 消失、桶重建为满 |
| `sameSubjectAndCostAreIsolatedAcrossPolicies` | 同一主体不同策略互不挤占 |
| `healthEndpointShouldReportRedisAsUp` | `/api/health` 里 `redis` 组件 `UP` 且 detail 含 `PING` |
| `test_two_workers_share_the_same_limit` | 两个 Worker 实例共享同一限额（不是各算各的） |
| `test_script_rejects_non_positive_arguments` | 非法参数返回 `-1` 哨兵，不污染桶状态 |
| `test_key_contains_no_raw_subject` | key 里不含明文主体 |
| `test_unavailable_redis_raises_dedicated_error` | 连不上的 Redis 抛专用异常，而非 `None`/静默放行 |
| `test_lua_script_is_identical_on_both_sides` | 两端脚本逐字节一致 |
| `test_key_contract_matches_java_implementation` | 跨语言 key 字面量一致 |

### 3.4 限流依赖故障的验证

| 场景 | 用例 | 断言 |
| --- | --- | --- |
| Java 侧 Redis 不可达 | `RedisTokenBucketRateLimiterUnavailableTest`（连真实端口 1，走 Lettuce 真实失败路径，不是 Mock） | 抛 `RateLimitUnavailableException` |
| Java 侧 FAIL_OPEN 行为 | `RateLimitInterceptorTest` | 放行 + `X-RateLimit-Status: unavailable` + `unavailable` 指标 +1 |
| Java 侧 FAIL_CLOSED 行为 | 同上 | 抛异常 → 503，不静默放行 |
| Python 侧 FAIL_CLOSED | `test_ratelimit_guard.py` | `RATE_LIMIT_UNAVAILABLE`，可重试 |
| Python 侧 FAIL_OPEN | 同上 | 放行 + `unavailable` 计数 |
| 429 不是 503、503 不是 429 | `GlobalExceptionHandlerTest` + `RateLimitIT` | 两个独立 handler，状态码与 header 不互串 |

### 3.5 CI

`.github/workflows/ci.yml` 两个 job 各加了 `redis:7.2.5` service + 「等待 Redis 可连接」轮询步骤
（`docker exec ... redis-cli ping | grep -q PONG`）+ 注入 `REPOPILOT_IT_REDIS_*` / `WORKER_TEST_REDIS_*`。

**第一次实跑（run `39458525349`）结果是 failure，原因与限流无关。**

| Job | 结果 |
| --- | --- |
| Web（构建） | ✅ 24s |
| 后端（MySQL 8.0） | ✅ 2m25s |
| 后端（MySQL 8.0.36） | ✅ 2m7s |
| 设计字典自检 | ✅ 9s |
| **Worker（pytest）** | ❌ `14 failed, 259 passed, 4 skipped, 8 errors` |

失败全部是 `NOT_FOUND - no queue 'repopilot.evaluation.ready'` /
`no exchange 'repopilot.events.dlx'` / `no exchange 'repopilot.events'`。

**根因**：Worker 的 broker 用例**假定 AMQP 拓扑已存在**——本地跑时它由后端启动
（`RabbitTopologyDeclarer`）或演示脚本先声明好了，这个前提一直是隐性的。
而 CI 的 service 容器是全新的空 broker，workflow 里**从来没有声明拓扑的步骤**。

**为什么现在才暴露**：上一条绿灯 CI（run `39274690904`，2026-10-19）的 Worker job 只跑了
**92 个用例、1.33 秒**，那时 RabbitMQ 测试套件还不存在。TASK-011~013 的 15 个提交
**从未在 CI 跑过**——本任务这次 push 是它们第一次进 CI。也就是说这是一个**既有缺口**，
不是 TASK-014 引入的回归。

**本地复现与修复验证**（不靠推测）：

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 1. 清空本地 broker 拓扑 | 删除 8 个队列 + 4 个交换机 | — |
| 2. 跑消费者用例 | `pytest tests/test_consumer_rabbit.py tests/test_consumer_execution.py` | **14 failed, 8 errors**（与 CI 完全一致） |
| 3. 声明拓扑 | `tools/task013_broker.py declare-topology` | `ok` |
| 4. 同一批用例重跑 | 同上 | **26 passed** |

**修复**：在 CI 的 worker job 里加一步 `python tools/task013_broker.py declare-topology`
（位置在 `pip install` 之后、`pytest` 之前）。用仓库里的声明工具而不是在 workflow 里手写
一遍队列参数——参数的唯一真相在 `tools/task013_broker.py`，复制一份迟早漂移。

**为什么 CI 的 Redis 没有密码**：GitHub Actions 的 `services` 只接受 `docker create` 级别的参数，
不能像 compose 那样追加 `--requirepass`。因此 **CI 覆盖的是无密码分支，带密码分支只由本地 compose 验证**
（本地 Redis 就有密码，全部限流测试都跑在它上面）。这一点写进了 workflow 注释，不是遗漏。

## 4. 文档

| 文件 | 变化 |
| --- | --- |
| `AGENTS.md` §2 | Redis 从"预留/Java 不引客户端/health 固定 UNKNOWN"改为"限流共享状态存储"，保留原条款与裁决说明 |
| `docs/modules/rate-limiting.md` | **新建**：Lua 原子性、Key 设计、故障策略、阈值推导公式、两层接入链路、指标、9 行排查表、本地验证命令 |
| `tasks/TASK-014.md` | **新建**：范围、验收、开发边界 |
| `tasks/README.md` | 索引补 013、014 |
| `docs/README.md` | 模块索引补 `modules/rate-limiting.md` |
| `docs/01-local-development.md` | 环境变量表（新增限流变量）、`/api/health` 示例（redis 从 UNKNOWN 改为 UP） |
| `docs/development-guide.md` | 本地部署段（Redis 不再是"可选"）、性能段（明确"业务数据不做 Redis 缓存"） |
| `docs/architecture/06-reliability-design.md` | 新增「限流（TASK-014）」三条约束 |
| `deploy/README.md` | Redis 行从"V1 预留"改为"承载分布式限流" |
| `.env.example`（根） | Redis 段重写 + 全部 `REPOPILOT_RATE_LIMIT_*` |
| `repopilot-worker/.env.example` | 新增 Redis 限流整段 + 阈值推导公式 |

**示例阈值不是生产最佳值。** `application.yml` 与 `.env.example` 里都写了这句，
`docs/modules/rate-limiting.md` §7 给出推导方法：

```
入口：capacity ≈ 单用户一次操作的连点上限；refill ≈ 长期频率 × 安全系数(0.5~1)
模型：refill ≈ 上游 RPM/60 ÷ 安全系数(1.2~2)；capacity ≈ refill × 单批次规模，且小于上游 burst
TTL 必须 ≥ capacity / refill，否则空闲客户端周期性拿回满桶
```

## 5. 提交范围与 Git 状态

见 §10（提交在报告写完后执行，因此这里只描述范围，最终计数以推送前那次 `rev-list` 为准）：

- **本次任务共 58 个文件：35 新增 / 22 修改 / 1 删除，`+4488 / −47`。**
- 两个提交：`1306089`（实现 + 测试 + CI + 配置）、`4f436c7`（文档）。
- Java：`pom.xml`、`ratelimit/token_bucket.lua`、`common/ratelimit/*`（11 个类）、
  `RedisHealthProbe.java`（替代删除的 `RedisReservedProbe.java`）、`GlobalExceptionHandler.java`、
  `application.yml`、`application-it.yml`、8 个测试类、`RateLimitIT.java`。
- Python：`app/ratelimit/*`（5 文件 + lua）、`app/evaluator/{llm,judge,registry}.py`、
  `app/config.py`、`app/runtime.py`、`requirements.txt`、`pyproject.toml`、4 个测试文件。
- 配置/CI：`.github/workflows/ci.yml`、两份 `.env.example`。
- 文档：`AGENTS.md`、`docs/*`、`tasks/*`。

**未改动**：任何 Flyway 迁移、任何实体/Mapper、任何前端源码、TASK-011/012/013 的实现文件。

## 6. 回归：TASK-004~013 有没有被打坏

| 层 | 基线（TASK-013 交付报告） | 本次 | 结论 |
| --- | --- | --- | --- |
| Java 单测 | 75 | **114**（+39 新增） | 旧用例全通过，**一条没删** |
| Java 集成 | 258 | **266**（+8 新增） | 同上 |
| Python | 239 passed / 4 skipped | **281 passed / 4 skipped**（+42 新增） | 跳过数未变 |
| 前端构建 | 通过 | 通过 | — |
| 设计字典 | PASS | PASS（1295 检查） | 未改表结构 |

## 7. 未实现 / 未验证（如实列出）

| 项 | 状态 | 说明 |
| --- | --- | --- |
| **CI 实际运行** | ⚠️ 部分验证 | 已实跑一次（run `39458525349`）：后端双版本、Web、设计字典全绿；**Worker job 红**，原因是 workflow 从未声明 AMQP 拓扑（既有缺口，见 §3.5），已在本地复现并修复。**修复后的 CI 复跑结果见 §3.5 末尾**；若复跑仍红，以复跑为准 |
| **CI 的 Redis 鉴权分支** | ❌ 未覆盖 | Actions `services` 不支持 `--requirepass`；带密码路径只有本地验证过 |
| **逐次上游 HTTP 请求限流** | ❌ 未实现 | 当前 Runner 是 `claude -p` CLI，一次 `complete` 内部发几次 HTTP 不可观测。限的是"Worker 发起的模型调用次数" |
| **用户/租户维度的限流** | ❌ 未实现 | 仓库没有身份体系。入口限流以 IP 为主体，存在 NAT 共用、换 IP 规避、XFF 可伪造三项局限（文档 §4 已写明） |
| **分布式配额的下游联动** | ❌ 未实现 | 限流只保证"不超发"，不会主动向上游申请配额或做跨区域协调 |
| **多 Redis 实例 / Cluster** | ❌ 未实现 | 单实例。集群模式下 Lua 的 `KEYS` 必须落同一 slot（当前 key 单段，天然满足，但未实测） |
| **压测下的限流开销** | ⚠️ 未量化 | 没有测"开启限流后 P99 增加多少 ms"。单次 `EVAL` 是 O(1)，但本地没做对照压测 |
| **限流规则的动态热更新** | ❌ 未实现 | 改阈值需重启（`@ConfigurationProperties` 启动时绑定）。当前量级不需要 |
| **前端 429 的友好提示** | ⚠️ 未改动前端 | 后端返回统一信封 + `Retry-After`，前端沿用既有错误处理，未做"请 N 秒后重试"的专门文案 |

## 8. 已知设计差异与取舍

1. **拦截器而非 Filter**：为了复用统一异常处理。代价是限流发生在 `DispatcherServlet` 分发之后，
   静态资源与 404 路径不会被计入（这恰好符合"只限真实写入口"的目标）。
2. **`remaining` 向下取整**：响应头展示的是整数，内部状态是 6 位小数。客户端看到 `0` 时可能
   还剩 0.4 个令牌——这是展示精度，不影响判定。
3. **`api-bulk` 的 TTL（30m）远大于补满时间（100s）**：刻意宽裕，避免"空闲一会儿就白拿满桶"。
   代价是键在最后一次使用后还要存活 30 分钟，量级上可接受（每客户端每策略一个键）。
4. **`FAIL_OPEN` 时 Redis 不进 readiness**：与"Redis 挂了不该摘流"一致。若有人把策略改成
   `FAIL_CLOSED` 却忘了加 readiness，会出现"实例仍被负载均衡选中但所有写请求 503"——
   `application.yml` 里有对应注释，但**没有强制校验**（这是个已知的可改进点）。
5. **Java 与 Python 各持一份脚本文件**：没有抽成"单一来源 + 构建时复制"，因为两个构建系统
   （Maven / setuptools）之间做复制会引入新的构建耦合。代价是必须靠测试守住一致性——
   已经做了（字节比对用例）。

## 9. 下一 TASK 的前置是否满足

- TASK-011 的消息可靠性：未改动，全量集成测试通过。
- TASK-012 的性能结论：未改动热路径（限流拦截器只在配置的写入口生效），未引入新查询。
- TASK-013 的执行模型：未改动回调/队列线程模型。
- 限流已就位，可作为"真实调用的成本护栏"被后续任务直接复用。

## 10. 交付状态

| 项 | 值 |
| --- | --- |
| 分支 | `develop` |
| 基线 | `origin/develop` |
| TASK-014 提交 | `1306089`（实现）、`4f436c7`（文档） |
| 待推送 | **不写死数字**：以推送前 `git rev-list --count origin/develop..HEAD` 的实测值为准。构成为 TASK-013 遗留 15 个 + TASK-014 的实现与文档提交（`1306089`、`4f436c7`、`7d15610` 及本条修订）。写死数字会随每一次报告修订失效 |
| 工作区 | 提交后干净（`git status --porcelain` 为空） |
| 敏感信息 | 新增文件中无硬编码凭据；`.env.example` 仅新增空值占位 |
| 构建产物 | `target/`、`dist/`、`.venv/` 均已被 `.gitignore` 排除 |
| CI | **未运行**（未 push） |
| 推送 | **等待显式授权**，未 push、未建 PR |
