/**
 * 报告类型（对应 `docs/contracts/openapi-reporting.yaml`）。
 *
 * 这里的可空性是**契约的一部分**，不是偷懒：
 * `rate: null` 表示"分母为 0"，`average: null` 表示"没有有效样本"，
 * `amount: null` 表示"成本未知"。前端遇到 null 必须显示"—"或"未知"，
 * 绝不能 `?? 0`——那会把"没测"显示成"测了并得 0 分"。
 */

/** 分子 / 分母 / 比率，以及没被计入分母的那些。 */
export interface Ratio {
  numerator: number
  denominator: number
  /** 分母为 0 时为 null，不是 0 */
  rate: number | null
  /** 未计入分母但确实发生过的数量：UNKNOWN、N-A、技术失败 */
  excluded: number
  /** 应有但完全没有数据的数量 */
  missing: number
}

/** 只统计有效样本的平均值。 */
export interface Average {
  /** 无有效样本时为 null（不是 0） */
  average: number | null
  validSampleCount: number
  missingCount: number
}

/** p50 / p95（最近秩法，不插值）与有效样本数。 */
export interface Percentiles {
  p50: number | null
  p95: number | null
  validSampleCount: number
  missingCount: number
}

/** 一笔金额，永远带币种。不同币种不相加。 */
export interface Money {
  /** 未知时为 null（不是 0） */
  amount: number | null
  currency: string | null
  knownCount: number
  unknownCount: number
}

/** 一个维度的成本汇总。没有"总额"字段。 */
export interface CostBreakdown {
  byCurrency: Money[]
  unknownCostCount: number
  sampleCount: number
}

/** 某个算子上的结论分布；四类结论 + 技术失败 + 缺失。 */
export interface VerdictDistribution {
  pass: number
  fail: number
  indeterminate: number
  notApplicable: number
  /** Job 没跑完导致的失败：平台问题，不是被测对象的质量问题 */
  technicalFailure: number
  missing: number
  /** 分母只含 PASS + FAIL */
  passRate: Ratio
}

/** 单个 Target 的槽位级指标。 */
export interface TargetMetrics {
  targetKey: string
  agentVersionId: string | null
  agentVersionLabel: string | null
  /** false 时本 Target 只参与描述性对比，不构成受控因果实验 */
  agentVersionKnown: boolean
  /** 应有槽位数，取 expectedRunCount / Target 数 */
  expectedSlotCount: number
  observedSlotCount: number
  slotCoverage: Ratio
  completedCount: number
  failedCount: number
  timedOutCount: number
  cancelledCount: number
  missingCount: number
  technicalCompletion: Ratio
  taskSuccessCount: number
  taskFailureCount: number
  taskUnknownCount: number
  taskNotApplicableCount: number
  /** 分母只含 SUCCESS + FAILURE */
  taskSuccessRate: Ratio
  verdicts: Record<string, VerdictDistribution>
  score: Average
  durationMs: Percentiles
  /** 最终观察成本：只算被接受的那次 Attempt / 离线导入的 usage */
  finalObservationCost: CostBreakdown
  /** 平台实际执行成本：所有 RunAttempt 与 JobAttempt（含被重试掉的） */
  allAttemptsCost: CostBreakdown
}

/** 批次与实验上下文。前端必须原样展示，不允许自己推断。 */
export interface ReportHeader {
  experimentId: string
  experimentName: string | null
  experimentStatus: string
  expectedRunCount: number
  batchId: string
  batchNo: number
  batchScope: BatchScope
  batchStatus: string
  triggerType: string
  planVersionId: string
  planVersion: number | null
  gateRuleVersion: number | null
  /** false 表示该方案版本没有 gateRules，门禁必然是 INCONCLUSIVE */
  gateConfigured: boolean
  annotationView: AnnotationView
}

export type BatchScope = 'INITIAL' | 'LATEST'
export type AnnotationView = 'AUTOMATIC' | 'REVIEWED'

