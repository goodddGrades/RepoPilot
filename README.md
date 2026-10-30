# RepoPilot

**面向 Agent 的轨迹评测与质量分析平台**

RepoPilot 解决四个问题：统一管理不同 Agent 的执行轨迹与评测数据、用规则/代码测试/LLM Judge 评价
Agent 表现、通过人工标注定位失败原因、对比不同 Agent 版本的质量并发现性能退化。

平台有两条评测路径，**只允许数据入口不同**，其余共用统一数据模型、评测引擎、标注系统与实验报告：

- **在线评测**：Agent Runner 执行 → 采集轨迹 → 自动评测
- **离线评测**：导入规范化 JSON/JSONL → 标准化 → 自动评测

> 当前状态：**TASK-001 基础工程**。仅搭建 Monorepo、基础设施编排与三端可独立启动的工程骨架，
> 不包含任何在线/离线评测业务实现，也不创建业务数据表。详见 [`tasks/TASK-001.md`](./tasks/TASK-001.md)。

---

## 1. 目录结构

```
RepoPilot/
├── repopilot-server/                 # Java 21 + Spring Boot 3.5.16（模块化单体）
│   ├── .mvn/wrapper/                 # Maven Wrapper（无需全局 Maven）
│   ├── mvnw / mvnw.cmd
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/repopilot/
│       │   ├── RepoPilotServerApplication.java
│       │   ├── common/
│       │   │   ├── api/              # ApiResponse、ErrorCode、FieldViolation、RequestIdHolder
│       │   │   ├── exception/        # BusinessException
│       │   │   ├── health/           # HealthController/HealthService + mysql/rabbitmq/flyway/redis 探针
│       │   │   └── web/              # RequestIdFilter、GlobalExceptionHandler
│       │   └── modules/              # agent/dataset/experiment/trace/evaluation/annotation/report
│       │                             # 仅 package-info 声明边界，无业务实现
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── application-dev.yml
│       │   ├── logback-spring.xml
│       │   └── db/migration/V1__baseline.sql     # 空白迁移，不建业务表
│       └── test/java/com/repopilot/              # 5 个单元测试 + 1 个集成测试
│
├── repopilot-worker/                 # Python 3.11+ + FastAPI
│   ├── app/
│   │   ├── main.py                   # 应用工厂
│   │   ├── config.py                 # pydantic-settings 配置
│   │   ├── api/                      # 路由、请求上下文中间件、统一错误响应
│   │   ├── common/logging.py         # 结构化日志（json / console）
│   │   ├── runner/                   # 目录与约束说明（V1 不实现真实 Runner）
│   │   └── evaluator/                # 目录与约束说明（V1 不实现真实 Judge）
│   ├── tests/                        # pytest
│   ├── pyproject.toml
│   ├── requirements.txt / requirements-dev.txt / requirements.lock.txt
│   └── .env.example
│
├── repopilot-web/                    # Vue3 + TypeScript + Vite
│   ├── src/
│   │   ├── main.ts / App.vue
│   │   ├── api/                      # axios 实例、健康检查接口
│   │   ├── config/navigation.ts      # 侧边菜单定义
│   │   ├── layouts/DefaultLayout.vue # 后台布局（侧边菜单 + 顶部导航）
│   │   ├── router/                   # Vue Router
│   │   ├── stores/                   # Pinia：app（布局）、health（后端状态）
│   │   ├── styles/ / types/ / views/ # 工作台、404
│   ├── package.json / vite.config.ts / tsconfig.json
│   └── .env.development
│
├── deploy/
│   ├── docker-compose.yml            # MySQL / RabbitMQ / MinIO / Redis
│   └── README.md                     # 基础设施配置说明与排障
├── docs/                             # 设计基线（唯一来源）
│   ├── README.md                     # 文档索引与阅读顺序
│   ├── 00-product-scope.md           # 产品范围与 V1 边界
│   ├── architecture/                 # 01~06 全局架构规范
│   ├── modules/                      # 在线/离线/轨迹/评测/标注/报告/前端
│   ├── contracts/                    # 数据库字典 + 5 份 JSON Schema/OpenAPI
│   ├── reviews/                      # 设计校验、对齐报告、合并报告
│   ├── development-guide.md          # 通用开发/测试/部署规范
│   └── 01-local-development.md       # 本仓库 Runbook
├── tasks/                            # TASK-001~012（任务范围与验收）
├── examples/                         # 规范样例（不含真实密钥）
├── tools/verify_design.py            # 设计一致性自检
├── AGENTS.md                         # 项目级开发约束（必读）
├── .env.example                      # 环境变量模板（无真实凭据）
├── .gitignore
├── README.md
└── project_design.md                 # 历史草案（已被 docs/ 取代）
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

配置了 `Asia/Shanghai` 之外的时区统一约定：**数据库与应用时间一律 UTC**。

---

## 3. 快速开始

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

### 3.4 启动后端（8080）

```bash
cd repopilot-server
./mvnw spring-boot:run
```

- 业务健康检查：<http://127.0.0.1:8080/api/health>（含 MySQL / RabbitMQ / Flyway 真实探测结果）
- 运维健康端点：<http://127.0.0.1:8080/actuator/health>
- 首次启动时 Flyway 会创建 `flyway_schema_history` 并记录 version=1（**不创建业务表**）

### 3.5 启动 Worker（8000）

```bash
cd repopilot-worker
python -m venv .venv
. .venv/Scripts/activate          # Linux/macOS: source .venv/bin/activate
pip install -r requirements-dev.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

