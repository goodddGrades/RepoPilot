# 本地 RabbitMQ 可用性核查与连通验证

日期：2026-10-18　分支：`develop`　核查基准 HEAD：`3b63092`（核查过程**未修改任何代码**，本报告是唯一新增文件）

## 1. 结论

本地已跑起**真实 RabbitMQ 3.13.7**（`rabbitmq:3.13.7-management`），Java Spring Boot 可正常连接，
并完成「发布确认 → 消费 → 手动 ACK」闭环；项目自身完整验收 `./mvnw verify` 通过
（单元 38 / 集成 38，**Skipped 0**，含 readiness 全依赖 UP 用例）。

运行方式是 **Docker Desktop + `deploy/docker-compose.yml`**，与仓库契约一致（端口、账号、镜像版本均取自 `.env`）。

## 2. 环境核查结果

| 项目 | 实测结果 |
| --- | --- |
| 操作系统 | Windows 10 Pro 19045（x64），Git Bash / MSYS 作为执行 shell |
| RabbitMQ 安装 | **未安装**（无 `rabbitmq-server`/`rabbitmqctl`/`erl`，无 `Program Files\RabbitMQ Server`、无 Erlang） |
| 5672 / 15672 | 核查时**均未监听**；启动容器后 `0.0.0.0:5672`、`0.0.0.0:15672` 正常监听 |
| Docker Desktop | 已安装（Docker 29.4.3 / Compose v5.1.3）；**引擎初始未启动**，需由用户在桌面端手动启动（Agent 沙箱禁止调用 `wsl.exe`） |
| 项目连接配置 | `application-dev.yml`：host/port/user/password/vhost 全部来自 `RABBITMQ_*`；`publisher-confirm-type=correlated`、`publisher-returns=true`；V1 不声明任何交换机/队列 |
| 后端依赖 | `spring-boot-starter-amqp`（Spring Boot 3.5.16） |
| JDK | 系统默认 **Java 18**，项目要求 **21**；本机已有 `C:\Users\15334\.jdks\ms-21.0.10`（Microsoft OpenJDK 21.0.10），运行时需显式指定 `JAVA_HOME` |

## 3. 实际运行方式

```bash
# 1) 由用户在桌面端启动 Docker Desktop（Agent 沙箱无法代启，wsl.exe 被安全策略拦截）
# 2) 仓库根目录启动 broker
docker compose --env-file .env -f deploy/docker-compose.yml up -d rabbitmq
docker compose --env-file .env -f deploy/docker-compose.yml ps
```

实测状态：

```
repopilot-rabbitmq  rabbitmq:3.13.7-management  Up (healthy)  0.0.0.0:5672->5672, 0.0.0.0:15672->15672
restart=unless-stopped   volume=repopilot-rabbitmq-data   network=repopilot-net
```

- Broker：**RabbitMQ 3.13.7**，**Erlang/OTP 26 [erts-14.2.5.12]**
- 监听：`5672` AMQP、`15672` HTTP API、`15692` Prometheus、`25672` 集群
- 账号/vhost：`repopilot`（administrator）/ `/`，均来自 `.env`，未硬编码

## 4. Java 连接与收发验证

### 4.1 项目自身：完整验收

```bash
cd repopilot-server
# JAVA_HOME=JDK21；REPOPILOT_IT_DB_* 指向本机 13306 测试实例；
# SPRING_RABBITMQ_* 指向 127.0.0.1:5672；REPOPILOT_IT_REQUIRE_ALL_UP=true
./mvnw -B verify
```

结果：`Tests run: 38`（Surefire）＋`Tests run: 38`（Failsafe），`Failures 0 / Errors 0 / Skipped 0`，**BUILD SUCCESS**。
其中 `ApiHealthIT` 4/4 通过，包含 `REPOPILOT_IT_REQUIRE_ALL_UP=true` 才执行的
`readinessShouldBeUpWhenEveryDependencyIsAvailable`——即 Spring Boot 通过仓库配置真实连上了 MySQL 与 RabbitMQ。

