# REST API 与 Java/Python 协议

## 公共约定

业务前缀 `/api/v1`，Worker前缀 `/internal/v1`，接口JSON使用camelCase。现有健康检查保留原路径。创建资源201；提交异步动作202；读和同步修改200；本契约无204成功响应。下载/上传的二进制另见Artifact约定。

成功：`{"code":"OK","message":"success","data":{},"errors":[],"requestId":"...","timestamp":"2026-10-17T04:00:00.000Z"}`。

错误：`{"code":"VALIDATION_ERROR","message":"请求参数不合法","data":null,"errors":[{"field":"name","message":"不能为空"}],"requestId":"...","timestamp":"2026-10-17T04:00:00.000Z"}`。不返回堆栈或原始SQL。data在成功时必有（可为 `{}`），错误时为 `null`；errors在成功时为空数组，错误时为字段级错误列表，无字段级信息时为空数组。

**信封已冻结**：TASK-001 已实现上述字段（`code/message/data/errors/requestId/timestamp`）。TASK-002 只允许扩展错误码与分页组件，**不得改字段名、不得新增 `details` 别名、不得去掉 timestamp**。前端 `repopilot-web/src/types/api.ts` 依赖该结构。

| 错误码 | HTTP |
| --- | --- |
| VALIDATION_ERROR / BAD_REQUEST / UNSUPPORTED_EVENT_VERSION | 400 |
| UNAUTHORIZED / FORBIDDEN | 401 / 403 |
| RESOURCE_NOT_FOUND | 404 |
| METHOD_NOT_ALLOWED / UNSUPPORTED_MEDIA_TYPE | 405 / 415 |
| RESOURCE_CONFLICT / IDEMPOTENCY_CONFLICT / INVALID_STATE_TRANSITION / STALE_ATTEMPT / OPTIMISTIC_LOCK_CONFLICT | 409 |
| PAYLOAD_TOO_LARGE | 413 |
| RATE_LIMITED | 429 |
| INTERNAL_ERROR / SERVICE_UNAVAILABLE | 500 / 503 |

TASK-001 已实现的 `NOT_FOUND` / `BUSINESS_ERROR` / `DEPENDENCY_UNAVAILABLE` 在本契约中**改名**为 `RESOURCE_NOT_FOUND` / 上述细分 409 码 / `SERVICE_UNAVAILABLE`。这是 TASK-002 的契约收敛动作：三者均为枚举值重命名，HTTP 状态与信封结构不变；`BUSINESS_ERROR` 不再作为新代码的兜底，409 必须使用具体码。

X-Request-Id接受1~64个ASCII字母、数字、下划线或短横线；缺失或不合法时由服务端生成ULID并返回响应头。写入MDC，finally清理，包括异常路径。它不是Trace ID。TASK-001 当前用 UUID v4 生成；TASK-002 引入 ULID 生成器后统一为 ULID。requestId 是不透明值，客户端不得解析其内部结构，因此该收敛不构成破坏性变更。

页码列表：page≥1、pageSize默认20且1~100，响应data={items,page,pageSize,total}，默认createdAt DESC,id DESC，只有白名单字段可排序。过滤字段超长、未知状态或未知排序返回400，不能拼接用户输入SQL。

普通创建、发布、实验开始/取消、导入创建和标注动作要求Idempotency-Key，1~128个ASCII字母数字及`._:-`。同scope同key同请求复用原data与状态码；异请求409。幂等回执与资源变更在同一事务提交，失败请求不缓存成功回执；并发唯一键冲突后读已提交回执。保留至少7天，清理后仍有业务唯一键。请求摘要由Java排序对象键并保留数组顺序后计算，数字使用既定DTO类型序列化，不让Python生成业务幂等摘要。

时间ISO8601 UTC毫秒；ULID为26位大写。请求JSON默认上限1MiB；轨迹批量500条且总计1MiB，单payload64KiB；文件默认20MiB/10000非空行，可配置。超过限制返回413。

## 版本管理接口（TASK-003）

