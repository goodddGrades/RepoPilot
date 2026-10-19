# TASK-009 交付报告：人工标注与失败归因

| 项 | 值 |
| --- | --- |
| 分支 | `develop` |
| 前置 | TASK-007（评测执行引擎已闭环）、TASK-008（Judge 与真实 Runner） |
| 数据库迁移 | **无新增**。复用 TASK-002 已建的 `annotation_tasks` / `annotation_records` |
| OpenAPI | 新增 `docs/contracts/openapi-annotation.yaml` |

## 1. 范围与结论

TASK-009 要求实现人工复核的完整闭环：创建 → 原子领取 → 提交 → 审核/退回 → 取消，
并保证失败可归因、历史不可覆盖、并发不丢失。全部完成并有真实 MySQL 集成测试覆盖。

| 验收项（任务书原文） | 状态 | 验证方式 |
| --- | --- | --- |
| 并发领取仅一个成功 | ✅ | 8 线程并发，同一 `expectedVersion`：1 OK + 7 × `OPTIMISTIC_LOCK_CONFLICT`，库里只有 1 条 CLAIM |
| 版本冲突 409 | ✅ | 过期 `expectedVersion` → 409，且不产生任何副作用 |
| 重复提交不重复历史 | ✅ | 同幂等键重发 → 版本不变、`SUBMIT` 记录仍只有 1 条 |
| 退回重提保留全部记录 | ✅ | CLAIM→SUBMIT→RETURN→CLAIM→SUBMIT→REVIEW 全链 6 条记录齐全 |
| 原 `EvaluationResult` 保持不变 | ✅ | 提交前后 `verdict\|reason\|score` 拼接串逐字节相等 |
| REVIEWED 视图只取有效审核 | ✅ | 审核记录内容取自**被认可的那次**提交（`RETRIEVAL`），而非被退回的那次（`CONTEXT`） |
| 页面标明开发模式，不允许冒充 operatorId | ✅ | 接口**不接收** `operatorId`；操作者由后端配置固定，前端顶部常驻提示 |
| 未注册失败分类被拒 | ✅ | 400 且不静默降级为 `OTHER` |

## 2. 三条不可协商的语义如何落地

### 2.1 并发靠数据库分胜负，不靠服务层先读后写

```sql
UPDATE annotation_tasks
   SET status = 'CLAIMED', assignee_id = ?, claimed_at = ?,
       version = version + 1, updated_at = ?
 WHERE id = ? AND version = ?
   AND status IN ('PENDING', 'RETURNED')
   AND (assignee_id IS NULL OR assignee_id = ?)
```

`version = version + 1` 与状态比对写在**同一条** UPDATE 里。两个标注者同时点"领取"，
只有一个拿到 1 行影响数，另一个拿到 0 行并被转成 409。没有"都以为自己领到了"的窗口。

`(assignee_id IS NULL OR assignee_id = ?)` 保证已领取的任务不能被抢走，
同时允许原领取者继续处理被退回的任务。

### 2.2 人工结论永不覆盖自动结论

`evaluation_results` 在整个标注流程中**只读**。人工判断写在 `annotation_records`，
每条记录带 `originalVerdict`——复核当时的自动评测结论。集成测试直接比对数据库：

```java
String before = jdbc.queryForObject("SELECT CONCAT(verdict,'|',IFNULL(reason,'NULL'),'|',IFNULL(score,'NULL')) FROM evaluation_results WHERE job_id = ?", ...);
// ...提交人工标注...
String after = jdbc.queryForObject(同上);
assertThat(after).isEqualTo(before);
```

模块边界由 `EvaluationVerdictQueryService` 门面保证：标注模块不接触评测模块的 Mapper
与表，只问"这个 Run / Job 的自动结论是什么"。

### 2.3 状态与历史同事务

每次动作都在一个 `TransactionTemplate` 里先写 `annotation_records` 再改状态行（或反之），
任一步失败整体回滚。不会出现"状态变了但没有记录"。

## 3. 失败归因：UNKNOWN 是显式选项，不是兜底

`FailureCategory` 注册 9 个值。判 `FAIL` 时 `failureCategory` **必填**，
但允许显式填 `UNKNOWN`（"证据不足，不归因"）。

这样设计的原因是：强迫标注者在证据不足时挑一个类别，会让失败归因报表**看起来完整
而实则空洞**——分母是全部失败，分子却掺了编造的归因。显式 `UNKNOWN` 让"我不知道"
在统计里可见。

未注册的值返回 400 并列出全部可选值，**不静默降级**为 `OTHER`：
静默降级会让数据质量问题伪装成归因完整。

## 4. 前端：标注工作台

新增 `AnnotationWorkbenchView.vue`（`/annotations`），并在 Run 详情页加了入口。

页面遵守与后端一致的三条约束：

1. **显式展示"人工改判"**：任务列表与历史时间线都同时显示自动结论与人工结论，
   两者不一致时打「人工已改判」标签，并说明"原结论保留不变"。
2. **409 提示刷新而不是重试覆盖**：捕获 `OPTIMISTIC_LOCK_CONFLICT` 后自动重新拉取
   任务，让页面立刻显示他人的最新结论。
3. **缺失就是缺失**：没有自动结论时显示"无结论（技术失败）"，不显示成 PASS 或 0 分；
   没有证据引用时留空，不编造内容。

顶部常驻开发模式提示：V1 无真实鉴权，所有操作记为同一个后端配置的 operator。

## 5. 实际发现并修复的缺陷

以下是本次开发中**真实发现**的问题，不是复述计划：

### 5.1 Java 文本块行尾空格被剥离，导致 SQL 语法错误（严重）

```java
// 错误写法
String COLUMNS = """
        id, run_id, ...
        """;
@Select("SELECT """ + COLUMNS + """ FROM ... """)
```

