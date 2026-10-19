import { buildQuery } from '@/api/query'
import { getJson, newIdempotencyKey, postJson, uploadForm } from '@/api/http'
import type {
  ArtifactView,
  ImportBatchView,
  ImportErrorView,
  ImportStatus,
  ImportType,
  PageResponse,
} from '@/types/domain'

/**
 * 离线导入相关接口。
 *
 * 分两步：先把原始 JSONL 作为 Artifact 上传（拿到 id），再创建导入批次。
 * 不合成一个接口的原因是可重试——文件只传一次，批次失败重开时不必重传几十兆。
 */

export interface ImportListParams {
  page?: number
  pageSize?: number
  importType?: ImportType
  status?: ImportStatus
  experimentId?: string
  datasetVersionId?: string
}

export interface CreateImportPayload {
  importType: ImportType
  datasetVersionId: string
  experimentId?: string
  fileArtifactId: string
}

export function uploadImportFile(
  file: File,
  onProgress?: (percent: number) => void,
): Promise<ArtifactView> {
  const form = new FormData()
  form.append('artifactType', 'IMPORT_FILE')
  form.append('file', file)
  return uploadForm<ArtifactView>('/v1/artifacts', form, newIdempotencyKey(), onProgress)
}

export function getArtifact(id: string): Promise<ArtifactView> {
  return getJson<ArtifactView>(`/v1/artifacts/${id}`)
}

export function createImport(payload: CreateImportPayload): Promise<ImportBatchView> {
  return postJson<ImportBatchView>('/v1/imports', payload)
}

export function listImports(params: ImportListParams = {}): Promise<PageResponse<ImportBatchView>> {
  return getJson<PageResponse<ImportBatchView>>(`/v1/imports${buildQuery({ ...params })}`)
}

export function getImport(id: string): Promise<ImportBatchView> {
  return getJson<ImportBatchView>(`/v1/imports/${id}`)
}

export function listImportErrors(
  id: string,
  params: { page?: number; pageSize?: number } = {},
): Promise<PageResponse<ImportErrorView>> {
  return getJson<PageResponse<ImportErrorView>>(`/v1/imports/${id}/errors${buildQuery({ ...params })}`)
}

export function cancelImport(id: string): Promise<ImportBatchView> {
  return postJson<ImportBatchView>(`/v1/imports/${id}/cancel`)
}

/** 终态批次不会再有新行；轮询遇到它就该停。 */
export function isTerminal(status: ImportStatus): boolean {
  return (
    status === 'COMPLETED' ||
    status === 'PARTIAL_FAILED' ||
    status === 'FAILED' ||
    status === 'CANCELLED'
  )
}
