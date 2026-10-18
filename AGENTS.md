# AGENTS.md — RepoPilot 开发约束

本文件是**项目级强制约束**，任何参与本仓库开发的 Agent 或人类工程师都必须先阅读本文件，
再按 [`docs/README.md`](./docs/README.md) 的阅读顺序加载对应契约。

当前进度：**TASK-001 有条件验收通过**（三端基础工程 + 公共组件 + 基础设施编排；真实 MySQL/RabbitMQ/Flyway 实跑转入 CI 验收）。
**TASK-002 文本已修订为可执行版本，尚未开始实现**；未经负责人确认不得进入编码。
TASK-003 及之后不得提前实现。

---

## 0. 权威顺序（冲突时按此裁决）

1. `docs/contracts/database-schema.json` — 数据库字段、索引、外键、CHECK 的**唯一来源**
2. `docs/architecture/*` — 跨模块契约（API、事件、可靠性、领域模型）
3. `docs/modules/*` — 模块内流程
4. `tasks/TASK-XXX.md` — 当次范围与验收
5. `AGENTS.md`（本文件） — 持续开发规则
6. `project_design.md` — **历史草案，无约束力**，仅供追溯

`tasks/TASK-XXX.md` 是**实施边界**，不是第二套架构规范。任务文本与 `docs/` 冲突时，
**不允许实施方自行选择**：停下冲突部分，报告差异与推荐方案，由负责人先改 `docs/`，
再改实现。影响契约的改动必须先改文档与差异说明，再改实现和测试，不能"代码先跑起来再说"。

---

## 1. 项目定位

RepoPilot 是面向 Agent 的**轨迹管理、自动化评测与质量分析平台**，不是模型训练平台，
也不是通用 Agent 开发框架。必须支撑两条评测路径，且**只允许数据入口不同**：

- 路径 A 在线评测：Agent Runner 执行 → 采集轨迹 → 统一评测引擎
- 路径 B 离线评测：导入规范化 JSON/JSONL → 标准化 → 统一评测引擎

两条路径共用：领域模型、Trace 存储、评测引擎、标注系统、实验报告。

---

## 2. 技术栈（不得擅自替换）

| 层次 | 技术 | 已锁定版本 |
| --- | --- | --- |
| 后端 | Java 21 + Spring Boot 3.x | Java 21，Spring Boot 3.5.16 |
| 持久化 | MyBatis + Flyway + MySQL 8 | mybatis-spring-boot-starter 3.0.5，Flyway 11.7.2，MySQL 8.0.36（CHECK 需 ≥ 8.0.16） |
| 消息 | RabbitMQ | 3.13.7（容器） |
| Worker | Python 3.11+ + FastAPI | FastAPI 0.143.3，pydantic 2.14.1 |
| 前端 | Vue3 + TypeScript + Vite + Element Plus + Pinia + Vue Router + Axios | 见 `repopilot-web/package.json` |
| 基础设施 | MySQL 8 / RabbitMQ / MinIO / Redis（预留） | 见 `deploy/docker-compose.yml` |

明确**禁止**引入：微服务拆分、Spring Cloud、Kafka、ClickHouse、Elasticsearch、Kubernetes。

**中间件启用时机**（避免误读）：

- MySQL、MinIO 是必要依赖。
- RabbitMQ 容器与连通性检查已就绪，但**业务发布/消费只在 TASK-011 实现**；在此之前不得声明交换机或队列。
- Redis 仅编排实例，Java 不引入客户端；`/api/health` 中固定 `UNKNOWN` 并标注"预留"。

---

## 3. 不可擅自改变的契约

1. Java 主后端拥有业务数据库写权限；Python 经内部 HTTP 协议领取、续租、回传，**不直连业务库**。
2. 在线与离线共用 Experiment、Run、EvaluationJob、Result；离线不要求 AgentVersion，但要求已发布的数据集样本。
3. **ULID 不是 OTel Trace ID**。所有业务时间为 UTC；技术状态与业务结论分离。
4. 评测必须经 `EvaluationBatch`：同一 Run 可在不同批次重评，同一批次内同一 Run×方案项只有一个 Job；
   重评复用已封口 Run 与 accepted Attempt，**不得重跑 Agent**，不得覆盖历史批次与结果。
5. 已发布版本内容不可原地修改；重试保留历史；已完成 Run 与最终结果不可覆盖。
6. 必须实现数据库主外键与唯一约束，**不能只写实体注解**。
7. 终态写入、轨迹写入、续租均校验当前 Attempt 与租约；过期 Worker 不得覆盖新任务。
8. 密钥通过运行环境中的引用解析；版本快照、Trace、错误日志不得持有明文密钥。
9. 缺失证据不得伪造结论，应返回 `INDETERMINATE` / `NOT_APPLICABLE`。

---

## 4. 架构约束

1. `repopilot-server` 是**模块化单体**：单进程、按 `com.repopilot.modules.<module>` 分包。
   模块划分以 `docs/architecture/01-system-overview.md` 为准（8 个：agent、dataset、evaluationconfig、
   experiment、trace、evaluation、annotation、importing）。`report` 不建空壳：报告是 experiment 的
   读取视图，在 TASK-010 需要时落地。
