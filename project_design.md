# Untitled

# RepoPilot 开发总手册 V1.0

**项目定位：面向 Agent 的轨迹管理、自动化评测与质量分析平台**

**开发原则：先平台功能，后真实数据接入；先完整闭环，后性能优化。**

## 1. 产品范围

RepoPilot 不是模型训练平台，也不是通用 Agent 开发框架。

它要解决四个核心问题：

1. 如何统一管理不同 Agent 的执行轨迹和评测数据？
2. 如何使用规则算子、代码测试、LLM Judge 等方法评价 Agent 表现？
3. 如何通过人工标注、轨迹分析定位失败原因？
4. 如何比较 Agent 不同版本的质量，发现性能退化？

### 1.1 必须实现的两条业务路径

**路径 A：在线评测**

```
创建评测实验
    ↓
选择数据集 + Agent 配置 + 评测方案
    ↓
创建在线评测任务
    ↓
调度 Agent Runner
    ↓
Agent 执行任务
    ↓
收集轨迹、结果与执行证据
    ↓
统一评测引擎
    ↓
自动评测结果
    ↓
人工标注与问题归因
    ↓
实验报告 / 版本对比
```

V1 不接真实 Agent，先实现 `MockAgentRunner`。

它必须能模拟任务成功、任务失败、工具调用、执行异常和超时等情况。

**路径 B：离线评测**

```
创建评测实验
    ↓
导入 JSON / JSONL 数据
    ↓
数据校验与标准化
    ↓
关联数据集和评测方案
    ↓
统一评测引擎
    ↓
自动评测结果
    ↓
人工标注与问题归因
    ↓
实验报告 / 版本对比
```

第一版采用自定义规范化数据格式。

**不开发 LiteLLM Adapter，不读取公司网关日志，不对接任何真实第三方数据源。**

### 1.2 核心设计原则

两种评测方式只允许在数据获取阶段不同。

```
RepoPilot
                  |
        Evaluation Experiment
                  |
           Evaluation Run
                  |
          +-------+-------+
          |               |
       ONLINE          OFFLINE
          |               |
     Agent Runner    Dataset Import
          |               |
          +-------+-------+
                  |
          Normalized Run
                  |
            Trace Store
                  |
          Evaluation Engine
                  |
        Evaluation Results
                  |
        Annotation / Analysis
                  |
          Experiment Report
```

这里需要特别注意：

**离线数据不一定包含完整 Agent 任务结果。**

因此评测引擎必须具备判断“某项指标是否可评测”的能力，不能把缺失证据当作任务失败。

---

# 2. 最终技术架构

我建议采用：

**Spring Boot 模块化单体 + Python Worker + Vue3 SPA**

第一版不要拆微服务。

### 2.1 技术选型

| 层次         | 技术                        | 具体职责             |
| ---------- | ------------------------- | ---------------- |
| 前端         | Vue3 + TypeScript + Vite  | 平台管理页面           |
| UI         | Element Plus + ECharts    | 表格、表单、图表         |
| 后端         | Java 21 + Spring Boot 3.x | 核心业务             |
| 数据访问       | MyBatis                   | SQL 与持久化         |
| 数据迁移       | Flyway                    | 数据库版本管理          |
| 数据库        | MySQL 8                   | 业务数据、任务状态        |
| 异步消息       | RabbitMQ                  | 执行任务、评测任务        |
| 缓存         | Redis                     | 限流、热点缓存，按需引入     |
| Agent 服务   | Python 3.11+              | Runner、Evaluator |
| Python API | FastAPI                   | 内部管理与健康检查        |
| 文件存储       | MinIO                     | 轨迹附件、日志、报告       |
| 容器         | Docker Compose            | 本地开发和部署          |
| 可观测        | OpenTelemetry             | 后期接入             |
| LLM 监控     | Langfuse                  | 后期接入             |

第一版**不使用** Kafka、ClickHouse、Elasticsearch、Kubernetes 和 Spring Cloud。

这些组件可以根据实际瓶颈再引入。

