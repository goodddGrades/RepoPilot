# TASK-001 最终验收报告

日期：2026-10-17
范围：TASK-001 最终验收与确定性问题修复。**未开发 TASK-002，未创建任何业务表，未新增业务接口。**
结论：**代码层面验收通过；基础设施实跑仍未通过环境关卡（非代码问题），因此 TASK-001 判为"有条件通过"。**

## 1. 结论摘要

| 维度 | 状态 |
| --- | --- |
| 公共组件（响应/异常/追踪） | 通过（复用现有实现，无重复开发） |
| 健康检查语义（liveness/readiness） | **发现缺陷并已修复**（修复前 readiness 在依赖全挂时仍报 UP） |
| 前端 → 后端联调 | **本轮已端到端验证通过**（此前被沙箱拦截，现已复测） |
| 单元测试 / Python / 前端构建 | 全部通过 |
| 数据库集成测试 | 4 项**跳过**（未伪装通过） |
| 基础设施实跑（Compose/MySQL/Rabbit/Flyway/MinIO） | **未验证**，环境硬性限制 |
| 敏感信息 | 通过（`.env` 从未入库，无密钥） |

## 2. 已通过项（附实测证据）

### 2.1 前端经 Vite Proxy 调用 Java（此前未完成的联调）

本轮 Node 出站 HTTP 不再被拦截，已补做端到端验证：

| 请求 | 结果 |
| --- | --- |
| `GET http://127.0.0.1:5199/` | HTTP 200 |
| `GET http://127.0.0.1:5199/api/health`（经代理到 18080） | HTTP 200，返回真实 `ApiResponse`，含 mysql/rabbitmq 的真实 DOWN 明细 |
| `GET http://127.0.0.1:5199/api/not-exist` | HTTP 404，统一结构 `{"code":"NOT_FOUND",...,"requestId":"acc8cc0a-...","timestamp":"..."}` |

结论：Vite 代理配置正确，前端能真实取到后端业务响应；此前"无法端到端验证"的限制**解除**。

### 2.2 Request ID 写入 MDC 与日志

404 响应体中的 `requestId` 与服务端日志完全对应（同一次请求）：

```
2026-10-17T14:07:50.222+08:00 WARN [http-nio-18080-exec-7] [acc8cc0a-0620-4604-bbeb-890390f463ec]
  c.r.common.web.GlobalExceptionHandler - 资源不存在: api/not-exist
```

同时 `/api/health` 的日志行也带 `[e3bbfd97-...]` 上下文。日志清理在 `finally` 中执行，异常路径同样覆盖（`RequestIdFilterTest` 已覆盖）。

### 2.3 错误响应不泄露堆栈

`server.error.include-message/include-stacktrace = never`，`GlobalExceptionHandler` 对未知异常固定返回 `INTERNAL_ERROR` + 通用文案；404 响应体只有 `code/message/requestId/timestamp`，无异常类名、无 SQL。依赖不可用时返回的错误摘要是**单行并截断到 200 字符**（`ProbeDetails`），不返回完整 JDBC 报文。

### 2.4 公共组件未重复开发

`ApiResponse`、`ErrorCode`、`FieldViolation`、`RequestIdHolder`、`RequestIdFilter`、`GlobalExceptionHandler` 均为 TASK-001 既有实现，本轮**只做契约措辞对齐与服务可用性修复，没有新增第二个响应模型或第二套异常处理**。

## 3. 发现并修复的缺陷

### 3.1 readiness 语义错误（重要）

评审关注"liveness 不应因外部依赖故障而 DOWN"。实测发现的真实问题是**反过来的**：

| 端点 | 修复前（MySQL/RabbitMQ 均不可用） | 修复后 |
| --- | --- | --- |
| `/actuator/health/liveness` | `UP`，HTTP 200 | `UP`，HTTP 200（只含 `livenessState`+`ping`） |
| `/actuator/health/readiness` | **`UP`，HTTP 200** ← 依赖全挂仍宣称就绪 | **`DOWN`，HTTP 503**，含 `db`/`rabbit` 明细 |

原因：`management.endpoint.health.probes.enabled=true` 只开启分组，默认 readiness 组不含 `db`/`rabbit`，导致"实例已死但对外宣称可服务"，流量会被打进一个连不上数据库的实例。

修复（`application.yml`，显式声明分组，不依赖默认值）：

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
      group:
        liveness:
          include: livenessState,ping      # 只反映进程自身
        readiness:
          include: readinessState,db,rabbit # 必须包含真实外部依赖
