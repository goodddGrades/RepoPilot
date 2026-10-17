# TASK-005 规范JSONL离线导入

前置：TASK-004。

## 必读

- docs/modules/offline-evaluation.md
- docs/architecture/02-domain-model.md
- docs/architecture/04-api-contract.md
- docs/contracts/dataset-case.schema.json
- docs/contracts/offline-run.schema.json

同时读取README、AGENTS.md、tasks/README.md及前置任务实际交付报告。数据库字段仍以database-schema.json为准。

## 实施内容

1. 实现READY文件上传、两类导入批次、流式JSONL校验与逐行事务。
2. 实现import_records/errors、幂等复用、计数可重算、恢复读取和取消；导入写入与start锁相同实验行。
3. 离线Run绑定已发布Case与Target，可没有AgentVersion/Attempt；seal后启动统一Job。
4. 界面展示非空行处理数、错误行、来源、未知数据和缺失槽位；先支持规范JSONL。

## 验收

- examples两文件能按说明进入统一Run/Job；缺Case明确报错。
- 同批次重放不重复计数，跨批次相同来源同正文复用Run，异正文409。
- 半途崩溃恢复、逐行错误、取消竞争和开始后禁止导入均正确。
- 未知成本/输出保持缺失，单LLM调用不伪装完整Agent轨迹。

## 开发边界

不解析未经样例核对的所有LiteLLM格式，不修改已发布数据集，不实现游离Run。

## 交付

按tasks/README.md模板报告实际文件、接口/迁移差异、命令和测试数量。补齐对应OpenAPI和前端类型；保持前置测试通过。环境阻塞标为未验收，不虚构通过。仅提交本地commit，不push。任务完成即停。
