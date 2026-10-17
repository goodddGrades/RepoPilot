import axios, { type AxiosError } from 'axios'

import type { ApiResponse } from '@/types/api'

/**
 * 前端统一 API 错误。
 *
 * 后端的所有失败响应都遵循统一结构，这里把它转换为可 catch 的异常，
 * 页面只需要展示 message，无需重复判断响应码。
 */
export class ApiError extends Error {
  readonly code: string
  readonly status?: number
  readonly requestId?: string

  constructor(code: string, message: string, status?: number, requestId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.requestId = requestId
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
      throw new ApiError(payload.code, payload.message, response.status, payload.requestId)
    }
    return response
  },
  (error: AxiosError<ApiResponse<unknown>>) => {
    const payload = error.response?.data
    if (payload?.code) {
      return Promise.reject(
        new ApiError(payload.code, payload.message, error.response?.status, payload.requestId),
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

export default http