- 健康检查：<http://127.0.0.1:8000/health>
- 接口文档：<http://127.0.0.1:8000/docs>

### 3.6 启动前端（5173）

```bash
cd repopilot-web
npm install
npm run dev
```

打开 <http://127.0.0.1:5173>，工作台的「后端服务状态」卡片展示的是**真实调用** `/api/health` 的结果，
后端未启动时会明确显示「后端不可达」，不会展示任何模拟数据。

### 3.7 停止

```bash
# 应用：各终端 Ctrl+C（后端已启用 graceful shutdown）

# 基础设施：保留数据卷
docker compose --env-file .env -f deploy/docker-compose.yml down

# 基础设施：连数据一起清除（谨慎）
docker compose --env-file .env -f deploy/docker-compose.yml down -v
```

---

## 4. 测试

| 范围 | 命令 | 实测结果 |
| --- | --- | --- |
| 后端单元测试 | `cd repopilot-server && ./mvnw test` | **Tests run: 19, Failures: 0, Errors: 0, Skipped: 0**（全部为单元测试） |
| 后端集成测试（需基础设施） | `set -a && source ../.env && set +a && ./mvnw test -Dtest=RepoPilotServerApplicationIT` | 环境无数据库时 **Skipped: 4**（未 Mock 顶替）；需基础设施就绪后复跑 |
| Worker | `cd repopilot-worker && pytest` | **14 passed** |
| 前端类型检查 + 构建 | `cd repopilot-web && npm run build` | **通过**（vue-tsc 无错误；主 chunk 1098.89 kB / gzip 365.02 kB） |
| Compose 语法与变量插值 | `docker compose --env-file .env -f deploy/docker-compose.yml config` | **通过**（含 MinIO 探活变量覆盖） |
| 后端启动 + 接口实测（无数据库降级运行） | `java -jar target/repopilot-server.jar --server.port=18080 --spring.flyway.enabled=false` 后 `curl /api/health` | 启动成功；`/api/health` 返回 `status=DOWN`，`mysql`/`rabbitmq` 均为 `DOWN` 并带真实错误摘要，**没有伪造 UP**；响应头带 `X-Request-Id`，响应体为统一结构 |
| 探针语义 | `/actuator/health/liveness`、`/actuator/health/readiness` | liveness **200 UP**（不受依赖影响）；readiness **503 DOWN**（db/rabbit 明细） |
| 前端 → 后端联调 | `npm run dev` 后 `curl http://127.0.0.1:5199/api/health` | **通过**：经 Vite 代理拿到真实响应；`/api/not-exist` 返回统一 404 结构 |

