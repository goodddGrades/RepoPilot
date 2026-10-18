# TASK-001 · Monorepo、Docker Compose 与三端基础工程

- 状态：**代码层已完成并通过最终验收；基础设施实跑待有 Docker 的环境补做**（2026-10-17，
  验收报告见 [`docs/reviews/task-001-final-acceptance.md`](../docs/reviews/task-001-final-acceptance.md)）
- 上游依据：设计基线 `docs/`（`00-product-scope.md` 交付阶段 + `architecture/01-system-overview.md` 工程组织）；
  早期草案 `project_design.md` 第 12 章仅作追溯
- 范围：只搭建基础工程，**不实现任何业务功能**

## 0. 仓库对齐核对（设计包 V1.0 要求）

设计包 `tasks/TASK-001.md` 把 TASK-001 定义为"工程现状核对与基础初始化"，交付物为对齐报告。
本仓库该核对已执行，报告见：

- [`docs/reviews/repository-alignment.md`](../docs/reviews/repository-alignment.md)：实际目录、版本、命令、Flyway 历史、与设计包的 10 项偏差
- [`docs/reviews/design-merge-report.md`](../docs/reviews/design-merge-report.md)：设计包并入时的文件冲突、文档去重、契约差异清单

核对结论：**工程已初始化，不重复初始化**；数据库仅有 V1 空白基线，TASK-002 从 V2 起新增迁移。
设计包的"现状核对"语义已由上述报告吸收，不再单独保留一份同编号任务文档。

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
| Java 单元测试 | `./mvnw test` | 19 run / 0 failures / 0 errors（全部单元测试） |
| Java 集成测试 | `./mvnw test -Dtest=RepoPilotServerApplicationIT`（无 `MYSQL_PASSWORD`） | 4 run / **Skipped: 4**（未 Mock 顶替） |
| Python 测试 | `pytest` | 14 passed |
| 前端类型检查 + 构建 | `npm run build` | 通过（vue-tsc 无错误；主 chunk 1098.89 kB / gzip 365.02 kB） |
| Compose 配置校验 | `docker compose --env-file .env -f deploy/docker-compose.yml config` | 通过（变量插值、健康检查、卷均正确；MinIO 探活可覆盖） |
| 后端启动 + HTTP 实测 | `java -jar target/repopilot-server.jar --server.port=18080 --spring.flyway.enabled=false` | 启动成功；`/api/health` 200、`message=success`、`X-Request-Id` 已生成、UTC ISO 时间 |
| 健康检查「不造假」实测 | `curl /api/health`（无数据库/队列） | `status=DOWN`，mysql/rabbitmq 均 DOWN 且带真实错误摘要（单行、截断 200 字符），flyway/redis 为 UNKNOWN |
| 统一错误结构实测 | `curl /api/not-exist` | HTTP 404，`{"code":"NOT_FOUND","message":"请求的资源不存在","requestId":"…","timestamp":"…"}` |
| 探针语义实测 | `curl /actuator/health/liveness`、`/readiness` | liveness **200 UP**（依赖故障不影响）；readiness **503 DOWN**（db/rabbit 明细） |
| requestId 贯通日志 | 用响应体 requestId 检索 `logs/repopilot-server.log` | 命中同一 ID 的 WARN 行，MDC 生效 |
| 前端 dev server + 代理联调 | `npm run dev` 后 `curl :5199/api/health`、`/api/not-exist` | 首页 200；**经代理拿到真实后端响应**；404 为统一结构 |
| 基础设施实跑 | 见 §6 | **未执行**（Docker 守护进程被环境策略阻断） |

## 5. 已知问题

1. **本机未能实跑容器（唯一未完成项）**：`wsl.exe` 被系统安全策略列入程序黑名单（非权限问题，
   明确禁止绕过），Docker Desktop 无法引导。因此「compose 真实启动」「Java 连接 MySQL/RabbitMQ」
   「Flyway 实际执行」「MinIO/RabbitMQ 健康检查」只能由使用者在具备 Docker 的机器上按 README 验证。
   `docker compose config` 已通过，集成测试 `RepoPilotServerApplicationIT` 就绪后可校验其中三项。
2. **探针缺陷已修复**：修复前 readiness 在 MySQL/RabbitMQ 全挂时仍返回 `UP`（默认分组不含依赖），
   已显式声明 `liveness=livenessState,ping` / `readiness=readinessState,db,rabbit`。
3. **前端 → 后端代理已端到端验证通过**（2026-10-17 复测），此前"沙箱拦截 Node 出站 HTTP"的限制解除。
4. MinIO 健康检查默认 `mc ready local`，可用 `MINIO_HEALTHCHECK` 覆盖为 HTTP 探活；
   镜像是否含 `mc` 必须 `docker compose exec minio mc --version` 实测（见 `deploy/README.md`）。
5. Redis 仅在 Compose 中提供实例，Java 侧健康检查显式返回 `UNKNOWN`（预留，未接入）。
6. 启动日志中的 `No MyBatis mapper was found` 属预期：本阶段尚无任何 Mapper。
7. `*IT` 未被 Surefire 默认选中，常规 `./mvnw test` 不含集成测试；建议 TASK-002 引入 Failsafe。


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

TASK-002（数据库领域模型、Flyway 与统一 API 基础）需要：

1. 本任务基础设施稳定运行、`/api/health` 返回 `UP`、`flyway_schema_history` 中存在 version=1 记录。
2. 先执行 [`docs/reviews/design-merge-report.md`](../docs/reviews/design-merge-report.md) §5 的 12 项契约对齐
   （`errors`→`details`、错误码重命名、ULID requestId、分页与幂等组件、模块划分 7→8），
   其中 §8 的 3 个待决项需项目负责人拍板。
3. 迁移从 **V2** 起新增，不修改 V1。
