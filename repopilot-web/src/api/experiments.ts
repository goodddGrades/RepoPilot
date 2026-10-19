import { getJson, postJson } from '@/api/http'
import { buildQuery } from '@/api/query'
import type {
  ExperimentStatus,
  ExperimentView,
  PageResponse,
  RunDetail,
  RunJobSummary,
  RunMode,
  RunStatus,
  RunView,
  AttemptView,
} from '@/types/domain'

export interface ExperimentListParams {
  page?: number
  pageSize?: number
  status?: string
  mode?: string
  keyword?: string
}

export interface TargetPayload {
  targetKey: string
  displayName: string
  agentVersionId: string
  repeatCount: number
}

export interface CreateExperimentPayload {
  name: string
  description?: string
  mode: RunMode
  datasetVersionId: string
  evaluationPlanVersionId: string
  config?: unknown
  targets: TargetPayload[]
}

export interface ExperimentStatusResult {
  id: string
  status: ExperimentStatus
}

export interface RunListParams {
  page?: number
  pageSize?: number
  targetId?: string
  caseKey?: string
  status?: string
  taskOutcome?: string
}

export function listExperiments(
  params: ExperimentListParams = {},
): Promise<PageResponse<ExperimentView>> {
  return getJson<PageResponse<ExperimentView>>(`/v1/experiments${buildQuery({ ...params })}`)
}

export function getExperiment(id: string): Promise<ExperimentView> {
  return getJson<ExperimentView>(`/v1/experiments/${id}`)
}

export function createExperiment(payload: CreateExperimentPayload): Promise<ExperimentView> {
  return postJson<ExperimentView>('/v1/experiments', payload)
}

/**
 * 提交实验：生成全部 Run 槽位。
 *
 * 同一个 Idempotency-Key 重发返回原回执；换 key 再提交会 409（实验已不在 DRAFT）。
 * 页面因此在点击成功后立刻换新 key，避免"重复点击被当成同一个请求"。
 */
export function startExperiment(id: string): Promise<ExperimentStatusResult> {
  return postJson<ExperimentStatusResult>(`/v1/experiments/${id}/start`)
}

export function cancelExperiment(id: string, reason?: string): Promise<ExperimentStatusResult> {
  return postJson<ExperimentStatusResult>(`/v1/experiments/${id}/cancel`, { reason })
}

export function listRuns(
  experimentId: string,
  params: RunListParams = {},
): Promise<PageResponse<RunView>> {
  return getJson<PageResponse<RunView>>(
    `/v1/experiments/${experimentId}/runs${buildQuery({ ...params })}`,
  )
}

export function getRun(id: string): Promise<RunDetail> {
  return getJson<RunDetail>(`/v1/runs/${id}`)
}

export function listAttempts(
  runId: string,
  params: { page?: number; pageSize?: number } = {},
): Promise<PageResponse<AttemptView>> {
  return getJson<PageResponse<AttemptView>>(`/v1/runs/${runId}/attempts${buildQuery({ ...params })}`)
}

export function getRunJobs(
  runId: string,
  params: { batchId?: string } = {},
): Promise<RunJobSummary> {
  return getJson<RunJobSummary>(`/v1/runs/${runId}/evaluation-jobs${buildQuery({ ...params })}`)
}

/** 状态文案：区分"执行结束"与"评测完成"，避免 EVALUATING 被显示成已完成。 */
export const RUN_STATUS_TEXT: Record<RunStatus, string> = {
  PENDING: '待入队',
  QUEUED: '待领取',
  RUNNING: '执行中',
  EVALUATING: '待评测',
  COMPLETED: '已完成',
  FAILED: '失败',
  TIMED_OUT: '超时',
  CANCELLED: '已取消',
}

export const RUN_STATUS_TAG: Record<RunStatus, 'info' | 'primary' | 'warning' | 'success' | 'danger'> = {
  PENDING: 'info',
  QUEUED: 'info',
  RUNNING: 'primary',
  EVALUATING: 'warning',
  COMPLETED: 'success',
  FAILED: 'danger',
  TIMED_OUT: 'danger',
  CANCELLED: 'info',
}

export const EXPERIMENT_STATUS_TEXT: Record<ExperimentStatus, string> = {
  DRAFT: '草稿',
  QUEUED: '已提交',
  RUNNING: '执行中',
  COMPLETED: '已完成',
  PARTIAL_FAILED: '部分失败',
  FAILED: '失败',
  CANCELLED: '已取消',
}

export const EXPERIMENT_STATUS_TAG: Record<
  ExperimentStatus,
  'info' | 'primary' | 'warning' | 'success' | 'danger'
> = {
  DRAFT: 'info',
  QUEUED: 'info',
  RUNNING: 'primary',
  COMPLETED: 'success',
  PARTIAL_FAILED: 'warning',
  FAILED: 'danger',
  CANCELLED: 'info',
}