| 方法与相对路径 | 输入/行为 | 返回data |
| --- | --- | --- |
| POST /agents | code,name,description?,agentType | Agent |
| GET /agents；GET /agents/{id} | page,pageSize,status?,keyword? | 页 / Agent |
| PATCH /agents/{id} | name?,description?,status?；不能改code | Agent |
| POST /agents/{id}/versions | config,runnerType；后端分配version | AgentVersion |
| GET /agents/{id}/versions；GET /agent-versions/{id} | 版本列表/详情 | 页 / AgentVersion |
| PUT /agent-versions/{id} | config,runnerType；只允许草稿 | AgentVersion |
| POST /agent-versions/{id}/publish 或 /archive | 无正文 | AgentVersion |
| POST /datasets；GET /datasets；GET /datasets/{id}；PATCH /datasets/{id} | code,name,description,status；规则同Agent | Dataset或页 |
| POST /datasets/{id}/versions；GET /datasets/{id}/versions | description?；分配新版本，可选cloneFromVersionId | DatasetVersion或页 |
| GET /dataset-versions/{id} | 固定版本 | DatasetVersion |
| POST /dataset-versions/{id}/publish 或 /archive | 发布校验非空样本 | DatasetVersion |
| POST /dataset-versions/{id}/cases | caseKey,taskType,input,expected?,metadata? | DatasetCase |
| GET /dataset-versions/{id}/cases | page,pageSize,keyword? | 页 |
| PUT /dataset-cases/{id}；DELETE /dataset-cases/{id} | 只允许父版本草稿，DELETE返回{id,deleted:true} | DatasetCase / 删除回执 |
| POST /evaluation-plans；GET /evaluation-plans；GET /evaluation-plans/{id} | code,name,description | EvaluationPlan或页 |
| POST /evaluation-plans/{id}/versions；GET /evaluation-plans/{id}/versions | config,items[] | PlanVersion或页 |
| GET /evaluation-plan-versions/{id}；PUT /evaluation-plan-versions/{id} | PUT整体替换草稿config/items | PlanVersion |
| POST /evaluation-plan-versions/{id}/publish 或 /archive | 发布时算子必须已发布 | PlanVersion |
| GET /evaluators；GET /evaluators/{id} | key?,type?,status? | 页 / EvaluatorDefinition |

V1算子定义通过经版本控制的注册清单和种子导入维护，不提供任意上传代码接口。AgentVersion响应含id,agentId,version,runnerType,config,status,publishedAt,createdAt,updatedAt；配置中的secretRef只展示引用，不解析密钥。DatasetCase响应字段与输入相同并补id,datasetVersionId,timestamps。PlanVersion含id,planId,version,status,config,items，Item含id,evaluatorDefinitionId,executionOrder,required,config。

## 实验与运行（TASK-004/005；评测批次接口 TASK-007）

| 方法与路径 | 请求 | 响应data |
| --- | --- | --- |
| POST /experiments | name,description?,mode,datasetVersionId,evaluationPlanVersionId,config,targets[{targetKey,displayName,agentVersionId?,repeatCount}] | Experiment详情，DRAFT |
| GET /experiments；GET /experiments/{id} | mode?,status?,分页 | 页 / 含targets与计数详情 |
| POST /experiments/{id}/start | 无正文；校验版本、预算及批次状态 | {id,status}，202 |
| POST /experiments/{id}/cancel | reason? | {id,status}，202；数据库立即终态，进程停止尽力完成 |
| GET /experiments/{id}/runs | targetId?,caseKey?,status?,taskOutcome?,分页 | Run列表 |
| GET /runs/{id} | 详情 | Run、acceptedAttemptId、输出、Job摘要、缺失标记 |
| GET /runs/{id}/attempts | 执行尝试 | 页，含状态、耗时、脱敏错误，不回传leaseToken |
| GET /runs/{id}/evaluation-jobs | batchId?；不传时返回全部批次的分组摘要 | 按批次分组的 Job、尝试、最终Result |
| POST /experiments/{id}/evaluation-batches | planVersionId,triggerType,parentBatchId?,runIds? 或 filter?；创建后202 | EvaluationBatch |
| GET /experiments/{id}/evaluation-batches | status?,分页 | 批次页，含batchNo、triggerType、计数 |
| GET /evaluation-batches/{id} | 详情 | 批次、固定方案版本、选择范围、Job计数与状态 |
| GET /evaluation-batches/{id}/runs | page,pageSize | 页；该批次固定登记的 Run 列表，只读 |
| POST /evaluation-batches/{id}/cancel | reason? | {id,status}，202 |

实验启动时自动创建 `triggerType=INITIAL` 的批次，并由它产生首批 Job。重评必须通过 `POST /experiments/{id}/evaluation-batches` 新建批次：后端只允许已封口的 Run 进入选择范围，复用原 accepted Attempt 与 Trace，**禁止触发新的 Agent 执行**。同一 Run 可在多个批次出现；同一批次内 `(runId, planItemId)` 唯一。批次创建后其 planVersionId 与 selection 不可修改。

