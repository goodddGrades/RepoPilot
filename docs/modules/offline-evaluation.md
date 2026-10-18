# 离线导入与评测

## 两类导入

DATASET_CASES只写DRAFT DatasetVersion；OFFLINE_RUNS只写DRAFT OFFLINE Experiment，且datasetVersionId与实验一致、为已发布版本。两者均以经校验的READY Artifact为输入；原始文件可保留在受控对象存储，界面错误摘要不回显正文。

历史数据没有样本时：先把任务输入规范化为 case_key 与 input，导入草稿数据集（导入器可按固定规则自动生成 DatasetCase），人工检查并发布；随后用同 case_key 导入历史 Run。V1 不支持"游离 Run"，也不让导入器向已发布数据集插入样本。自动生成的样本只写输入，不补造标准答案：没有参考答案时 `expected_json` 留空，没有工具观测时不生成事件；缺证据的算子在评测阶段返回 NOT_APPLICABLE，而不是给通过。规则细节见 [领域模型](../architecture/02-domain-model.md) 的"离线导入与样本归属"。

**两阶段是内部数据处理流程，不是产品操作步骤。** 同一个 JSONL 文件应当只上传一次：服务端解析出样本后先落草稿版本并返回预览（样本条数、caseKey 冲突、缺少哪些证据），用户确认后在同一份已上传文件上继续生成 Run，不需要重新上传。V1 允许前端先做"导入样本"再"导入 Run"两步操作，但两步共用同一个 Artifact 与哈希校验；等价的单次上传入口留到导入向导实现时补齐，届时数据模型不变。`import_batches` 的一次导入可以跨两个阶段，靠 `import_records` 分阶段记录，不要求用户理解内部阶段划分。

LiteLLM各部署日志字段可能不同。先支持本包规范JSONL；额外适配器需用真实脱敏样例建立字段映射、时间语义、请求/响应、用量和错误码测试，不能假定每种日志天然有工具链。单调用按LLM_CALL样本保存；Agent任务需要来源确实能关联的事件才能重建。

## 文件读取

只接受UTF-8 JSONL，允许首行UTF-8 BOM，空白行忽略但保留物理行号；拒绝压缩包、无限嵌套JSON和超限字段。单行上限1MiB，events≤500，每payload≤64KiB，整文件默认20MiB/10000非空行。每行按对应Schema校验，unknown字段报错防拼写静默失效；schemaVersion不支持报错。

时间必须含时区，入库转UTC；finishedAt不得早于startedAt。traceId不合法不能硬塞OTel列；上游任意标识转externalTraceRef。sourceSystem/sourceRecordId必须非空，repeatIndex须在Target预算范围；caseKey必须匹配固定样本。

## 逐行事务与恢复

V1使用Java单批次串行读取，在每行事务中锁批次行；同批次多实例读取允许，但相同(batch,line)回执唯一，已提交行跳过。不持有行锁跨对象存储网络读取，原始文件hash在创建批次时已固定。

DATASET_CASES每行写入还须锁父DatasetVersion并重新检查DRAFT，使用与发布相同的父行锁；不能只在创建批次时检查一次状态。OFFLINE_RUNS每行写入同样锁父Experiment并重新检查DRAFT，避免导入与开始交错。

每行先检查已存在import_records回执，再做业务唯一键检查。成功时Run、Trace、Artifact关联校验、回执与计数同事务提交。失败时业务写入回滚，在独立短事务中幂等写REJECTED回执与import_error并更新计数。取消锁同一批次行；取消之后不得提交新行。

DATASET_CASES：相同版本+caseKey相同规范化内容为REUSED，否则冲突。OFFLINE_RUNS：相同实验/Target/source身份同正文hash为REUSED；同身份异内容拒绝；同槽位不同来源拒绝；同来源跨Target允许。run_id保持首次成功创建值，import_batch_id指向首次批次，不被重导覆盖。

record_hash使用原始行字节（去行末LF/CRLF）SHA256用于错误追溯；Run.source_record_hash使用规范化业务JSON摘要用于语义幂等，二者不要混用。恢复顺序从last_line_no继续，但每次仍查回执。最后progress根据回执重算，不信任Worker本机计数。total_count在完成扫描后确定；过程中前端展示已处理数，不显示虚假的准确百分比。

批次完成：全成功或复用→COMPLETED；有成功有失败→PARTIAL_FAILED；无成功→FAILED；取消→CANCELLED。duplicate_count是success_count子集，success+failed=已处理非空行数。批次可部分失败而实验不必立即失败，启动实验前显示缺失槽位和导入错误。

## 启动

必须所有批次已终态且至少一条有效Run。start锁实验行并冻结清单；创建/执行OFFLINE_RUNS导入也锁该实验行，保证开始后不能继续写入。启动事务创建 `INITIAL` 批次，把冻结的 Run 清单写入 `evaluation_batch_runs`，再为每条已导入且 sealed 的 Run 建统一 Job；历史 Run 不创建虚假的RunAttempt。批次 Job 数在创建前冻结为 `expected_job_count`，之后到达的导入记录不得进入该批次。

离线缺少输出时保留null，不臆造答案；依赖输出的算子返回NOT_APPLICABLE。来源taskOutcome不可信时规范为UNKNOWN并保留来源说明。报告必须显示未知率与缺失槽位。
