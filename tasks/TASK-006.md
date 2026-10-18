# TASK-006 轨迹检索、附件与可视化

前置：TASK-005。

## 必读

- docs/modules/trace-pipeline.md
- docs/architecture/05-event-contract.md
- docs/architecture/04-api-contract.md

同时读取README、AGENTS.md、tasks/README.md及前置任务实际交付报告。数据库字段仍以database-schema.json为准。

## 实施内容

1. 实现Run/Attempt事件筛选与签名游标，按occurredAt,id稳定排序。
2. 实现工具关联、span拓扑与缺失父节点展示，支持失败Attempt切换与证据定位。
3. Artifact下载授权、大小/hash一致性与无引用对象清理；所有文本安全转义。
4. 活跃Run显示暂不完整，封口后重新读取稳定时间线。

## 验收

- 重复sequenceNo、乱序抵达、缺父span和未知traceId均可展示且不造假。
- 游标过滤变化不能复用，封口轨迹无重复遗漏，拒绝跨Run证据。
- 失败Attempt轨迹不会混入接受Attempt默认查询。
- HTML/脚本载荷不执行，附件不持久化临时签名URL。

## 开发边界

不建全文搜索集群，不引入ClickHouse，不把ULID作为Trace ID。

## 交付

按tasks/README.md模板报告实际文件、接口/迁移差异、命令和测试数量。补齐对应OpenAPI和前端类型；保持前置测试通过。环境阻塞标为未验收，不虚构通过。仅提交本地commit，不push。任务完成即停。
