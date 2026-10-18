import { getJson, postJson, putJson } from '@/api/http'
import { buildQuery } from '@/api/query'
import type {
  EvaluatorType,
  EvaluatorView,
  EvaluationPlanView,
  PageResponse,
  PlanVersionView,
} from '@/types/domain'

export interface PlanListParams {
  page?: number
  pageSize?: number
  keyword?: string
}

export interface CreatePlanPayload {
  code: string
  name: string
  description?: string
}

export interface PlanItemPayload {
  evaluatorDefinitionId: string
  executionOrder: number
  required: boolean
  config: unknown
}

export interface PlanVersionPayload {
  config: unknown
  items: PlanItemPayload[]
}

export function listPlans(params: PlanListParams = {}): Promise<PageResponse<EvaluationPlanView>> {
  return getJson<PageResponse<EvaluationPlanView>>(`/v1/evaluation-plans${buildQuery({ ...params })}`)
}

export function getPlan(id: string): Promise<EvaluationPlanView> {
  return getJson<EvaluationPlanView>(`/v1/evaluation-plans/${id}`)
}

export function createPlan(payload: CreatePlanPayload): Promise<EvaluationPlanView> {
  return postJson<EvaluationPlanView>('/v1/evaluation-plans', payload)
}

export function listPlanVersions(
  planId: string,
  params: { page?: number; pageSize?: number } = {},
): Promise<PageResponse<PlanVersionView>> {
  return getJson<PageResponse<PlanVersionView>>(
    `/v1/evaluation-plans/${planId}/versions${buildQuery({ ...params })}`,
  )
}

export function createPlanVersion(planId: string, payload: PlanVersionPayload): Promise<PlanVersionView> {
  return postJson<PlanVersionView>(`/v1/evaluation-plans/${planId}/versions`, payload)
}

export function getPlanVersion(versionId: string): Promise<PlanVersionView> {
  return getJson<PlanVersionView>(`/v1/evaluation-plan-versions/${versionId}`)
}

/** 整体替换草稿的 config 与 items。 */
export function replacePlanVersion(
  versionId: string,
  payload: PlanVersionPayload,
): Promise<PlanVersionView> {
  return putJson<PlanVersionView>(`/v1/evaluation-plan-versions/${versionId}`, payload)
}

export function publishPlanVersion(versionId: string): Promise<PlanVersionView> {
  return postJson<PlanVersionView>(`/v1/evaluation-plan-versions/${versionId}/publish`)
}

export function archivePlanVersion(versionId: string): Promise<PlanVersionView> {
  return postJson<PlanVersionView>(`/v1/evaluation-plan-versions/${versionId}/archive`)
}

export interface EvaluatorListParams {
  page?: number
  pageSize?: number
  key?: string
  type?: EvaluatorType | ''
  status?: string
}

export function listEvaluators(params: EvaluatorListParams = {}): Promise<PageResponse<EvaluatorView>> {
  return getJson<PageResponse<EvaluatorView>>(`/v1/evaluators${buildQuery({ ...params })}`)
}

export function getEvaluator(id: string): Promise<EvaluatorView> {
  return getJson<EvaluatorView>(`/v1/evaluators/${id}`)
}