### 2.2 服务划分

**repopilot-web**

Vue3 前端，负责用户操作与数据展示。

**repopilot-server**

Java 核心后端，负责：

- Agent 管理
- 数据集管理
- 在线和离线评测任务管理
- 轨迹存储与检索
- 评测算子配置
- 评测任务调度
- 标注流程
- 实验对比与报告
- 系统权限

**repopilot-worker**

Python Worker，负责：

- 模拟 Agent 执行
- 后续真实 Agent 适配
- 规则评测
- 测试执行
- Judge 调用
- 评测结果标准化

MySQL、RabbitMQ 和 MinIO 作为基础设施。

---

# 3. 核心领域模型

这是整个项目最重要的部分。

**必须先把领域模型设计好，再让其他 Agent 编写功能代码。**

否则后面很容易出现多个模块重复定义 Task、Run、Trace 等概念的问题。

### 3.1 核心实体

| 实体               | 含义             |
| ---------------- | -------------- |
| Agent            | 被评测的 Agent     |
| AgentVersion     | Agent 的具体配置版本  |
| Dataset          | 评测数据集          |
| DatasetVersion   | 不可变的数据集版本      |
| DatasetCase      | 一条评测样本         |
| Experiment       | 一次批量评测实验       |
| EvaluationRun    | 某个样本的一次评测执行    |
| RunAttempt       | 在线执行的一次尝试      |
| TraceEvent       | 结构化轨迹事件        |
| Artifact         | 日志、Diff、报告等大对象 |
| Evaluator        | 检测算子定义         |
| EvaluationJob    | 某个算子的执行任务      |
| EvaluationResult | 算子的具体评测结果      |
| AnnotationTask   | 人工标注任务         |
| AnnotationRecord | 人工标注结果         |

### 3.2 关系设计

```
Agent
  └── AgentVersion

Dataset
  └── DatasetVersion
       └── DatasetCase

Experiment
  ├── DatasetVersion
  ├── AgentVersion
  ├── EvaluationPlan
  └── EvaluationRun
       ├── DatasetCase
       ├── RunAttempt
       ├── TraceEvent
       ├── Artifact
       ├── EvaluationJob
       │    └── EvaluationResult
       └── AnnotationTask
            └── AnnotationRecord
```

一个 Experiment 可以包含多个 AgentVersion 的评测运行。

一个 DatasetCase 也可以被同一个 AgentVersion 重复运行，用于评估非确定性结果。

### 3.3 统一评测运行模型

在线与离线评测都必须最终形成 `EvaluationRun`。

基础字段：

```
{
  "run_id": "run_001",
  "experiment_id": "exp_001",
  "case_id": "case_001",
  "mode": "ONLINE",
  "agent_version_id": "agent_v1",
  "status": "COMPLETED",
  "input_ref": "artifact_input",
  "output_ref": "artifact_output",
  "trace_id": "trace_001",
  "started_at": "2026-10-16T10:00:00Z",
  "finished_at": "2026-10-16T10:02:00Z"
}
```

`mode`：

- `ONLINE`
- `OFFLINE`

注意：

`EvaluationRun.status` 表示评测工作流状态。

不能用它直接表示 Agent 是否成功完成业务任务。

业务结果应该单独保存，例如：

```
{
  "outcome": "FAIL",
  "failure_type": "TEST_FAILURE",
  "evidence_ids": ["artifact_test_001"]
}
```

如果缺少足够证据，应使用 `UNKNOWN`，而不是自动判为失败。

---

# 4. 功能模块设计

## 4.1 Agent 管理

V1 需要实现：

- Agent 创建、编辑和归档。
- Agent 类型管理。
- Agent 配置版本管理。
- Prompt 和模型配置快照。
- Runner 类型配置。
- Agent 历史评测实验查询。

支持的 Runner 类型先定义为：

```
MOCK
CODEX
CUSTOM
```

但是 **V1 只实现 MOCK**。

其他类型可以在数据模型中预留，但不提供虚假的可用按钮。

