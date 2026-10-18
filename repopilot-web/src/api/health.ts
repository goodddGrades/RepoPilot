import { getJson } from '@/api/http'
import type { HealthSnapshot } from '@/types/api'

/** 调用 repopilot-server 的健康检查接口。 */
export function fetchHealth(): Promise<HealthSnapshot> {
  return getJson<HealthSnapshot>('/health')
}