```

### 3.2 成功响应 message 与契约不一致

设计契约与评审给出的样例都是 `"message":"success"`，实际实现是 `"OK"`（来自 `ErrorCode.OK.defaultMessage()`）。已改为 `"success"`，并补测试断言。这是本轮**唯一**改动的响应字段，`code/data/errors/requestId/timestamp` 结构保持不变。

### 3.3 MinIO 健康检查的隐含假设

原实现 `["CMD","mc","ready","local"]` 硬编码假设镜像内含 `mc`，镜像不含时容器会永久 unhealthy 且难以发现。现改为可覆盖：

```yaml
test: ["CMD-SHELL", "${MINIO_HEALTHCHECK:-mc ready local}"]
```

已验证变量插值生效（默认 `mc ready local`，覆盖后为 `curl -f .../minio/health/live`）。
`deploy/README.md` 增加实测步骤：先 `exec minio mc --version` 确认工具存在，再决定用哪种探活；两者都不可用时明确要求改用外部探测，**不允许伪造恒 healthy 的探针**。

## 4. 因环境限制未验证项（不伪造结论）

| 项 | 状态 | 证据 / 原因 |
| --- | --- | --- |
| `docker compose up` 四件套 | **未执行** | `docker ps` 报 `npipe ... The system cannot find the file specified`；`wsl.exe` 被安全策略列入程序黑名单（返回 `PROGRAM BLOCKED BY SECURITY POLICY`，明确禁止绕过） |
| MySQL 健康检查 | 未验证 | 依赖上一条 |
| RabbitMQ 健康检查 | 未验证 | 同上；但健康探针确有真实建连动作（日志 `Attempting to connect to: [127.0.0.1:5672]` → `Connection refused`） |
| MinIO 健康检查 / `mc` 是否存在 | 未验证 | 无法 `docker exec`；已提供可覆盖方案与验证步骤 |
| Redis 健康检查 | 未验证 | 同上 |
| Java ↔ MySQL 真实连接 | 未验证 | 无数据库；降级启动时探针如实返回 DOWN，未伪造 UP |
| Flyway V1 实际执行 | 未验证 | 无数据库；`--spring.flyway.enabled=false` 运行，`flyway_schema_history` 未生成 |

说明：`docker compose config` 只证明配置可解析，**不构成本节任何一项的验收证据**，本报告不据此声称通过。

## 5. 测试命令与结果（区分层级）

| 命令（执行目录） | 结果 |
| --- | --- |
| `./mvnw test`（repopilot-server） | Tests run: **19**, Failures: 0, Errors: 0, Skipped: 0 —— 全部为单元测试 |
| `./mvnw test -Dtest=RepoPilotServerApplicationIT`（无 `MYSQL_PASSWORD`） | Tests run: 4, Failures: 0, Errors: 0, **Skipped: 4** —— 环境变量缺失被 JUnit 条件禁用，未执行、未 Mock |
| `./mvnw -DskipTests package`（repopilot-server） | BUILD SUCCESS，`target/repopilot-server.jar` 33.9 MB |
| `pytest -q`（repopilot-worker） | **14 passed**（1 条 starlette 弃用 warning） |
| `npm run build`（repopilot-web） | 通过，`vue-tsc` 无错误；主 chunk 1098.89 kB（gzip 365.02 kB），构建有 chunk 体积 warning（TASK-001 已记录，非本轮引入） |
| `docker compose --env-file .env -f deploy/docker-compose.yml config` | 退出码 0；MinIO 探活插值与覆盖均正确 |

**测试分层结论**（回应评审关注点）：

1. 19 个通过的全部是单元测试，不依赖数据库，**不代表任何联调通过**。
2. 数据库集成测试 `RepoPilotServerApplicationIT` 共 4 项：未设置 `MYSQL_PASSWORD` 时 `Skipped: 4`，没有用 Mock 顶替。
3. 还需注意一个此前未讲清的事实：`./mvnw test` 默认**根本不会选中** `*IT`（Surefire 默认包含模式为 `*Test/*Tests/Test*`），项目也未配置 Failsafe。也就是说常规 `mvn test` 里它既不算通过也不算跳过，而是没进入执行范围。因此"19 个测试通过"不能被读成"含集成测试"。建议 TASK-002 引入 Failsafe 或在 CI 中显式调用，避免长期误读。

## 6. 文档一致性检查（只报告，不改未冻结契约）

| # | 冲突 | 说明 | 建议 |
| --- | --- | --- | --- |
| 1 | 错误明细字段 `errors` vs `details` | TASK-001 用 `errors`；`04-api-contract.md` 用 `details` | 按评审决定保留 `errors`，由设计契约侧出修订（见 §7） |
| 2 | 信封含 `timestamp` vs 契约无 | 保留 `timestamp`（前端已消费） | 同上，契约补为正式字段 |
| 3 | 错误码命名 `NOT_FOUND`/`BUSINESS_ERROR`/`DEPENDENCY_UNAVAILABLE` vs `RESOURCE_NOT_FOUND`/细分 409/`SERVICE_UNAVAILABLE` | 现状为通用码 | TASK-002 扩展，不重写 |
| 4 | requestId 为 UUID vs 契约要求 ULID（26 位大写） | 字符集校验规则两边一致 | TASK-002 引入 `IdGenerator` 后切换 |
| 5 | 缺 `PageResponse` / `Idempotency-Key` | 契约要求 | TASK-002 新增 |
| 6 | 模块 7 个（含 `report`）vs 8 个（含 `evaluationconfig`、`importing`） | 当前仅 `package-info` | TASK-002 调整 |
| 7 | 路由前缀 `/api/v1` | 契约明确"健康检查保留原路径" | 现状合规，新增业务路由须带 `/api/v1` |
| 8 | 测试库隔离 | 契约要求独立测试库/Testcontainers；当前 IT 直连主库 | TASK-002 |

以上与 [`design-merge-report.md`](./design-merge-report.md) §5 一一对应，**本轮未修改任何一份契约文档**，也未修改 TASK-002 的任务文本。

## 7. 评审决定已登记（待落到文档）

按评审意见，以下 4 项已确认为项目决定，需在 TASK-002 开工前落到 `04-api-contract.md` 与 `openapi-common.yaml`（本轮不改，避免擅自修改未冻结契约）：

1. **响应信封保留 TASK-001 格式**：`code/message/data/errors/requestId/timestamp`（`errors` 而非 `details`，保留 `timestamp`）。
2. **成功 `message` 统一为 `success`**（已实现）。
3. **公共组件不重复开发**：TASK-002 改为"检查 + 必要扩展"，禁止另建第二套 `ApiResponse/ErrorCode/GlobalExceptionHandler/RequestIdFilter`。
4. **Flyway 从 V2 开始新增**；V1 不修改、不重命名；V1 尚未真实执行，执行顺序为"先 Compose + V1 跑通，再由 TASK-002 出 V2"。

## 8. TASK-002 修订建议（未改动 `tasks/TASK-002.md`，等批准）

| 位置 | 现文本 | 建议 |
| --- | --- | --- |
| §范围 / §实施顺序 6 | "实现 IdGenerator、ApiResponse/PageResponse、ErrorCode…" | 改为"复用 TASK-001 已有 `ApiResponse/ErrorCode/GlobalExceptionHandler/RequestIdFilter`，仅按契约做必要扩展；新增 `PageResponse`、`IdGenerator`(ULID)、幂等组件" |
| §范围 | 未提响应格式基线 | 明确以已冻结的 TASK-001 信封为基线，冲突处以修订后的契约文档为准 |
| §数据库测试矩阵 | 26 表约束校验完整 | 补充在线/离线双路径的 4 个未决点：离线 Run 是否必须绑定 DatasetCase、重试如何保存每次 Attempt 结果、导入 Run 与在线 Run 的关联键、算子版本变更后历史 Run 重评口径 —— **这些问题必须在 V2 迁移落笔前定稿** |
| §验收 | 未要求 Failsafe | 增加"集成测试可被 Maven 显式执行，且 CI 中不被静默跳过" |

## 9. 敏感信息检查（Push 前置）

| 检查 | 结果 |
| --- | --- |
| `.env` 是否入库 | 未被跟踪；历史中从未出现（`git log --all -- .env` 为空） |
| 私钥 / AWS AK / GitHub Token | 无匹配 |
| 跟踪文件中的硬编码密码 | 仅 `.env.example` 的**开发占位值**（`repopilot_dev_pwd` 等），文档已要求首次使用前修改；无生产凭据 |

结论：可以推送。但推送需远端凭据，且属于对外动作，**本轮未执行 push**，等明确指令。

## 10. 遗留问题

1. **基础设施实跑是本阶段唯一未完成项**，且被环境策略硬性阻断（`wsl.exe` 在程序黑名单，非权限问题，不可申请放行）。需在具备 Docker 的机器上执行 README §3.1 与 `RepoPilotServerApplicationIT`，届时 readiness 应变为 HTTP 200、`/api/health` 应为 `UP`。
2. 4 个集成测试的通过与否取决于上一条，当前状态只能是 `Skipped: 4`。
3. `*IT` 未接入 Failsafe，常规 `mvn test` 不会执行；建议 TASK-002 或 CI 建立时补齐。
4. 前端主 chunk 1.07 MB（gzip 365 KB）为 TASK-001 已知项，页面增多后再拆分。
5. `.env.example` 含开发占位密码，团队协作时需在 README 显著提示替换。
