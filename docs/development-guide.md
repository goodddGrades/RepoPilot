# 开发、测试与部署规范

## 仓库对齐

TASK-001先读取现有pom/gradle、Python依赖、package.json/锁文件、Compose、migration、README和AGENTS；记录实际启动命令与端口。工程已初始化则只补缺口，不删目录重建。没有真实仓库时本包不指定虚构包名、端口、Commit或测试数量。

Java固定21；其余依赖优先保留兼容版本并锁定，最终记录在仓库。MySQL测试版本与部署版本一致；使用CHECK时最低8.0.16。Python与Node运行版本由实际工具链验证，写版本文件与CI矩阵；不要只写“最新版”。

## 测试层级

1. 单元：状态转换、ULID、摘要、算子逻辑、缺失分支、DTO校验。
2. 真实MySQL集成：Flyway空库/增量、所有主外键、唯一键、CHECK、JSON和UTC、并发锁、幂等回执。
3. HTTP契约：公共格式、状态码、requestId、内部鉴权、失效Token、Schema跨语言样例。
4. 端到端：发布→在线Mock→Trace→规则→标注→报告；规范JSONL离线路径得到同口径结果。
5. 故障：旧Attempt、回调重复、取消竞争、导入恢复、消息重复和Outbox不一致窗口。

MySQL可用Testcontainers或隔离Compose数据库；无法运行Docker时明确记录阻塞与未执行测试，不能静默改H2后声称MySQL验证通过。测试用独立库和测试凭证。测试时在非UTC JVM与浏览器时区运行时间用例，验证API始终Z后缀与毫秒精度。

## 命令记录

实际命令由TASK-001确认：Maven工程可用./mvnw test/verify，Gradle工程使用既有wrapper；Python使用已配置pytest；前端用锁文件对应包管理器执行typecheck/build和已有测试。这里是选项，不声称这些命令已在仓库存在。每次交付写执行目录、精确命令、退出码、通过/失败/跳过，不仅写“测试通过”。

## 本仓库的实际分层（TASK-002 已落地）

测试按"是否需要外部基础设施"分成两层，命令本身就能区分，不依赖口头约定：

| 命令 | 执行位置 | 内容 | 依赖 |
| --- | --- | --- | --- |
| `./mvnw test` | `repopilot-server/` | 只跑 `*Test`：状态机、ULID、分页、异常映射、requestId、健康服务 | 无，离线可跑 |
| `./mvnw verify` | `repopilot-server/` | 先跑单测，再由 Failsafe 跑 `*IT`：空库迁移、字典比对、约束拒绝、JSON/UTC/ULID 映射、健康接口 | 真实 MySQL 8（`REPOPILOT_IT_DB_*`）与 RabbitMQ（仅就绪探针用例） |

Surefire 显式排除 `**/*IT.java`，Failsafe 显式只收 `**/*IT.java` 并设 `failIfNoTests`：
以前只配了 `excludedGroups`，`*IT` 连"跳过"都不会出现在报告里，这是被纠正的实际缺陷。

集成测试连接参数（缺任何一个主机变量都会启动失败，不会静默跳过）：

```
REPOPILOT_IT_DB_HOST=127.0.0.1
REPOPILOT_IT_DB_PORT=3306
REPOPILOT_IT_DB_NAME=repopilot_it
REPOPILOT_IT_DB_USER=repopilot
REPOPILOT_IT_DB_PASSWORD=...
```

CI 里额外设置 `REPOPILOT_IT_REQUIRE_ALL_UP=true`，把"所有依赖都 UP"的就绪探针断言打开；
本地只跑 MySQL 时该用例会被显式跳过并在报告中单列，不会被算作通过。

本仓库的具体端口、启动顺序与已实测命令见 [本地开发 Runbook](./01-local-development.md)；本文不重复维护命令清单，避免两处漂移。

## CI与文档

CI至少编译Java、真实MySQL迁移集成测试、Python测试、前端类型/构建和Schema校验。TASK-002优先落实Java与数据库，其余随模块加入。OpenAPI导出与TS生成应由实际构建检查，禁止让手写定义长期漂移。

