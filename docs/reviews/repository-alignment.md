# TASK-001 仓库现状核对报告（设计包对齐）

日期：2026-10-17
对应用户指令：设计包 `tasks/TASK-001.md`——"核对本地现有工程，确定真实目录、依赖、构建命令和迁移历史"。
结论：**仓库已初始化且 TASK-001 已交付，本次不重复初始化，只做核对与差异登记。**

## 1. Git 现状

| 项 | 值 |
| --- | --- |
| 分支 | `main` |
| HEAD | `e2bc69f docs: 补充本轮实跑结论与已知问题` |
| 输入区 | `MANIFEST.sha256`、`RepoPilot-design-v1.0/`、`docs/*`、`examples/`、`tools/` 为本次并入的设计包内容（见 `design-merge-report.md`） |
| 用户文件 | `git_address.txt`、`task02.txt` 未改动、未提交 |

未执行 `git reset`、`git clean` 或任何历史改写；不删除用户内容。

## 2. 实际版本（读取工程文件与运行时，非推测）

| 组件 | 版本 | 证据来源 |
| --- | --- | --- |
| JDK | 21.0.10 LTS | `java -version` |
| Maven | 3.9.9（仓库自带 `./mvnw`） | `./mvnw -v` |
| Spring Boot | 3.5.16 | `repopilot-server/pom.xml` |
| MyBatis starter | 3.0.5 | `pom.xml` |
| Flyway | 11.7.2（含 `flyway-mysql`） | `pom.xml` |
| MySQL 驱动 | 9.7.0 | `pom.xml` |
| MySQL 服务 | 8.0.36（Compose） | `deploy/docker-compose.yml` |
| RabbitMQ | 3.13.7（Compose） | 同上 |
| Redis | 7.2.5（Compose，仅预留） | 同上 |
| MinIO | RELEASE.2025-04-22（Compose） | 同上 |
| Node.js / npm | v22.22.2 / 10.x | `node -v` |
| Vue / Vite / TS | 3.5.44 / 7.3.7 / 5.9.3 | `repopilot-web/package.json` |
| Element Plus / Pinia / Router | 2.14.7 / 3.0.4 / 4.6.4 | 同上 |
| Python | 3.13.14（工程要求 3.11+） | `python --version`、`.venv` |
| FastAPI / pydantic | 0.143.3 / 2.14.1 | `requirements.lock.txt` |

## 3. 实际目录

```
repopilot-server/   Java 模块化单体（common + 7 个 modules 边界，仅 package-info）
repopilot-worker/   FastAPI（api/ common/ runner/ evaluator/，无真实 Runner/Judge）
repopilot-web/      Vue3（api/ config/ layouts/ router/ stores/ styles/ types/ views/）
deploy/             docker-compose.yml + README
docs/               设计基线（architecture/ modules/ contracts/ reviews/）
examples/           样例 JSON/JSONL（无真实密钥）
tasks/              TASK-001~012
tools/              verify_design.py + requirements-design.txt
```

`AGENTS.md`、`.env.example`、`.gitignore`、`.gitattributes`、`README.md` 位于根目录。

## 4. 构建与测试命令（实际执行）

| 命令 | 执行目录 | 结果 |
| --- | --- | --- |
| `./mvnw test` | `repopilot-server` | Tests run: 19, Failures: 0, Errors: 0 |
| `./mvnw -DskipTests package` | `repopilot-server` | BUILD SUCCESS，产出 `target/repopilot-server.jar` |
| `pytest` | `repopilot-worker` | 14 passed |
| `npm run build` | `repopilot-web` | 通过（vue-tsc 无错误，1694 modules） |
| `docker compose --env-file .env -f deploy/docker-compose.yml config` | 仓库根 | 通过（变量插值、健康检查、命名卷均正确） |
| `java -jar target/repopilot-server.jar --server.port=18080 --spring.flyway.enabled=false` + `curl /api/health` | `repopilot-server` | 启动 6.1s；`status=DOWN`（mysql/rabbitmq 如实 DOWN，未伪造 UP） |
| `python tools/verify_design.py` | 仓库根 | PASS，866 项检查 |

