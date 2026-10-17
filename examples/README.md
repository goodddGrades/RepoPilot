# 最小导入示例

1. 在草稿DatasetVersion导入dataset-cases.jsonl，发布。
2. 使用一个已发布方案，创建OFFLINE实验，Target为targetKey=historical_a，displayName=历史对象A，agentVersionId=null，repeatCount=1。
3. 把offline-runs.jsonl作为OFFLINE_RUNS导入该草稿实验。
4. 启动实验，同一条规则应得到和Mock在线输入相同的结果。

JSON中的英文是API标识：caseKey=样本标识，targetKey=比较对象标识，repeatIndex=重复观察编号，sourceRecordId=来源记录标识，taskOutcome=任务业务结论，UNKNOWN=尚未确定。示例的costKnown=false表示成本未知，不表示零费用。

trace-event.json和event-envelope.json中的ULID仅为结构示例，真实服务生成新值。示例没有真实Agent版本、模型调用或密钥，不表示该流程已经运行。