`runIds` 或 `filter` 在创建时一次性解析为具体 Run 列表，写入 `evaluation_batch_runs`；后续查询批次范围只读这张登记表，不再重新执行过滤条件。`selection_json` 只保存"当初为什么选这些 Run"的依据与排除原因，不是范围真相。批次创建事务同时写批次行、Run 登记行与 `expectedJobCount`；三者任一写入失败则整批回滚。Job 只能针对已登记的 Run 创建，跨实验的 Run 由复合外键拒绝。

Experiment创建时只建立草稿与Targets；开始事务固定expectedRunCount、预算快照与对象不可变配置。在线生成全部Run；离线冻结已导入Run清单并禁止后续导入，缺失槽位记录为报告缺失。大实验生成可分批，但V1上限10000个Run，TASK-004先用单事务完整生成并验证上限；不能在部分Run生成时开始执行。

## 文件、导入与轨迹（TASK-005/006）

| 方法与路径 | 行为 |
| --- | --- |
| POST /artifacts | multipart file,artifactType,runId?；服务端流式校验hash/长度后写元数据，201 |
| GET /artifacts/{id} | 返回元数据，不持久化签名URL |
| GET /artifacts/{id}/download | 校验访问范围后302到短期URL或200字节流；异常返回公共错误JSON |
| POST /imports | importType,datasetVersionId,experimentId?,fileArtifactId；创建后202 |
| GET /imports；GET /imports/{id} | 分页/进度、计数、最后处理行号 |
| GET /imports/{id}/errors | 分页错误摘要，不回显原始敏感行 |
| POST /imports/{id}/cancel | 无正文；202；已提交行保留，批次终态 |
| GET /runs/{id}/events | cursor?,limit默认100最大500,attemptId?,eventType? |

Trace游标使用(created_at? 不使用) **(occurred_at,id)** 升序，cursor编码这两个字段、Run ID及过滤条件摘要并签名；修改过滤条件需重置游标。响应{items,nextCursor,hasMore}。活跃轨迹可有迟到事件插入旧时间段，分页是当前可见数据，不承诺活跃Run无遗漏；前端在封口后重新读取。历史封口轨迹稳定。默认在线读取accepted Attempt；执行中默认active Attempt；显式allAttempts=true用于排障，不进入自动评测输入。

## 标注、报告（TASK-009/010）

| 方法与路径 | 请求/返回 |
| --- | --- |
| POST /annotation-tasks | runId,evaluationJobId? → Task |
| GET /annotation-tasks；GET /annotation-tasks/{id} | status?,assigneeId?,分页；详情含records |
| POST /annotation-tasks/{id}/claim | expectedVersion → Task |
| POST /annotation-tasks/{id}/submit | expectedVersion,verdict,failureCategory?,reason,evidence → Task |
| POST /annotation-tasks/{id}/review | expectedVersion,approved,reason → Task；false对应RETURN |
| POST /annotation-tasks/{id}/cancel | expectedVersion,reason → Task |
| GET /experiments/{id}/report | annotationView=RAW或REVIEWED,batchScope=INITIAL或LATEST或SPECIFIED(默认INITIAL),batchId? → 指标、覆盖率、缺失、分组结果；响应必须回显实际使用的batchId与batchScope |
| GET /experiments/{id}/comparison | baselineTargetId,candidateTargetId,batchScope?,batchId?,baselineBatchId?,candidateBatchId? → 配对变化与不可比原因；单批次比较时两Target必须来自同一批次 |

报告与对比的批次选择规则（**默认值固定，不随后台重评漂移**）：

1. `batchScope=SPECIFIED`（或直接传 `batchId`）：使用指定批次；`SPECIFIED` 缺 `batchId` 返回 400。
2. `batchScope=LATEST`：使用该实验**最新创建**的批次（按 `created_at`, `id` 倒序），**不跳过任何状态**。若该批次仍为 `PENDING/RUNNING` 或已 `FAILED/CANCELLED`，响应照常返回该批次及其 `status`，报告指标标记为不可用或部分可用，**不得静默回退到上一个成功批次**。
3. 不传任何批次参数：**默认返回初始批次**（`trigger_type=INITIAL`），而不是"最近一个批次"，避免同一 URL 在重评后返回不同历史结论。
4. 响应必须回显 `batchId`、`batchScope` 与 `batchStatus`，前端据此标注"初始评测 / 最新评测 / 指定批次"。实验没有初始批次时明确报错，不静默回退到其他批次或另一方案版本。
5. 若将来需要"最近一次跑完的评测"，单独增加 `batchScope=LATEST_COMPLETED`，不改变 `LATEST` 的语义。`LATEST` 与 `LATEST_COMPLETED` 必须能同时解释同一实验的两个不同批次，因此两者不可合并。

