# TASK-014 Redis + Lua 分布式限流

前置：TASK-011（Outbox/RabbitMQ 可靠性）。与 TASK-012、TASK-013 并行不冲突，不得回归。

## 必读

- docs/development-guide.md
- docs/architecture/06-reliability-design.md
- docs/modules/messaging-reliability.md
- docs/modules/evaluation-engine.md

同时读取 README、AGENTS.md、tasks/README.md 及前置任务实际交付报告。数据库字段仍以
`database-schema.json` 为准——**本任务不改任何表**。

## 背景与冲突裁决

`AGENTS.md` §2 原写「Redis 仅编排实例，Java 不引入客户端；`/api/health` 中固定 `UNKNOWN` 并标注预留」，
与本任务直接冲突。按 §0 裁决规则**先改文档再改实现**，本次已在 `AGENTS.md` §2 显式修订并保留原条款说明。

## 实施内容

1. **审计先行。** 逐项核对 Redis 客户端、Lua 脚本、入口限流、模型调用限流、并发配额的真实实现状态，
   建立审计表（实现文件 / 调用入口 / 测试证据 / 状态）。不得凭"依赖存在"或"健康检查 UNKNOWN"
   推断已实现或未实现。已有实现优先复用。
2. **Redis + Lua 原子令牌桶。** Lua 内原子完成「补令牌 → 判断 → 扣减 → 写回」；
   用 Redis 服务端时间（`TIME`）；首次创建从满桶开始；时钟回拨按 0 处理；
   小数速率用 6 位小数存储；每次写入重置 TTL；参数非法返回哨兵值而不是 `error()`。
   Java 与 Python 两端共用**逐字节相同**的脚本。
3. **接入两个真实场景。**
   - A. Java API 入口：高成本写入口（创建实验、启动实验、创建评测批次、导入、制品上传）限流；
     跳过健康检查、静态资源、只读请求；超限 429 + 统一信封 + 计算得出的 `Retry-After`。
   - B. Python Worker 模型调用：在 `JudgeClient.complete` 这个唯一可观测边界接入；
     多 Worker 共享同一限额；主体不含 `worker_id`；被限流不得记为 FAIL 或产出分数。
   两层分别验证，入口限流不得代替模型调用限流。
4. **配置、故障与可观测性。** Redis 连接、环境变量、本地启动、健康检查；
   严格区分 429（超限）与 503（依赖故障）；`FAIL_OPEN` / `FAIL_CLOSED` 可独立配置且
   **不得退化为每实例各自计数**；限流依赖故障不得记为评测失败；补 allowed/denied/unavailable 指标。
5. **测试必须有真实证据。** 需要 Redis 的用例在本地与 CI 都连真实 Redis，验证实际 Lua 执行结果；
   强制验收模式下依赖不可用必须明确失败，不得靠跳过伪造通过。
6. **文档。** 限流设计、Key 设计、故障策略、阈值推导、两层接入位置与流程、排查清单。

## 验收

- 令牌桶：初始化满桶、放行、耗尽拒绝、随时间补充、TTL 回收、参数非法、异常参数不污染状态。
- 原子性：64 并发打容量 8 的桶，放行数**恰好 8**（Java 与 Python 各一条）。
- 隔离：多主体、多策略、多模型互不影响；跨语言 Key 契约一致。
- Java：429 状态码、统一错误响应、`Retry-After` ≥ 1 且由缺口与速率算出、响应头策略与剩余额度正确。
- Python：两个 Worker 共享限额；限流错误转可重试，任务不判 FAIL、不产出 verdict/score；
  算子内 1s/3s 快速重试被跳过，交任务级 5s/15s 退避。
- Redis 不可用：`FAIL_OPEN` 放行且带 `X-RateLimit-Status: unavailable` + 指标；
  `FAIL_CLOSED` 拒绝；两者都不出现本地兜底计数。
- TASK-004~013 全部用例不回归。

## 开发边界

- 不重写 TASK-011 的 Outbox/RabbitMQ/Inbox；不破坏 TASK-012 性能结论与 TASK-013 执行模型。
- 不引入第三方限流框架、不引入新的缓存层或微服务；只引官方 `spring-boot-starter-data-redis` 与
  `redis==5.2.1`，算法留在仓库内的 Lua。
- 不修改或删除旧测试换取全绿；不伪造测试结果。
- 示例阈值**不是生产最佳值**，必须配置化并说明推导方法。

## 交付

按 tasks/README.md 模板报告实际文件、两层调用链、原子性验证方式、命令与测试数量
（通过/失败/跳过分别列出）、CI 状态、提交范围、Git 工作区状态，以及未实现或未验证的内容。
仅提交本地 commit，不 push。任务完成即停。
