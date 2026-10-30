# 状态机、租约与故障恢复

## 状态全集

| 对象 | 状态 |
| --- | --- |
| Agent / Dataset | ACTIVE, ARCHIVED |
| 三类配置版本、EvaluatorDefinition | DRAFT, PUBLISHED, ARCHIVED |
| Experiment | DRAFT, QUEUED, RUNNING, COMPLETED, PARTIAL_FAILED, FAILED, CANCELLED |
| Run | PENDING, QUEUED, RUNNING, EVALUATING, COMPLETED, FAILED, TIMED_OUT, CANCELLED |
| RunAttempt / JobAttempt | RUNNING, COMPLETED, FAILED, TIMED_OUT, CANCELLED |
| EvaluationBatch | PENDING, RUNNING, COMPLETED, PARTIAL_FAILED, FAILED, CANCELLED |
| Job | PENDING, RUNNING, COMPLETED, FAILED, SKIPPED |
| AnnotationTask | PENDING, CLAIMED, SUBMITTED, REVIEWED, RETURNED, CANCELLED |
| ImportBatch | PENDING, RUNNING, COMPLETED, PARTIAL_FAILED, FAILED, CANCELLED |
| Outbox | PENDING, PUBLISHED, FAILED |
| Verdict | PASS, FAIL, INDETERMINATE, NOT_APPLICABLE |
| TaskOutcome | UNKNOWN, SUCCESS, FAILURE, NOT_APPLICABLE |

状态值由代码枚举及迁移 CHECK 共同约束，拒绝未知值。终态不自动回到执行态。用户重评创建新 EvaluationBatch，复用历史 Run 与封口 Trace，不重跑 Agent，也不改写原批次结果；基础设施重试在同一 Run/Job 下追加 Attempt。

## 允许的转换

- Experiment：DRAFT→QUEUED；QUEUED→RUNNING；RUNNING→COMPLETED/PARTIAL_FAILED/FAILED；DRAFT/QUEUED/RUNNING→CANCELLED。提交阶段校验失败保持 DRAFT；暂时无 Worker 保持 QUEUED，由运维显示等待。
- 在线 Run：PENDING→QUEUED→RUNNING→EVALUATING→COMPLETED。离线：PENDING→EVALUATING→COMPLETED。任何非终态可 CANCELLED；执行失败耗尽→FAILED/TIMED_OUT；评测必需算子失败→FAILED。
- RunAttempt/JobAttempt：创建时 RUNNING；之后只到 COMPLETED/FAILED/TIMED_OUT/CANCELLED。
- Job：PENDING→RUNNING→COMPLETED/FAILED；PENDING→SKIPPED。重试时 Job 保持 RUNNING，增加新的 Attempt；取消时待执行 Job 置 SKIPPED、运行中 Job 置 FAILED(error_code=CANCELLED)，Run 自身置 CANCELLED。
- EvaluationBatch：PENDING→RUNNING→COMPLETED/PARTIAL_FAILED/FAILED（无任何成功 Job 才 FAILED）；PENDING/RUNNING→CANCELLED。批次创建事务同时写入批次行、`evaluation_batch_runs` 的 Run 登记行与 `expected_job_count`，提交后三者均不可修改：不能追加 Run、不能改方案版本、不能改选择依据。
- ImportBatch：PENDING→RUNNING→COMPLETED/PARTIAL_FAILED/FAILED；PENDING/RUNNING→CANCELLED。`DATASET_CASES` 导入按 2026-10-18 裁决为**整批原子**：任一错误行则整批不写入、不落批次，只可能产生 `COMPLETED` 或 `FAILED`；`PARTIAL_FAILED` 仅保留给后续 `OFFLINE_RUNS` 场景。
- Annotation：PENDING→CLAIMED→SUBMITTED→REVIEWED；SUBMITTED→RETURNED→CLAIMED；任意未审核状态→CANCELLED。
- Version：DRAFT→PUBLISHED；DRAFT→ARCHIVED（丢弃建错的草稿，2026-10-18 裁决）；PUBLISHED→ARCHIVED；不支持回到 DRAFT。ARCHIVED 是终态：不可修改、不可发布、不可被新实验引用，历史记录保留（归档时刻以 `updated_at` 记录，V1 不新增专列）。Agent/Dataset 可 ACTIVE→ARCHIVED，不物理删除历史。

同状态心跳是更新时间，不算状态转换。非法转换返回 INVALID_STATE_TRANSITION/409。Java 实现可测试的转换表和原子数据库条件更新，不能只在 Controller 中判断。

## 领取与 fencing（隔离旧执行者）

1. Java 以数据库 UTC 为准，在短事务中锁定可执行 Run/Job，检查状态、next_retry_at、尝试预算与实验未取消。
2. 创建 Attempt，attempt_no 单调递增；生成随机 leaseToken，仅保存 SHA-256 摘要，设置 worker_id、lease_until；设置父记录 active_attempt_id。
3. 提交后才由 Worker 执行。禁止持有数据库行锁等待模型/工具返回。
4. 续租、事件上传、完成回调均检查：Worker 凭证有效、Token 匹配、Attempt RUNNING、未过租约、父 active_attempt_id 匹配、父未取消。Worker 本机时钟不能决定有效性。
5. 回收器与回调锁同一父行。先赢得事务的合法操作生效；租约已过期的完成请求返回 STALE_ATTEMPT/409，不能复活。
6. 成功回调一次性封口 Attempt，锁定 Run.accepted_attempt_id 并清除 active_attempt_id；同一完成回调凭相同摘要可重放返回成功，不再新增 Job。异摘要返回409。即使终态幂等重放也必须校验 Worker 与原 Token，不能泄露他人结果。fail回调也保存终态completion_hash，同正文复用原回执，异正文拒绝；已被回收器判为过期的Attempt仍拒绝迟到回调。

