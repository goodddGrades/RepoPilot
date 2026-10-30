# TASK-010 交付报告：实验报告、配对回归与门禁

| 项 | 值 |
| --- | --- |
| 分支 | `develop` |
| 前置 | TASK-008（Judge 与真实 Runner）、TASK-009（人工标注与失败归因） |
| 数据库迁移 | **无新增**。全部指标、结论、复核记录都已在 TASK-002~009 建好的表里 |
| OpenAPI | 新增 `docs/contracts/openapi-reporting.yaml` |
| 新增接口 | `GET /api/v1/experiments/{experimentId}/report`（只读） |

## 1. 范围与结论

TASK-010 要交付的是**一个可下钻、每个数字都能手工核对分母的实验报告**，包含槽位级指标、
同数据集下的版本配对回归、以及按发布方案 gateRules 计算的质量门禁。全部完成，
27 个集成测试用例走真实链路（建实验 → Worker 领取 → 封口 → 评测 Job → 提交真实结论）。

| 验收项（任务书原文） | 状态 | 验证方式 |
| --- | --- | --- |
| 人工构造小数据能逐项手工核对分母，缺失槽位进入覆盖率 | ✅ | 27 个用例逐项核对分子/分母；`missingSlotsShowUpInCoverage` 断言 `1/2 = 50%` 且 `missing = 1` |
| UNKNOWN/N-A 不算 PASS | ✅ | `notApplicableIsNeitherPassNorFailure`：N-A 不进分母，也不计 PASS |
| null 分数不补 0 | ✅ | `averageIsNullWhenNoValidSample`：无有效样本时 `average` 为 `null` |
| 不同币种不相加 | ✅ | `costsAreGroupedByCurrencyAndUnknownIsNotZero`：USD 与 CNY 各自成行，无"总额"字段 |
| 描述性离线对比标识 Agent 版本未知，不宣称因果优劣 | ✅ | `controlledComparison=false` + `incomparabilityReason`，前端顶部黄色告警 |
| 技术完成与质量门禁分别显示正确 | ✅ | `gateIsIndependentOfExperimentTechnicalStatus`：实验 `COMPLETED` 而门禁 `FAIL`，两者并排 |
| 原始/已审核标注视图可切换 | ✅ | `annotationViewsDifferAfterReview`：AUTOMATIC 回归 1、REVIEWED 回归 0，`evaluation_results` 保持 `FAIL` |

## 2. 四条不可协商的语义

### 2.1 每个比率都带分母

```java
public record Ratio(long numerator, long denominator, Double rate,
                    long excluded, long missing) {
    public static Ratio of(long num, long den, long excluded, long missing) {
        // 分母为 0 时 rate 是 null，不是 0
    }
}
```

前端统一渲染成 `1/2 = 50.0%`。分母为 0 显示"—（无有效样本）"。

这是整份报告最容易出事的地方：一个 `FAIL_RATE = 0%` 既可能是"全测了且全通过"，
也可能是"一个都没测成"。后者显示成 0% 会让读报告的人得出完全相反的结论，
所以**分母必须与分子一起出现**，不能只给一个比率。

`excluded` 与 `missing` 分开：前者是"确实发生过但不计入该分母"（INDETERMINATE、
N-A、技术失败），后者是"应有但完全没有数据"。两者的运维含义完全不同——
前者说明评测跑了但判不出来，后者说明评测没跑完。

### 2.2 观测 / 缺失 / 非终态三态互斥

```java
long observedSlots = runs.stream()
        .filter(run -> run.byEvaluator().values().stream()
                .anyMatch(slot -> slot.verdict() != null))
        .count();
long nonTerminalSlots = countRows(rows, RunStatus.PENDING, RunStatus.QUEUED,
        RunStatus.RUNNING, RunStatus.EVALUATING);
long missingSlots = Math.max(expectedSlots - observedSlots - nonTerminalSlots, 0);
```

`missing = missingSlots + nonTerminalSlots`。三者严格互斥，一个槽位不会既被算成
"还在跑"又被算成"缺失"——重复计数会让分母虚高，覆盖率看起来比实际好。

### 2.3 门禁判定顺序不可调换

```
明确违规（回归超限 / N-A 处理 / INDETERMINATE 计失败 / 成本超预算） → FAIL
无违规但证据不足（覆盖率不足 / 无配对槽位）                     → INCONCLUSIVE
其余                                                              → PASS
```

