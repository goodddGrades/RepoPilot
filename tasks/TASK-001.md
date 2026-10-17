# TASK-001 · Monorepo、Docker Compose 与三端基础工程

- 状态：**已完成**（2026-10-17）
- 上游依据：`project_design.md` 第 12 章任务拆分
- 范围：只搭建基础工程，**不实现任何业务功能**

## 1. 目标

| 编号 | 目标 | 结果 |
| --- | --- | --- |
| 1 | Monorepo 目录结构与仓库规范 | 完成 |
| 2 | Docker Compose 启动 MySQL / RabbitMQ / MinIO / Redis | 编排完成，配置校验通过（本机容器实跑见 §6 限制说明） |
| 3 | Java 工程可独立启动，连接 MySQL + RabbitMQ，Flyway 空白迁移 | 完成（单元测试通过） |
| 4 | Python Worker 可独立启动并提供健康检查 | 完成（pytest 通过） |
| 5 | Vue3 前端可独立启动并调用 Java 健康检查 | 完成（构建通过） |

## 2. 交付物

### 2.1 根目录

- `AGENTS.md`：项目级强制开发约束
- `.env.example`：基础设施与三端环境变量模板（无真实凭据）
- `.gitignore`：忽略 `target/`、`node_modules/`、`.venv/`、`.env`、`logs/` 等
- `README.md`：总启动/停止说明
- `docs/`、`tasks/`、`deploy/`

### 2.2 repopilot-server（Java 21 / Spring Boot 3.5.16）

- 模块化单体包结构：`common`（通用能力）+ `modules`（7 个业务模块边界，仅 `package-info.java`）
- 统一响应 `ApiResponse{code,message,data,errors,requestId,timestamp}` + `ErrorCode`
- 全局异常处理 `GlobalExceptionHandler`（业务异常 / 参数校验 / 404 / 405 / 未知异常）
- 请求追踪 `RequestIdFilter`（`X-Request-Id` 透传或生成，写入 MDC，响应回写）
- 健康检查 `GET /api/health`：真实探测 MySQL、RabbitMQ、Flyway，Redis 标记为预留
- Actuator `/actuator/health` 供运维探针使用
- MyBatis + Flyway + MySQL 数据源（Hikari 3s 快速失败）
- RabbitMQ 连接配置（不声明任何业务队列/交换机）
- `db/migration/V1__baseline.sql`：**空白迁移**，不建任何业务表
- `logback-spring.xml`：dev 环境彩色控制台 + 滚动文件日志，日志含 `requestId`
- 测试：`ApiResponseTest`、`RequestIdFilterTest`、`GlobalExceptionHandlerTest`、
  `HealthServiceTest`、`HealthControllerTest`（共 19 个）+ 集成测试 `RepoPilotServerApplicationIT`

### 2.3 repopilot-worker（Python 3.11+ / FastAPI）

- `app/main.py`：应用工厂 + 结构化日志初始化
- `app/config.py`：pydantic-settings 配置，支持 `.env`，密码字段不进 `repr`
- `app/common/logging.py`：JSON / console 双格式，`request_id` 上下文透传
- `app/api/`：路由、请求上下文中间件、统一错误响应
- `app/runner/`、`app/evaluator/`：目录与边界说明，**不含任何真实实现**
- 测试：`tests/test_health.py`、`tests/test_config.py`、`tests/test_logging.py`（14 个用例）

### 2.4 repopilot-web（Vue3 + TS + Vite）

- 基础后台布局：侧边菜单（未实现模块置灰并标注「规划中」）+ 顶部导航
- 空白工作台 `/dashboard`：通过真实 HTTP 调用展示后端健康状态
- Axios 实例 + 统一错误封装 `ApiError`、`getJson` 拆包
- Pinia：`app`（布局状态）、`health`（后端健康状态）
- Vue Router：`/dashboard` + 404
- Element Plus 全量引入 + 中文语言包
- `npm run build` 通过（`vue-tsc` 类型检查 + 生产构建）

### 2.5 deploy

- `docker-compose.yml`：MySQL 8 / RabbitMQ / MinIO / Redis，全部含健康检查、命名卷持久化、
  端口与镜像可通过环境变量覆盖，密码缺失时 compose 直接报错