默认参数是可配置设计起点：租约60秒、心跳15秒、领取最多10项、每 Worker 本地并发2、Run 最多2次尝试、Job最多3次；每个任务另有硬截止时间，续租不能无限延长硬截止。超时预算写入实验/方案快照，不读取后续可变配置。

## 重试

网络断连、明确限流和暂时服务不可用可重试；无效配置、schema错误、权限拒绝不自动重试；质量 FAIL 不重跑掩盖。退避示例5秒、15秒，加入抖动，next_retry_at 落库。连接已断但上游可能已接受的模型调用属于不确定重试，记录重复调用风险与所有尝试成本，不能承诺外部调用恰好一次。

旧 Attempt 先终态，父 Run/Job 保持 RUNNING，active_attempt_id 置空并写下一次可领取时间；预算耗尽后父终态。失败 Attempt 的 Trace 保留，但评测输入只取 accepted_attempt_id。Job 同理，最终结果只能由有效 JobAttempt 创建，每个 Job 唯一一条 Result；失败尝试证据保留在尝试表和 Artifact。重评是新建批次与 Job，不是复活旧 Job。

## 封口与迟到事件

Worker 完成前上传全部事件并获得逐项确认，再调用完成。完成事务验证封口摘要后不再接受新的业务事件；重复已存在 event_id 可重放，新的迟到事件拒绝并记录计数。V1 不使用“等待几秒应该到齐”作完整性判断。离线整行 Run+events 提交后封口，失败整行回滚。

## Run / Experiment 聚合

Run 的 required Job 必须全部达到 COMPLETED 才能技术完成；NOT_APPLICABLE/INDETERMINATE 仍属于算子正常执行，质量门禁另行判断。required Job FAILED/SKIPPED 导致 Run FAILED；optional Job 失败保留警告但不让技术成功率下降。所有 Job 都终态后再聚合 Run。

实验启动时固定 expected_run_count=样本数×各Target.repeat_count之和。在线必有对应槽位；离线允许缺失但缺失数必须进入报告。所有已提交工作终态后：无缺失且技术成功全数→COMPLETED；至少一个技术成功且有失败/缺失→PARTIAL_FAILED；无技术成功→FAILED。取消优先→CANCELLED。质量 FAIL 不让 Experiment 技术状态变 FAILED。离线0条有效记录禁止开始。

评测批次独立聚合：expected_job_count 在 Job 全部创建前冻结，Job 全部终态后按"全完成/部分完成/全失败"判定 COMPLETED/PARTIAL_FAILED/FAILED。Experiment 的技术状态只由初始批次（trigger_type=INITIAL）与 Run 执行结果决定，后续重评批次失败不得回写 Experiment.status。

## Outbox 与 MQ（TASK-011）

业务状态变化和 Outbox 插入同一事务。发布器短事务领取 PENDING 到租约；不持锁跨网络；持久化消息、durable 队列、publisher confirms、mandatory routing；确认成功且未被退回才标记 PUBLISHED。确认丢失可能重发，必须允许重复。

消费者在数据库事务内写入 inbox 唯一(consumer_name,event_id)和短时调度效果，事务提交后手动 ACK。长时间 Agent 执行通过数据库 Attempt 承接，不保持 MQ 消息未确认直到模型完成。重复通知可 ACK；不支持的事件版本进入隔离队列，不能无限 requeue。消费成功不代表 Agent 成功。

Outbox 超限置 FAILED并告警；人工重新投递保留同 event_id，增加发布次数；已成功 Inbox 消息不重新产生副作用。轮询扫描负责发现通知遗漏，MQ只是加速。Redis锁不是正确性前提。

发布确认回写须匹配当前claimToken和有效发布租约，失效发布器不能将新领取者的事件错误标为已发布。Inbox同eventId异payloadHash应隔离并告警，不能当作正常重复静默ACK。数据库通知消费只做短事务唤醒登记；Python消费者通过`POST /internal/v1/notifications/accept`完成inbox事务，不直连MySQL；路由见API契约。

## 限流（TASK-014）

限流保护的是**依赖容量**，不是正确性：它不参与状态判定，也不改变任何业务事实。三条约束：

1. **状态必须共享。** 令牌桶在 Redis 里，多实例看到同一个桶。进程内计数器在扩缩容后立刻失效，
   而且"每实例 N 次"等于"总量 N × 实例数"，是伪限流。
2. **判定必须原子。** 「补令牌 → 判断 → 扣减 → 写回」在 Lua 内完成；应用侧 `GET`+`SET`
   在并发下会超额放行，超额量随并发度增长。
3. **依赖故障不得污染业务结论。** 429（超限）与 503（依赖故障）严格区分；
   模型调用被限流是**可重试技术错误**，不得记为质量 FAIL、不得产出分数。
   Redis 故障时按策略 `FAIL_OPEN` 或 `FAIL_CLOSED`，**不允许退化为本地计数**。

阈值、Key 设计、两层接入点与排查见 [`../modules/rate-limiting.md`](../modules/rate-limiting.md)。

## 故障验收

必须注入：领取后进程崩溃、续租丢失、新 Attempt 开始后旧回调抵达、数据库提交后 ACK 前崩溃、发布成功后确认丢失、导入同一行重放、取消与回调并发、必需算子技术失败、Agent业务失败但算子正常完成。断言唯一结果、无状态倒退、无跨 Attempt 污染、计数可重算。
