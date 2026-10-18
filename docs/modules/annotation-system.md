# 人工标注与失败归因

复核对象为Run，可选绑定某个Job。V1按任务创建，不采用“覆盖Result”的模式。记录包括CLAIM、SUBMIT、REVIEW、RETURN、CANCEL；状态行与追加记录必须同事务提交。

领取使用UPDATE ... WHERE status IN(PENDING,RETURNED) AND version=?；成功增加version。提交必须当前领取者，审核必须SUBMITTED，所有动作带expectedVersion与幂等键。竞争失败返回409并提示刷新，不能后写覆盖前写。

提交内容：verdict、failureCategory、reason、evidence。失败分类初始注册为MODEL_REASONING（推理错误）、TOOL_USE（工具使用）、RETRIEVAL（检索）、CONTEXT（上下文）、ENVIRONMENT（环境）、DATA_QUALITY（数据质量）、EVALUATOR（评测问题）、OTHER（其他）；UNKNOWN结果可以不归因，不能强迫选一个假原因。

REVIEW action持有审核者认可的最终标注快照；退回记录说明原因，再领取重提时追加新SUBMIT。报告REVIEWED视图只使用最新有效REVIEW，不使用未审核草稿，保留原始自动结果。多项归因先只支持主分类，复杂标签后续再扩展。

任务不能跨Run绑定Job，证据也不能跨Run引用。V1测试身份由后端开发配置固定，前端显示开发模式。没有真正用户鉴权和授权前只用于本地/受控演示。

验收：两个请求并发领取只有一个成功；重发提交不追加重复记录；退回重提保留全部历史；版本过期返回409；自动Result字节不变；报告审核视图与原始视图都可回溯到操作者和时间。