数据库字典变更时同步生成数据库文档，检查全部引用、枚举、索引与FK。事件/导入Schema新增字段需要JSON正反样例；新增状态要更新状态机、数据库CHECK、Java/Python/TS和页面映射。

## 本地部署

Compose提供MySQL和对象存储；RabbitMQ只在TASK-011或显式启用配置时启动；Langfuse可选。Redis自TASK-014起是**分布式限流的共享状态存储**（不是可选组件：限流开启时它必须可连，否则按 `FAIL_OPEN`/`FAIL_CLOSED` 策略降级，见 [`modules/rate-limiting.md`](./modules/rate-limiting.md)）。端口从实际工程读取，开发模式只绑定127.0.0.1，非本机访问需受控入口。Java先迁移，再启动API，再启动Worker与Web。

服务ready必须区分：DB/对象存储必要依赖失败→未就绪；可选观测出口失败不让所有评测停机，但需告警。Worker启动声明能力，缺密钥的真实Runner不应领取相应任务；Mock始终可测试。

## 配置清单

| 配置域 | 内容 |
| --- | --- |
| 业务数据库 | JDBC地址、独立账号、UTC连接配置 |
| 对象存储 | endpoint,bucket,服务账号、上传限制、签名TTL |
| Worker | API baseURL、Worker凭证、workerId、能力、并发 |
| 执行 | 租约、心跳、硬超时、重试次数、Run数量上限 |
| 模型 | gateway URL、model、secretRef环境映射、token预算 |
| 消息 | TASK-011 broker地址、凭证、确认/路由/队列设置 |
| 观测 | OTel/Langfuse可选出口与脱敏策略 |

变量的最终名称在仓库.env.example统一定义，示例只有占位符，真实.env不提交。Worker凭证与模型密钥分开；日志屏蔽Authorization、Cookie与密钥字段。

## 备份、恢复与留存

数据库备份与对象存储备份必须成对覆盖，恢复后按sha256抽样核对Artifact。运行迁移前备份；DDL失败需要检查Flyway历史与实际结构再处理，不能直接repair掩盖半完成变更。只使用前向修复迁移，不承诺任意DDL自动回滚。

V1配置默认保留已关联实验数据；删除不实现级联。临时未关联对象可按24小时宽限期清理，先检查引用；API幂等回执不少于7天。其他保留期由项目负责人配置，不硬编码删除历史Trace。对象DELETED状态与业务关联检查需一致。

## 性能验证

先记录基线，再看慢查询和EXPLAIN，按真实访问路径调整索引。冷数据分批清理；Trace使用游标，文件流式读取，长任务不占事务。只有MySQL成为明确瓶颈后才讨论ClickHouse；**业务数据不做Redis缓存**——TASK-014 引入的 Redis 只存限流令牌桶，不承担任何读缓存职责（缓存失效与一致性问题不因引入限流而获得豁免）。

报告硬件、数据行数/体积、并发、请求组合、持续时间、p50/p95/p99、错误率、CPU内存、数据库锁等待与原始结果。测试前后配置一致，不能拿缓存热态对比冷态后宣称普遍收益。

## 本包核对的官方资料（2026-10-17）

- [MySQL CHECK约束](https://dev.mysql.com/doc/refman/8.0/en/create-table-check-constraints.html)：8.0.16前后约束执行存在差异，因此不能只检查DDL能解析。
- [MySQL锁定读](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)：SKIP LOCKED适合任务表领取；不用于一般业务一致性读。
- [MySQL外键](https://dev.mysql.com/doc/refman/8.0/en/create-table-foreign-keys.html)：实施外键与被引用键要求。
- [RabbitMQ确认机制](https://www.rabbitmq.com/docs/confirms)与[可靠性](https://www.rabbitmq.com/docs/reliability)：发布确认和消费ACK相互独立；重复投递必须幂等。
- [OpenTelemetry传播API](https://opentelemetry.io/docs/specs/otel/context/api-propagators/)：Trace ID与Span ID格式。本文数据库使用对应十六进制文本表示。

上述链接用于核对机制，不代表本项目已经安装页面当前展示的产品版本。具体依赖兼容性以仓库实际锁定版本为准。