6. `comparison` 跨批次比较（对比两版 Judge）必须同时给 `baselineBatchId` 与 `candidateBatchId`，只给一个返回 400。不同方案版本的跨批次比较不自动判定回归，只返回"不可比原因=方案版本不同"，由人决定是否做描述性对比。

operator从可信认证上下文取得；开发模式固定测试身份可配置，只绑定本机，不把浏览器任意operatorId当成已鉴权身份。V1同一操作者允许提交和审核，但在记录中标出selfReview=true；不能宣传为双人审核。

## Worker 协议（TASK-004/007）

Worker使用独立Bearer凭证，后端校验允许的workerId与能力范围；网络可达不意味着有授权。领取请求含workerId,capabilities,maxTasks；maxTasks=1~10。能力取RUN:MOCK、RUN:CODEX、EVAL:<evaluatorKey>等注册字符串。

| 方法与完整路径 | 输入 | 返回 |
| --- | --- | --- |
| POST /internal/v1/work/claim | workerId,capabilities,maxTasks | {tasks:[WorkItem]}；无任务200空数组 |
| POST /internal/v1/run-attempts/{id}/heartbeat | leaseToken | {leaseUntil,deadlineAt,cancelled:false}；过期/撤销409 |
| POST /internal/v1/run-attempts/{id}/events | leaseToken,events[] | {items:[{eventId,status:ACCEPTED或DUPLICATE}]}；整批先校验后同事务写入 |
| POST /internal/v1/run-attempts/{id}/complete | leaseToken,taskOutcome,output或outputArtifactId,usage,observationMetadata?,expectedEventCount,eventManifestSha256 | {attemptId,status} |
| POST /internal/v1/run-attempts/{id}/fail | leaseToken,errorCode,errorMessage,retryableHint,usage? | Attempt状态；是否重试由后端决定 |
| POST /internal/v1/job-attempts/{id}/heartbeat | leaseToken | 租约回执 |
| POST /internal/v1/job-attempts/{id}/complete | leaseToken,verdict,score?,reason?,evidence?,result,generatedBy,usage? | {attemptId,status,jobId} |
| POST /internal/v1/job-attempts/{id}/fail | leaseToken,errorCode,errorMessage,retryableHint,usage? | Attempt状态 |
| POST /internal/v1/artifacts | Worker凭证+multipart，带runId/attemptId/leaseToken并校验租约 | READY Artifact |

WorkItem公共字段：kind(RUN/EVALUATION),runId,attemptId,attemptNo,leaseToken,leaseUntil,deadlineAt,input。RUN另含agentVersionId,runnerType,agentConfig,datasetCase；EVALUATION另含jobId,evaluatorDefinition,evaluatorConfig,acceptedRunSnapshot。配置只传secretRef，Worker按允许引用在环境解析，不通过业务API返回真实密钥。

评测输入结构为{schemaVersion:1,runId,case:{caseKey,taskType,input,expected,metadata},execution:{taskOutcome,output,outputArtifactId,usage,acceptedAttemptId},trace:{sealed:true,eventsUrl,manifestSha256},evaluator:{key,version,config}}。eventsUrl是后端相对路径，Worker凭证只允许访问已领取任务的Run，禁止任意URL取数。

事件清单摘要算法：取该Attempt已接受eventId，按ASCII排序，以LF连接，末尾无LF，对UTF-8求SHA256；空集合为SHA256空字节。后端比对数量与摘要，确保未丢事件后才接受完成。完成回调摘要另外对整个业务回调正文求摘要（去除leaseToken）；相同最终回调幂等，不同正文409。

HTTP重试只对超时、连接错误、429、503等暂时错误使用同一请求身份与正文；409/400不可无限重试。若完成响应丢失，重发同一完成正文获取原回执。

## OpenAPI 落实

TASK-011新增内部通知入口：`POST /internal/v1/notifications/accept`，Worker Bearer凭证校验consumerName权限，正文{consumerName,event:EventEnvelope}，信封遵守event-envelope.schema.json。Java在同一短事务校验资源与当前状态、写Inbox去重记录，返回200 data={eventId,status:ACCEPTED或DUPLICATE,resourceId}。同ID异正文409；无权限403；未知版本400；瞬时数据库错误503。Python收到200后ACK并唤醒本地领取循环；即便唤醒丢失，定期领取仍会恢复。此入口不创建第二份Run/Job，也不运行长任务。

`docs/contracts/openapi-common.yaml`只提供公共组件，不宣称已覆盖全部路由。TASK-002需把公共响应、错误码、RequestId与健康接口接入现有springdoc方式；后续每TASK补齐新增路由的实际请求/响应Schema及示例，CI导出并检查与本路由表一致，不能只靠手写Markdown充当完整OpenAPI实现。
