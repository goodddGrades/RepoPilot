# 在线执行

## 提交前校验

数据集和方案必须PUBLISHED且非空，所有Target必须绑定PUBLISHED AgentVersion；Runner已注册；预算有效；每样本重复数1~20；Run总数≤10000；样本taskType在Runner能力内。归档版本不允许新建实验引用，历史已绑定实验仍可读取。发布状态检查与开始动作处于同一事务或使用固定版本行锁，避免并发归档造成不明确行为。

开始动作对DRAFT实验行加锁，写QUEUED和完整Run集合，同步写幂等回执；二次start不新增Run。Run从PENDING置QUEUED的调度结果必须可恢复；后台扫描QUEUED实验的PENDING槽位，确保进程在事务后崩溃仍可推进。

## Runner契约

输入固定为任务样本、已发布AgentConfig、预算、工作目录、取消信号和事件出口；输出为结构化output、taskOutcome、usage或结构化技术错误。Mock必须支持成功、业务失败、技术异常、超时、可重试错误五种预设，不能把所有情况返回成功。

真实Runner通过注册适配器接入。Codex适配必须在本地验证实际可用命令或协议后实现：保留版本、进程退出码、脱敏stderr摘要、工作区、代码提交、最终输出与适配器版本。进程退出0只说明进程正常结束，不必然表示任务成功。执行命令使用参数数组，禁止把用户输入拼为shell。

每Attempt独立工作目录；可信仓库可以用隔离worktree或临时副本。取消/超时清理子进程组与目录，不能只停止等待HTTP。V1只支持可信代码；工具与网络白名单、只读凭证、资源限制为实际启用的运行配置，不宣称容器自动等于安全沙箱。

## 完成与评测

Worker按事件协议写完整事件，确认后提交清单摘要。后端同事务封口、记录acceptedAttempt、转EVALUATING、为全部方案项幂等创建Job。首次规则实现前可以将Job留在PENDING，前端显示“等待评测”，不能用假分数模拟完成。

可重试错误在Attempt终态后等待nextRetryAt，Run保持RUNNING；耗尽后FAILED或TIMED_OUT，保留各次轨迹。任务业务失败可以有有效输出，仍进入EVALUATING并生成质量FAIL。

## 验收

2个Target×3个Case×2次重复应得到12个Run；其中1个基础设施重试只增加Attempt。重复start、重复complete不增加Run/Job；失效租约不能写Trace或结果；取消可停止尚未领取的Run，正在执行的进程尽力停止且不能回写成功终态。