**这里踩过一个坑，值得留档**：若在 Git Bash 中把 `SPRING_RABBITMQ_VIRTUAL_HOST` 显式设为 `/`，
该值会被 MSYS 转成 Windows 路径，Spring 去连名为 `D:/WorkBuddy/.../PortableGit/` 的 vhost，
`readiness` 返回 503 并报 `530 NOT_ALLOWED`——而其余 37 条测试全部通过。
**表现像 broker 故障，实际是路径转换**。不设该变量即可（Spring Boot 默认 vhost 就是 `/`）。

### 4.2 最小收发闭环（临时工程，不在仓库内）

工程位置 `D:\tools\rmq-smoke`（独立 Maven 工程，`spring-boot-starter-amqp`），队列名随机、非持久化、用完即删，
**未声明任何业务交换机/队列**（符合 TASK-011 边界）。结果 5/5 通过：

```
[SMOKE] connected to RabbitMQ 3.13.7 vhost=/ localPort=56419
[SMOKE] declared temp queue repopilot.smoke.1792336014107
[SMOKE] publish smoke-1 ack=true
[SMOKE] publish smoke-2 ack=true
[SMOKE] publish smoke-3 ack=true
[SMOKE] ready messages after publish = 3
[SMOKE] first delivery tag=1 held WITHOUT ack; ready=2 (3 published)
[SMOKE] basicAck sent for tag=1
[SMOKE] after all acks -> ready=0
[SMOKE] received=[smoke-1, smoke-2, smoke-3]
[SMOKE] deleted temp queue repopilot.smoke.1792336014107
```

验证点：`publisher confirm` 逐条 ack=true；消费端 `AcknowledgeMode.MANUAL` + `prefetch=1`；
第一条故意不 ack 时 ready 由 3 降到 2（证明进入 unacked）；ack 后 ready 归零且停止消费者不再回队（证明 ACK 生效）。

验证后 broker 拓扑为空（无用户队列，无自建交换机），未留下残留。

## 5. 过程中发现的环境问题（均有实际复现）

1. **MSYS 路径转换会破坏 `RABBITMQ_VHOST=/`**：在 Git Bash 中 `source .env` 后，`/` 被转换成
   MSYS 根路径，Spring 会去连名为 `D:/WorkBuddy/resources/vendor/PortableGit/` 的 vhost 并报
   `reply-code=530 NOT_ALLOWED`。本地跑后端/测试时应 `unset RABBITMQ_VHOST`（yml 默认值即 `/`）。
   Ubuntu CI 不受影响。
2. **管理 API 有统计预热期**：队列刚创建时 `/api/queues/{vhost}/{name}` 返回精简对象
   （无 `messages_ready` 等字段），约 1–2 秒后才有完整统计；带 `?columns=` 可规避。
3. **Java HttpClient 处理 `%2F` + query 不稳**：同一 URL 加 query 后返回 `[]`，curl 正常。
   最终改用纯 AMQP 断言（`RabbitAdmin.getQueueInfo` 的 ready 计数）表达 ACK 语义，不依赖管理 API。
4. **JDK 版本**：系统 `JAVA_HOME` 指向 jdk-18，`./mvnw` 必须显式使用 JDK 21。
5. **本机测试 MySQL（13306）不自启动**：上一轮会话结束后实例已退出，`tools/it-mysql.sh start`
   启动的进程会随启动它的 shell 退出而被回收；本次用常驻后台方式启动才稳定。

## 6. 遗留问题与注意

- **Docker Desktop 必须保持运行**，否则容器与端口消失；容器 `restart=unless-stopped`，但引擎本身不随开机自启。
- **未走免安装路线**：GitHub 直连不通，`ghproxy`/`gh-proxy` 镜像对 Erlang 165MB 包限速到 15–90KB/s，
  且分块数据出现 CRC 不一致；`D:\tools\jdk21`、`D:\tools\rabbitmq-local` 下残留未完成的 Erlang/RabbitMQ zip
  与分块（可整目录删除，不影响现有运行方式）。
- 本次未启动常驻后端进程，`/api/health` 的 RabbitMQ UP 证据来自 `ApiHealthIT` 的真实 HTTP 断言
  （随机端口起真实上下文 + RabbitMQ readiness 组）。
- 未 push、未改动业务代码、未新增迁移与队列；仅新增本报告文件。