## 5. 数据库与迁移历史

- 现有迁移脚本：`repopilot-server/src/main/resources/db/migration/V1__baseline.sql`，**仅有 `SET NAMES utf8mb4`，零业务表**。
- 最高版本：**1**（空白基线）。
- `flyway_schema_history` 实际内容：**未获取**。本机 Docker 守护进程被安全策略拦截（`sc.exe` / `wsl.exe` 无法引导 Docker Desktop），无法启动 MySQL；不以任何方式推测已有版本记录。
- 影响：TASK-002 的迁移编号应从 **V2** 起；"空库完整迁移"与"已有版本增量迁移"两类验收必须由具备 Docker 的环境执行，见 §7。

## 6. 与本设计包的偏差

| # | 偏差 | 说明 | 是否需修正 |
| --- | --- | --- | --- |
| 1 | 公共响应用 `errors`，设计用 `details` | 已实现一侧为 TASK-001 产物 | TASK-002 统一 |
| 2 | 错误码命名不同（`NOT_FOUND`/`BUSINESS_ERROR`/`DEPENDENCY_UNAVAILABLE`） | 设计为 `RESOURCE_NOT_FOUND`/细分 409/`SERVICE_UNAVAILABLE` | TASK-002 统一 |
| 3 | 信封含 `timestamp`，设计无 | 前端已消费 | 待负责人决定 |
| 4 | requestId 用 UUID，设计要求 ULID | 缺 ULID 生成器 | TASK-002 提供 `IdGenerator` |
| 5 | 模块 7 个（含 `report`），设计 8 个（含 `evaluationconfig`、`importing`） | 当前仅 `package-info` 占位，无代码依赖 | TASK-002 调整 |
| 6 | RabbitMQ 已在 Compose 且参与健康检查 | 设计定位为 TASK-011 才启用；用户 TASK-001 明确要求 | 保留，但业务不声明交换机/队列 |
| 7 | Redis 已在 Compose（预留） | 设计"不默认引入"；用户 TASK-001 明确要求 | 保留容器，无客户端 |
| 8 | 无分页组件、无 Idempotency-Key | 设计 04 契约要求 | TASK-002 |
| 9 | 集成测试直连主库 `repopilot` | 设计要求独立测试库/Testcontainers | TASK-002 |
| 10 | 文档曾有两套规划（`docs/README.md` 旧表 vs 设计包目录） | 已重写索引，废止旧表 | 已修正 |

逐项证据与处置建议见 [`design-merge-report.md`](./design-merge-report.md)。

## 7. 未执行项与环境阻塞（如实登记）

1. **基础设施实跑**：Docker 守护进程无法启动，`docker compose up` 未执行；MySQL/RabbitMQ/MinIO/Redis 均未真实运行。
2. **Java 连接 MySQL / RabbitMQ**：因 1 未执行；代码路径已就绪，`RepoPilotServerApplicationIT` 可在基础设施就绪后验证。
3. **Flyway 真实执行**：迁移脚本未在真实 MySQL 跑过；`V1` 为空白迁移，预期只写 `flyway_schema_history` 一条记录。
4. **前端 Vite 代理端到端**：沙箱拦截 Node 出站 HTTP，代理转发未完成一次真实往返；后端接口与前端构建已分别验证。
5. **真实 Codex Runner / LiteLLM 日志**：本阶段不涉及，未核实入口与字段，设计包亦未承诺。

## 8. 核对结论

- 工程已初始化，构建、单测、前端构建、设计自检均通过，**无需重建任何目录**。
- 数据库侧只有空白基线，TASK-002 从 V2 开始新增迁移，不与已执行版本冲突。
- 设计包落地为 `docs/` 唯一基线后，本仓库与设计的主要差异集中在**公共 API 契约**与**模块划分**，均已登记为 TASK-002 范围，未提前实现。