先判证据不足会让**已经明确的回归被降级成"没结论"**，等于没人看。所以哪怕覆盖率只有
2/3，只要回归数超了上限就必须报 FAIL——`explicitViolationOutranksInsufficientEvidence`
专门钉这一条。

`GateEvaluator` 是纯函数：输入规则、配对结果、预算币种成本，输出结论。判定逻辑与
数据装配分离，可以单独推理。

### 2.4 成本口径分离

四个维度各自独立，互不相加：

| 口径 | 数据来源 | 用途 |
| --- | --- | --- |
| 最终观察成本 | Run 的 `usage_json`（被接受的那次 Attempt） | 用户感知的"这次实验花了多少" |
| 全部尝试成本 | 所有 `RunAttempt` + `JobAttempt` 的 usage | 平台真实支出（含被重试掉的） |
| Agent / Judge | Judge 侧加 `JUDGE:` 前缀单列 | 分清"被测对象贵"还是"裁判贵" |
| 币种 | `byCurrency` 列表 | USD 与 CNY **没有总额** |

门禁预算用"最终观察成本"：预算是给人看这次实验花了多少，平台内部重试与 Judge 开销
另有口径。混用会让预算既不等于用户感知也不等于平台真实成本。

`candidateCostInCurrency` 只取与预算币种**恰好相同**的那一条，没有就返回 `null`
由门禁判"无法评估"——不拿别的币种凑一个数。

## 3. 配对：用 ID 而不是业务标识

```java
private record PairKey(String datasetCaseId, int repeatIndex) { }
```

刻意不用 `caseKey`：它是业务可读标识，在不同数据集版本之间可能重复甚至被复用，
只有 ID 唯一才能避免把两个不同数据集的同名用例错配成一对。展示仍用 `caseKey`。

同一 `(case, repeatIndex)` 的多次重复**各自是一个槽位**，不合并成一个样本——
合并会把"同一 case 表现不稳定"这个事实抹掉，而那恰恰是回归分析要暴露的东西。

五种判定结果：`REGRESSION`（PASS→FAIL）、`IMPROVEMENT`（FAIL→PASS）、`STABLE`、
`SINGLE_SIDED`（单侧缺失）、`TECHNICAL_ERROR`（任一侧技术失败）。
**技术失败不是质量回归**：混进去会让人去查错方向（以为是 Agent 退化了，其实是评测挂了）。

`validPairCoverage` 的分母是配对键空间总大小 `totalSlots`，不是成功配对数——
否则单侧缺失会从分母里消失，"没数据"就被算成"没问题"。

## 4. 模块边界：报告不建自己的 Mapper

`report` 包不是一个独立领域模块。`modules/report/package-info.java` 写明：
报告代码属于 experiment 的读侧，数据访问通过 experiment / evaluation 模块暴露的
查询接口完成，**不另建一套 Mapper 直接跨表 join，也不为报告新建业务表**。

本次为此新增 4 个只读门面，都是跨模块协作的正式接口而不是绕过边界：

| 门面 | 所属模块 | 暴露什么 |
| --- | --- | --- |
| `EvaluationReportQueryService` | evaluation | 批次解析、批次内 Job/Attempt、结果读取、技术失败判定 |
| `PlanVersionConfigQueryService` | evaluationconfig | 方案版本与配置 JSON |
| `GateRuleReader` | evaluationconfig | 解析并校验 `gateRules` |
| `AgentVersionQueryService` | agent | Agent 版本标签（未知返回 `null`，与空串区分） |
| `ReviewedVerdictQueryService` | annotation | 已审核人工结论及其槽位归属 |

`GateRuleReader` 的解析失败**一律抛异常**。让配置错误退化成"门禁规则缺失 → INCONCLUSIVE"
看起来很安全，实际后果是所有违规都被静默放过——配置坏了必须炸出来。

## 5. 实际发现并修复的缺陷

以下是本次开发中**真实发现**的问题。

### 5.1 `passRate` 是 record 计算方法，Jackson 根本不序列化（最隐蔽）

```java
// 修复前
public record VerdictDistribution(long pass, long fail, ...) {
    public Ratio passRate() { ... }   // ← record 的计算方法，不是组件
}
```

