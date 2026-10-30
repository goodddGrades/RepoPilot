import { getJson } from '@/api/http'
import { buildQuery } from '@/api/query'
import type { TraceEventPage } from '@/types/domain'

export interface TraceQueryParams {
  /** 上一页返回的 nextCursor；换过滤条件时必须清空 */
  cursor?: string
  limit?: number
  attemptId?: string
  eventType?: string
  allAttempts?: boolean
}

/**
 * 读取一页轨迹事件。
 *
 * 服务端用 (occurredAt, id) 的签名游标分页，不接受页码：
 * 轨迹会追加、迟到事件可能插入旧时间段，页码分页会产生重复与漏行。
 */
export function listTraceEvents(
  runId: string,
  params: TraceQueryParams = {},
): Promise<TraceEventPage> {
  return getJson<TraceEventPage>(`/v1/runs/${runId}/events${buildQuery({ ...params })}`)
}

/** 事件类型的展示文案。未知类型原样显示——新适配器可能引入注册表之外的类型。 */
export const EVENT_TYPE_TEXT: Record<string, string> = {
  RUN_STARTED: '开始执行',
  RUN_COMPLETED: '结束执行',
  MODEL_REQUEST: '模型请求',
  MODEL_RESPONSE: '模型响应',
  TOOL_STARTED: '工具调用开始',
  TOOL_COMPLETED: '工具调用结束',
  OUTPUT_PRODUCED: '产出输出',
  ERROR: '错误',
}

export type EventTagType = 'info' | 'primary' | 'warning' | 'success' | 'danger'

export function eventTypeTag(type: string): EventTagType {
  switch (type) {
    case 'ERROR':
      return 'danger'
    case 'TOOL_COMPLETED':
      return 'success'
    case 'TOOL_STARTED':
    case 'MODEL_REQUEST':
      return 'primary'
    case 'MODEL_RESPONSE':
    case 'OUTPUT_PRODUCED':
      return 'success'
    case 'RUN_STARTED':
    case 'RUN_COMPLETED':
      return 'info'
    default:
      return 'warning'
  }
}

/** 工具事件：按 toolCallId 把开始/结束配成一次调用。 */
export interface ToolCallPair {
  key: string
  toolName?: string
  startedAt?: string
  finishedAt?: string
  outcome?: string
  durationMs?: number
  failureMessage?: string
  /** 只有开始没有结束，或只有结束没有开始：如实标为不完整，不补造另一端 */
  incomplete: boolean
}

export function isToolEvent(type: string): boolean {
  return type === 'TOOL_STARTED' || type === 'TOOL_COMPLETED'
}

/** 从 payload 里按需取字段；payload 是不可信数据，取不到就返回 undefined。 */
export function payloadField(payload: unknown, key: string): unknown {
  if (!payload || typeof payload !== 'object') {
    return undefined
  }
  return (payload as Record<string, unknown>)[key]
}

export function payloadText(payload: unknown, key: string): string | undefined {
  const value = payloadField(payload, key)
  return typeof value === 'string' ? value : undefined
}