每次实验必须绑定不可变的 Agent 配置版本，避免后来修改配置导致历史实验无法复现。

---

## 4.2 数据集管理

功能要求：

- 创建数据集。
- 创建数据集版本。
- 添加、编辑、删除草稿样本。
- JSONL 批量导入。
- 校验输入格式。
- 发布不可变的数据集版本。
- 查看数据集统计信息。
- 复制已有版本创建新版本。

建议的数据格式：

```
{
  "case_id": "case_001",
  "task_type": "CODE_FIX",
  "input": {
    "instruction": "修复重复创建订单的问题"
  },
  "expected": {
    "type": "TEST_PASS",
    "test_command": "mvn test"
  },
  "metadata": {
    "language": "Java",
    "difficulty": "medium"
  }
}
```

对于暂时不涉及代码执行的任务，允许使用通用文本样本。

**数据集需要支持不同任务类型，不应全部强制绑定 Git 仓库。**

---

## 4.3 在线评测模块

Java 负责创建、调度和跟踪任务。

Python 负责执行 Runner。

先定义统一接口：

```
from abc import ABC, abstractmethodclass AgentRunner(ABC):    @abstractmethod    async def run(self, context):        pass
```

V1 实现：

```
MockAgentRunner
```

Mock Runner 必须支持固定测试场景：

| 场景           | 模拟行为        |
| ------------ | ----------- |
| SUCCESS      | 成功完成任务      |
| TASK_FAILURE | 正常执行但业务失败   |
| TOOL_FAILURE | 工具执行异常      |
| TIMEOUT      | 执行超时        |
| INFRA_ERROR  | Runner 内部异常 |
| MULTI_STEP   | 多轮模型与工具调用   |

每个模拟场景使用固定 Fixture，保证结果可复现。

Mock Runner 不应该随机编造评分，也不能在结果里伪装成真正运行过 Codex。

建议运行记录增加：

```
execution_provider = MOCK
```

---

## 4.4 离线评测模块

第一版重点实现标准化数据导入。

功能要求：

- 上传 JSON/JSONL。
- 文件校验。
- 数据格式检查。
- 批次管理。
- 异常记录查看。
- 数据去重。
- 生成标准化 EvaluationRun。
- 执行已有评测方案。

需要支持两种导入内容：

**第一种：已完成的执行记录**

包含输入、输出、执行轨迹和部分结果。

**第二种：普通对话或模型调用记录**

可能只有输入、输出、模型信息及耗时。

第二种不能强行生成完整的工具调用轨迹。

建议导入后生成数据完整度信息：

```
{
  "has_input": true,
  "has_output": true,
  "has_trace": true,
  "has_tool_results": false,
  "has_ground_truth": false
}
```

评测引擎根据这些信息判断算子是否能够执行。

---

# 5. 统一轨迹管理

这是核心模块之一。

## 5.1 TraceEvent 规范

```
{
  "schema_version": 1,
  "event_id": "event_001",
  "run_id": "run_001",
  "trace_id": "trace_001",
  "span_id": "span_001",
  "parent_span_id": null,
  "sequence": 1,
  "event_type": "LLM_CALL",
  "timestamp": "2026-10-16T10:00:00Z",
  "payload": {},
  "metadata": {}
}
```

第一版支持：

```
RUN_STARTED
RUN_FINISHED
LLM_CALL
TOOL_CALL
TOOL_RESULT
FILE_CHANGE
TEST_RESULT
ERROR
```

### 5.2 轨迹存储要求

- 根据 `run_id` 查询轨迹。
- 根据 `trace_id` 查询关联事件。
- 支持事件分页。
- 支持事件类型筛选。
- 支持按时间和顺序重建轨迹。
- 支持查看原始事件。
- 支持查看关联 Artifact。
- 支持两个 Run 的轨迹对比。

如果上游没有 `trace_id`，可以生成平台内部追踪 ID，但必须保留其来源为平台生成，不能误认为它是上游真实的分布式 Trace ID。

### 5.3 轨迹对比

