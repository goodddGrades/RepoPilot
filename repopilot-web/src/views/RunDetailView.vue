<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Refresh } from '@element-plus/icons-vue'

import * as experimentApi from '@/api/experiments'
import TracePanel from '@/components/TracePanel.vue'
import type {
  AttemptStatus,
  JobStatus,
  JobView,
  RunDetail,
  RunMode,
  RunStatus,
  Verdict,
} from '@/types/domain'
import { formatTime, stringifyJson, toastError } from '@/utils/feedback'

const route = useRoute()
const router = useRouter()
const runId = computed(() => String(route.params.id ?? ''))

const detail = ref<RunDetail | null>(null)
const loading = ref(false)

const runStatusText = experimentApi.RUN_STATUS_TEXT
const runStatusTag = experimentApi.RUN_STATUS_TAG

const ATTEMPT_STATUS_TEXT: Record<AttemptStatus, string> = {
  RUNNING: '执行中',
  COMPLETED: '已完成',
  FAILED: '失败',
  TIMED_OUT: '超时',
  CANCELLED: '已取消',
}

const JOB_STATUS_TEXT: Record<JobStatus, string> = {
  PENDING: '待评测',
  RUNNING: '评测中',
  COMPLETED: '已完成',
  FAILED: '评测失败',
  SKIPPED: '已跳过',
}

/** 判定文案：INDETERMINATE 不是"没评"，是"评了但无法下结论"；页面必须区分这两者。 */
const VERDICT_TEXT: Record<Verdict, string> = {
  PASS: '通过',
  FAIL: '未通过',
  INDETERMINATE: '无法判定',
  NOT_APPLICABLE: '不适用',
}

const VERDICT_TAG: Record<Verdict, 'success' | 'danger' | 'warning' | 'info'> = {
  PASS: 'success',
  FAIL: 'danger',
  INDETERMINATE: 'warning',
  NOT_APPLICABLE: 'info',
}

/**
 * Judge 的审计字段一律从 result 里取，缺失就显示"未知"。
 * 绝不因为字段缺失就显示 0 或空字符串——那会把"没拿到"说成"确实是 0"。
 */
function judgeFact(row: JobView, key: string): string {
  const result = row.result?.result as Record<string, unknown> | undefined
  const value = result?.[key]
  return value == null ? '' : String(value)
}

function judgeQuotes(row: JobView): string[] {
  const result = row.result?.result as Record<string, unknown> | undefined
  const quotes = result?.evidenceQuotes
  return Array.isArray(quotes) ? quotes.map((item) => String(item)) : []
}

function judgeAttempts(row: JobView): string {
  const result = row.result?.result as Record<string, unknown> | undefined
  const attempts = result?.attempts
  if (!Array.isArray(attempts)) {
    return '-'
  }
  return `${attempts.length} 次（含重试）`
}

function judgeCost(row: JobView): string {
  const result = row.result?.result as Record<string, unknown> | undefined
  const cost = result?.costUsd
  const currency = result?.currency
  if (typeof cost !== 'number' || !Number.isFinite(cost)) {
    return ''
  }
  return `${currency ?? 'USD'} ${cost.toFixed(6)}`
}

function judgeElapsed(row: JobView): string {
  const result = row.result?.result as Record<string, unknown> | undefined
  const elapsed = result?.elapsedMs
  if (typeof elapsed !== 'number' || !Number.isFinite(elapsed)) {
    return ''
  }
  return elapsed >= 1000 ? `${(elapsed / 1000).toFixed(2)} 秒` : `${Math.round(elapsed)} 毫秒`
}