契约 `openapi-reporting.yaml` 声明了 `passRate` 字段，响应里却**永远不会出现**。
Jackson 只序列化 record 的组件（构造参数），手写的 accessor 会被忽略。
前端于是读到静默的 `undefined`——不报错、不报警，只是比率永远算不出来。

修复：改为真正的 record 组件 + 静态工厂 `VerdictDistribution.of(...)`。
这也是 `notApplicableIsNeitherPassOrFailure` / `passRateExcludesIndeterminateAndNotApplicable`
两个用例从失败转通过的关键。

### 5.2 `observedSlotCount` 统计口径错误

原实现统计"有 Job 的 Run"，而不是"拿到结论的 Run"。但 Job 一创建就进批次登记表，
哪怕它还在 PENDING 或已经技术失败。结果：一个从未产出结论的 Run 被算成"已观察"，
覆盖率显示 100%，而实际上什么都没测出来。

### 5.3 缺失与非终态重复计数

`missing` 与 `nonTerminal` 各算一遍，同一个槽位被重复计入。诊断时出现
`technicalCompletion.missing = 2` 而 `missingCount = 1` 的矛盾。已改为
`missing = max(expected − observed − nonTerminal, 0) + nonTerminal`。

### 5.4 自我比较未被拒绝

`baseline == candidate` 时所有槽位都判为 STABLE、回归 0——一个"全部稳定、零回归"的
假象，正是选错参数时最容易误读成"没问题"的场景。现在：多 Target 时 400 并说明原因；
单 Target 实验返回 `null`（没有可比较的对象，不是非法请求）。

### 5.5 `annotation_tasks` 没有 `experiment_id` 列（严重）

`listReviewedVerdictsOfExperiment` 写了 `WHERE t.experiment_id = ?`，运行期报
`Unknown column 't.experiment_id'`。查 `database-schema.json` 才发现
`annotation_tasks` 只记复核对象（`run_id` / `evaluation_job_id`），实验归属要经
`evaluation_runs` 才能拿到。已改为 JOIN `evaluation_runs`。

这个缺陷的性质与 TASK-009 的 5.3 一致：**照着"这里应该有实验字段"的直觉写 SQL
是不可靠的**，字段必须逐个对照契约核实。

## 6. 前端：实验报告页

新增 `ReportView.vue`（`/experiments/:id/report`），从实验详情页进入。

报告必须绑定具体实验，所以**不作为一级菜单**——放进 `navigationItems` 会让人以为
存在一个"所有实验的汇总报告"，而那需要跨数据集版本的对比，按任务书边界是不做的。

页面遵守与后端一致的五条约束：

1. **比率带分母**：`1/2 = 50.0%`，分母 0 显示"—（无有效样本）"。
2. **null 不补 0**：平均分、p50/p95、成本金额为 `null` 时显示"—"。
3. **不同币种不相加**：成本按币种分行，没有"总额"列。
4. **技术失败单列**：在结论分布里独立成行，不并入 FAIL。
5. **门禁与实验技术状态并排**：顶部固定告警"实验状态为 COMPLETED 只说明平台把该跑的
   跑完了，质量是否达标由下面规则判定"。

其他：批次范围（初始/最新）与结论视图（自动/含人工复核）切换；配对槽位可按判定结果筛选
并分页；每个 Run ID 与违规 Run 列表都可点击下钻到 Run 执行证据页；算子结论分布用条形图
展示；Agent 版本未知时顶部告警并标为描述性对比；空状态区分"没有 Target"、"没有算子结论"、
"没有符合筛选的槽位"、"未选择候选 Target"四种情形。

## 7. 测试

`ReportAggregationIT`（27 个用例，真实 HTTP + 真实 MySQL 8）。

**素材全部由完整链路跑出来**，不用 JDBC 伪造结论行：发布方案 → 建实验 → Worker 领取执行
任务 → 写轨迹事件 → 执行封口 → 生成评测 Job → 领取评测 Job → 提交真实
`evaluation_results`。报告的价值在于读平台真正产出的数据，用插进去的行测等于没测。

技术失败槽位走完整的 `领取 → 失败 → 退避 → 再领取 → … → 重试耗尽` 真实路径，
断言 Job 终态为 FAILED 且 `resultCount == 0`（技术失败不产生质量结论）。