V1 不需要实现特别复杂的自动轨迹对齐算法。

先支持：

- 同一数据样本的两个 Run 并排查看。
- 按时间线展示事件。
- 比较事件数量。
- 比较工具调用次数。
- 比较耗时、Token 与错误数量。
- 查看差异节点。

后续再增加基于操作类型、执行阶段和语义的自动对齐。

---

# 6. 评测引擎设计

建议采用可扩展的 Evaluator 模式。

### 6.1 统一算子协议

输入：

```
{
  "job_id": "job_001",
  "run_id": "run_001",
  "evaluator_key": "rule_evaluator",
  "evaluator_version": "1.0",
  "config": {},
  "input_refs": [],
  "trace_refs": []
}
```

输出：

```
{
  "job_id": "job_001",
  "status": "COMPLETED",
  "verdict": "PASS",
  "score": 1.0,
  "reason": "符合检测规则",
  "evidence_ids": []
}
```

执行状态：

```
PENDING
RUNNING
COMPLETED
FAILED
SKIPPED
```

检测结论：

```
PASS
FAIL
INDETERMINATE
NOT_APPLICABLE
```

必须严格区分执行状态和检测结论。

### 6.2 V1 必须实现的算子

| 算子                | 功能             | 实现     |
| ----------------- | -------------- | ------ |
| RuleEvaluator     | 字段、格式、关键词等规则检查 | Python |
| TraceEvaluator    | 重复调用、异常事件等轨迹检查 | Python |
| CostEvaluator     | Token、耗时和成本统计  | Python |
| TestEvaluator     | 根据真实测试证据判断结果   | Python |
| LLMJudgeEvaluator | 使用模型评价响应质量     | Python |

其中 LLM Judge 第一版可以提供 `MockJudgeProvider`，先验证完整流程。

但必须在数据中明确标记模拟评分。没有配置真实模型时，不能把模拟结果展示为真实质量评测结论。

### 6.3 算子管理功能

支持：

- 算子列表。
- 算子参数配置。
- 算子版本管理。
- 启用和停用。
- 测试算子。
- 创建评测方案。
- 配置评测方案包含哪些算子。
- 查询执行记录和结果。

不允许用户从 Web 界面上传任意 Python 代码直接执行。

V1 的算子类型由开发者在代码中注册，界面只负责配置，避免引入任意代码执行风险。

---

# 7. 人工标注与问题归因

### 7.1 标注工作流

```
待标注
   ↓
领取
   ↓
标注中
   ↓
提交
   ↓
审核
   ├── 通过
   └── 驳回修改
```

V1 功能：

- 标注任务列表。
- 任务领取。
- 轨迹查看。
- 自动评测结果查看。
- 错误类别选择。
- 人工备注。
- 提交标注。
- 审核与驳回。
- 标注历史记录。

### 7.2 默认错误分类

```
TASK_UNDERSTANDING
CONTEXT_INSUFFICIENT
TOOL_SELECTION
TOOL_EXECUTION
REASONING_ERROR
OUTPUT_FORMAT
TEST_FAILURE
ENVIRONMENT_ERROR
OTHER
```

问题归因必须关联证据。

例如：

```
{
  "category": "TOOL_EXECUTION",
  "reason": "工具调用超时导致后续步骤未执行",
  "evidence_event_ids": ["event_12", "event_13"]
}
```

自动归因结果与人工标注结果分开保存。

人工审核不能直接覆盖原始 Judge 输出。

---

# 8. 实验管理与版本对比

一个 Experiment 需要记录：

- 数据集版本。
- Agent 配置版本。
- 评测方案版本。
- 运行次数。
- 执行环境信息。
- 创建时间。
- 执行状态。

### 8.1 实验报告

至少包括：

| 指标       | 说明               |
| -------- | ---------------- |
| 总样本数     | 实验包含的样本          |
| 完成数      | 完成整个评测流程的样本      |
| 可评测数     | 具有对应指标所需证据的样本    |
| 任务成功率    | 有明确任务结果的样本中成功的比例 |
| 平均耗时     | 有效耗时记录的统计        |
| 平均 Token | 有效 Token 记录的统计   |
| 算子通过率    | 按各算子分别计算         |
| 错误分布     | 根据归因标签统计         |

