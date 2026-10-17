# 设计包 V1.0 合并报告与冲突处置

日期：2026-10-17
对象：`RepoPilot-design-v1.0/`（交付包原件）→ 本仓库工作目录
结论：**设计包已合并为唯一设计基线（`docs/`），文件级冲突 3 处、文档组织冲突 4 组、契约级差异 12 项。文件与组织冲突已处理；契约级差异归入 TASK-002 执行，本文只登记不擅自改代码。**

## 1. 合并原则

1. 设计包是**设计基线**，不是已验收代码。文档内容照收，但不得据此声称任何功能"已完成"。
2. **同名文件不覆盖已有工程内容**：已有实现（`AGENTS.md`、`README.md`、`tasks/TASK-001.md`）保留为主，吸收设计包中不重复的约束。
3. **唯一来源原则**：文档正文以 `docs/` 为准；数据库字段以 `docs/contracts/database-schema.json` 为准；任务范围以 `tasks/` 为准。三方不得互相抄写。
4. 影响数据库语义、跨语言契约或后续 TASK 的差异，只写"差异 + 推荐方案"，等负责人决定（`AGENTS.md` §变更方式）。

## 2. 交付包完整性检查

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 清单校验 | `sha256sum -c MANIFEST.sha256` | 40 项中 37 项 OK，**3 项失配**（见 §3） |
| 设计自检 | `python tools/verify_design.py` | **PASS**：866 项检查；26 表；4 份 JSON Schema；4 份正例；18 份反例被拒 |

自检首次运行是 **FAIL**，原因是工具缺陷而非文档缺陷：

```
broken link: ...\repopilot-web\node_modules\asynckit\README.md test/test-parallel-array.js
```

`tools/verify_design.py` 用 `ROOT.rglob('*.md')` 扫描整个仓库，在真实工程里必然撞上 `node_modules`、`.venv`、`target` 里的第三方 README。已改为跳过 `.git`、`node_modules`、`target`、`dist`、`build`、`.venv`、`venv`、`.tools-venv`、`__pycache__`、`logs` 等目录，重跑即 PASS。这是设计包假设"纯文档仓库"与真实工程的第一处直接冲突，已修复。

## 3. 文件级冲突（3 处，均已处理）

`MANIFEST.sha256` 失配的 3 项，恰好就是设计包与本地工程同名的 3 个文件。

| 文件 | 设计包版本 | 本地已有版本 | 冲突性质 | 处置 |
| --- | --- | --- | --- | --- |
| `AGENTS.md` | 38 行，自述"待合并规则"，只有契约红线与交付要求 | 122 行，含技术栈版本表、目录职责、标准命令、当前进度 | 规则集 vs 工程手册，**互补但会双份约束** | 合并为单文件：保留本地版本结构与命令，吸收设计包的"不可擅自改变的契约""变更方式""测试与交付"三节 |
| `README.md` | 是**文档包说明**（"本文档包不是已实现软件"，并指向 `/Users/mac/...` Mac 绝对路径） | 是**工程 README**（启动/停止/测试/已知问题） | 同名词不同用途，直接覆盖会毁掉唯一的运行说明 | 不覆盖。工程 README 保留；设计包的"唯一来源表"移入 `docs/README.md`；Mac 路径改为仓库内相对路径 |
| `tasks/TASK-001.md` | 任务是"工程现状核对与基础初始化"，交付物 `docs/reviews/repository-alignment.md` | 是已完成的交付记录（19 单测 + 14 pytest + 构建） | **同编号、不同语义**：设计包假定仓库可能未初始化 | 保留本地记录为主；执行其核对要求并产出对齐报告，在本地 TASK-001 增加 §0 指向该报告 |

`MANIFEST.sha256` 本身描述的是**交付包原件**，合并后必然与工作目录不符。已在文件头加说明注释（`sha256sum -c` 会忽略 `#` 行），避免后续被误当成"仓库被篡改"。

## 4. 文档组织冲突（4 组，已处理）

### 4.1 文档索引重复规划

本地 `docs/README.md` 曾规划 `03-domain-model.md`、`04-database-design.md`、`05-api-contract.md`、`06-event-contract.md` 放在 `docs/` 根目录；设计包已把这些内容放在 `docs/architecture/02~05`。若两套并存，同一契约会有两份真相。

**处置**：`docs/README.md` 重写为设计包目录结构的索引，废止旧规划表，并给出"旧规划名 → 现行路径"映射。

