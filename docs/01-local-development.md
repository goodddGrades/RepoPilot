# 本地开发环境

面向 TASK-001 的工程骨架，说明如何在本机把基础设施与三端服务跑起来。

## 1. 前置要求

| 组件 | 版本要求 | 校验命令 | 备注 |
| --- | --- | --- | --- |
| JDK | 21（LTS） | `java -version` | 必须是 21；Maven 以 `release 21` 编译 |
| Maven | 3.9+ | `./mvnw -v` | 推荐直接用仓库自带 `mvnw`，无需全局安装 |
| Python | 3.11+ | `python --version` | 建议用虚拟环境 |
| Node.js | 20.19+ / 22.12+ | `node -v` | Vite 7 的最低要求，实测 22.x 通过 |
| Docker | 24+ 且含 Compose v2 | `docker compose version` | 用于基础设施 |

## 2. 配置环境变量

```bash
cp .env.example .env
```

`.env` 已被 `.gitignore` 忽略，**不要提交**。关键变量：

| 变量 | 作用 | 默认值 |
| --- | --- | --- |
| `MYSQL_PORT` / `MYSQL_USER` / `MYSQL_PASSWORD` | 数据库端口与账号 | 13306 / repopilot / 模板值 |
| `RABBITMQ_*` | 消息队列连接 | 5672 / 15672 |
| `MINIO_*` | 对象存储地址与凭据 | 9000 / 9001 |
| `REDIS_PORT` / `REDIS_PASSWORD` | 分布式限流状态（TASK-014 起真实使用） | 16379 |
| `REPOPILOT_RATE_LIMIT_*` | 入口限流开关、命名空间、环境段、各策略容量与补充速率 | 见 `.env.example` |
| `WORKER_RATE_LIMIT_*` / `WORKER_JUDGE_RATE_LIMIT_*` | Worker 模型调用限流的故障模式、维度与额度 | 见 `repopilot-worker/.env.example` |
| `SERVER_PORT` | 后端端口 | 8080 |
| `WORKER_PORT` | Worker 端口 | 8000 |

Spring Boot 会按 `optional:file:./.env`、`optional:file:./../.env` 的顺序自动加载 `.env`
（作为 properties 文件），因此在 `repopilot-server/` 下直接 `./mvnw spring-boot:run`
也能读到同一份配置。

## 3. 启动顺序

### 3.1 基础设施

```bash
docker compose --env-file .env -f deploy/docker-compose.yml up -d
docker compose --env-file .env -f deploy/docker-compose.yml ps
```

等待四个服务状态变为 `healthy`（MySQL 首次初始化约 30~60s）。详见 `deploy/README.md`。

### 3.2 后端（8080）

```bash
cd repopilot-server
./mvnw spring-boot:run
```

启动后：

- 业务健康检查：<http://127.0.0.1:8080/api/health>
- 运维健康端点：<http://127.0.0.1:8080/actuator/health>

`/api/health` 每次请求都会真实探测 MySQL、RabbitMQ 与 Flyway 迁移状态：

```json
{
  "code": "OK",
  "data": {
    "status": "UP",
    "application": "repopilot-server",
    "version": "0.1.0-SNAPSHOT",
    "profile": "dev",
    "components": [
      { "name": "mysql", "status": "UP", "required": true, "detail": "MySQL 8.0.36, schema=repopilot", "latencyMs": 4 },
      { "name": "rabbitmq", "status": "UP", "required": true, "detail": "broker=3.13.7, vhost=/", "latencyMs": 9 },
      { "name": "flyway", "status": "UP", "required": true, "detail": "current=1, applied=1, pending=0", "latencyMs": 2 },
      { "name": "redis", "status": "UP", "required": false, "detail": "PING PONG, 用途=分布式限流, version=7.2.5", "latencyMs": 1 }
    ]
  },
  "requestId": "…",
  "timestamp": "2026-10-17T05:00:00.000Z"
}
```

### 3.3 Worker（8000）

```bash
cd repopilot-worker
python -m venv .venv
. .venv/Scripts/activate        # Windows；Linux/macOS: source .venv/bin/activate
pip install -r requirements-dev.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

- 健康检查：<http://127.0.0.1:8000/health>
- 接口文档：<http://127.0.0.1:8000/docs>

### 3.4 前端（5173）

```bash
cd repopilot-web
npm install
npm run dev
```

打开 <http://127.0.0.1:5173>，工作台页面会实时展示后端健康状态。
`/api` 请求由 Vite 代理到 `VITE_API_PROXY_TARGET`（默认 `http://127.0.0.1:8080`）。

> 若本机 8080 被占用：后端用 `SERVER_PORT=18080 ./mvnw spring-boot:run`，
> 并在 `repopilot-web/.env.development.local` 中设置
> `VITE_API_PROXY_TARGET=http://127.0.0.1:18080`（该文件已被 git 忽略）。

## 4. 测试

```bash
# 后端单元测试（不依赖基础设施）
cd repopilot-server && ./mvnw test

# 后端集成测试（需基础设施就绪，且已加载 .env）
set -a && source ../.env && set +a
./mvnw test -Dtest=RepoPilotServerApplicationIT

# Worker
cd repopilot-worker && pytest

# 前端类型检查 + 构建
cd repopilot-web && npm run build
```

集成测试在未设置 `MYSQL_PASSWORD` 时会自动 skip，不会伪装成通过。

## 5. 停止

```bash
# 应用：在各自终端 Ctrl+C（Spring Boot 已开启 graceful shutdown）

# 基础设施：保留数据
docker compose --env-file .env -f deploy/docker-compose.yml down

# 基础设施：连数据一起清除
docker compose --env-file .env -f deploy/docker-compose.yml down -v
```

## 6. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| 启动报 `Could not resolve placeholder 'MYSQL_PASSWORD'` | 没有创建 `.env`，或不在仓库根/模块目录下启动；按 §2 复制模板 |
| `/api/health` 返回 `DOWN` 且 mysql 组件报连接失败 | 基础设施未启动、端口不一致、或密码被改过导致 MySQL 初始化账号不匹配（需 `down -v` 重建） |
| Flyway 报 `Detected failed migration` | 本地库处于失败状态；开发环境可 `down -v` 重置后重试 |
| 前端页面显示「后端不可达」 | 后端未启动或端口不符；检查 `VITE_API_PROXY_TARGET` |
| 端口 3306 / 6379 被本机服务占用 | 本项目默认用 13306 / 16379，一般不会冲突；若仍冲突改 `.env` |
| `npm run build` 报类型错误 | 确认 Node 版本 ≥ 20.19；删除 `node_modules` 后重装 |
