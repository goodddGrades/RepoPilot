/**
 * 领域模型类型（与 repopilot-server 的 DTO 一一对应）。
 *
 * 只镜像服务端真正返回的字段：多写一个"前端觉得应该有"的字段，
 * 运行期就会变成 undefined，而 TypeScript 不会报错。
 */

export type LifecycleStatus = 'ACTIVE' | 'ARCHIVED'
export type VersionStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
export type AgentType = 'MOCK' | 'CODING'
export type RunnerType = 'MOCK' | 'CODEX' | 'CUSTOM'
export type TaskType = 'AGENT_TASK' | 'LLM_CALL'
export type EvaluatorType = 'RULE' | 'TRACE' | 'COST' | 'TEST' | 'LLM_JUDGE'

export interface PageResponse<T> {
  items: T[]
  page: number
  pageSize: number
  total: number
}

export interface AgentView {
  id: string
  code: string
  name: string
  description?: string
  agentType: AgentType
  status: LifecycleStatus
  createdAt: string
  updatedAt: string
}

export interface AgentVersionView {
  id: string
  agentId: string
  version: number
  runnerType: RunnerType
  config: unknown
  configHash: string
  status: VersionStatus
  publishedAt?: string
  createdAt: string
  updatedAt: string
}

export interface DatasetView {
  id: string
  code: string
  name: string
  description?: string
  status: LifecycleStatus
  createdAt: string
  updatedAt: string
}

export interface DatasetVersionView {
  id: string
  datasetId: string
  version: number
  status: VersionStatus
  description?: string
  contentHash?: string
  publishedAt?: string
  caseCount: number
  createdAt: string
  updatedAt: string
}

export interface DatasetCaseView {
  id: string
  datasetVersionId: string
  caseKey: string
  taskType: TaskType
  input: unknown
  expected?: unknown
  metadata?: unknown
  createdAt: string
  updatedAt: string
}

export interface ImportReceipt {
  importBatchId: string
  versionId: string
  status: string
  importedCount: number
  totalCount: number
  sizeBytes: number
  sha256: string
}

export interface EvaluationPlanView {
  id: string
  code: string
  name: string
  description?: string
  createdAt: string
  updatedAt: string
}

export interface PlanItemView {
  id: string
  evaluatorDefinitionId: string
  executionOrder: number
  required: boolean
  config: unknown
}

export interface PlanVersionView {
  id: string
  planId: string
  version: number
  status: VersionStatus
  config: unknown
  items: PlanItemView[]
  contentHash?: string
  publishedAt?: string
  createdAt: string
  updatedAt: string
}

export interface EvaluatorView {
  id: string
  evaluatorKey: string
  version: number
  displayName: string
  description?: string
  evaluatorType: EvaluatorType
  implementationRef: string
  inputSchema: unknown
  outputSchema: unknown
  status: VersionStatus
  publishedAt?: string
  createdAt: string
  updatedAt: string
}

// ---------------------------------------------------------------------------
// TASK-004 在线执行
// ---------------------------------------------------------------------------

export type ExperimentStatus =
  | 'DRAFT'
  | 'QUEUED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'PARTIAL_FAILED'
  | 'FAILED'
  | 'CANCELLED'

export type RunMode = 'ONLINE' | 'OFFLINE'

export type RunStatus =
  | 'PENDING'
  | 'QUEUED'
  | 'RUNNING'
  | 'EVALUATING'
  | 'COMPLETED'
  | 'FAILED'
  | 'TIMED_OUT'
  | 'CANCELLED'

export type TaskOutcome = 'UNKNOWN' | 'SUCCESS' | 'FAILURE' | 'NOT_APPLICABLE'

export type AttemptStatus = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'TIMED_OUT' | 'CANCELLED'

export type JobStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED'

export interface ExperimentTargetView {
  id: string
  targetKey: string
  displayName: string
  agentVersionId: string
  repeatCount: number
}

export interface ExperimentView {
  id: string
  name: string
  description?: string
  mode: RunMode
  status: ExperimentStatus
  datasetVersionId: string
  evaluationPlanVersionId: string
  config: unknown
  expectedRunCount: number
  /** 详情接口返回：已导入的 Run 数（列表页为 0，不逐行统计）。离线实验看这个 */
  importedRunCount: number
  /** 详情接口返回：完整槽位里还差多少条轨迹；在线实验恒为 0 */
  missingRunCount: number
  targets: ExperimentTargetView[]
  startedAt?: string
  finishedAt?: string
  createdAt: string
  updatedAt: string
}

export interface RunView {
  id: string
  experimentId: string
  experimentTargetId: string
  targetKey?: string
  datasetCaseId: string
  caseKey?: string
  repeatIndex: number
  mode: RunMode
  status: RunStatus
  taskOutcome: TaskOutcome
  attemptCount: number
  acceptedAttemptId?: string
  sealedAt?: string
  startedAt?: string
  finishedAt?: string
  createdAt: string
}

export interface AttemptView {
  id: string
  attemptNo: number
  workerId: string
  status: AttemptStatus
  leaseUntil?: string
  deadlineAt?: string
  errorCode?: string
  errorMessage?: string
  startedAt?: string
  finishedAt?: string
  createdAt: string
}

export interface JobBatchSummary {
  batchId: string
  batchNo: number
  planVersionId: string
  total: number
  pending: number
  running: number
  completed: number
  failed: number
  skipped: number
}

export interface JobView {
  id: string
  batchId: string
  batchNo: number
  evaluatorKey: string
  evaluatorVersion: number
  executionOrder: number
  required: boolean
  status: JobStatus
  attemptCount: number
  errorCode?: string
}

export interface RunDetail {
  run: RunView
  /** 只有离线导入的 Run 有来源；在线执行没有"来源记录"这回事 */
  sourceSystem?: string
  sourceRecordId?: string
  output?: unknown
  outputArtifactId?: string
  usage?: unknown
  observationMetadata?: unknown
  traceEventCount: number
  attempts: AttemptView[]
  jobBatches: JobBatchSummary[]
  jobs: JobView[]
}

export interface RunJobSummary {
  batches: JobBatchSummary[]
  jobs: JobView[]
}

/** 导入批次状态；OFFLINE_RUNS 允许 PARTIAL_FAILED，DATASET_CASES 只有 COMPLETED/FAILED。 */
export type ImportStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'PARTIAL_FAILED' | 'FAILED' | 'CANCELLED'
export type ImportType = 'DATASET_CASES' | 'OFFLINE_RUNS'

export interface ImportBatchView {
  id: string
  importType: ImportType
  status: ImportStatus
  datasetVersionId: string
  experimentId?: string
  fileArtifactId: string
  totalCount: number
  /** totalCount 只在批次终态才有意义；处理中为 false，前端不要拿它算百分比 */
  totalKnown: boolean
  processedCount: number
  successCount: number
  failedCount: number
  /** success 的子集：同一来源记录被重复导入时计入这里 */
  duplicateCount: number
  lastLineNo: number
  startedAt?: string
  finishedAt?: string
  createdAt: string
  updatedAt: string
}

export interface ImportErrorView {
  lineNo: number
  errorCode: string
  errorMessage: string
}

export type ArtifactType = 'OUTPUT' | 'STDOUT' | 'EVIDENCE' | 'IMPORT_FILE'

/** 服务端刻意不回传 objectKey：存储路径属于实现细节。 */
export interface ArtifactView {
  id: string
  runId?: string
  artifactType: ArtifactType
  contentType: string
  sizeBytes: number
  sha256: string
  status: string
  createdAt: string
}
