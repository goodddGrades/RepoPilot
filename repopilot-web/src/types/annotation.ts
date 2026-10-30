/**
 * 人工标注与失败归因的类型（TASK-009）。
 *
 * 与 `com.repopilot.modules.annotation.dto.AnnotationDtos` 一一对应。
 *
 * 一条贯穿全局的语义：**人工结论不覆盖自动结论**。
 * `RecordView.originalVerdict` 是被复核那一刻的自动评测结论，
 * `RecordView.verdict` 是人工给出的判断。两者分开存，报告才能同时给出
 * "机器怎么说"和"人怎么看"，而不是把其中一个悄悄改掉。
 */

export type AnnotationTaskStatus =
  | 'PENDING'
  | 'CLAIMED'
  | 'SUBMITTED'
  | 'REVIEWED'
  | 'RETURNED'
  | 'CANCELLED'

export type AnnotationAction = 'CLAIM' | 'SUBMIT' | 'REVIEW' | 'RETURN' | 'CANCEL'

/**
 * 失败归因分类。
 *
 * `UNKNOWN` 是**显式的"不归因"**，不是兜底：证据不足时应当明说，
 * 而不是随便挑一个类别让归因统计看起来完整。
 */
export type FailureCategory =
  | 'MODEL_REASONING'
  | 'TOOL_USE'
  | 'RETRIEVAL'
  | 'CONTEXT'
  | 'ENVIRONMENT'
  | 'DATA_QUALITY'
  | 'EVALUATOR'
  | 'OTHER'
  | 'UNKNOWN'

export const FAILURE_CATEGORY_LABELS: Record<FailureCategory, string> = {
  MODEL_REASONING: '模型推理',
  TOOL_USE: '工具调用',
  RETRIEVAL: '检索召回',
  CONTEXT: '上下文',
  ENVIRONMENT: '环境',
  DATA_QUALITY: '数据质量',
  EVALUATOR: '评测器自身',
  OTHER: '其他',
  UNKNOWN: '证据不足，不归因',
}

export interface AnnotationRecordView {
  id: string
  /** V1 由后端开发配置固定，接口不接受调用方自称的身份 */
  operatorId: string
  action: AnnotationAction
  /** 人工给出的判定；CLAIM / CANCEL 等动作没有判定 */
  verdict?: string
  failureCategory?: string
  reason?: string
  evidence?: unknown
  /** 复核当时的自动评测结论，用于展示"人工把哪条改成了什么" */
  originalVerdict?: string
  createdAt: string
}

export interface AnnotationTaskView {
  id: string
  runId: string
  /** 可选：针对某个具体算子 Job 的复核 */
  evaluationJobId?: string
  status: AnnotationTaskStatus
  assigneeId?: string
  /** 乐观锁版本：每个动作都要带上前端看到的这个值 */
  version: number
  claimedAt?: string
  submittedAt?: string
  reviewedAt?: string
  createdAt: string
  updatedAt: string
  records: AnnotationRecordView[]
}

export interface DispatchHint {
  runId: string
  evaluationJobId?: string
  /** 为什么需要人看：技术状态或"自动评测判定未通过" */
  reason: string
  /** 机器给出的结论；技术失败时没有结论（缺失即"没有"，不补 0 或 PASS） */
  autoVerdict?: string
  createdAt: string
}

export interface SubmitAnnotationPayload {
  expectedVersion: number
  verdict: string
  /** 判 FAIL 时必填；证据不足请显式填 UNKNOWN */
  failureCategory?: FailureCategory
  reason: string
  evidence?: unknown[]
}

export interface ReviewAnnotationPayload {
  expectedVersion: number
  approved: boolean
  /** 退回时必填 */
  reason?: string
  failureCategory?: FailureCategory
  evidence?: unknown[]
}