### 4.2 部署 / 测试 / 前端规范归属

本地规划 `14-deployment-guide.md`、`15-testing-strategy.md`、`13-frontend-specification.md`；设计包已分别由 `docs/development-guide.md`（部署、测试层级、CI、备份、性能）与 `docs/modules/frontend-design.md` 覆盖。
**处置**：不再新建这三个文件，索引指向设计包路径。

### 4.3 本地开发文档与开发规范重叠

`docs/01-local-development.md`（本地）与 `docs/development-guide.md`（设计）都写命令、端口、Compose 启停。
**处置**：按性质分工——`01-local-development.md` 是**本仓库可执行 Runbook**（真实端口 13306/16379/8080/8000/5173、实测命令）；`development-guide.md` 是**通用规范**（测试层级、CI、备份留存、性能方法），不再罗列具体命令，改为引用 Runbook。

### 4.4 双源：`project_design.md` 与 `docs/`

根目录 `project_design.md`（27KB，《开发总手册 V1.0》）早于设计包，与 `docs/` 在任务拆分、模块划分上存在重叠。两份都是"总纲"会造成指令冲突。

**处置**：`docs/` 为唯一设计基线；`project_design.md` 降级为**历史草案**，仅供追溯，与 `docs/` 冲突时以 `docs/` 为准。`AGENTS.md` 中的"必须先读 §project_design.md"已改为"先读 `docs/` 索引"。

## 5. 契约级差异（12 项，归入 TASK-002）

以下差异**当前代码已实现一侧**，而设计包契约另一侧。按"不提前开发 TASK-002"的要求，本次只登记，TASK-002 第 6 步（`ApiResponse`/`PageResponse`/`ErrorCode`/`RequestIdFilter`）统一执行。

| # | 项 | 设计包（04-api-contract.md） | 当前实现 | 推荐处置 |
| --- | --- | --- | --- | --- |
| 1 | 错误明细字段名 | `details` | `errors`（`FieldViolation`） | 改 `details`，同步前端 `types/api.ts` |
| 2 | 404 错误码 | `RESOURCE_NOT_FOUND` | `NOT_FOUND` | 重命名 |
| 3 | 409 错误码 | `RESOURCE_CONFLICT` / `IDEMPOTENCY_CONFLICT` / `INVALID_STATE_TRANSITION` / `STALE_ATTEMPT` / `OPTIMISTIC_LOCK_CONFLICT` | 单一 `BUSINESS_ERROR` | 拆分为 5 个稳定码 |
| 4 | 503 错误码 | `SERVICE_UNAVAILABLE` | `DEPENDENCY_UNAVAILABLE` | 重命名 |
| 5 | 400 错误码 | 仅 `VALIDATION_ERROR`（+`UNSUPPORTED_EVENT_VERSION`） | 另有 `BAD_REQUEST` | 合并或保留为内部码并补文档 |
| 6 | 响应时间戳 | 信封无 `timestamp` | 有 `timestamp`（前端已消费） | 二选一并回写契约：删字段，或在 `openapi-common.yaml` 声明为扩展 |
| 7 | 成功 message | `"success"` | `"OK"` | 改 `"success"` |
| 8 | requestId 生成 | 缺失/非法时生成 **ULID（26 位大写）** | UUID（36 位小写含短横线） | 改 ULID，`IdGenerator` 一并提供 |
| 9 | 分页信封 | `data={items,page,pageSize,total}`，`page≥1`，`pageSize` 默认 20、上限 100 | 无分页组件 | TASK-002 新增 `PageResponse` |
| 10 | 幂等 | 创建/发布/导入/标注要求 `Idempotency-Key`（1~128） | 无 | TASK-002 建表 + 拦截，业务动作留后续 TASK |
| 11 | 路由前缀 | 业务 `/api/v1`、Worker `/internal/v1`，**健康检查保留原路径** | 仅 `/api/health` | 现状与"保留原路径"一致，无需改；新增业务路由必须带 `/api/v1` |
| 12 | 模块划分 | `agent`、`dataset`、`evaluationconfig`、`experiment`、`trace`、`evaluation`、`annotation`、`importing`（8 个） | `agent`、`dataset`、`experiment`、`trace`、`evaluation`、`annotation`、`report`（7 个） | TASK-002 调整为 8 模块：新增 `evaluationconfig`、`importing`；`report` 归入 `experiment`（报告读实验数据）或经负责人同意保留 |