2. 模块之间只能通过 `application` 层服务接口协作；**禁止跨模块直接访问他人 Mapper 或表**。
3. Mapper 位于基础设施层，Controller 返回 DTO，事务由业务服务控制；不返回 Entity 作为 API DTO。
4. `repopilot-worker` 负责 Agent 执行与评测算子；Java 侧不实现 Agent 执行逻辑。
5. 在线/离线共用一套 Evaluation/Annotation/Report 服务；**不得为两条路径复制业务实现**。
6. 所有数据库变更必须通过新增 Flyway 迁移脚本，**已执行脚本不可修改**（当前最高版本为 V1 空白基线，下一版从 V2 起）。
7. 所有对外接口必须返回统一响应结构，异常必须经全局异常处理；接口路径业务前缀 `/api/v1`，
   Worker 前缀 `/internal/v1`，健康检查保留 `/api/health`。
8. 状态流转必须校验前置状态，禁止直接改写终态；重试是新 Attempt，不是把终态改回 RUNNING。
9. 分页、排序、幂等键遵循 `docs/architecture/04-api-contract.md`，不自创第二套格式。

---

## 5. 目录职责

```
repopilot-server/   Java 后端（模块化单体）
repopilot-worker/   Python Worker（Runner / Evaluator）
repopilot-web/      Vue3 前端
deploy/             Docker Compose 及基础设施说明
docs/               设计基线（唯一来源，见 docs/README.md）
tasks/              分阶段任务说明与验收记录
examples/           规范样例（不含真实密钥，不可直接用于生产）
tools/              设计自检工具（verify_design.py）
```

---

## 6. 标准命令

```bash
# 设计自检
python -m venv .tools-venv && ./.tools-venv/Scripts/pip install -r tools/requirements-design.txt
python tools/verify_design.py

# 基础设施
cp .env.example .env
docker compose --env-file .env -f deploy/docker-compose.yml up -d
docker compose --env-file .env -f deploy/docker-compose.yml ps
docker compose --env-file .env -f deploy/docker-compose.yml down

# 后端
cd repopilot-server
set -a && source ../.env && set +a          # Git Bash / WSL 载入环境变量
./mvnw test                                  # 单元测试（不依赖基础设施）
./mvnw verify                                # 单元 + 集成（Failsafe，需基础设施）
./mvnw spring-boot:run                       # 启动，默认 8080

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

## 7. 工作方式

1. 开工先 `git status --short`，记录分支与已有改动；**不撤销用户工作**。
2. 读 `docs/README.md` → 当前 TASK → 其 Required reads → 对应契约。不凭记忆写字段。
3. 检查已有构建文件、锁文件、目录与 Flyway 历史。设计没有声明仓库已使用某版本时，以仓库实际锁定版本为准。
4. 仅实现当前 TASK，不提前实现后续业务，不重构无关模块。
5. 普通实现细节自行决定并记录；影响数据库语义、跨语言契约或后续 TASK 的改动，先写具体差异与推荐方案，
   继续不受影响的工作，等负责人决定。**不能以"优化"为由引入新中间件或改变架构。**

---

## 8. 测试与交付

- 使用**真实 MySQL 8** 做迁移、约束与 UTC 映射测试，不以 H2 结果替代；无法运行 Docker 时如实记录阻塞。
- 后端完整验收使用 `./mvnw verify`（Surefire 单测 + Failsafe 集成）；`./mvnw test` 不代表集成通过。
  CI 未实际运行时必须写"未运行"，不得用"workflow 已配置"代替"通过"。
- 测试使用独立库与测试凭证；禁止在生产启用 Flyway Clean，禁止自动删库重建。
- 不添加公开假 API 来测试公共组件，用测试 Controller / MockMvc。
- 不用随意延时掩盖竞争条件，用明确同步点测试租约与并发。
- 不吞异常、不伪造成功结果、不跳过状态校验。
- 每次交付必须给出：分支与 Commit ID · 变更文件与行为 · 数据库/API/事件契约变化 ·
  **实际执行命令、退出码、通过/失败/跳过数量** · 未执行检查及环境原因 · 已知问题与下一 TASK 前置。
- 不虚构压测收益，不把设计目标写成已完成成果。
- 已完成授权的任务可创建本地 Git commit；**不自动 push、部署或发送外部消息**。
- 任务完成即停，不自动进入下一 TASK。

---

## 9. 红线

- 不得擅自引入新框架、中间件或数据库。
- 不得提交任何真实凭据；`.env` 已忽略，只提交 `.env.example`（仅占位符）。
- 不得修改已执行的 Flyway 迁移；不得用 `repair` 掩盖半完成变更。
- 不得把离线缺失的证据伪造成评测结果。
- 不得绕过状态机直接修改终态。
- 不得宣称未测试的功能已完成。
- 不得在 TASK-001 阶段创建业务表或业务接口（当前已交付，保持为零业务表）。