- `README.md`：基础设施配置说明与排障

## 3. 明确的非目标（本阶段不做）

- 不创建任何业务数据库表
- 不实现 Agent / Trace / Evaluation / Annotation / Report 的业务接口
- 不实现真实 Agent Runner 与 Judge（`runner/`、`evaluator/` 仅目录与说明）
- 不做登录鉴权、不做前端业务页面、不造假统计数据
- 不引入 Redis 客户端（容器已编排，服务端未接入）

## 4. 实际执行的验证

| 验证项 | 命令 | 结果 |
| --- | --- | --- |
| Java 单元测试 | `./mvnw test` | 19 run / 0 failures / 0 errors |
| Python 测试 | `pytest` | 14 passed |
| 前端类型检查 + 构建 | `npm run build` | 通过（vue-tsc 无错误，1694 modules） |
| Compose 配置校验 | `docker compose --env-file .env -f deploy/docker-compose.yml config` | 通过（变量插值、健康检查、卷均正确） |
| 后端启动 + HTTP 实测 | `java -jar target/repopilot-server.jar --server.port=18080 --spring.flyway.enabled=false` | 启动成功（6.1s）；`/api/health` 200、`X-Request-Id` 已生成、统一结构与 UTC ISO 时间 |
| 健康检查「不造假」实测 | `curl /api/health`（无数据库/队列） | `status=DOWN`，mysql/rabbitmq 均 DOWN 且带真实错误摘要（已压成单行并截断 200 字符），flyway/redis 为 UNKNOWN |
| 统一错误结构实测 | `curl /api/not-exist` | HTTP 404，`{"code":"NOT_FOUND","message":"请求的资源不存在","requestId":"…","timestamp":"…"}` |
| Actuator 实测 | `curl /actuator/health` | HTTP 200，`status=DOWN`，liveness/readiness 分组正常，db/rabbit 明细可见 |
| 前端 dev server | `npm run dev` | 首页 HTTP 200，`/api` 代理规则已配置 |
| 基础设施实跑 + 集成测试 | 见 §6 | **未执行**（当前环境无法启动容器） |

## 5. 已知问题

1. **本机未能实跑容器**：当前开发环境禁止启动 Docker 守护进程（`sc.exe` / `wsl.exe` 被安全策略拦截，
   Docker Desktop 无法引导），因此「compose 真实启动」「Java 连接 MySQL/RabbitMQ」
   「Flyway 实际执行」三项只能由使用者在具备 Docker 的机器上按 README 步骤验证。
   代码与配置均已就绪，`docker compose config` 已通过；集成测试 `RepoPilotServerApplicationIT`
   会在基础设施就绪后自动执行这三项校验。
2. **前端 → 后端代理未端到端验证**：当前沙箱拦截了 Node 进程的出站 HTTP
   （TCP 可建立但不返回响应），Vite dev proxy 无法完成一次真实转发。
   已分别验证后端接口（curl）与前端启动/构建，代理配置为标准写法，本地环境可直接工作。
3. MinIO 健康检查使用镜像内置 `mc ready local`；若所用 MinIO 镜像不含 `mc`，
   需改为 HTTP 探活 `http://localhost:9000/minio/health/live`（见 `deploy/README.md`）。
4. Redis 仅在 Compose 中提供实例，Java 侧健康检查显式返回 `UNKNOWN`（预留，未接入）。
5. 启动日志中的 `No MyBatis mapper was found` 属预期：本阶段尚无任何 Mapper。

## 6. 待你执行的验收步骤

```bash
cp .env.example .env
docker compose --env-file .env -f deploy/docker-compose.yml up -d
docker compose --env-file .env -f deploy/docker-compose.yml ps     # 四个服务应为 healthy

cd repopilot-server
set -a && source ../.env && set +a
./mvnw test -Dtest=RepoPilotServerApplicationIT                    # 校验 MySQL / Flyway / RabbitMQ / 健康接口
./mvnw spring-boot:run                                             # 启动后访问 http://127.0.0.1:8080/api/health
```

## 7. 下一阶段依赖

TASK-002（Flyway 数据库结构、统一实体与 API 基础）需要：本任务基础设施稳定运行、
`/api/health` 返回 `UP`、`flyway_schema_history` 中存在 version=1 记录。
