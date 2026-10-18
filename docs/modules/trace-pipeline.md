# 轨迹采集、存储与展示

## 摄取

在线事件必须带当前runAttemptId并通过租约校验；离线事件通过每行事务生成runId和eventId，runAttemptId=null。服务端校验Run、Artifact、Attempt归属，计算eventHash并以eventId幂等。id是数据库行ULID，eventId是跨传输重放标识，两者职责不同。

payload只存小体积脱敏结构；大输出上传对象存储后引用Artifact。对象先写，元数据后提交；失败留下的无引用对象由定期清理器按宽限期删除，不能在事务回滚时假设对象存储也自动回滚。READY元数据只在hash和大小验证后创建。

## 查询

基础筛选：Run、Attempt、eventType、occurredAt游标。不要用JSON全表LIKE检索构建通用搜索。报告到Run的失败筛选使用Job/Result/标注结构化列，Trace正文全文检索不在V1承诺内。

事件列表按occurredAt,id稳定分页；拓扑视图另用span关系和toolCallId组织。展示缺父节点、未知trace、时间冲突。sequenceNo相同允许；不能简单ORDER BY数据库创建时间并声称是实际调用顺序。

## 页面

Run详情包含任务输入、期望、接受输出、执行尝试选择、事件时间线、工具调用、算子结论和证据跳转。默认只展示接受Attempt，可选择失败Attempt排障；切换Attempt清空旧游标。完成封口后刷新完整事件，活跃时增量显示但标识尚未完整。

## 成本

观测完整性单独存Run.observation_metadata_json：outputKnown为布尔，traceCoverage为NONE/PARTIAL/COMPLETE，outcomeSource和adapterVersion说明来源。在线complete可提供observationMetadata，但后端依据适配器注册能力与事件清单复核，不能仅信任Worker自称完整。离线未声明时保守置outputKnown=是否显式提供output、traceCoverage=PARTIAL（有事件）或NONE（无事件）；只有经验证的适配器能够声明COMPLETE。outputKnown=true且空内容与outputKnown=false须分开处理。

usage结构固定基础字段：inputTokens、outputTokens、totalTokens、costAmount、currency、costKnown、costSource、latencyMs；未知值为null，不能0填充。金额JSON使用十进制字符串；costKnown=true时costAmount/currency/costSource必填，costAmount≥0；totalTokens若已知不得小于各已知分项和。

来源优先明确的供应商计费，其次按固定价格快照估算并标ESTIMATED；缓存/推理token等额外量保留扩展字段。不把不可观测成本算作零；不同币种分别报告，不隐式兑换。

报告的“最终观察成本”取接受Attempt或离线usage；“平台实际执行成本”汇总所有RunAttempt和JobAttempt（包括重试和Judge）。这两个口径必须分开，避免只展示成功那次费用。