| 分组 | 用例要点 |
| --- | --- |
| 指标与分母 | 覆盖率分母是应有槽位；缺失槽位进 missing；INDETERMINATE 与 N-A 不进通过率分母；null 分数不进均值；无有效样本时均值为 null；无耗时时分位数为 null |
| 成本口径 | 不同币种分别报告；未知成本为 null 非 0；最终观察与全部尝试分开 |
| 配对回归 | PASS→FAIL 记回归；FAIL→PASS 记改善；重复样本各自配对不合并；单侧缺失单独计数；技术失败不记回归；Agent 版本已知才是受控对比 |
| 门禁 | 回归超限判 FAIL 并列出违规 Run；覆盖率不足判 INCONCLUSIVE 且违规列表为空；既有回归又覆盖不足必须判 FAIL；证据充分判 PASS；没配 gateRules 判 INCONCLUSIVE 而非 PASS；门禁不复用实验技术状态 |
| 批次与视图 | 默认 INITIAL、LATEST 取最新并回显批次上下文；AUTOMATIC/REVIEWED 两视图结论不同且自动结论字节级不变；只读接口重复调用结果一致；非法 scope/view 返回 400；未知实验与外来批次返回 404 |

集成测试素材里有一条值得记录的教训：**`claimAs` 一次最多返回 5 个任务**。
测试里"先排空其余 Job、再单独领取目标 Job 注入技术失败"的做法会让目标 Job 的租约
被上一轮持有而永远领不到，现象是"任务不存在"——真实原因却是测试自己拿着租约。
最终把技术失败注入合并进同一次 drain 循环，用同一个 task 对象走完整条路径。

## 8. 未验收与已知限制

1. **usage 字段名与契约不一致（真实偏差，未在本任务扩大范围修改）**。
   `docs/modules/trace-pipeline.md` 写 `costAmount` / `costKnown` / `costSource`，
   而 Worker CLI 与 Judge 适配器实际写的是 `costUsd` / `currency`。
   `UsageReader` 两者都读（`costAmount` 优先），显式 `costKnown=false` 优先于任何字段猜测。
   应在 TASK-011 或独立的一致性修正里统一到契约定义的字段名。

2. **Judge 成本归属依赖 jobId 索引**。`JobAttempt` 只认 `jobId`，因此 Judge 侧成本
   必须经 `runIdByJobId` 索引才能归到正确的 Run。索引按 jobId 去重，
   若同一批次内出现同一 jobId 的多条 Run 记录（当前不可能），会静默取第一条。

3. **无成本预算的跨币种换算**。配了 `costBudget` 但只有其他币种的数据时，
   门禁判"无法评估"（BLOCKING），不做汇率换算——换算需要引入汇率来源与时点，
   属于新设计。

4. **无报告缓存与物化表**。按任务书边界"不先做缓存/物化表"。当前每次查询都实时聚合
   `evaluation_runs` × `evaluation_jobs` × `evaluation_results` × attempts，
   大实验（数万 Run）下会有性能压力，留给 TASK-012 压测后再定。

5. **`expectedSlotCount` 取 `expectedRunCount / Target 数`**。若实验的 Target 数
   与实际创建的目标数不一致（例如创建过程中失败），这个除法会与真实槽位数有偏差。
   当前实验创建是原子的，没有中间态，所以一致。

6. **未做自动跨数据集版本对比**。符合任务书边界。报告只在本实验的
   `datasetVersionId` 内配对。

7. **前端分页在客户端做**。报告是一次性返回的快照，槽位明细在浏览器里筛选分页。
   大实验下这个响应体会很大，与限制 4 是同一个问题的两面。

## 9. 对下一任务的前置

TASK-011（Outbox/RabbitMQ 可靠性增强）的输入已就绪：

- 报告是判断消息可靠性改造有没有产生副作用的**观察窗口**：任何重复投递、
  租约丢失、状态机错乱都会在 `observedSlotCount` / `missingCount` /
  `technicalCompletion` 上直接显形。
- `EvaluationReportQueryService` 已经把批次内 Job / Attempt 一次性读出，
  故障演练后重跑报告即可对比，不需要新增查询。

## 10. 执行记录

见仓库执行日志与最终汇总报告。本任务未推送远端（按 `tasks/README.md` 要求，
TASK 级交付只提交本地 commit）。