不同指标必须使用合理分母。

例如 100 条历史记录中，只有 40 条包含真实任务成功证据，就不能把另外 60 条直接算作失败。

### 8.2 版本回归

支持选择两个实验进行对比。

第一版要求：

- 必须能根据 `case_id` 匹配样本。
- 显示共有样本数量。
- 显示成功变失败的样本。
- 显示失败变成功的样本。
- 显示耗时和成本变化。
- 查看两个 Run 的轨迹。

只有数据集版本、任务条件和其他控制变量满足可比性要求时，才允许生成正式的回归结论。否则只能作为描述性对比。

---

# 9. 数据库设计

第一版建议先确定以下表。

| 表名                      | 主要字段                                                  |
| ----------------------- | ----------------------------------------------------- |
| `agents`                | id、name、type、status                                   |
| `agent_versions`        | id、agent_id、version、config_json                       |
| `datasets`              | id、name、description                                   |
| `dataset_versions`      | id、dataset_id、version、status                          |
| `dataset_cases`         | id、dataset_version_id、case_key、data_json              |
| `evaluation_plans`      | id、name、version、config_json                           |
| `evaluator_definitions` | id、key、version、schema_json                            |
| `experiments`           | id、name、dataset_version_id、status                     |
| `experiment_agents`     | experiment_id、agent_version_id                        |
| `evaluation_runs`       | id、experiment_id、case_id、agent_version_id、mode、status |
| `run_attempts`          | id、run_id、attempt_no、worker_id、lease_until、status     |
| `trace_events`          | id、event_id、run_id、event_type、sequence、payload_ref    |
| `artifacts`             | id、run_id、type、object_key、checksum                    |
| `evaluation_jobs`       | id、run_id、evaluator_id、status、attempt_no              |
| `evaluation_results`    | id、job_id、verdict、score、result_json                   |
| `annotation_tasks`      | id、run_id、assignee_id、status                          |
| `annotation_records`    | id、task_id、reviewer_id、result_json                    |
| `import_batches`        | id、filename、status、total_count、failed_count           |
| `import_errors`         | id、batch_id、line_no、error_message                     |
| `outbox_events`         | id、event_type、payload、status、created_at               |

开发时通过 Flyway 管理建表和修改脚本。

核心约束：

- `trace_events.event_id` 唯一。
- `run_attempts(run_id, attempt_no)` 唯一。
- `dataset_cases(dataset_version_id, case_key)` 唯一。
- 每个 EvaluationJob 的执行结果使用明确的幂等标识。
- 任务状态更新使用条件更新或乐观锁。

所有日期使用 UTC 存储。

---

# 10. 核心 API 契约

建议先冻结以下 API 路径和基本语义。

| 模块         | API                                 | 作用       |
| ---------- | ----------------------------------- | -------- |
| Agent      | `POST /api/agents`                  | 创建 Agent |
| Agent      | `POST /api/agents/{id}/versions`    | 创建配置版本   |
| Dataset    | `POST /api/datasets`                | 创建数据集    |
| Dataset    | `POST /api/datasets/{id}/versions`  | 创建版本     |
| Dataset    | `POST /api/datasets/{id}/imports`   | 导入样本     |
| Experiment | `POST /api/experiments`             | 创建实验     |
| Experiment | `POST /api/experiments/{id}/start`  | 启动实验     |
| Experiment | `GET /api/experiments/{id}`         | 查询实验     |
| Run        | `GET /api/runs/{id}`                | 查询执行详情   |
| Trace      | `GET /api/runs/{id}/events`         | 查询轨迹     |
| Trace      | `GET /api/runs/compare`             | 对比两次执行   |
| Evaluator  | `GET /api/evaluators`               | 获取算子列表   |
| Evaluation | `POST /api/evaluations`             | 创建评测任务   |
| Annotation | `POST /api/annotations/claim`       | 领取标注     |
| Annotation | `POST /api/annotations/{id}/submit` | 提交标注     |
| Report     | `GET /api/experiments/{id}/report`  | 获取报告     |

