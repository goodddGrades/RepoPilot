# TASK-010 实验报告、配对回归与门禁

前置：TASK-008,009。

## 必读

- docs/modules/experiment-analysis.md
- docs/modules/frontend-design.md
- docs/architecture/04-api-contract.md

同时读取README、AGENTS.md、tasks/README.md及前置任务实际交付报告。数据库字段仍以database-schema.json为准。

## 实施内容

1. 实现结构化聚合与可下钻的报告，每指标返回分子/分母/缺失与未知计数。
2. 同实验Target按Case/repeat配对，显示改善/回归/单侧缺失；重复样本不伪装独立统计。
3. 按发布方案gate规则计算PASS/FAIL/INCONCLUSIVE，并展示违反规则和证据不足原因。
4. 成本币种、最终观察与全部尝试、Agent与Judge分开。

## 验收

- 人工构造小数据能逐项手工核对分母，缺失槽位进入覆盖率。
- UNKNOWN/N/A不算PASS，null分数不补0，不同币种不相加。
- 描述性离线对比标识Agent版本未知，不宣称因果优劣。
- 技术完成与质量门禁显示分别正确，原始/已审核标注视图可切换。

## 开发边界

不先做缓存/物化表，不自动跨不同数据集版本得出受控回归结论。

## 交付

按tasks/README.md模板报告实际文件、接口/迁移差异、命令和测试数量。补齐对应OpenAPI和前端类型；保持前置测试通过。环境阻塞标为未验收，不虚构通过。仅提交本地commit，不push。任务完成即停。
