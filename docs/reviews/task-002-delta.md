# 与原 TASK-002 草案的差异及待本地对齐事项

> 历史记录：本报告描述设计包 V1.0 的原始决定（26 表）。2026-10-18 已引入 `evaluation_batches`
> 解决"重评历史 Run"无法表达的问题，表数为 27；随后 V1.2 增 `evaluation_batch_runs`，**表数为 28**。
> 差异与裁决见 [task-002-revision.md](./task-002-revision.md)、[task-002-freeze.md](./task-002-freeze.md)。

本报告对照的是本轮对话中用户提供的TASK-002文本，未读取本地仓库，不能声称已有代码有以下缺陷。V1.0选择如下规则供项目负责人审核；本地已有实现时做前向差异迁移，不推翻成果。

| 原草案未定或不足 | V1.0决定 | 原因与影响 |
| --- | --- | --- |
| dataset_case_id未明确可空性 | 所有Run必绑固定Case | 历史数据先构建/发布样本；V1不支持游离记录，报告可配对 |
| repeat_count有定义，Run无重复编号 | repeat_index与唯一Target/Case/index | 逻辑重复与基础设施重试分开 |
| RunAttempt有重试但Trace无尝试归属 | trace_events.run_attempt_id，Run有active/accepted指针 | 保留失败轨迹，评测只用接受尝试 |
| 仅lease_until，旧Worker仍可能回写 | Token摘要、active指针、硬截止、completionHash | 原子条件隔离旧执行者，终态回调可幂等 |
| Job无独立尝试历史 | 新增evaluation_job_attempts | 保留Judge失败/重试证据与成本 |
| Result每Job一份但未指向尝试 | job_attempt_id与复合外键 | 明确哪次合法执行产生最终结果 |
| 外部来源唯一键仅实验范围 | 增加Target维度 | 同来源记录在不同被比较对象可区分；同对象不重复 |
| 导入原文件Artifact强制依附Run会循环依赖 | artifact.run_id可空 | 原始文件可在Run创建前上传 |
| 导入仅进度和错误表 | 新增import_records逐行回执、类型与重复计数 | 崩溃恢复不重复写入或重复计数 |
| 仅Outbox状态，无并发领取 | 发布租约/Token与Inbox去重表 | TASK-011可可靠实现至少一次通知 |
| 创建/发布重发未定义 | 新增api_idempotency_keys | 业务写入与成功回执同事务 |
| 发布不可变未定义竞争处理 | 修改和发布锁同一父版本行 | 防并发编辑绕过不可变规则 |
| traceId类型不详 | 规范OTel列+externalTraceRef | ULID/任意来源ID不伪装OTel ID |
| 缺少输出、用量与配置摘要 | 增加output/usage、配置hash和固定预算 | 支撑Judge、成本和可复现性 |
| 004早于011，但任务领取依赖未解释 | 004/007用DB租约；011增MQ通知 | 前面阶段能运行，基本正确性不延后 |
| TASK-003仅Agent/数据集 | 同步实现方案版本管理 | 在线实验需要可发布的方案；不由代码硬编码假方案 |

原草案22张表，本包26张表。新增：evaluation_job_attempts、import_records、inbox_events、api_idempotency_keys。其他为原表字段和约束细化。002只建辅助表，不提前实现对应业务。

## 仍须本地核实

1. TASK-001真实目录、构建工具、依赖版本、健康路径与Flyway历史。
2. 真实Codex执行入口、版本、可信仓库路径和凭证可用性。
3. LiteLLM日志的脱敏样例与实际字段，本包仅承诺规范JSONL适配。
4. MySQL/对象存储/Docker是否可用，哪些集成测试能够执行。
5. 原AGENTS与设计文件是否已有约束；本包必须合并，不能静默覆盖。

这些不影响在云端准备设计文件，但影响本地实际实现与验收。若现有代码已完成TASK-002，先产生实际DDL/Entity与本包字典的差异报告，再决定迁移，不重复初始化。
