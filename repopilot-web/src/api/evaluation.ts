import { getJson, postJson } from '@/api/http'
import { buildQuery } from '@/api/query'
import type {
  EvaluationBatchView,
  PageResponse,
  RunView,
} from '@/types/domain'

/**
 * 评测批次接口。
 *
 * INITIAL 批次随实验启动自动创建，这里只暴露三个动作：
 * 创建重评批次（幂等）、查列表/详情、取消未终态批次。
 */

export interface EvaluationBatchListParams {
  page?: number
  pageSize?: number
  status?: string
}

export interface CreateEvaluationBatchPayload {
  planVersionId: string
  /** 后端只接受 REEVALUATION / MANUAL；INITIAL 由实验启动事务创建 */
  triggerType: 'REEVALUATION' | 'MANUAL'
  parentBatchId?: string
  runIds?: string[]
  /** 与 runIds 二选一；创建时一次性解析为具体行 */
  filter?: {
    targetId?: string
    caseKey?: string
    status?: string
    taskOutcome?: string
  }
}

export function listEvaluationBatches(
  experimentId: string,
  params: EvaluationBatchListParams = {},
): Promise<PageResponse<EvaluationBatchView>> {
  return getJson<PageResponse<EvaluationBatchView>>(
    `/v1/experiments/${experimentId}/evaluation-batches${buildQuery({ ...params })}`,
  )
}

export function createEvaluationBatch(
  experimentId: string,
  payload: CreateEvaluationBatchPayload,
  idempotencyKey?: string,
): Promise<EvaluationBatchView> {
  return postJson<EvaluationBatchView>(
    `/v1/experiments/${experimentId}/evaluation-batches`,
    payload,
    idempotencyKey,
  )
}

export function getEvaluationBatch(batchId: string): Promise<EvaluationBatchView> {
  return getJson<EvaluationBatchView>(`/v1/evaluation-batches/${batchId}`)
}

export function listEvaluationBatchRuns(
  batchId: string,
  page = 1,
  pageSize = 20,
): Promise<PageResponse<RunView>> {
  return getJson<PageResponse<RunView>>(
    `/v1/evaluation-batches/${batchId}/runs${buildQuery({ page, pageSize })}`,
  )
}

export function cancelEvaluationBatch(
  batchId: string,
  reason?: string,
): Promise<EvaluationBatchView> {
  return postJson<EvaluationBatchView>(`/v1/evaluation-batches/${batchId}/cancel`, { reason })
}
