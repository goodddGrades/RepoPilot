import { ElMessage } from 'element-plus'

import { ApiError } from '@/api/http'

/**
 * 错误提示。
 *
 * 字段级错误一并展示（最多 5 条）：后端把行号放在 field 里，
 * 只说"请求失败"会让用户无法定位到具体哪一行。
 */
export function toastError(error: unknown, fallback = '操作失败'): void {
  if (error instanceof ApiError) {
    const detail = error.errors
      .slice(0, 5)
      .map((item) => `${item.field}: ${item.message}`)
      .join('\n')
    ElMessage({
      type: 'error',
      duration: 6000,
      showClose: true,
      message: detail ? `${error.message}\n${detail}` : error.message || fallback,
    })
    return
  }
  const message = error instanceof Error ? error.message : fallback
  ElMessage({ type: 'error', duration: 5000, showClose: true, message })
}

export function toastSuccess(message: string): void {
  ElMessage({ type: 'success', message })
}

/**
 * 解析用户输入的 JSON 文本。
 *
 * 必须在这里就报错：把一个无效字符串发给后端，用户只会拿到"请求体格式不正确"，
 * 无法知道是哪一个输入框写错了。
 */
export function parseJsonText(text: string, label: string): unknown {
  const trimmed = text.trim()
  if (trimmed === '') {
    return undefined
  }
  try {
    return JSON.parse(trimmed)
  } catch {
    throw new Error(`${label} 不是合法 JSON`)
  }
}

export function stringifyJson(value: unknown): string {
  if (value === undefined || value === null) {
    return ''
  }
  return JSON.stringify(value, null, 2)
}

/** ISO 时间 → 本地可读时间；无效值原样返回，避免显示 "Invalid Date"。 */
export function formatTime(value?: string): string {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString('zh-CN', { hour12: false })
}

export function shortHash(value?: string): string {
  return value ? `${value.slice(0, 12)}…` : '-'
}
