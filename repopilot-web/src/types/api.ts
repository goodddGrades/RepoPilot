/**
 * 后端统一响应结构（与 repopilot-server 的 ApiResponse 保持一致）。
 */
export interface FieldViolation {
  field: string
  message: string
}

export interface ApiResponse<T> {
  code: string
  message: string
  data?: T
  errors?: FieldViolation[]
  requestId?: string
  timestamp: string
}

/** 健康状态，与后端 HealthStatus 枚举一致（含 V1 预留但未接入的 UNKNOWN）。 */
export type HealthStatus = 'UP' | 'DOWN' | 'DEGRADED' | 'UNKNOWN'

export interface ComponentHealth {
  name: string
  status: HealthStatus
  required: boolean
  detail?: string
  latencyMs: number
}

export interface HealthSnapshot {
  status: HealthStatus
  application: string
  version: string
  profile: string
  timestamp: string
  components: ComponentHealth[]
}
