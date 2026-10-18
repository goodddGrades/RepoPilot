# 统一评测引擎与算子

## 输入冻结

Run必须封口，Agent/数据集/方案/算子版本均可追溯。Java生成固定评测输入及inputHash；事件通过受限API读取，选acceptedAttempt，禁止把失败重试Trace合并成一次成功调用。同一批次内每个Run×方案项只创建一个Job，重试追加JobAttempt，不覆盖最终Result。

## 批次与重评

Job 永远属于一个 EvaluationBatch，不能游离存在。`INITIAL` 批次在实验启动时创建；升级 Judge、规则算子或方案版本后重评，必须新建批次（`REEVALUATION`，`parentBatchId` 指向原批次），而不是复用旧 Job 或复活终态。批次固定一个已发布 PlanVersion，Job 的 `(planVersionId, planItemId)` 与批次的 `(batchId, planVersionId)` 由复合外键共同约束。

批次同时固定**参与本次评测的 Run 集合**：创建批次时把 `runIds` 或过滤条件解析成具体行写入 `evaluation_batch_runs`，Job 的 `(batchId, runId)` 必须指向该表。因此"第二次只重评 20 条失败样本"是一个只含 20 行的批次，而不是一个每次查询都重新过滤的视图。批次与 Run 必须属于同一 Experiment：登记表的 `experimentId` 快照列同时参与两条复合外键，跨实验组合在数据库层无法写入，服务层只负责给出可读错误码。

重评的输入是**已存在的 Run 与其 accepted Attempt / 封口 Trace**：不重新执行 Agent，不新增 RunAttempt，不修改原 Run 的输出与 taskOutcome。新批次产生新的 Job、JobAttempt 与 Result；旧批次的结果保持只读。这样"同一个 Agent 产物、两版 Judge"的差异可以直接对比，也能解释每条分数由哪版算子产生。

批次是结果版本的载体：不设 `result.version` 自增列，避免"批次版本"与"结果版本"两套真相。查询某 Run 的历史评分时按批次列出，不覆盖。报告接口不传批次参数时固定返回初始批次，"最新评测"必须显式请求；否则同一个 URL 会在重评后返回不同结论。

V1按executionOrder串行推进同一Run的算子，不做通用依赖DAG；不同Run并行。前一个Job终态才可领取下一个，技术失败不自动跳过后续取证算子。取消则按状态机结束全部Job。算子不得读取前一个算子的非声明输出，避免隐藏依赖。

## 最小算子集合

| key | 类型 | 条件与输出 |
| --- | --- | --- |
| output.non_empty | RULE | 已有输出但为空→FAIL；来源缺失输出→NOT_APPLICABLE；有内容→PASS |
| answer.exact_match | RULE | 指定outputPath/expectedPath，缺失ground truth→NOT_APPLICABLE；有值后按配置规范化比较 |
| trace.tool_error | TRACE | 完整工具观测中有失败→FAIL；缺少工具观测→NOT_APPLICABLE，不能自动PASS |
| cost.budget | COST | 成本/币种齐全且同币种时比较；未知→NOT_APPLICABLE |
| test.command | TEST | 只运行可信测试白名单，超时为技术错误，测试断言失败为COMPLETED+FAIL |
| judge.rubric | LLM_JUDGE | 固定rubric/prompt/model配置，结构化结果含证据与评分 |

精确匹配规范在算子配置中固定：可选trim、统一换行，默认不忽略大小写、不删除数字或标点。评分统一0~1；非评分型可score=null。若需要把分值转PASS，阈值写config并发布固定；缺失不自动给0分。

## 输出

{verdict,score,reason,evidence,result,generatedBy}。证据引用eventId、artifactId或样本字段路径，服务端校验引用归属。verdict=FAIL表示质量失败，不能因此让Job.status=FAILED。运行异常无最终Result，保留Attempt错误，耗尽后Job FAILED。

required只决定技术完成条件；门禁规则决定是否通过质量标准。required的NOT_APPLICABLE仍是算子正常完成，但质量门禁默认不能把它当通过。taskOutcome来源在PlanVersion.config中仅允许一个明确注册的任务检查算子；未指定则保留Runner或来源的UNKNOWN/明确结果。

## Judge实施

Judge与被测Agent分开配置；固定模型标识、prompt版本、温度、超时、token预算与rubric。被测文本用明确数据边界包裹，不能采纳其“忽略规则”指令；输出严格JSON Schema校验，非法JSON为可识别技术错误而不是FAIL。最多有限重试，保存脱敏请求/响应及每次成本，不记录供应商私有推理链。

证据不足时INDETERMINATE；无适用输入时NOT_APPLICABLE；模型中断/限流为技术失败。LLM解释不是客观事实，界面标“模型判断”。构造至少含指令注入、缺期望、矛盾证据的离线测试集，比较人工审核一致率并显示样本数，不虚报通用准确率。

Ragas留作可选适配器，只有样本确实有question/context/reference等字段时才引入，固定实际依赖版本并验证字段映射；不让V1所有评测都依赖Ragas。Langfuse只导出关联信息，不替代业务Job和Result。

## 执行保障

算子能力注册在Worker启动清单；Worker不支持的key不能领取，Java发现无可用Worker时展示等待而不是静默跳过。Job领取、续租、过期回收和终态结果的原子写入遵守可靠性文档。输出分数越界、generatedBy与算子类型不一致、跨Run证据均拒绝400/409。
