# 事件与消息契约

## 两类eventId

TraceEvent描述Agent执行内部动作；Outbox消息描述业务资源可领取或已完成。二者分别生成ULID，不能把同一个运行中所有动作都复用Run ID作为eventId。

`trace-event.schema.json`定义规范化轨迹；`event-envelope.schema.json`定义MQ信封。所有生产者遵守schemaVersion=1。未知版本返回400/隔离，不尝试猜测字段。新增可选字段不改变旧语义，破坏性变更升级版本并同时提供迁移策略。

## 轨迹事件

| eventType | payload最低字段 | 用途 |
| --- | --- | --- |
| RUN_STARTED / RUN_COMPLETED | runnerType / taskOutcome | 执行边界，最终状态仍以Run为准 |
| MODEL_REQUEST | model,requestSummary | 记录脱敏模型请求摘要 |
| MODEL_RESPONSE | model,outputSummary,usage? | 响应与用量，可引用Artifact |
| TOOL_STARTED | toolCallId,toolName,argumentsSummary | 工具调用开始 |
| TOOL_COMPLETED | toolCallId,toolName,outcome,durationMs | 工具结束，outcome为SUCCESS/FAILURE |
| OUTPUT_PRODUCED | outputSummary | 最终输出或附件引用 |
| ERROR | errorCode,message | 脱敏异常 |

Schema约束信封，payload按本注册表在服务层二次校验。规范字段完整不代表来源观测完整。sourceEventId为上游标识；eventId是归一化标识，重放稳定。在线Worker在首次生成事件时确定ID，网络重发保持ID和正文；离线先检查来源记录幂等，再生成事件ID，并在同一行事务提交。

traceId为32位小写十六进制非零，spanId/parentSpanId为16位非零，可缺失；来源不符合OTel格式时保存externalTraceRef，规范traceId为null。不要生成伪造OTel Trace ID填补未知上游ID。平台自身HTTP观测Trace与被评测Agent Trace分开关联。

拓扑优先parentSpanId与toolCallId；顺序使用sequenceNo和occurredAt辅助；数据库id只作稳定平局排序。parent不存在显示缺失父节点；重复sequenceNo允许；乱序抵达不能导致状态错误。对于两个都缺失顺序/时间分辨率不足的事件，显示“顺序不确定”。

## 消息信封

字段：eventId,eventType,schemaVersion,occurredAt,aggregateType,aggregateId,correlationId,payload。correlationId使用Experiment ID或Run ID，不能假设它是OTel Trace ID；需要链路传播另用traceparent头。

V1通知注册：run.ready（payload.runId）、evaluation.ready（payload.jobId）、run.finished（payload.runId）、experiment.finished（payload.experimentId）。只携带身份，不在MQ发送prompt、密钥、完整Trace或最终大输出。

TASK-011交换机repopilot.events，topic类型；队列repopilot.run.ready、repopilot.evaluation.ready及隔离/死信队列。消息持久化、队列durable，manual ACK；prefetch默认与本地并发一致。多节点高可用部署是否使用quorum队列另行验证，不把单机durable描述为高可用。

Java发布、Python消费用于唤醒领取；服务内部事件聚合可以在事务后调用同一个调度器。事件通知无法取代数据库状态检查。消息重复是正常边界，不宣称恰好一次。

## 幂等与脱敏

Java计算eventHash，忽略接收时间和数据库ID，保留业务字段；相同eventId同hash返回DUPLICATE，相同eventId异hash返回409并整批回滚。artifactId必须归属同Run。sourceEventId只做关联，不替代eventId全局唯一约束。

认证头、Cookie、API key、secret字段在采集端删去，服务端再次按规则校验与脱敏。模型输出与日志作为不可信数据存储，前端只做转义展示，不能直接渲染为可执行HTML。