Java 文本块会**剥离行尾空白**，`"SELECT "` 的那个空格消失，拼成 `SELECTid, run_id, ...`。
编译期完全正常，运行时每个查询都抛 `BadSqlGrammarException`——13 个用例同时 500。

修复：`COLUMNS` 改用普通字符串拼接（`"a, b," + " c"`），并加注释说明**为什么不能
用文本块**，避免后来者改回去。

### 5.2 审核意见被固定文案覆盖（语义缺陷）

```java
// 修复前：审核者写的"认可"被丢掉，只留"审核通过"
approved ? "审核通过" : reason
```

"通过"已经由 `action=REVIEW` 表达，再写一句"审核通过"只会把审核者真正写的
意见挤掉——而审核意见恰恰是复核留痕里最该保留的部分。改为直接保存 `reason`。

### 5.3 Mapper 里写了不存在的列（严重）

`EvaluationJobMapper.findByRunIdsOfRun` 的 SELECT 列表里凭空多出
`evaluator_definition_id`、`execution_order`、`required`、`max_attempts`——
这四列属于 `evaluation_plan_items` / `evaluation_batches`，不在 `evaluation_jobs` 上。
运行期报 `Unknown column`。

这说明**照着相邻方法"看起来应该有哪些字段"去写 SQL 是不可靠的**。已改为与同文件
`findById` 完全一致的列清单，并核对 `docs/contracts/database-schema.json`。

### 5.4 Run 级复核任务看不到任何自动结论（可用性缺陷）

原实现只在任务**绑定了具体 Job** 时才填 `originalVerdict`。但复核对象通常是 Run
（`POST /api/v1/annotation-tasks` 的必填项是 `runId`，`evaluationJobId` 可选），
此时历史里 `originalVerdict` 恒为空——复核者看不到"机器当初判了什么"，
也就看不出自己改的是哪条结论，而这正是复核的意义。

修复：未绑定 Job 时回落到该 Run 的主导结论（任一 FAIL 即 FAIL，
否则有 INDETERMINATE 即 INDETERMINATE）。技术失败的 Run 没有任何结论，
仍然返回 `null`——缺失表示"机器没给出结论"，不表示 PASS。

## 6. 测试

`AnnotationWorkflowIT`（15 个用例，真实 HTTP + 真实 MySQL 8）。

**素材全部由完整链路跑出来**，不用 JDBC 伪造：发布方案 → 建实验 → Worker 领取执行任务
→ 写轨迹事件 → 执行封口 → 生成评测 Job → 领取评测 Job → 提交结论。
人工复核的价值就在于复核的是**真实结论**，用伪造行测等于没测。

关键用例：

| 用例 | 验证的不变式 |
| --- | --- |
| `concurrentClaimHasExactlyOneWinner` | 8 线程并发领取只有一个赢家，其余 409 |
| `claimedTaskCannotBeStolen` | 已领取不能被抢走 |
| `submitRequiresValidClaim` | 未领取不能提交；`expectedVersion` 过期不改状态 |
| `submitPersistsRecordWithoutTouchingAutoResult` | 记录带 `originalVerdict`；自动结果字节级不变 |
| `repeatedSubmitWithSameKeyDoesNotDuplicate` | 同幂等键重发不追加记录 |
| `returnThenResubmitKeepsFullHistory` | 退回重提 6 条记录齐全；REVIEWED 视图只取被认可的提交 |
| `reviewRequiresExplicitDecision` | `approved` 必须显式给；退回必须给原因 |
| `cancelIsTerminalInBothDirections` | 取消后不可提交；已审核为终态不可取消 |
| `dispatchCandidatesSkipHealthyRuns` | 技术失败与自动判未通过都进队列；全 PASS 不进；已派单不重复 |
| `crossRunJobBindingIsRejected` | 不能跨 Run 绑定 Job |

## 7. 未验收与已知限制

1. **无真实用户鉴权**。操作者由 `app.repopilot.annotation.dev-operator-id` 固定，
   所有人共用一个身份。页面已标明，但这不是审计能力。接入 SSO/组织权限后才能谈追溯到人。
2. **单一主分类**。按 `docs/modules/annotation-system.md`，V1 只支持一个主分类，
   多标签归因未实现。
3. **无证据归属校验**。`evidence` 只做了"必须是数组且 ≤ 50 条"的形状校验，
   尚未校验引用的 eventId 是否真属于该 Run（TASK-007 的 `EvidenceValidator`
   只覆盖评测侧）。跨 Run 引用证据在 V1 是可能的。
4. **REVIEWED 视图的数据层尚未实现**。审核记录的快照语义已在
   `AnnotationRecordMapper.findLatestReview` 与测试中确立，但 TASK-010 的报告
   还没有真正按 RAW / REVIEWED 两个视图出数。
5. **未做鉴权与授权**，符合任务书"开发边界：不开发完整组织权限和生产 SSO"。

## 8. 对下一任务的前置

TASK-010（实验报告与回归）可以直接消费本任务的数据：

- 失败归因分布：按 `annotation_records.failure_category` 聚合，分母用全部 FAIL 记录，
  `UNKNOWN` 单列（不摊到其他类别）。
- 人工与自动的一致率：同一 Run 的 `originalVerdict` vs 最新有效 `SUBMIT.verdict`。
- RAW / REVIEWED 双视图：`findLatestReview` 已是 REVIEWED 视图的唯一数据源。
- 回归对比：报告需要区分"自动判失败"与"人工改判后通过"，本任务的历史记录已能区分。

## 9. 执行记录

见仓库执行日志与最终汇总报告。本任务未推送远端（按 tasks/README.md 要求，
TASK 级交付只提交本地 commit）。
