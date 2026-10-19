import { getJson } from '@/api/http'
import { buildQuery } from '@/api/query'
import type { ExperimentReport, ReportQuery } from '@/types/report'

/**
 * 报告接口（TASK-010）。
 *
 * 纯只读：不带幂等键，也不做任何写操作。所有参数都有契约默认值
 * （`batchScope=INITIAL`、`annotationView=AUTOMATIC`），这里**显式传下去**
 * 而不是依赖后端兜底——默认值写在两处就会漂移，显式传参让请求可自解释。
 */
export function getExperimentReport(
  experimentId: string,
  query: ReportQuery = {},
): Promise<ExperimentReport> {
  return getJson<ExperimentReport>(
    `/v1/experiments/${experimentId}/report${buildQuery({
      batchId: query.batchId,
      batchScope: query.batchScope ?? 'INITIAL',
      baselineTargetKey: query.baselineTargetKey,
      candidateTargetKey: query.candidateTargetKey,
      annotationView: query.annotationView ?? 'AUTOMATIC',
    })}`,
  )
}