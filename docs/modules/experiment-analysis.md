# 实验报告、配对回归与门禁

## 每个指标必须带分母

| 指标 | 定义 |
| --- | --- |
| 槽位覆盖率 | 已导入或已生成观察数 / expectedRunCount；离线缺失不可忽略 |
| 技术完成率 | COMPLETED Run / expectedRunCount；同时列出FAILED/TIMED_OUT/CANCELLED/缺失 |
| 任务成功率 | SUCCESS / (SUCCESS+FAILURE)，另列UNKNOWN和NOT_APPLICABLE数量与占比 |
| 算子通过率 | PASS / (PASS+FAIL)，另列INDETERMINATE、NOT_APPLICABLE、技术失败及缺失 |
| 平均得分 | 只对非空score计算，显示有效样本数；不可把null补0 |
| 耗时 | 最终观察执行耗时p50/p95及有效样本数；另报含排队、重试、评测的端到端耗时 |
| 成本 | 最终观察与全部尝试分开、Agent与Judge分开、币种分开，并显示未知成本覆盖率 |

耗时不得从页面读取时刻减去开始时刻作为完成值；未结束观察标进行中。离线来源没有可靠start/finish则不参与耗时统计。每个数字能点开对应筛选Run列表。

## 比较资格

V1先支持同一Experiment内Target比较，因此共享数据集与方案版本。配对键为(datasetCaseId,repeatIndex)。仅比较双方可用的观察，并同时展示总槽位、成功配对、单侧缺失与技术错误。未知AgentVersion的导入Target可以做描述性对比，但显示来源未知，不声称是受控因果实验。

输出：PASS→FAIL回归、FAIL→PASS改善、稳定、未知/缺失、scoreDelta、成本/耗时变化。对多次重复既展示逐次配对，也按Case聚合均值与分布；不能把同Case的相关重复当独立样本计算显著性。跨实验且版本不同的受控比较不在V1自动门禁范围，先返回不可比原因。

## 质量门禁

PlanVersion.config固定gate规则，指定evaluatorKey、最大回归数量、最低有效配对覆盖率、INDETERMINATE/NOT_APPLICABLE如何处理，以及可选成本预算。默认缺少必需指标或覆盖不足→INCONCLUSIVE（证据不足），有明确规则违规→FAIL，全部有充分证据且满足阈值→PASS。门禁结果不复用Experiment技术状态。

聚合顺序：先判断明确违规FAIL；无违规但证据不足INCONCLUSIVE；其余PASS。门禁结果含规则版本、样本数、违规Run链接和不可用原因，不只显示一个绿勾。阈值是方案配置，不暗示统计显著性。

报告在V1按需从结构化表聚合，不另存可漂移的结果总表；量大后再依据压测引入缓存/物化视图，缓存键包含实验和标注视图版本，并说明失效机制。
