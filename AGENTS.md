# AGENTS.md — RepoPilot 开发约束

本文件是**项目级强制约束**，任何参与本仓库开发的 Agent 或人类工程师都必须先阅读
`project_design.md`（总手册 V1.0）与本文件，再开始编码。

当前进度：**TASK-001 已完成**（Monorepo + 基础设施 + 三端基础工程）。TASK-002 及之后尚未开始。

---

## 1. 项目定位

RepoPilot 是面向 Agent 的**轨迹管理、自动化评测与质量分析平台**，不是模型训练平台，
也不是通用 Agent 开发框架。必须支撑两条评测路径，且**只允许数据入口不同**：

- 路径 A 在线评测：Agent Runner 执行 → 采集轨迹 → 统一评测引擎
- 路径 B 离线评测：导入规范化 JSON/JSONL → 标准化 → 统一评测引擎

两条路径共用：领域模型、Trace 存储、评测引擎、标注系统、实验报告。

---

## 2. 技术栈（不得擅自替换）

| 层次 | 技术 | 版本约束 |
| --- | --- | --- |
| 后端 | Java 21 + Spring Boot 3.x | Java 21，Spring Boot 3.5.16 |
| 持久化 | MyBatis + Flyway + MySQL 8 | mybatis-spring-boot-starter 3.0.5 |
| 消息 | RabbitMQ | 3.13.x（容器） |
| Worker | Python 3.11+ + FastAPI | Python 3.11+ |
| 前端 | Vue3 + TypeScript + Vite + Element Plus + Pinia + Vue Router + Axios | 见 `repopilot-web/package.json` |
| 基础设施 | MySQL 8 / RabbitMQ / MinIO / Redis（预留） | 见 `deploy/docker-compose.yml` |

明确**禁止**引入：微服务拆分、Spring Cloud、Kafka、ClickHouse、Elasticsearch、Kubernetes。

---

## 3. 架构约束

1. `repopilot-server` 是**模块化单体**：单进程、按 `com.repopilot.modules.<module>` 分包。
2. 模块之间只能通过 `application` 层服务接口协作；**禁止跨模块直接访问他人 Mapper 或表**。
3. `repopilot-worker` 负责 Agent 执行与评测算子；Java 侧不实现 Agent 执行逻辑。
4. 在线/离线评测共用一个 `EvaluationRun` 模型、一套 Evaluation/Annotation/Report 服务；
   **不得为两条路径复制业务实现**。
5. 所有数据库变更必须通过新增 Flyway 迁移脚本（`db/migration/V*.sql`），已执行脚本不可修改。
6. 所有对外接口必须返回统一响应结构 `ApiResponse`，异常必须经 `GlobalExceptionHandler`。
7. 接口路径统一以 `/api` 为前缀，分页参数与响应结构保持全局一致。
8. 状态流转必须校验前置状态，禁止直接改写终态。

---

## 4. 目录职责

```
repopilot-server/   Java 后端（模块化单体）
repopilot-worker/   Python Worker（Runner / Evaluator）
repopilot-web/      Vue3 前端
deploy/             Docker Compose 及基础设施说明
docs/               开发文档（按 docs/README.md 的规划补充）
tasks/              分阶段任务说明与验收记录
```

---

## 5. 标准命令

```bash
# 基础设施
cp .env.example .env
docker compose --env-file .env -f deploy/docker-compose.yml up -d
docker compose --env-file .env -f deploy/docker-compose.yml ps
docker compose --env-file .env -f deploy/docker-compose.yml down

# 后端
cd repopilot-server
set -a && source ../.env && set +a          # Git Bash / WSL 载入环境变量
./mvnw test                                  # 单元测试（不依赖基础设施）
./mvnw spring-boot:run                       # 启动，默认 8080
./mvnw test -Dtest=RepoPilotServerApplicationIT   # 集成测试（需基础设施就绪）

# Worker
cd repopilot-worker
python -m venv .venv && . .venv/Scripts/activate   # Windows；Linux/macOS 用 .venv/bin/activate
pip install -r requirements-dev.txt
pytest
uvicorn app.main:app --port 8000

# 前端
cd repopilot-web
npm install
npm run dev        # 5173
npm run build      # 类型检查 + 生产构建
```

---

## 6. 开发要求

1. 开始任务前先读 `project_design.md` 中对应章节与本目录下 `tasks/TASK-XXX.md`。
2. **只实现当前分配的 TASK，不扩大范围**，不重构无关模块。
3. 核心逻辑必须写单元测试；跨服务能力写集成测试；交付时必须给出**实际执行过的**测试命令与结果。
4. 不允许用硬编码假数据冒充后端业务能力；Mock 数据与模拟评分必须显式标记。
5. 不允许吞异常、伪造成功结果、跳过状态校验。
6. 不允许把真实企业敏感数据用于测试。
7. 修改公共 API/消息格式需同步更新 `docs/05-api-contract.md`、`docs/06-event-contract.md`（待建）。

---

## 7. 禁止事项（红线）

- 不得擅自引入新框架或中间件。
- 不得在仓库中提交任何真实凭据；`.env` 已被忽略，只提交 `.env.example`。
- 不得把离线缺失的证据伪造成评测结果（缺证据应返回 `INDETERMINATE` / `NOT_APPLICABLE`）。
- 不得绕过状态机直接修改任务终态。
- 不得宣称未测试的功能已完成。
- 不得在 TASK-001 阶段创建业务表或业务接口。

---

## 8. 每次交付必须包含

本次实现的功能 · 修改的文件 · 数据库变更 · API/事件协议变更 · 测试命令与结果 · 已知问题 · 下一阶段依赖。
