# TASK-002 数据库领域模型、Flyway与统一API基础

## 正式执行指令

你负责RepoPilot的TASK-002。先检查Git状态，阅读现有AGENTS、TASK-001现状报告、本文与全部必读契约；仅完成本任务。没有真实仓库对齐报告时先补001核对，不盲目按示例包名建工程。

## 必读

- docs/architecture/01-system-overview.md
- docs/architecture/02-domain-model.md
- docs/architecture/03-database-design.md
- docs/contracts/database-schema.json
- docs/architecture/04-api-contract.md
- docs/contracts/openapi-common.yaml
- docs/architecture/05-event-contract.md
- docs/architecture/06-reliability-design.md
- docs/reviews/task-002-delta.md
- docs/development-guide.md

## 范围

实现26张表、全部主外键/索引/CHECK、Entity与必要基础Mapper、ULID、JSON/UTC TypeHandler、状态枚举和转换表、API响应/分页/错误码/全局异常/RequestId、OpenAPI公共契约、数据库与公共组件测试及设计同步。

表名以database-schema.json为唯一来源：agents,agent_versions,datasets,dataset_versions,dataset_cases,evaluator_definitions,evaluation_plans,evaluation_plan_versions,evaluation_plan_items,experiments,experiment_targets,evaluation_runs,run_attempts,artifacts,trace_events,evaluation_jobs,evaluation_job_attempts,evaluation_results,annotation_tasks,annotation_records,import_batches,import_records,import_errors,outbox_events,inbox_events,api_idempotency_keys。

仅建立可靠性辅助表与实体，**不在002实现Outbox发布器、Inbox消费者、调度器或API幂等业务中间件**。状态转换为纯规则组件，业务动作后续任务实现。不要把字段表复制到另一个独立版本。

## 实施顺序

1. 检查现有migration、实际MySQL版本、现有Entity和公共API组件；能复用则扩展，不能无理由重写。
2. 逐表核对关系、复合外键的被引用UNIQUE和循环依赖；整理预期DDL清单。
3. 在已有最大Flyway版本后新增迁移。先建表，最后补循环外键；不修改已执行版本，不关闭外键校验掩盖错误。
4. 建持久化Entity与Mapper；JSON使用JsonNode，UTC使用Instant，必要时显式TypeHandler；不返回Entity作为API DTO。
5. 领域枚举包括生命周期、模式、Runner、技术状态、业务结果、Verdict、来源、导入/标注动作；状态转换按06文档测试。
6. 实现IdGenerator、ApiResponse/PageResponse、ErrorCode/BusinessException、全局异常转换、参数校验和RequestIdFilter。
7. OpenAPI注册公共Schema、错误响应和RequestId参数；保留TASK-001健康路径，不添加公开测试业务API。
8. 运行真实MySQL空库迁移及约束测试、公共API测试、现有回归；同步文档并交付本地commit。

## 数据库测试矩阵

| 场景 | 预期 |
| --- | --- |
| 空MySQL完整迁移、已有版本增量迁移 | 全部成功；历史checksum不变 |
| 同Agent重复version；同DatasetVersion重复caseKey | UNIQUE拒绝 |
| 同Target/Case/repeatIndex重复Run | UNIQUE拒绝 |
| 同Case不同Target或repeatIndex | 允许多个Run |
| repeatIndex=0、repeatCount=0、越界score | CHECK拒绝 |
| 同Run重复attemptNo；同Job重复attemptNo | UNIQUE拒绝 |
| 重复eventId；同Job两个最终Result | UNIQUE拒绝 |
| Trace引用不存在Run或其他Run的Attempt | 外键拒绝 |
| Run引用其他Experiment的Target | 复合外键拒绝 |
| Result引用不存在Job或其他Job的Attempt | 外键拒绝 |
| Annotation引用其他Run的Job | 外键拒绝 |
| 离线Target无AgentVersion；原文件Artifact无runId | 允许 |
| JSON嵌套对象/数组/null与中文 | 无损往返，非法JSON明确失败 |
| 非UTC JVM/连接配置下毫秒Instant往返 | UTC业务时刻保持，API为Z后缀 |
| ULID生成并存取 | 26位大写合法、主键唯一 |
| 任意未定义status | CHECK及反序列化拒绝 |
| 循环引用删除 | RESTRICT阻止误删，不级联历史 |

Case是否属于实验DatasetVersion、online AgentVersion必填、版本不可变是后续服务层规则，002不提供相关业务API；文档应标明边界，不能声称这些已被单列外键强制保障。

## 公共组件测试

MockMvc/测试Controller验证200/201成功序列化、400字段校验、404、409业务/状态错误、500未知异常脱敏、分页默认与上限、X-Request-Id生成/合法透传/非法替换、MDC在异常和线程复用后清理。错误HTTP码必须真实返回，不能全200再写code。

测试状态机覆盖所有合法边与典型非法边，终态不可重置；重试只是新Attempt而非终态回RUNNING。JSON解析异常不可静默变空对象。不要仅断言实体有注解，要真实向MySQL插入冲突数据。

## 禁止内容

Agent/数据集/方案CRUD、在线业务、导入业务、Runner/Judge、标注业务、RabbitMQ消费者/发布器、Vue业务页、新中间件、自动删库重建、未经论证改TASK-001架构。必要构建/测试依赖可以在现有技术栈内补齐并说明。

## 验收

26表及全部设计约束落实；全部迁移在空真实MySQL可执行；持久化JSON/UTC映射通过；公共API测试通过；现有功能与构建通过；OpenAPI公共组件可导出；文档与实际DDL一致。

若环境不能运行MySQL，仍可完成静态代码和单测，但结论必须标“数据库验收未完成”，不能用H2替代。若已有业务表与设计冲突，先列最小前向迁移方案，重大跨模块变化等待负责人决定，同时继续不受影响的组件工作。

## 交付报告

按tasks/README.md模板给Git Commit ID、文件列表、实际建表、主外键/索引/CHECK、API公共组件、精确测试命令及数量、已知限制和设计差异。已完成/未完成逐项对应验收，完成即停止，不进入003。
