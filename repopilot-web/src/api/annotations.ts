import { getJson, postJson } from '@/api/http'
import { buildQuery } from '@/api/query'
import type { PageResponse } from '@/types/domain'
import type {
  AnnotationTaskView,
  DispatchHint,
  SubmitAnnotationPayload,
  ReviewAnnotationPayload,
} from '@/types/annotation'

/**
 * 人工标注接口（TASK-009）。
 *
 * 每个写操作都必须带 `expectedVersion`：后端在同一条 UPDATE 里比对版本与前置状态，
 * 并发领取只有一个赢家。前端不能省掉这个字段——省了就是"后写覆盖前写"。
 *
 * 幂等键由 http 层自动生成（`newIdempotencyKey`），因此网络重试或用户双击
 * 不会产生两条历史记录。
 */

export interface AnnotationListParams {
  page?: number
  pageSize?: number
  status?: string
  assigneeId?: string
  runId?: string
}

export function listAnnotationTasks(
  params: AnnotationListParams = {},
): Promise<PageResponse<AnnotationTaskView>> {
  return getJson<PageResponse<AnnotationTaskView>>(`/v1/annotation-tasks${buildQuery({ ...params })}`)
}

export function getAnnotationTask(taskId: string): Promise<AnnotationTaskView> {
  return getJson<AnnotationTaskView>(`/v1/annotation-tasks/${taskId}`)
}

export function listDispatchCandidates(
  experimentId: string,
  limit = 50,
): Promise<DispatchHint[]> {
  return getJson<DispatchHint[]>(`/v1/experiments/${experimentId}/annotation-candidates${buildQuery({ limit })}`)
}

export function createAnnotationTask(
  runId: string,
  evaluationJobId?: string,
  idempotencyKey?: string,
): Promise<AnnotationTaskView> {
  return postJson<AnnotationTaskView>(
    '/v1/annotation-tasks',
    { runId, evaluationJobId },
    idempotencyKey,
  )
}

export function claimAnnotationTask(
  taskId: string,
  expectedVersion: number,
  idempotencyKey?: string,
): Promise<AnnotationTaskView> {
  return postJson<AnnotationTaskView>(
    `/v1/annotation-tasks/${taskId}/claim`,
    { expectedVersion },
    idempotencyKey,
  )
}

export function submitAnnotation(
  taskId: string,
  payload: SubmitAnnotationPayload,
  idempotencyKey?: string,
): Promise<AnnotationTaskView> {
  return postJson<AnnotationTaskView>(
    `/v1/annotation-tasks/${taskId}/submit`,
    payload,
    idempotencyKey,
  )
}

export function reviewAnnotation(
  taskId: string,
  payload: ReviewAnnotationPayload,
  idempotencyKey?: string,
): Promise<AnnotationTaskView> {
  return postJson<AnnotationTaskView>(
    `/v1/annotation-tasks/${taskId}/review`,
    payload,
    idempotencyKey,
  )
}

export function cancelAnnotation(
  taskId: string,
  expectedVersion: number,
  reason: string,
  idempotencyKey?: string,
): Promise<AnnotationTaskView> {
  return postJson<AnnotationTaskView>(
    `/v1/annotation-tasks/${taskId}/cancel`,
    { expectedVersion, reason },
    idempotencyKey,
  )
}
