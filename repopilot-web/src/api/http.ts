import axios, { type AxiosError } from 'axios'

import type { ApiResponse, FieldViolation } from '@/types/api'

/**
 * 前端统一 API 错误。
 *
 * 后端的所有失败响应都遵循统一结构，这里把它转换为可 catch 的异常，
 * 页面只需要展示 message，无需重复判断响应码。
 *
 * errors 保留字段级明细：导入这种一次返回 100 条行级错误的场景，
 * 只显示顶层 message 等于让用户自己猜是哪一行。
 */
export class ApiError extends Error {
  readonly code: string
  readonly status?: number
  readonly requestId?: string
  readonly errors: FieldViolation[]

  constructor(
    code: string,
    message: string,
    status?: number,
    requestId?: string,
    errors: FieldViolation[] = [],
  ) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.requestId = requestId
    this.errors = errors
  }
}

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10000,
  headers: { Accept: 'application/json' },
})

http.interceptors.response.use(
  (response) => {
    const payload = response.data as ApiResponse<unknown> | undefined
    if (payload && typeof payload.code === 'string' && payload.code !== 'OK') {
      throw new ApiError(
        payload.code,
        payload.message,
        response.status,
        payload.requestId,
        payload.errors ?? [],
      )
    }
    return response
  },
  (error: AxiosError<ApiResponse<unknown>>) => {
    const payload = error.response?.data
    if (payload?.code) {
      return Promise.reject(
        new ApiError(
          payload.code,
          payload.message,
          error.response?.status,
          payload.requestId,
          payload.errors ?? [],
        ),
      )
    }
    const reason = error.code === 'ECONNABORTED' ? '请求超时' : error.message
    return Promise.reject(new ApiError('NETWORK_ERROR', `无法连接后端服务：${reason}`))
  },
)

/**
 * GET 请求并解包统一响应中的 data 字段。
 *
 * 后端约定成功响应一定包含 data，缺失时视为契约错误而不是返回 undefined，
 * 避免页面在无数据时静默展示空状态。
 */
export async function getJson<T>(url: string): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url)
  const payload = response.data
  if (payload.data === undefined || payload.data === null) {
    throw new ApiError('EMPTY_RESPONSE', '后端响应缺少 data 字段', response.status, payload.requestId)
  }
  return payload.data
}

/**
 * 写操作必须携带 Idempotency-Key（后端对缺失的请求直接 400）。
 *
 * 用随机 UUID 而不是时间戳：同一毫秒内连点两次「提交」也可能发出两个请求，
 * 时间戳会让它们被当成同一个请求而复用回执。
 */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `idem-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function writeHeaders(idempotencyKey?: string): Record<string, string> {
  return { 'Idempotency-Key': idempotencyKey ?? newIdempotencyKey() }
}

export async function postJson<T>(url: string, body?: unknown, idempotencyKey?: string): Promise<T> {
  const response = await http.post<ApiResponse<T>>(url, body ?? {}, {
    headers: writeHeaders(idempotencyKey),
  })
  return unwrap(response)
}

export async function putJson<T>(url: string, body: unknown, idempotencyKey?: string): Promise<T> {
  const response = await http.put<ApiResponse<T>>(url, body, {
    headers: writeHeaders(idempotencyKey),
  })
  return unwrap(response)
}

export async function patchJson<T>(url: string, body: unknown, idempotencyKey?: string): Promise<T> {
  const response = await http.patch<ApiResponse<T>>(url, body, {
    headers: writeHeaders(idempotencyKey),
  })
  return unwrap(response)
}

export async function deleteJson<T>(url: string, idempotencyKey?: string): Promise<T> {
  const response = await http.delete<ApiResponse<T>>(url, {
    headers: writeHeaders(idempotencyKey),
  })
  return unwrap(response)
}

/**
 * 上传 JSONL。
 *
 * 不在浏览器里做格式校验：文件的合法性由服务端统一判定（含行号），
 * 前端再写一套规则必然与服务端逐渐不一致。
 */
export async function uploadFile<T>(
  url: string,
  file: File,
  idempotencyKey?: string,
  onProgress?: (percent: number) => void,
): Promise<T> {
  const form = new FormData()
  form.append('file', file)
  const response = await http.post<ApiResponse<T>>(url, form, {
    headers: writeHeaders(idempotencyKey),
    timeout: 120000,
    onUploadProgress: (event) => {
      if (onProgress && event.total) {
        onProgress(Math.round((event.loaded / event.total) * 100))
      }
    },
  })
  return unwrap(response)
}

function unwrap<T>(response: { data: ApiResponse<T>; status: number }): T {
  const payload = response.data
  if (payload.data === undefined || payload.data === null) {
    throw new ApiError('EMPTY_RESPONSE', '后端响应缺少 data 字段', response.status, payload.requestId)
  }
  return payload.data
}

export default http
