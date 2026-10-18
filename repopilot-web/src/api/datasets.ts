import { deleteJson, getJson, patchJson, postJson, putJson, uploadFile } from '@/api/http'
import { buildQuery } from '@/api/query'
import type {
  DatasetCaseView,
  DatasetVersionView,
  DatasetView,
  ImportReceipt,
  PageResponse,
  TaskType,
} from '@/types/domain'

export interface DatasetListParams {
  page?: number
  pageSize?: number
  status?: string
  keyword?: string
}

export interface CreateDatasetPayload {
  code: string
  name: string
  description?: string
}

export interface CreateDatasetVersionPayload {
  description?: string
  cloneFromVersionId?: string
}

export interface CasePayload {
  caseKey: string
  taskType: TaskType
  input: unknown
  expected?: unknown
  metadata?: unknown
}

export function listDatasets(params: DatasetListParams = {}): Promise<PageResponse<DatasetView>> {
  return getJson<PageResponse<DatasetView>>(`/v1/datasets${buildQuery({ ...params })}`)
}

export function getDataset(id: string): Promise<DatasetView> {
  return getJson<DatasetView>(`/v1/datasets/${id}`)
}

export function createDataset(payload: CreateDatasetPayload): Promise<DatasetView> {
  return postJson<DatasetView>('/v1/datasets', payload)
}

export function patchDataset(id: string, payload: { name?: string; description?: string; status?: string }) {
  return patchJson<DatasetView>(`/v1/datasets/${id}`, payload)
}

export function listDatasetVersions(
  datasetId: string,
  params: { page?: number; pageSize?: number } = {},
): Promise<PageResponse<DatasetVersionView>> {
  return getJson<PageResponse<DatasetVersionView>>(
    `/v1/datasets/${datasetId}/versions${buildQuery({ ...params })}`,
  )
}

export function createDatasetVersion(
  datasetId: string,
  payload: CreateDatasetVersionPayload = {},
): Promise<DatasetVersionView> {
  return postJson<DatasetVersionView>(`/v1/datasets/${datasetId}/versions`, payload)
}

export function getDatasetVersion(versionId: string): Promise<DatasetVersionView> {
  return getJson<DatasetVersionView>(`/v1/dataset-versions/${versionId}`)
}

export function publishDatasetVersion(versionId: string): Promise<DatasetVersionView> {
  return postJson<DatasetVersionView>(`/v1/dataset-versions/${versionId}/publish`)
}

export function archiveDatasetVersion(versionId: string): Promise<DatasetVersionView> {
  return postJson<DatasetVersionView>(`/v1/dataset-versions/${versionId}/archive`)
}

export function listCases(
  versionId: string,
  params: { page?: number; pageSize?: number; keyword?: string } = {},
): Promise<PageResponse<DatasetCaseView>> {
  return getJson<PageResponse<DatasetCaseView>>(
    `/v1/dataset-versions/${versionId}/cases${buildQuery({ ...params })}`,
  )
}

export function createCase(versionId: string, payload: CasePayload): Promise<DatasetCaseView> {
  return postJson<DatasetCaseView>(`/v1/dataset-versions/${versionId}/cases`, payload)
}

export function updateCase(caseId: string, payload: CasePayload): Promise<DatasetCaseView> {
  return putJson<DatasetCaseView>(`/v1/dataset-cases/${caseId}`, payload)
}

export function deleteCase(caseId: string): Promise<{ id: string; deleted: boolean }> {
  return deleteJson<{ id: string; deleted: boolean }>(`/v1/dataset-cases/${caseId}`)
}

/** 上传 JSONL 原文件；整批原子，失败时后端不写任何数据。 */
export function importCases(
  versionId: string,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<ImportReceipt> {
  return uploadFile<ImportReceipt>(
    `/v1/dataset-versions/${versionId}/cases:import`,
    file,
    undefined,
    onProgress,
  )
}
