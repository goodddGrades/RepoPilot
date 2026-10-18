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