所有涉及分页的列表接口统一使用分页参数和响应结构。

所有写接口统一使用请求校验、错误码和可追踪的请求 ID。

状态变化接口必须校验当前状态，不能允许任意状态跳转。

---

# 11. 前端页面

第一版开发以下七个页面。

| 页面       | 必要功能              |
| -------- | ----------------- |
| 工作台      | 实验概况、待处理任务、最近结果   |
| Agent 管理 | Agent 列表、配置版本     |
| 数据集中心    | 数据集、样本、版本、导入      |
| 在线/离线评测  | 创建实验、选择模式、查看执行进度  |
| 轨迹分析     | 轨迹时间线、详情、筛选、对比    |
| 评测中心     | 算子管理、方案配置、结果查看    |
| 标注与报告    | 标注流转、归因、实验统计、回归对比 |

页面应围绕真实业务 API 开发，不允许直接在前端硬编码模拟评测结果作为正式功能。

V1 可以通过 Mock Runner 和 Fixture 数据展示效果，但必须保证数据实际经过 Java 后端、数据库和评测 Worker。

---

# 12. 开发任务拆分

**不要让 Coding Agent 一次性实现全部模块。**

建议按以下顺序开发，每个阶段完成验收后再进入下一阶段。

| 阶段       | 开发任务                                         | 验收目标                |
| -------- | -------------------------------------------- | ------------------- |
| TASK-001 | Monorepo、Docker Compose、Java/Python/Vue 基础工程 | 所有服务能启动             |
| TASK-002 | Flyway 数据库结构、统一实体与 API 基础                    | 数据模型和契约稳定           |
| TASK-003 | Agent 管理、数据集及版本管理                            | 完整 CRUD、版本不可变       |
| TASK-004 | Mock Runner、在线评测任务创建                         | 能产生真实存储的模拟轨迹        |
| TASK-005 | 离线 JSONL 导入与标准化                              | 能生成统一 EvaluationRun |
| TASK-006 | 轨迹存储、查询和回放                                   | 在线、离线共用查询 API       |
| TASK-007 | 算子注册、评测方案与执行引擎                               | 可批量运行规则算子           |
| TASK-008 | Judge Provider、测试评测与证据关联                     | 结果结构化、状态正确          |
| TASK-009 | 人工标注、审核、错误归因                                 | 完整标注闭环              |
| TASK-010 | 实验报告、版本对比                                    | 定位回归与退化样本           |
| TASK-011 | RabbitMQ 可靠调度、Outbox、任务恢复                    | 通过故障注入              |
| TASK-012 | Redis 限流、监控与性能测试                             | 有实测数据               |
| TASK-013 | 全链路集成测试、部署文档                                 | 一键运行完整 Demo         |

这里有一个重要要求：

**TASK-004 和 TASK-005 应当分别实现数据入口，但 TASK-006 之后必须共享同一套业务逻辑。**

不得为在线、离线分别复制一套 Evaluation Service、Annotation Service 和 Report Service。

## 13. 统一验收测试

开发完成后，必须通过以下场景。

| 编号    | 测试场景               | 预期结果             |
| ----- | ------------------ | ---------------- |
| AT-01 | 在线 Mock Agent 成功   | 完整运行、轨迹、评测结果     |
| AT-02 | 在线 Mock Agent 业务失败 | 正确标记任务失败，流程可完成   |
| AT-03 | 在线 Worker 超时       | 状态正确、资源得到回收      |
| AT-04 | 导入完整离线轨迹           | 正确解析并评测          |
| AT-05 | 导入缺少工具结果的轨迹        | 不伪造工具执行结论        |
| AT-06 | 重复导入同一记录           | 幂等处理             |
| AT-07 | Judge 执行失败         | 算子失败，不伪造评分       |
| AT-08 | 两人同时领取标注任务         | 仅一人领取成功          |
| AT-09 | 两个实验版本对比           | 正确匹配共同案例         |
| AT-10 | 重复投递 MQ 消息         | 不产生重复业务结果        |
| AT-11 | Worker 执行中崩溃       | 任务可恢复、旧结果不能覆盖新结果 |
| AT-12 | 完整业务演示             | 在线和离线均能生成报告      |