/** 配对槽位的判定结果。 */
export type PairOutcome =
  | 'REGRESSION'
  | 'IMPROVEMENT'
  | 'STABLE'
  | 'SINGLE_SIDED'
  | 'TECHNICAL_ERROR'

export const PAIR_OUTCOME_LABELS: Record<PairOutcome, string> = {
  REGRESSION: '回归（PASS → FAIL）',
  IMPROVEMENT: '改善（FAIL → PASS）',
  STABLE: '稳定',
  SINGLE_SIDED: '单侧缺失',
  TECHNICAL_ERROR: '技术失败',
}

/** 配对结果的展示配色：与"通过率高 → 红"的财务直觉相反，这里按工程语义配色。 */
export const PAIR_OUTCOME_TAGS: Record<PairOutcome, 'danger' | 'success' | 'info' | 'warning'> = {
  REGRESSION: 'danger',
  IMPROVEMENT: 'success',
  STABLE: 'info',
  SINGLE_SIDED: 'warning',
  TECHNICAL_ERROR: 'warning',
}

/** 单个配对槽位，可下钻到两个 Run。 */
export interface PairedSlot {
  caseKey: string
  repeatIndex: number
  baselineRunId: string | null
  candidateRunId: string | null
  baselineVerdict: string | null
  candidateVerdict: string | null
  baselineScore: number | null
  candidateScore: number | null
  /** 任一侧无分时为 null（不按 0 处理） */
  scoreDelta: number | null
  outcome: PairOutcome
  /** 人可读的判定依据，直接展示，不要前端重新措辞 */
  note: string
}

/** 一次配对比较。 */
export interface PairComparison {
  baselineTargetKey: string
  candidateTargetKey: string
  baselineAgentVersion: string | null
  candidateAgentVersion: string | null
  baselineAgentVersionKnown: boolean
  candidateAgentVersionKnown: boolean
  /** false 时不得据此宣称因果优劣 */
  controlledComparison: boolean
  incomparabilityReason: string | null
  totalSlots: number
  pairedSlots: number
  singleSidedSlots: number
  technicalErrorSlots: number
  regressions: number
  improvements: number
  stableSlots: number
  /** 分母是 totalSlots（配对键空间），不是 pairedSlots */
  validPairCoverage: Ratio
  slots: PairedSlot[]
  meanBaselineScore: number | null
  meanCandidateScore: number | null
  meanScoreDelta: number | null
}

/** 单条门禁发现。 */
export interface GateFinding {
  rule: string
  severity: 'BLOCKING' | 'INFO' | 'WARNING'
  message: string
  /** 违规 Run，可直接下钻 */
  offendingRunIds: string[]
}

export type GateVerdict = 'PASS' | 'FAIL' | 'INCONCLUSIVE'

export const GATE_VERDICT_LABELS: Record<GateVerdict, string> = {
  PASS: '通过',
  FAIL: '不通过',
  INCONCLUSIVE: '证据不足',
}

export const GATE_VERDICT_TAGS: Record<GateVerdict, 'success' | 'danger' | 'warning'> = {
  PASS: 'success',
  FAIL: 'danger',
  INCONCLUSIVE: 'warning',
}

/**
 * 门禁结论。**不复用 experiments.status**：
 * 技术完成与质量达标是两件事。
 */
export interface GateResult {
  verdict: GateVerdict
  ruleVersion: number | null
  ruleSummary: string | null
  pairedSlots: number
  totalSlots: number
  validPairCoverage: number | null
  violations: GateFinding[]
  insufficientEvidence: GateFinding[]
  passed: GateFinding[]
}

export interface ExperimentReport {
  header: ReportHeader
  targets: TargetMetrics[]
  /** 未指定候选 Target 时缺省 */
  comparison?: PairComparison | null
  gate?: GateResult | null
}

export interface ReportQuery {
  batchId?: string
  batchScope?: BatchScope
  baselineTargetKey?: string
  candidateTargetKey?: string
  annotationView?: AnnotationView
}