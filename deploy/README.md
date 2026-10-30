# 本地基础设施（Docker Compose）

本目录只负责 RepoPilot 的本地依赖组件，不包含任何应用容器。
应用（server / worker / web）在宿主机上以开发模式运行，便于热更新与调试。

## 1. 启动与停止

所有命令都在**仓库根目录**执行，并通过 `--env-file` 指向根目录 `.env`：

```bash
cp .env.example .env                                                   # 首次执行
docker compose --env-file .env -f deploy/docker-compose.yml up -d
docker compose --env-file .env -f deploy/docker-compose.yml ps         # 观察 health 状态
docker compose --env-file .env -f deploy/docker-compose.yml logs -f mysql
docker compose --env-file .env -f deploy/docker-compose.yml stop       # 停止，保留容器与数据
docker compose --env-file .env -f deploy/docker-compose.yml down       # 删除容器，保留数据卷
docker compose --env-file .env -f deploy/docker-compose.yml down -v    # 连数据一起删除（谨慎）
```

## 2. 组件与端口

| 组件 | 镜像（默认） | 宿主机端口 | 容器端口 | 用途 |
| --- | --- | --- | --- | --- |
| MySQL | `mysql:8.0.36` | `${MYSQL_PORT:-13306}` | 3306 | 业务库 `repopilot`，UTC 时区，utf8mb4 |
| RabbitMQ | `rabbitmq:3.13.7-management` | `${RABBITMQ_PORT:-5672}` / `${RABBITMQ_MANAGEMENT_PORT:-15672}` | 5672 / 15672 | 任务调度消息（V1 仅连通性） |
| MinIO | `minio/minio:RELEASE.2025-04-22T22-12-26Z` | `${MINIO_API_PORT:-9000}` / `${MINIO_CONSOLE_PORT:-9001}` | 9000 / 9001 | 轨迹附件、日志、报告对象存储 |
| Redis | `redis:7.2.5-alpine` | `${REDIS_PORT:-16379}` | 6379 | **TASK-014 起承载分布式限流的令牌桶状态**（Java 与 Python 共用；不存业务数据） |

宿主机端口刻意避开 3306 / 6379 等常见默认端口，避免与本机已安装的 MySQL / Redis 冲突。
所有端口都可以在 `.env` 中覆盖；如果改了端口，请同步修改 `.env` 里的
`MYSQL_PORT` / `REDIS_PORT`，后端与 Worker 会读取同名变量。

镜像名同样可覆盖（适合内网 registry 或镜像站）：

```
MYSQL_IMAGE=registry.example.com/mysql:8.0.36
RABBITMQ_IMAGE=registry.example.com/rabbitmq:3.13.7-management
MINIO_IMAGE=registry.example.com/minio/minio:RELEASE.2025-04-22T22-12-26Z
REDIS_IMAGE=registry.example.com/redis:7.2.5-alpine
```

## 3. 凭据与安全

- 密码**只**来自 `.env`（已被 `.gitignore` 忽略），仓库中只有 `.env.example` 模板。
- compose 使用 `${VAR:?message}` 语法：关键变量缺失时立即失败并提示，不会以空密码启动。
- 默认密码仅用于本地开发，**禁止**用于任何共享或生产环境。
- 修改 `.env` 中的密码后若要生效，需要 `down -v` 重建数据卷（MySQL/MinIO 的初始账号只在首次初始化时创建）。

## 4. 健康检查

| 组件 | 检查方式 | 说明 |
| --- | --- | --- |
| MySQL | `mysqladmin ping -u${MYSQL_USER} -p***` | 业务账号可登录即视为就绪，`start_period` 40s 覆盖初始化耗时 |
| RabbitMQ | `rabbitmq-diagnostics -q ping` | 官方推荐的存活检查 |
| MinIO | `mc ready local` | 依赖镜像内置的 `mc` 客户端；**尚未在本机实跑验证**（Docker 守护进程不可用） |
| Redis | `redis-cli -a *** ping` | 带认证的 PING |

**MinIO 健康检查不确定项**：`mc` 是否存在于镜像内，必须实测，不能假设。启动容器后先执行：

```bash
docker compose --env-file .env -f deploy/docker-compose.yml exec minio mc --version
# 若报 "executable file not found"，说明该镜像不含 mc，改用 HTTP 探活（见下）
```

健康检查已支持用环境变量覆盖，无需改 `docker-compose.yml`：

```bash
# 方式一：镜像含 curl
MINIO_HEALTHCHECK="curl -f http://127.0.0.1:9000/minio/health/live"
# 方式二：镜像含 wget
MINIO_HEALTHCHECK="wget -q -O- http://127.0.0.1:9000/minio/health/live"
```

写入 `.env` 后 `docker compose up -d` 即可生效。两种方式都不可用时（如 distroless 镜像），
删掉该 healthcheck，改用外部探测，并把 `restart` 策略与日志告警作为兜底——不要伪造一个恒为 healthy 的探针。

## 5. 数据持久化

使用命名卷，`down` 不会丢数据：

| 卷名 | 组件 | 容器路径 |
| --- | --- | --- |
| `repopilot-mysql-data` | MySQL | `/var/lib/mysql` |
| `repopilot-rabbitmq-data` | RabbitMQ | `/var/lib/rabbitmq` |
| `repopilot-minio-data` | MinIO | `/data` |
| `repopilot-redis-data` | Redis | `/data` |

完全重置本地环境：

```bash
docker compose --env-file .env -f deploy/docker-compose.yml down -v
docker compose --env-file .env -f deploy/docker-compose.yml up -d
```

## 6. 常用地址

- MySQL：`mysql -h 127.0.0.1 -P 13306 -u repopilot -p repopilot`
- RabbitMQ 管理台：<http://127.0.0.1:15672>（账号见 `.env`）
- MinIO 控制台：<http://127.0.0.1:9001>
- Redis：`redis-cli -h 127.0.0.1 -p 16379 -a <password>`

## 7. 排障

| 现象 | 排查方向 |
| --- | --- |
| 端口被占用 | 改 `.env` 中对应 `*_PORT`；Linux 用 `ss -lntp`、Windows 用 `netstat -ano` 查看占用 |
| MySQL 一直 `unhealthy` | 首次初始化耗时较长，等 40s 以上；密码含特殊字符时确认没有被 `.env` 解析截断 |
| 改了密码连不上 | 初始化阶段才写入账号，需 `down -v` 重建卷 |
| 拉取镜像失败 | 配置 Docker 镜像加速，或用 `*_IMAGE` 覆盖为内网地址 |
| MinIO 控制台打不开 | 确认 9001 端口映射未被占用，控制台地址由 `--console-address` 指定 |