后端接口实测响应（降级运行时，用于验证"不造假"行为）：

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "status": "DOWN",
    "components": [
      { "name": "mysql", "status": "DOWN", "required": true, "detail": "Communications link failure …", "latencyMs": 1010 },
      { "name": "rabbitmq", "status": "DOWN", "required": true, "detail": "java.net.ConnectException: Connection refused: getsockopt", "latencyMs": 2 },
      { "name": "flyway", "status": "UNKNOWN", "required": true, "detail": "未配置 Flyway", "latencyMs": 0 },
      { "name": "redis", "status": "UNKNOWN", "required": false, "detail": "V1 预留：容器已编排，但服务端尚未接入 Redis 客户端", "latencyMs": 0 }
    ]
  },
  "requestId": "acc8cc0a-0620-4604-bbeb-890390f463ec",
  "timestamp": "2026-10-17T06:07:11.776Z"
}
```

后端测试覆盖：统一响应结构、请求追踪 ID 的生成/透传/清理、全局异常映射（业务异常/参数校验/
未处理异常/404/非法请求体）、健康状态聚合规则、健康接口响应结构。
集成测试 `RepoPilotServerApplicationIT` 校验：Spring 上下文、`flyway_schema_history` 中 version=1、
`/api/health` 三个依赖全部 UP、`/actuator/health` 可访问；**未设置 `MYSQL_PASSWORD` 时 4 项全部 Skipped**。
注意：该测试类名以 `IT` 结尾，Surefire 默认不选中，常规 `./mvnw test` 不会执行它（需显式 `-Dtest=`）。


---

## 5. 关键设计说明

- **模块化单体**：单进程按 `com.repopilot.modules.*` 分包，模块间只通过 application 层协作，
  禁止跨模块访问他人 Mapper/表；不引入 Spring Cloud、微服务、Kafka、ClickHouse。
- **统一响应**：所有接口（含异常）返回 `{code, message, data, errors, requestId, timestamp}`，
  `code` 为稳定字符串；`X-Request-Id` 透传或生成，同时写入 MDC 与响应头，日志与前端可对账。
- **健康检查不造假**：MySQL 走 `Connection.isValid`、RabbitMQ 真实建连、Flyway 读取迁移状态；
  Redis 未接入时显式返回 `UNKNOWN` 并注明原因，而不是伪造 `UP`。
- **Flyway 空白迁移**：`V1__baseline.sql` 只有注释，用于验证迁移流程；业务表在 TASK-002 起通过新脚本引入。
- **凭据管理**：密码只来自 `.env`；`application-dev.yml` 不写死密码；Compose 关键变量缺失即失败。
- **前端不造数据**：菜单中未实现的模块置灰并标注「规划中」；工作台只展示真实接口返回。

---

## 6. 文档

- [`AGENTS.md`](./AGENTS.md)：项目级开发约束（权威顺序、架构红线、命令、交付要求）
- [`docs/README.md`](./docs/README.md)：**设计基线索引**（架构 / 模块 / 契约 / 评审）
- [`docs/01-local-development.md`](./docs/01-local-development.md)：环境搭建、启动顺序、排障
- [`docs/development-guide.md`](./docs/development-guide.md)：测试层级、CI、配置、备份、性能规范
- [`deploy/README.md`](./deploy/README.md)：基础设施端口、健康检查、持久化与重置
- [`tasks/TASK-001.md`](./tasks/TASK-001.md)：本阶段交付清单与验收记录
- [`docs/reviews/repository-alignment.md`](./docs/reviews/repository-alignment.md)：仓库对齐核对（实际版本、命令、偏差）
- [`docs/reviews/design-merge-report.md`](./docs/reviews/design-merge-report.md)：设计包合并的冲突清单与处置
- `project_design.md`：历史草案，已被 `docs/` 取代，无约束力

设计包自检：`python tools/verify_design.py`（依赖见 `tools/requirements-design.txt`）。

下一阶段：TASK-002（数据库领域模型、Flyway 与统一 API 基础）——本仓库当前**未包含**其任何实现；
开前需先处理 [`docs/reviews/design-merge-report.md`](./docs/reviews/design-merge-report.md) §5 的契约对齐项。