另有 2 项属测试与部署约定，不影响当前代码但会影响 TASK-002 验收：

- **测试库隔离**：设计要求"测试用独立库和测试凭证"，当前集成测试直连 `repopilot` 主库（端口 13306）。TASK-002 应新增 `repopilot_test` 库或改用 Testcontainers，禁止用 H2 替代。
- **时间精度**：设计要求 API 时间恒为 `Z` 后缀、毫秒精度。当前 `Instant` 默认序列化精度不固定，TASK-002 需显式配置 Jackson。

> **2026-10-17 裁决**：第 1 项（`errors`）、第 6 项（保留 `timestamp`）按现有实现冻结，由契约文档侧改为一致；
> 第 2/3/4/5/8/9/10/11/12 项归 TASK-002 执行；第 7 项（`message`）已改为 `success`。

## 6. 架构定位差异（2 项，按用户指令保留）

设计包对中间件的定位比本项目 TASK-001 更保守，两者不一致处**以用户下达的 TASK-001 验收标准为准**（用户明确要求 Compose 启 RabbitMQ/Redis、Java 能连 RabbitMQ），但必须标注清楚，避免被读成"业务已使用 MQ"。

| 项 | 设计包 | 用户 TASK-001 要求 | 处置 |
| --- | --- | --- | --- |
| RabbitMQ | TASK-001 步骤 4："必要依赖只包括 MySQL 和对象存储；MQ 可选"；01-概览："TASK-011 后唤醒通知"；development-guide："只在 TASK-011 或显式启用配置时启动" | 验收第 4 条"Java 服务可以连接 RabbitMQ"；Compose 需启动 RabbitMQ | 保留容器与连通性健康检查，**不声明任何交换机/队列**，业务发布/消费留到 TASK-011；文档统一表述为"通道已就绪、业务未使用" |
| Redis | 00-范围："不默认引入 ClickHouse、Redis"；development-guide："只有热点聚合有证据才引入" | Compose 需启动 Redis（预留，无业务） | 保留容器；Java 不引入客户端；`/api/health` 中固定 `UNKNOWN` 并标注"预留" |

两者都不构成"引入新中间件"，因为都在用户给定的技术栈清单内；差异仅在于**启用时机**。

## 7. 已确认一致、无需处理的点

- 健康检查路径保留原路径 → `/api/health` 与 `/actuator/health` 均在，无需迁移到 `/api/v1`。
- MySQL 需支持真实 CHECK（≥ 8.0.16）→ Compose 用 8.0.36，满足。
- 禁止微服务、Spring Cloud、Kafka、ClickHouse、Kubernetes → 现状未违反。
- 真实 MySQL 集成测试、禁止 H2 冒充 → 现状 IT 直连真实 MySQL，符合方向。
- 密钥只通过环境变量引用、不提交明文 → 现状符合（`.env.example` 只有占位符）。

## 8. 待决项（2026-10-17 项目负责人已裁决）

评审结论见 [`task-001-final-acceptance.md`](./task-001-final-acceptance.md)。

| 原待决项 | 决定 | 状态 |
| --- | --- | --- |
| §5-6 响应是否保留 `timestamp` | **保留**；信封维持 TASK-001 格式 `code/message/data/errors/requestId/timestamp` | 已冻结，待 TASK-002 把契约文档改成一致 |
| §5-1 `errors` 还是 `details` | **保留 `errors`**（不改成 `details`） | 同上 |
| §5-12 `report` 模块是否独立 | 暂不动，TASK-002 按设计 8 模块调整时一并决定 | 待 TASK-002 |
| §5-5 405/415 错误码是否补文档 | 属契约补全，随 TASK-002 一起改文档 | 待 TASK-002 |
| 是否删除交付包原件 | **保留**，仅本地留档，已 gitignore | 已处理 |

补充决定（评审同时确认）：

1. 成功响应 `message` 统一为 `"success"`，已在 TASK-001 实现中修正。
2. TASK-002 **不得重复开发** `ApiResponse`/`ErrorCode`/`GlobalExceptionHandler`/`RequestIdFilter`，
   改为"检查 + 必要扩展"。
3. Flyway TASK-002 从 **V2** 起；V1 不修改、不重命名，且须先在真实 MySQL 跑通再出 V2。