验收必须依靠实际运行、数据库记录和测试报告，不能只通过页面截图判断功能完成。

---

# 14. 交给 Coding Agent 的统一开发指令

以下内容可以直接作为后续 Coding Agent 的项目级开发约束：

```
# RepoPilot 开发约束

你正在参与 RepoPilot Agent 评测与质量分析平台的开发。

## 技术栈
- Java 21 + Spring Boot 3.x + MyBatis + Flyway
- Python 3.11+ + FastAPI
- Vue3 + TypeScript + Element Plus
- MySQL 8 + RabbitMQ + MinIO
- Redis 按实际需要使用

## 架构约束
1. Java 使用模块化单体架构，不允许擅自拆分微服务。
2. Python 负责 Agent Runner 和 Evaluator。
3. 在线评测与离线评测只允许数据入口不同。
4. 两条评测路径共用领域模型、评测引擎和报告系统。
5. 所有真实数据源接入均不属于 V1 范围。
6. 在线评测使用 MockAgentRunner。
7. 离线评测使用规范化 JSON/JSONL Fixture。
8. 不允许擅自增加与业务无关的中间件。
9. 不允许自行修改已确定的公共数据契约。

## 开发要求
1. 开始任务前阅读项目设计文档。
2. 只实现当前分配的 TASK，不扩大范围。
3. 修改数据库必须增加 Flyway Migration。
4. 修改公共 API 必须同步更新 OpenAPI 契约。
5. 修改消息格式必须同步更新事件 Schema。
6. 核心业务必须编写单元测试。
7. 跨服务功能必须编写集成测试。
8. 不允许以硬编码假数据冒充后端业务功能。
9. Mock 数据和模拟评分必须明确标记。
10. 不允许忽略异常、吞掉错误或伪造成功结果。
11. 完成后必须报告实际执行的测试及结果。

## 每次交付必须包含
- 本次实现的功能
- 修改的文件
- 数据库变更
- API 或事件协议变更
- 测试命令及执行结果
- 已知问题
- 下一阶段依赖

## 禁止事项
- 不得擅自重构无关模块。
- 不得擅自引入新的技术框架。
- 不得将离线输入缺失的证据伪造成评测结果。
- 不得绕过数据库状态机直接修改任务终态。
- 不得把真实企业敏感数据用于测试。
- 不得宣称未经测试的功能已经完成。
```

---

## 15. 最终要形成的开发文档体系

上面这份是**总手册 V1.0**，用于确定整个项目的产品范围、技术架构和模块契约。

真正交给其他 Coding Agent 开发时，我建议进一步拆成以下文件：

```
RepoPilot/
│
├── docs/
│   ├── 00-project-overview.md
│   ├── 01-product-requirements.md
│   ├── 02-system-architecture.md
│   ├── 03-domain-model.md
│   ├── 04-database-design.md
│   ├── 05-api-contract.md
│   ├── 06-event-contract.md
│   ├── 07-online-evaluation.md
│   ├── 08-offline-evaluation.md
│   ├── 09-trace-pipeline.md
│   ├── 10-evaluation-engine.md
│   ├── 11-annotation-workflow.md
│   ├── 12-experiment-analysis.md
│   ├── 13-frontend-specification.md
│   ├── 14-deployment-guide.md
│   └── 15-testing-strategy.md
│
├── tasks/
│   ├── TASK-001.md
│   ├── TASK-002.md
│   └── ...
│
└── AGENTS.md
```

其中每个 `TASK-XXX.md` 应当细化到接口、字段、状态转换、修改范围和测试用例，避免 Coding Agent 在执行过程中自行猜测关键架构决策。
