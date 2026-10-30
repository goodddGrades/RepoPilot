# RepoPilot

**面向 Agent 的轨迹评测与质量分析平台**

RepoPilot 解决四个问题：统一管理不同 Agent 的执行轨迹与评测数据、用规则/代码测试/LLM Judge 评价
Agent 表现、通过人工标注定位失败原因、对比不同 Agent 版本的质量并发现性能退化。

平台有两条评测路径，**只允许数据入口不同**，其余共用统一数据模型、评测引擎、标注系统与实验报告：

- **在线评测**：Agent Runner 执行 → 采集轨迹 → 自动评测
- **离线评测**：导入规范化 JSON/JSONL → 标准化 → 自动评测

> **当前状态：TASK-001 ~ TASK-012 全部完成。**
> 28 张表（Flyway V1~V7）、9 个业务模块、217 个真实 MySQL 集成用例、13 个 Vue 页面、
> Python Worker（Mock/CLI Runner + 规则/Judge/测试命令评测器）、
> Outbox 消息可靠性、压测与有证据的并发优化均已落地并验证。
> 每项的实测数字见[§4 测试](#4-测试)。

---

## 1. 目录结构

```
RepoPilot/
├── repopilot-server/                 # Java 21 + Spring Boot 3.5.16（模块化单体）
│   ├── mvnw / mvnw.cmd               # Maven Wrapper（无需全局 Maven）
│   └── src/
│       ├── main/java/com/repopilot/
│       │   ├── RepoPilotServerApplication.java
│       │   ├── common/               # 跨模块基础设施（非业务）
│       │   │   ├── api/              # ApiResponse、ErrorCode、FieldViolation、RequestIdHolder
│       │   │   ├── exception/        # BusinessException
│       │   │   ├── health/           # HealthController/HealthService + mysql/rabbitmq/flyway 探针
│       │   │   ├── web/              # RequestIdFilter、GlobalExceptionHandler
│       │   │   ├── reliability/      # event（事件信封）、messaging（发布器）、service（Outbox/Inbox）
│       │   │   ├── persistence/      # DeadlockRetry（死锁重试边界判定）
│       │   │   ├── cursor/           # 签名 keyset 游标
│       │   │   ├── idempotency/      # 幂等键
│       │   │   ├── state/            # 状态机
│       │   │   ├── worker/           # Worker 协议 DTO 与鉴权
│       │   │   ├── storage/          # Artifact 存储
│       │   │   ├── hash/ id/ json/ jsonl/ registry/ validation/
│       │   └── modules/              # 业务模块（只经application 层协作，不跨模块访问他人Mapper/表）
│       │       ├── agent/            # Agent与版本
│       │       ├── dataset/          # 数据集、样本、发布/归档
│       │       ├── evaluationconfig/ # 评测算子定义与方案版本
│       │       ├── experiment/       # 实验、Run 槽位、领取/租约/封口、离线导入编排
│       │       ├── trace/            # 轨迹事件写入与统一查询
│       │       ├── evaluation/       # 评测批次、Job、Attempt、结果聚合
│       │       ├── annotation/       # 人工标注、失败归因、复核
│       │       ├── report/           # 报告聚合、配对回归、质量门禁
│       │       └── importing/        # JSONL 导入、逐行校验与回执
│       ├── main/resources/
│       │   ├── application.yml / application-dev.yml / application-it.yml
│       │   ├── logback-spring.xml
│       │   └── db/migration/V1~V7    # 28 张表；V1 空白基线，V2~V7 业务表
│       └── test/java/com/repopilot/  # 79 单元用例 + 217 集成用例（26 个 IT 类）
│
├── repopilot-worker/                 # Python 3.11+ + FastAPI（执行与评测 Worker）
│   ├── app/
│   │   ├── main.py                   # 应用工厂 + lifespan（按配置启停轮询）
│   │   ├── config.py                 # pydantic-settings 配置
│   │   ├── runtime.py / poller.py    # 后台轮询与单次执行
│   │   ├── client.py                 # 后端 HTTP 客户端
│   │   ├── api/                      # 路由、请求上下文中间件、统一错误响应
│   │   ├── common/logging.py         # 结构化日志（json / console）
│   │   ├── runner/                   # base / mock（零延迟）/ cli_coding_agent / process_tree
│   │   └── evaluator/                # registry / rules / testing / trace / judge / llm
│   ├── tests/                        # pytest（12 个测试文件）
│   └── pyproject.toml / requirements*.txt / .env.example
│
├── repopilot-web/                    # Vue3 + TypeScript + Vite（13 个业务页面）
│   └── src/
│       ├── main.ts / App.vue
│       ├── api/                      # 11 个 API 模块（agents/datasets/experiments/traces/…）
│       ├── types/                    # domain / report / annotation 类型
│       ├── router/                   # 16 条路由
│       ├── stores/ config/ layouts/ components/ styles/
│       └── views/                    # Agent/数据集/方案/实验/Run/导入/标注/报告…
│
├── deploy/
│   ├── docker-compose.yml            # MySQL / RabbitMQ / MinIO / Redis(预留)
│   └── README.md                     # 基础设施配置说明与排障
├── docs/                             # 设计基线（唯一来源）+ 交付报告
│   ├── README.md                     # 文档索引与阅读顺序
│   ├── 00-product-scope.md
│   ├── architecture/                 # 01~06 全局架构规范
│   ├── modules/                      # 在线/离线/轨迹/评测/标注/报告/消息可靠性/前端
│   ├── contracts/                    # database-schema.json（字段唯一来源）+ 8 份 OpenAPI + 4 份 JSON Schema
│   ├── reviews/                      # task-002~012 交付报告 +压测原始数据（perf/）
│   ├── development-guide.md          # 通用开发/测试/部署规范
│   └── 01-local-development.md       # 本仓库 Runbook
├── tasks/                            # TASK-001~012（任务范围与验收）
├── examples/                         # 规范样例（不含真实密钥）
├── tools/
│   ├── demo-task-003.sh ~ demo-task-007.sh   # 端到端演示脚本（只依赖 curl）
│   ├── it-mysql.sh                   # 本地隔离 MySQL 8 实例（集成测试用）
│   ├── perf-diagnose.sql             # 慢查询/索引/锁等待采集
│   ├── verify_design.py              # 设计一致性自检
│   └── gen_ddl_from_dict.py / gen_java_from_dict.py
├── AGENTS.md                         # 项目级开发约束（必读）
├── .env.example                      # 环境变量模板（无真实凭据）
└── README.md
```

---

## 2. 技术栈与版本

| 层次 | 技术 | 版本 |
| --- | --- | --- |
| 语言/运行时 | Java | 21（LTS） |
| 框架 | Spring Boot | 3.5.16（Spring Framework 6.2.19） |
| 构建 | Maven + Maven Wrapper | 3.9.9 |
| 持久化 | MyBatis / mybatis-spring | 3.5.19 / 3.0.5 |
| 迁移 | Flyway（core + mysql） | 11.7.2 |
| 数据库驱动 | mysql-connector-j | 9.7.0 |
| 消息 | Spring AMQP / amqp-client | 3.2.12 / 5.25.0 |
| 校验 | Spring Validation（Jakarta Bean Validation） | 随 Boot 3.5.16 |
| Worker | Python / FastAPI | 3.11+（实测 3.13）/ 0.143.3 |
| Worker | pydantic / pydantic-settings / uvicorn | 2.14.1 / 2.16.0 / 0.54.0 |
| Worker 测试 | pytest / httpx | 8.4.2 / 0.28.1 |
| 前端 | Vue / Vue Router / Pinia | 3.5.44 / 4.6.4 / 3.0.4 |
| 前端 UI | Element Plus / @element-plus/icons-vue | 2.14.7 / 2.3.2 |
| 前端 | Axios / TypeScript / Vite / vue-tsc | 1.20.0 / 5.9.3 / 7.3.7 / 3.3.13 |
| 前端运行时 | Node.js | ≥ 20.19（实测 22.x） |
| 基础设施 | MySQL / RabbitMQ | 8.0.36 / 3.13.7-management |
| 基础设施 | MinIO / Redis（预留） | RELEASE.2025-04-22T22-12-26Z / 7.2.5-alpine |
| 编排 | Docker Compose | v2 |

**数据库与应用时间一律 UTC**（时区约定见 `docs/development-guide.md`）。

---

## 3. 从零运行完整演示

本节是新开发者接手后的**唯一入口**，每条命令都在本仓库真实执行过。

### 3.1 前置要求

JDK 21 · Docker + Compose v2 · Python 3.11+ · Node.js ≥ 20.19

### 3.2 准备环境变量（必须）

```bash
cp .env.example .env      # .env 已被 .gitignore 忽略，禁止提交
```

### 3.3 启动基础设施

```bash
docker compose --env-file .env -f deploy/docker-compose.yml up -d
docker compose --env-file .env -f deploy/docker-compose.yml ps
```

预期四个服务均为 `healthy`：

| 服务 | 地址 |
| --- | --- |
| MySQL 8 | `127.0.0.1:13306`，库 `repopilot` |
| RabbitMQ | `127.0.0.1:5672`，管理台 <http://127.0.0.1:15672> |
| MinIO | API `127.0.0.1:9000`，控制台 <http://127.0.0.1:9001> |
| Redis（预留） | `127.0.0.1:16379` |

> Docker 不可用的机器：可用 `tools/it-mysql.sh` 起一个**隔离的本地 MySQL 8**（只监听回环、
> 独立数据目录），见 [§4.2](#42-后端集成测试需真实-mysql)。

### 3.4 启动后端（8080）

```bash
cd repopilot-server
REPOPILOT_WORKER_TOKEN=local-demo-token ./mvnw spring-boot:run
```

- 业务健康检查：<http://127.0.0.1:8080/api/health>（含 MySQL / RabbitMQ / Flyway 真实探测）
- 运维健康端点：<http://127.0.0.1:8080/actuator/health>
- 首次启动时 Flyway 执行 V1~V7，创建 **28 张业务表**
- **`REPOPILOT_WORKER_TOKEN` 必须显式设置**：留空时 `/internal/v1` 全部返回 401，
  Worker 无法领取任务。这是刻意的默认拒绝，不是缺陷。

数据库连接变量名是 **`MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DATABASE` / `MYSQL_USER` / `MYSQL_PASSWORD`**
（不是 `DB_*`；写错会得到 `Access denied`，因为值会落回默认值而不是报"变量未设置"）。
凭据从根目录 `.env` 取，本机演示可用 `eval "$(./tools/it-mysql.sh env)"` 注入。

### 3.5 启动 Worker（8000）

```bash
cd repopilot-worker
python -m venv .venv
. .venv/Scripts/activate          # Linux/macOS: source .venv/bin/activate
pip install -r requirements-dev.txt
cp .env.example .env
# 关键三项：后端地址、Worker 凭证（与后端一致）、Runner 用Mock
WORKER_SERVER_BASE_URL=http://127.0.0.1:8080 \
WORKER_TOKEN=local-demo-token \
WORKER_CAPABILITIES=RUN:MOCK,EVAL:output.non_empty,EVAL:answer.exact_match,EVAL:trace.tool_error,EVAL:cost.budget \
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

- 健康检查：<http://127.0.0.1:8000/health>，接口文档：<http://127.0.0.1:8000/docs>
- 默认**不自动轮询**（`WORKER_AUTOSTART=false`）：演示与测试用 `POST /worker/run-once` 显式触发。
  需要常驻Worker 时设 `WORKER_AUTOSTART=true`。

### 3.6 启动前端（5173）

```bash
cd repopilot-web
npm install
npm run dev
```

打开 <http://127.0.0.1:5173>。页面只展示**真实接口返回**，后端未启动时明确显示「后端不可达」，
不展示任何模拟数据。

### 3.7 跑通业务闭环（只依赖 curl）

5 个演示脚本按页面操作顺序调用真实接口，**每次运行带随机后缀，可对同一个库重复执行**。
先启动后端与 Worker（3.4 / 3.5），再依次执行：

```bash
cd /d/0project/RepoPilot        # 脚本在仓库根目录
BASE=http://127.0.0.1:8080/api/v1 WORKER_TOKEN=local-demo-token bash tools/demo-task-003.sh
BASE=http://127.0.0.1:8080/api/v1 WORKER_TOKEN=local-demo-token bash tools/demo-task-004.sh
BASE=http://127.0.0.1:8080/api/v1 WORKER_TOKEN=local-demo-token bash tools/demo-task-005.sh
BASE=http://127.0.0.1:8080/api/v1 WORKER_TOKEN=local-demo-token bash tools/demo-task-006.sh
BASE=http://127.0.0.1:8080/api/v1 WORKER_TOKEN=local-demo-token bash tools/demo-task-007.sh
```

| 脚本 | 打通什么 | 对应页面 |
| --- | --- | --- |
| `demo-task-003.sh` | Agent / 数据集 / 评测算子 / 方案版本的创建、发布、归档、JSONL 导入 | Agent、数据集、方案页 |
| `demo-task-004.sh` | 实验提交 → Run 槽位 → Worker 领取（租约）→ 事件上报 → 封口 → 待评测 Job | 实验详情、Run 详情 |
| `demo-task-005.sh` | 离线 JSONL 导入：逐行回执、幂等恢复、缺失槽位 | 导入页|
| `demo-task-006.sh` | 统一 Trace 查询与回放：签名游标分页、尝试隔离、证据跳转 | Run 详情回放面板 |
| `demo-task-007.sh` | 评测闭环：Job 领取 → 提交结论（verdict/score/evidence）→ 状态机收口；非法提交被拒；重评新建批次 | 评测批次、评测任务 |

TASK-008~012 的能力（真实 CLI Runner、Judge、标注、报告、消息可靠性）由集成测试覆盖，
对应可复现命令见 [§4](#4-测试)。

### 3.8 停止

```bash
# 应用：各终端 Ctrl+C（后端已启用 graceful shutdown）
docker compose --env-file .env -f deploy/docker-compose.yml down      # 保留数据卷
docker compose --env-file .env -f deploy/docker-compose.yml down -v    # 连数据一起清除（谨慎）
```

---

## 4. 测试

**下表全部为本机实跑数字**，非估算。命令与前提逐条列出，可直接复现。

| 范围 | 命令 | 实测结果 |
| --- | --- | --- |
| 后端单元测试 | `cd repopilot-server && ./mvnw test` | **79 run / 0 失败 / 0 错误 / 4 跳过** |
| 后端集成测试（26 个 IT 类） | 见 [§4.2](#42-后端集成测试需真实-mysql) | **217 run / 0 失败 / 0 错误 / 1 跳过** |
| Worker | `cd repopilot-worker && pytest` | **169 passed / 4 skipped**（116.5 s） |
| 前端类型检查 | `cd repopilot-web && npm run type-check` | **通过**（vue-tsc 无错误） |
| 前端生产构建 | `cd repopilot-web && npx vite build --emptyOutDir=false` | **通过**（24.4 s；主 chunk 1 104.75 kB / gzip 366.72 kB，业务页2~25 kB 按需切分） |
| 设计一致性自检 | `python tools/verify_design.py` | **PASS · 1278 checks / 28 tables** |
| 压测 | 见 [§4.3](#43-压测复现) | 8并发×60Run 与 16并发×120Run 两档全绿，零死锁 |

### 4.1 跳过的 5 个用例是什么（不掩盖）

| 跳过数 | 用例 | 原因 | 核实方式 |
| --- | --- | --- | --- |
| 1 | `ApiHealthIT.readinessShouldBeUpWhenEveryDependencyIsAvailable` | 由 `@EnabledIfEnvironmentVariable(REPOPILOT_IT_REQUIRE_ALL_UP)` 门控；本机 `application-it.yml` 未配 `spring.rabbitmq`，Spring 用默认 guest/guest，与容器凭据不符 | 按 CI 方式带 `SPRING_RABBITMQ_*` + `REPOPILOT_IT_REQUIRE_ALL_UP=true` 实跑 → **4 run / 0 跳过 / readiness 200** |
| 4 | `repopilot-worker` `test_cli_runner_smoke.py` | 真实 CLI冒烟会**产生真实费用**，需显式开启 | 代码中以显式开关跳过，不是失败 |
| 4 | 后端单元测试 | 依赖本机 Docker 的容器级集成场景 | — |

**没有为了让 CI 变绿而删测试、放宽断言或增加无理由跳过。**

### 4.2 后端集成测试（需真实 MySQL）

集成测试**不使用 H2**，必须在真实 MySQL 8 上跑。

```bash
# 方式A：Docker 起 MySQL（与CI 一致）
docker compose --env-file .env -f deploy/docker-compose.yml up -d mysql

# 方式 B：本机隔离实例（Docker 不可用时）
./tools/it-mysql.sh start
eval "$(./tools/it-mysql.sh env)"       # 导出集成测试所需环境变量
```

然后：

```bash
cd repopilot-server
export JAVA_HOME=/path/to/jdk21
set -a && source ../.env && set +a
eval "$(../tools/it-mysql.sh env)"
./mvnw verify                            # 单元 + 集成
```

只跑单个 IT 类：`-Dit.test=MessagingReliabilityIT -DfailIfNoSpecifiedTests=false`

> 改了 `src/main` 代码必须先 `./mvnw test-compile`——failsafe 不会自动重新编译，
> 会拿旧class 跑出"改了没生效"的假象。

### 4.3 压测复现

```bash
cd repopilot-server
eval "$(../tools/it-mysql.sh env)"
./mvnw -o test-compile
./mvnw -o failsafe:integration-test -Dit.test=PerformanceBaselineIT

# 加压
./mvnw -o failsafe:integration-test -Dit.test=PerformanceBaselineIT \
  -Drepopilot.perf.concurrency=16 -Drepopilot.perf.runs=120
```

结果落盘在 `repopilot-server/target/perf/*.json`（含硬件、参数、各阶段分位、原始样本）。
Mock Runner 是**零延迟纯 CPU** 实现，所以端到端耗时**就是**平台开销，不需要估算或扣减。

同条件对照（8 并发 × 60 Run × 40 事件）的实测结果：

| 指标 | 修复前 | 修复后 |
| --- | --- | --- |
| 事件上传 500 错误 | 每次必现 | **0** |
| InnoDB `lock_deadlocks` 增量 | 每次压测必然发生 | **0**（16 并发 × 120 Run 一轮：95 → 95） |
| `run.execute` P50 / P95 / max | 193.8 / 381.4 / 408.5 ms | 119.4 / 177.7 / 203.5 ms |
| 实验提交 | 338 ms | 134 ms |
| 并发执行 | 2131 ms | 1311 ms |
| 并发评测 | 665 ms | 462 ms |

完整方法、根因、慢查询分析与原始数据见
[`docs/reviews/task-012-delivery.md`](./docs/reviews/task-012-delivery.md)。

> 压测类**刻意不挂在常规 verify 路径**上，且**不对性能数字做断言**——
> CI 机器性能不稳定，把毫秒数写成断言只会制造 flaky；断言只针对正确性。

### 4.4 集成测试覆盖概览

| 领域 | IT 类 | 规模 |
| --- | --- | --- |
| 契约与持久化 | `SchemaDictionaryIT` `SchemaMigrationIT` `PersistenceMappingIT` `EnumDictionaryIT` `ConstraintIT` | 34 |
| 配置类API | `AgentApiIT` `DatasetApiIT` `DatasetImportIT` `PlanApiIT` `ArtifactAccessIT` | 46 |
| 在线执行 | `ExecutionFlowIT` `ExecutionConcurrencyIT` `ConcurrencyIT` | 24 |
| 离线导入 | `OfflineImportIT` | 18 |
| Trace 查询 | `TraceQueryIT` | 10 |
| 评测引擎 | `EvaluationEngineIT` | 17 |
| 标注与报告 | `AnnotationWorkflowIT` `ReportAggregationIT` | 42 |
| 消息可靠性 | `MessagingReliabilityIT` | 13 |
| 并发与重试 | `DeadlockRetryIT` | 8 |
| 压测 | `PerformanceBaselineIT` | 1 |
| 健康检查 | `ApiHealthIT` | 4 |

---

## 5. 关键设计说明

- **模块化单体**：单进程按 `com.repopilot.modules.*` 分包，模块间只通过 application 层协作，
  禁止跨模块访问他人 Mapper/表；不引入 Spring Cloud、微服务、Kafka、ClickHouse。
- **统一响应**：所有接口（含异常）返回 `{code, message, data, errors, requestId, timestamp}`，
  `code` 为稳定字符串；`X-Request-Id` 透传或生成，同时写入 MDC 与响应头。
- **健康检查不造假**：MySQL 走真实连接探测、RabbitMQ 真实建连、Flyway 读取迁移状态；
  Redis 未接入时显式返回 `UNKNOWN` 并注明原因，而不是伪造 `UP`。
- **两条铁律的可靠性设计**（`docs/architecture/06-reliability-design.md`）：
  领取在短事务内完成、**不持锁跨网络**；确认回写必须 `claim_token_hash` 匹配**且**租约未过期
  （fencing），防止失效发布器误标已发布导致永久不重发；confirm 超时按**可重试**处理
  （`uncertain`），由 inbox 去重兜住重复投递。
- **字段唯一来源**：`docs/contracts/database-schema.json`。改字段先改它，再生成迁移与文档；
  已发布迁移由 `migrations.lock.json` 冻结，只新增不修改。
- **凭据管理**：密码只来自 `.env`；配置不写死密码；Compose 关键变量缺失即失败；
  `/internal/v1` 无凭证一律 401。
- **前端不造数据**：只展示真实接口返回；等待中的实验显示用户可理解的语言，
  不暴露 Outbox / 队列等内部概念。
- **压测发现的并发缺陷按根因修，不靠重试掩盖**：间隙锁死锁的修法是去掉 `FOR UPDATE`、
  由唯一约束承担幂等，而不是调索引或加重试；`DeadlockRetry` 只认死锁/锁等待类异常，
  **唯一键冲突、连接故障、业务异常一律不重试**。

---

## 6. 文档

- [`AGENTS.md`](./AGENTS.md)：项目级开发约束（权威顺序、架构红线、命令、交付要求）
- [`docs/README.md`](./docs/README.md)：**设计基线索引**（架构 / 模块 / 契约 / 评审 / 交付报告）
- [`docs/01-local-development.md`](./docs/01-local-development.md)：环境搭建、启动顺序、排障
- [`docs/development-guide.md`](./docs/development-guide.md)：测试层级、CI、配置、备份、性能规范
- [`deploy/README.md`](./deploy/README.md)：基础设施端口、健康检查、持久化与重置
- [`docs/modules/messaging-reliability.md`](./docs/modules/messaging-reliability.md)：Outbox/Inbox 运维手册
- [`docs/reviews/task-012-delivery.md`](./docs/reviews/task-012-delivery.md)：压测方法、瓶颈根因与优化对照（最新）

各 TASK 的实施交付报告（实际文件、契约差异、命令、测试数量、已知问题）在
[`docs/reviews/task-003-delivery.md`](./docs/reviews/task-003-delivery.md) ~
[`docs/reviews/task-012-delivery.md`](./docs/reviews/task-012-delivery.md)。
压测原始数据在 `docs/reviews/perf/`。

设计包自检：`python tools/verify_design.py`（依赖见 `tools/requirements-design.txt`）。