async function load(): Promise<void> {
  if (!runId.value) {
    return
  }
  loading.value = true
  try {
    detail.value = await experimentApi.getRun(runId.value)
  } catch (error) {
    toastError(error, '加载 Run 详情失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

const run = computed(() => detail.value?.run ?? null)
const attempts = computed(() => detail.value?.attempts ?? [])
const jobs = computed(() => detail.value?.jobs ?? [])
const batches = computed(() => detail.value?.jobBatches ?? [])

const pendingJobs = computed(() => jobs.value.filter((job) => job.status === 'PENDING').length)

/** 评测结果尚未产生：这只是"待评测"，页面必须显式说明，不能让人误读成 0 分或通过。 */
const evaluationPending = computed(
  () => run.value?.status === 'EVALUATING' && pendingJobs.value > 0,
)

const outputText = computed(() => stringifyJson(detail.value?.output) || '（无输出正文）')
const observationText = computed(() => stringifyJson(detail.value?.observationMetadata) || '（无）')

const runMode = computed<RunMode>(() => (run.value?.mode as RunMode) ?? 'ONLINE')

/** 轨迹完整度来自服务端派生元数据；缺失就按"未知"展示，不假定完整。 */
const traceCoverage = computed(() => {
  const meta = detail.value?.observationMetadata
  if (meta && typeof meta === 'object' && 'traceCoverage' in meta) {
    return String((meta as Record<string, unknown>).traceCoverage ?? '')
  }
  return ''
})
</script>

<template>
  <div class="page" v-loading="loading">
    <header class="page__header">
      <div>
        <el-button
          link
          :icon="ArrowLeft"
          @click="run ? router.push(`/experiments/${run.experimentId}`) : router.push('/experiments')"
        >
          返回实验
        </el-button>
        <h1 class="page__title" style="margin-top: 8px">Run 执行证据</h1>
        <p class="page__subtitle">
          <span class="mono">{{ runId }}</span>
          <span v-if="run"> · {{ run.caseKey ?? run.datasetCaseId }} · 第 {{ run.repeatIndex }} 次</span>
        </p>
      </div>
      <div class="page__toolbar-group">
        <el-tag v-if="run" :type="runStatusTag[run.status as RunStatus]" effect="dark">
          {{ runStatusText[run.status as RunStatus] }}
        </el-tag>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </header>

    <el-alert
      v-if="evaluationPending"
      type="warning"
      show-icon
      :closable="false"
      title="执行已结束，评测尚未开始"
      description="Run 已封口并生成待评测任务（PENDING）。评分由评测 Worker 领取后写入，本页现在显示的是执行结果，不是评测结论。"
    />

    <el-card v-if="run" class="page__card" shadow="never">
      <el-descriptions :column="3" border>
        <el-descriptions-item label="结果">{{ run.taskOutcome }}</el-descriptions-item>
        <el-descriptions-item label="尝试次数">{{ run.attemptCount }}</el-descriptions-item>
        <el-descriptions-item label="接受 Attempt">
          <span class="mono">{{ run.acceptedAttemptId ?? '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="封口时间">{{ formatTime(run.sealedAt) }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ formatTime(run.startedAt) }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ formatTime(run.finishedAt) }}</el-descriptions-item>
        <el-descriptions-item label="Trace 事件数">{{ detail?.traceEventCount ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="Target">
          <span class="mono">{{ run.targetKey ?? run.experimentTargetId }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="输出附件">
          <span class="mono">{{ detail?.outputArtifactId ?? '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item v-if="detail?.sourceSystem" label="来源">
          <span class="mono">{{ detail.sourceSystem }}</span>
          <span v-if="detail.sourceRecordId" class="muted"> · {{ detail.sourceRecordId }}</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card class="page__card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>执行尝试</span>
          <span class="muted">每次领取产生一条；超时/失联的 Attempt 也会保留，不做覆盖</span>
        </div>
      </template>
      <el-table :data="attempts" size="small" style="width: 100%">
        <el-table-column prop="attemptNo" label="#" width="60" />
        <el-table-column prop="workerId" label="Worker" min-width="160" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            {{ ATTEMPT_STATUS_TEXT[row.status as AttemptStatus] }}
          </template>
        </el-table-column>
        <el-table-column label="租约到期" width="180">
          <template #default="{ row }">{{ formatTime(row.leaseUntil) }}</template>
        </el-table-column>
        <el-table-column label="硬截止" width="180">
          <template #default="{ row }">{{ formatTime(row.deadlineAt) }}</template>
        </el-table-column>
        <el-table-column label="错误" min-width="200">
          <template #default="{ row }">
            <span v-if="row.errorCode" class="mono">{{ row.errorCode }}</span>
            <span v-if="row.errorMessage" class="muted"> · {{ row.errorMessage }}</span>
            <span v-if="!row.errorCode && !row.errorMessage" class="muted">-</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有 Worker 领取过这个 Run" />
        </template>
      </el-table>
    </el-card>

    <TracePanel
      v-if="run"
      :run-id="runId"
      :mode="runMode"
      :attempts="attempts"
      :accepted-attempt-id="run.acceptedAttemptId"
      :trace-coverage="traceCoverage"
    />

    <el-card class="page__card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>评测任务</span>
          <span class="muted">按批次分组；重评会新增批次，历史结果原样保留</span>
        </div>
      </template>
      <el-table :data="batches" size="small" style="width: 100%">
        <el-table-column prop="batchNo" label="批次" width="80" />
        <el-table-column prop="total" label="总数" width="80" />
        <el-table-column prop="pending" label="待评测" width="90" />
        <el-table-column prop="running" label="进行中" width="90" />
        <el-table-column prop="completed" label="已完成" width="90" />
        <el-table-column prop="failed" label="失败" width="80" />
        <el-table-column prop="skipped" label="跳过" width="80" />
        <el-table-column label="方案版本" min-width="240">
          <template #default="{ row }">
            <span class="mono">{{ row.planVersionId }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有评测任务（Run 尚未成功封口）" />
        </template>
      </el-table>

      <el-table :data="jobs" size="small" style="width: 100%; margin-top: 12px" row-key="id">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="job-expand">
              <div v-if="row.result" class="job-expand__block">
                <template v-if="row.result.generatedBy === 'LLM'">
                  <div class="judge-banner">
                    本结论由模型 Judge 依据固定 rubric 给出，属于「模型判断」，不是规则计算的客观事实。
                  </div>
                  <el-descriptions :column="2" size="small" border class="judge-facts">
                    <el-descriptions-item label="评分标准 rubric">
                      <span class="mono">{{ judgeFact(row, 'rubricKey') || '-' }}</span>
                    </el-descriptions-item>
                    <el-descriptions-item label="prompt 版本">
                      <span class="mono">{{ judgeFact(row, 'promptVersion') || '-' }}</span>
                    </el-descriptions-item>
                    <el-descriptions-item label="模型">
                      <span class="mono">{{ judgeFact(row, 'model') || '-' }}</span>
                      <el-tag
                        v-if="judgeFact(row, 'provider') === 'stub'"
                        class="source-tag"
                        size="small"
                        type="danger"
                        effect="plain"
                      >测试桩，非真实调用</el-tag>
                    </el-descriptions-item>
                    <el-descriptions-item label="调用次数">
                      {{ judgeAttempts(row) }}
                    </el-descriptions-item>
                    <el-descriptions-item label="Judge 成本">
                      <span v-if="judgeCost(row)">{{ judgeCost(row) }}</span>
                      <span v-else class="muted">未知（供应商未回报）</span>
                    </el-descriptions-item>
                    <el-descriptions-item label="调用耗时">
                      <span v-if="judgeElapsed(row)">{{ judgeElapsed(row) }}</span>
                      <span v-else class="muted">-</span>
                    </el-descriptions-item>
                  </el-descriptions>
                  <div class="muted" style="margin-top: 8px">
                    模型引用的原文片段（引自被测输出，平台不代为扩写）
                  </div>
                  <ul v-if="judgeQuotes(row).length" class="judge-quotes">
                    <li v-for="(quote, index) in judgeQuotes(row)" :key="index" class="mono">
                      {{ quote }}
                    </li>
                  </ul>
                  <div v-else class="muted">模型没有引用具体片段。</div>
                </template>
                <div class="muted">评分依据（evidence）——只引用本 Run 的事件、附件或数据集字段</div>
                <pre class="json-block">{{ stringifyJson(row.result.evidence) || '（无）' }}</pre>
                <div class="muted" style="margin-top: 8px">结构化结果（result）</div>
                <pre class="json-block">{{ stringifyJson(row.result.result) || '（无）' }}</pre>
              </div>
              <div v-else class="muted">还没有结果；失败的任务展开后只能看到下面的尝试历史。</div>
              <div class="muted" style="margin-top: 8px">评测尝试历史（重试不覆盖旧记录）</div>
              <el-table :data="row.attempts" size="small" border>
                <el-table-column prop="attemptNo" label="#" width="60" />
                <el-table-column prop="workerId" label="Worker" min-width="140" />
                <el-table-column label="状态" width="100">
                  <template #default="{ row: att }">
                    {{ ATTEMPT_STATUS_TEXT[att.status as AttemptStatus] }}
                  </template>
                </el-table-column>
                <el-table-column label="开始" width="170">
                  <template #default="{ row: att }">{{ formatTime(att.startedAt) }}</template>
                </el-table-column>
                <el-table-column label="结束" width="170">
                  <template #default="{ row: att }">{{ formatTime(att.finishedAt) }}</template>
                </el-table-column>
                <el-table-column label="错误" min-width="200">
                  <template #default="{ row: att }">
                    <span v-if="att.errorCode" class="mono">{{ att.errorCode }}</span>
                    <span v-if="att.errorMessage" class="muted"> · {{ att.errorMessage }}</span>
                    <span v-if="!att.errorCode && !att.errorMessage" class="muted">-</span>
                  </template>
                </el-table-column>
                <template #empty>
                  <el-empty description="还没有 Worker 领取过" :image-size="48" />
                </template>
              </el-table>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="executionOrder" label="顺序" width="70" />
        <el-table-column label="算子" min-width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.evaluatorKey }}</span>
            <span class="muted"> v{{ row.evaluatorVersion }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <span class="mono">{{ row.evaluatorType }}</span>
          </template>
        </el-table-column>
        <el-table-column label="必需" width="70">
          <template #default="{ row }">{{ row.required ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">{{ JOB_STATUS_TEXT[row.status as JobStatus] }}</template>
        </el-table-column>
        <el-table-column label="判定" width="150">
          <template #default="{ row }">
            <el-tag
              v-if="row.result"
              :type="VERDICT_TAG[row.result.verdict as Verdict]"
              size="small"
              effect="plain"
            >
              {{ VERDICT_TEXT[row.result.verdict as Verdict] }}
            </el-tag>
            <span v-else class="muted">-</span>
            <el-tooltip
              v-if="row.result?.generatedBy === 'LLM'"
              content="该结论由模型 Judge 给出，不是规则计算的客观事实"
              placement="top"
            >
              <el-tag class="source-tag" size="small" type="warning" effect="plain">模型判断</el-tag>
            </el-tooltip>
            <el-tooltip
              v-else-if="row.result?.generatedBy === 'TEST'"
              content="该结论来自执行可信测试命令的退出码"
              placement="top"
            >
              <el-tag class="source-tag" size="small" type="info" effect="plain">测试执行</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="分数" width="90">
          <template #default="{ row }">
            <span v-if="row.result && row.result.score != null" class="mono">
              {{ Number(row.result.score).toFixed(3) }}
            </span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="结论" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.result?.reason">{{ row.result.reason }}</span>
            <span v-else-if="row.errorMessage" class="muted">{{ row.errorMessage }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="开始时间" width="170">
          <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="结束时间" width="170">
          <template #default="{ row }">{{ formatTime(row.finishedAt) }}</template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有评测任务" />
        </template>
      </el-table>
    </el-card>

    <el-card class="page__card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>输出与观测元数据</span>
          <span class="muted">Mock Runner 的输出固定带 mock=true，不会被当作真实 Agent 结果</span>
        </div>
      </template>
      <div class="json-grid">
        <div>
          <div class="muted">output</div>
          <pre class="json-block">{{ outputText }}</pre>
        </div>
        <div>
          <div class="muted">observationMetadata</div>
          <pre class="json-block">{{ observationText }}</pre>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.job-expand {
  padding: 4px 8px;
}

.job-expand__block {
  margin-bottom: 8px;
}

/* 模型判断的提示条：与规则结论视觉上必须可区分，不能混为一谈 */
.judge-banner {
  margin-bottom: 8px;
  padding: 6px 10px;
  border-left: 3px solid var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.judge-facts {
  margin-bottom: 8px;
}

.judge-quotes {
  margin: 4px 0 8px;
  padding-left: 20px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.judge-quotes li {
  margin-bottom: 2px;
}

.source-tag {
  margin-left: 4px;
}

.json-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

@media (max-width: 960px) {
  .json-grid {
    grid-template-columns: 1fr;
  }
}
</style>
