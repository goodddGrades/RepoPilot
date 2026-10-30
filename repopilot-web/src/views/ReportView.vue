<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'

import { getExperimentReport } from '@/api/reports'
import { toastError } from '@/utils/feedback'
import type {
  AnnotationView,
  Average,
  BatchScope,
  CostBreakdown,
  ExperimentReport,
  GateResult,
  Money,
  PairOutcome,
  Percentiles,
  Ratio,
  VerdictDistribution,
} from '@/types/report'
import {
  GATE_VERDICT_LABELS,
  GATE_VERDICT_TAGS,
  PAIR_OUTCOME_LABELS,
  PAIR_OUTCOME_TAGS,
} from '@/types/report'

/**
 * 实验报告（TASK-010）。
 *
 * 这一页最容易犯的错是"把缺失显示成 0"：
 * 一个 FAIL_RATE 0% 既可能是"全测了且全失败"，也可能是"一个都没测成"。
 * 后者显示成 0% 会让读报告的人做出完全相反的判断，所以本页的硬规则是：
 *
 * 1. **任何比率都带分母**。展示成 `1/2 = 50%`，分母为 0 时显示"—"并注明"无有效样本"。
 * 2. **null 不补 0**。平均分、p50/p95、成本金额为 null 时显示"—"。
 * 3. **不同币种不相加**。成本按币种分行，没有"总额"这一列。
 * 4. **技术失败单列**。它既不是通过也不是未通过，混进分母会让人去查错方向。
 * 5. **门禁不复用实验技术状态**。页面上把两者并排展示，让人自己看清
 *    "平台把该跑的跑完了" 与 "质量达标" 是两件事。
 */

const route = useRoute()

const loading = ref(false)
const errorText = ref('')
const report = ref<ExperimentReport | null>(null)

const query = reactive({
  batchScope: 'INITIAL' as BatchScope,
  annotationView: 'AUTOMATIC' as AnnotationView,
  baselineTargetKey: '',
  candidateTargetKey: '',
  /** 配对槽位的筛选与分页在前端做：报告本身是一次性快照，不需要服务端分页。 */
  outcomeFilter: '' as PairOutcome | '',
  page: 1,
  pageSize: 20,
})

const experimentId = computed(() => String(route.params.id ?? ''))

async function load(): Promise<void> {
  if (!experimentId.value) {
    errorText.value = '缺少实验 ID'
    return
  }
  loading.value = true
  errorText.value = ''
  try {
    report.value = await getExperimentReport(experimentId.value, {
      batchScope: query.batchScope,
      annotationView: query.annotationView,
      baselineTargetKey: query.baselineTargetKey || undefined,
      candidateTargetKey: query.candidateTargetKey || undefined,
    })
    // Target 选项以服务端返回为准：前端不猜有哪些 Target
    if (!query.candidateTargetKey && report.value.targets.length >= 2) {
      query.candidateTargetKey = report.value.targets[1].targetKey
    }
  } catch (error) {
    report.value = null
    errorText.value = error instanceof Error ? error.message : '加载报告失败'
    toastError(error, '加载报告失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

watch(
  () => [query.batchScope, query.annotationView, query.baselineTargetKey, query.candidateTargetKey],
  () => {
    query.page = 1
    void load()
  },
)

// ------------------------------------------------------------------ 比率与数值

/**
 * 比率的展示文本。**分母为 0 绝不能显示 0%**——
 * 「没有有效样本」和「有效样本全失败」是两个完全不同的结论。
 */
function ratioText(ratio?: Ratio | null): string {
  if (!ratio) {
    return '—'
  }
  if (ratio.denominator === 0) {
    return '—（无有效样本）'
  }
  const percent = ratio.rate === null ? '—' : `${(ratio.rate * 100).toFixed(1)}%`
  return `${ratio.numerator}/${ratio.denominator} = ${percent}`
}

function ratioSub(ratio?: Ratio | null): string {
  if (!ratio) {
    return ''
  }
  const parts: string[] = []
  if (ratio.excluded > 0) {
    parts.push(`未计入分母 ${ratio.excluded}`)
  }
  if (ratio.missing > 0) {
    parts.push(`缺失 ${ratio.missing}`)
  }
  return parts.join(' · ')
}

function averageText(average?: Average | null): string {
  if (!average || average.average === null) {
    return '—'
  }
  return average.average.toFixed(3)
}

function percentileText(percentiles?: Percentiles | null): string {
  if (!percentiles) {
    return '—'
  }
  const p50 = percentiles.p50 === null ? '—' : `${percentiles.p50} ms`
  const p95 = percentiles.p95 === null ? '—' : `${percentiles.p95} ms`
  return `p50 ${p50} · p95 ${p95}`
}

function moneyText(money?: Money | null): string {
  if (!money || money.amount === null || !money.currency) {
    return '—（未知）'
  }
  return `${money.amount} ${money.currency}`
}

/**
 * 成本按币种分行展示。
 * 刻意不提供"合计"：USD 与 CNY 相加得到的数字没有意义。
 */
function costRows(breakdown?: CostBreakdown | null): { label: string; value: string; muted?: string }[] {
  if (!breakdown) {
    return [{ label: '—', value: '—' }]
  }
  const rows = (breakdown.byCurrency ?? []).map((money) => ({
    label: money.currency ?? '未知币种',
    value: moneyText(money),
    muted:
      money.unknownCount > 0
        ? `其中 ${money.knownCount} 笔有金额、${money.unknownCount} 笔未上报`
        : `${money.knownCount} 笔`,
  }))
  if (breakdown.unknownCostCount > 0) {
    rows.push({
      label: '未知成本',
      value: '—',
      muted: `${breakdown.unknownCostCount} 笔没有上报金额`,
    })
  }
  if (rows.length === 0) {
    return [{ label: '无样本', value: '—', muted: `样本数 ${breakdown.sampleCount}` }]
  }
  return rows
}

// ------------------------------------------------------------------ 配对槽位

const filteredSlots = computed(() => {
  const comparison = report.value?.comparison
  if (!comparison) {
    return []
  }
  if (!query.outcomeFilter) {
    return comparison.slots
  }
  return comparison.slots.filter((slot) => slot.outcome === query.outcomeFilter)
})

const slotTotal = computed(() => filteredSlots.value.length)
const slotPages = computed(() => Math.max(1, Math.ceil(slotTotal.value / query.pageSize)))
const currentPage = computed(() => Math.min(query.page, slotPages.value))
const pagedSlots = computed(() => {
  const start = (currentPage.value - 1) * query.pageSize
  return filteredSlots.value.slice(start, start + query.pageSize)
})

watch(slotTotal, (total) => {
  if (query.page > Math.max(1, Math.ceil(total / query.pageSize))) {
    query.page = Math.max(1, Math.ceil(total / query.pageSize))
  }
})

function filterByOutcome(outcome: PairOutcome | ''): void {
  query.outcomeFilter = outcome
  query.page = 1
}

function verdictTag(verdict?: string | null): 'success' | 'danger' | 'warning' | 'info' {
  switch (verdict) {
    case 'PASS':
      return 'success'
    case 'FAIL':
      return 'danger'
    case 'INDETERMINATE':
      return 'warning'
    case 'NOT_APPLICABLE':
      return 'info'
    default:
      return 'info'
  }
}

function verdictText(verdict?: string | null): string {
  if (!verdict) {
    return '无结论'
  }
  switch (verdict) {
    case 'INDETERMINATE':
      return '不可判定'
    case 'NOT_APPLICABLE':
      return '不适用'
    default:
      return verdict
  }
}

function scoreText(score?: number | null): string {
  return score === null || score === undefined ? '—' : score.toFixed(2)
}

function deltaText(delta?: number | null): string {
  if (delta === null || delta === undefined) {
    return '—'
  }
  const sign = delta > 0 ? '+' : ''
  return `${sign}${delta.toFixed(2)}`
}

function shortId(value?: string | null): string {
  return value ? `${value.slice(0, 10)}…` : '—'
}

const gateVerdictLabel = (gate?: GateResult | null): string =>
  gate ? GATE_VERDICT_LABELS[gate.verdict] : '—'

const gateVerdictTag = (gate?: GateResult | null): 'success' | 'danger' | 'warning' =>
  gate ? GATE_VERDICT_TAGS[gate.verdict] : 'warning'

const verdictRows = (distribution?: VerdictDistribution | null) => {
  if (!distribution) {
    return []
  }
  return [
    { label: 'PASS', value: distribution.pass, type: 'success' as const },
    { label: 'FAIL', value: distribution.fail, type: 'danger' as const },
    { label: 'INDETERMINATE', value: distribution.indeterminate, type: 'warning' as const },
    { label: 'NOT_APPLICABLE', value: distribution.notApplicable, type: 'info' as const },
    { label: '技术失败', value: distribution.technicalFailure, type: 'warning' as const },
    { label: '缺失', value: distribution.missing, type: 'info' as const },
  ]
}

/** 每个算子的结论分布条：宽度按最大类别归一化，避免 0 值也画出可见色块。 */
function barWidth(value: number, distribution?: VerdictDistribution | null): string {
  if (!distribution) {
    return '0%'
  }
  const max = Math.max(
    distribution.pass,
    distribution.fail,
    distribution.indeterminate,
    distribution.notApplicable,
    distribution.technicalFailure,
    distribution.missing,
    1,
  )
  return `${(value / max) * 100}%`
}
</script>

<template>
  <div class="page">
    <header class="page__header">
      <div>
        <h1 class="page__title">实验报告</h1>
        <p class="page__subtitle">
          同数据集下不同 Agent 版本的槽位级指标、配对回归与门禁结论。
          每个比率都带分母，缺失与技术失败单列——它们不等于「未通过」。
        </p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </header>

    <el-alert v-if="errorText" type="error" :closable="false" show-icon :title="errorText" />

    <section class="page__card">
      <el-card v-loading="loading" shadow="never">
        <template #header>
          <div class="page__toolbar">
            <span>报告筛选</span>
            <div class="page__toolbar-group">
              <el-radio-group v-model="query.batchScope" size="small">
                <el-radio-button value="INITIAL">初始批次</el-radio-button>
                <el-radio-button value="LATEST">最新批次</el-radio-button>
              </el-radio-group>
              <el-radio-group v-model="query.annotationView" size="small">
                <el-radio-button value="AUTOMATIC">自动结论</el-radio-button>
                <el-radio-button value="REVIEWED">含人工复核</el-radio-button>
              </el-radio-group>
              <el-select
                v-model="query.baselineTargetKey"
                placeholder="基线 Target"
                clearable
                style="width: 180px"
              >
                <el-option
                  v-for="target in report?.targets ?? []"
                  :key="target.targetKey"
                  :label="target.targetKey"
                  :value="target.targetKey"
                />
              </el-select>
              <el-select
                v-model="query.candidateTargetKey"
                placeholder="候选 Target"
                clearable
                style="width: 180px"
              >
                <el-option
                  v-for="target in report?.targets ?? []"
                  :key="target.targetKey"
                  :label="target.targetKey"
                  :value="target.targetKey"
                />
              </el-select>
            </div>
          </div>
        </template>

        <el-empty v-if="!report && !loading" description="暂无报告数据" :image-size="72" />

        <template v-else-if="report">
          <!-- 批次上下文：原样展示，不在前端推断 -->
          <el-descriptions :column="4" size="small" border>
            <el-descriptions-item label="实验">{{ report.header.experimentName ?? report.header.experimentId }}</el-descriptions-item>
            <el-descriptions-item label="实验状态">{{ report.header.experimentStatus }}</el-descriptions-item>
            <el-descriptions-item label="批次">
              第 {{ report.header.batchNo }} 批（{{ report.header.batchScope }}）·{{ report.header.batchStatus }}
            </el-descriptions-item>
            <el-descriptions-item label="触发方式">{{ report.header.triggerType }}</el-descriptions-item>
            <el-descriptions-item label="方案版本">
              v{{ report.header.planVersion ?? '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="应有 Run 数">{{ report.header.expectedRunCount }}</el-descriptions-item>
            <el-descriptions-item label="门禁规则">
              <span v-if="report.header.gateConfigured">已配置 v{{ report.header.gateRuleVersion ?? '—' }}</span>
              <span v-else class="muted">未配置 gateRules</span>
            </el-descriptions-item>
            <el-descriptions-item label="结论视图">
              {{ report.header.annotationView === 'REVIEWED' ? '自动结论 + 人工复核' : '仅自动结论' }}
            </el-descriptions-item>
          </el-descriptions>

          <el-alert
            v-if="report.header.annotationView === 'REVIEWED'"
            type="info"
            :closable="false"
            show-icon
            title="当前为「含人工复核」视图"
            description="人工结论参与统计，但不会改写自动评测结果；切回「自动结论」可以看到机器当初的判断。"
            style="margin-top: 12px"
          />
        </template>
      </el-card>
    </section>

    <!-- 门禁 -->
    <section v-if="report" class="page__card">
      <el-card shadow="never">
        <template #header>
          <div class="page__toolbar">
            <span>质量门禁</span>
            <el-tag :type="gateVerdictTag(report.gate)" size="large">
              {{ gateVerdictLabel(report.gate) }}
            </el-tag>
          </div>
        </template>

        <el-empty
          v-if="!report.gate"
          description="未选择候选 Target，不产出比较，因此没有门禁结论"
          :image-size="60"
        />
        <template v-else>
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="门禁结论与实验技术状态无关"
            :description="`实验状态为 ${report.header.experimentStatus}，只说明平台把该跑的跑完了；质量是否达标由下面的门禁规则判定。`"
            style="margin-bottom: 12px"
          />
          <p v-if="report.gate.ruleSummary" class="muted">规则：{{ report.gate.ruleSummary }}</p>
          <p class="muted">
            有效配对覆盖率 {{ report.gate.validPairCoverage === null ? '—' : `${(report.gate.validPairCoverage * 100).toFixed(1)}%` }}
            （{{ report.gate.pairedSlots }}/{{ report.gate.totalSlots }} 个槽位）
          </p>

          <div class="finding-grid">
            <div class="finding">
              <h3 class="finding__title finding__title--danger">明确违规 {{ report.gate.violations.length }}</h3>
              <el-empty v-if="!report.gate.violations.length" description="无" :image-size="48" />
              <ul v-else class="finding__list">
                <li v-for="(finding, index) in report.gate.violations" :key="`v-${index}`">
                  <strong>{{ finding.rule }}</strong>
                  <span class="muted"> · {{ finding.severity }}</span>
                  <p>{{ finding.message }}</p>
                  <p v-if="finding.offendingRunIds.length" class="run-links">
                    <router-link
                      v-for="runId in finding.offendingRunIds"
                      :key="runId"
                      class="mono run-link"
                      :to="`/runs/${runId}`"
                    >{{ shortId(runId) }}</router-link>
                  </p>
                </li>
              </ul>
            </div>
            <div class="finding">
              <h3 class="finding__title finding__title--warning">证据不足 {{ report.gate.insufficientEvidence.length }}</h3>
              <el-empty v-if="!report.gate.insufficientEvidence.length" description="无" :image-size="48" />
              <ul v-else class="finding__list">
                <li v-for="(finding, index) in report.gate.insufficientEvidence" :key="`i-${index}`">
                  <strong>{{ finding.rule }}</strong>
                  <p>{{ finding.message }}</p>
                </li>
              </ul>
            </div>
            <div class="finding">
              <h3 class="finding__title finding__title--success">已满足 {{ report.gate.passed.length }}</h3>
              <el-empty v-if="!report.gate.passed.length" description="无" :image-size="48" />
              <ul v-else class="finding__list">
                <li v-for="(finding, index) in report.gate.passed" :key="`p-${index}`">
                  <strong>{{ finding.rule }}</strong>
                  <p>{{ finding.message }}</p>
                </li>
              </ul>
            </div>
          </div>
        </template>
      </el-card>
    </section>

    <!-- 配对比较 -->
    <section v-if="report" class="page__card">
      <el-card shadow="never">
        <template #header>
          <div class="page__toolbar">
            <span>版本回归对比</span>
            <div class="page__toolbar-group">
              <el-tag v-if="report.comparison" size="small" :type="report.comparison.controlledComparison ? 'success' : 'warning'">
                {{ report.comparison.controlledComparison ? '受控对比' : '描述性对比' }}
              </el-tag>
              <span v-if="report.comparison" class="muted">
                {{ report.comparison.baselineTargetKey }} → {{ report.comparison.candidateTargetKey }}
              </span>
            </div>
          </div>
        </template>

        <el-empty
          v-if="!report.comparison"
          description="未选择候选 Target。请在上方选择要对比的 Target 后查看配对回归。"
          :image-size="72"
        />
        <template v-else>
          <el-alert
            v-if="!report.comparison.controlledComparison"
            type="warning"
            :closable="false"
            show-icon
            title="这不是受控对比"
            :description="report.comparison.incomparabilityReason ?? '至少一侧的 Agent 版本未知，结论只能作为描述性参考，不能据此宣称因果优劣。'"
            style="margin-bottom: 12px"
          />

          <div class="metric-row">
            <div class="metric">
              <span class="metric__label">回归（PASS → FAIL）</span>
              <span class="metric__value metric__value--danger">{{ report.comparison.regressions }}</span>
            </div>
            <div class="metric">
              <span class="metric__label">改善（FAIL → PASS）</span>
              <span class="metric__value metric__value--success">{{ report.comparison.improvements }}</span>
            </div>
            <div class="metric">
              <span class="metric__label">稳定</span>
              <span class="metric__value">{{ report.comparison.stableSlots }}</span>
            </div>
            <div class="metric">
              <span class="metric__label">单侧缺失</span>
              <span class="metric__value metric__value--warning">{{ report.comparison.singleSidedSlots }}</span>
            </div>
            <div class="metric">
              <span class="metric__label">技术失败</span>
              <span class="metric__value metric__value--warning">{{ report.comparison.technicalErrorSlots }}</span>
            </div>
            <div class="metric">
              <span class="metric__label">有效配对覆盖率</span>
              <span class="metric__value metric__value--small">{{ ratioText(report.comparison.validPairCoverage) }}</span>
            </div>
          </div>

          <el-descriptions :column="3" size="small" border style="margin-top: 12px">
            <el-descriptions-item label="基线 Agent 版本">
              {{ report.comparison.baselineAgentVersion ?? '未知（描述性对比）' }}
            </el-descriptions-item>
            <el-descriptions-item label="候选 Agent 版本">
              {{ report.comparison.candidateAgentVersion ?? '未知（描述性对比）' }}
            </el-descriptions-item>
            <el-descriptions-item label="槽位总数">
              {{ report.comparison.totalSlots }}（配对 {{ report.comparison.pairedSlots }}）
            </el-descriptions-item>
            <el-descriptions-item label="基线平均分">{{ scoreText(report.comparison.meanBaselineScore) }}</el-descriptions-item>
            <el-descriptions-item label="候选平均分">{{ scoreText(report.comparison.meanCandidateScore) }}</el-descriptions-item>
            <el-descriptions-item label="分数变化">{{ deltaText(report.comparison.meanScoreDelta) }}</el-descriptions-item>
          </el-descriptions>
        </template>
      </el-card>
    </section>

    <!-- 槽位明细 -->
    <section v-if="report?.comparison" class="page__card">
      <el-card shadow="never">
        <template #header>
          <div class="page__toolbar">
            <span>槽位明细（共 {{ slotTotal }} 个）</span>
            <div class="page__toolbar-group">
              <el-radio-group :model-value="query.outcomeFilter" size="small" @change="filterByOutcome($event as PairOutcome | '')">
                <el-radio-button value="">全部</el-radio-button>
                <el-radio-button value="REGRESSION">回归</el-radio-button>
                <el-radio-button value="IMPROVEMENT">改善</el-radio-button>
                <el-radio-button value="TECHNICAL_ERROR">技术失败</el-radio-button>
                <el-radio-button value="SINGLE_SIDED">单侧缺失</el-radio-button>
              </el-radio-group>
            </div>
          </div>
        </template>

        <el-empty v-if="!slotTotal" description="没有符合筛选条件的槽位" :image-size="72" />
        <template v-else>
          <el-table :data="pagedSlots" size="small">
            <el-table-column label="用例" min-width="160">
              <template #default="{ row }">
                <span class="mono">{{ row.caseKey }}</span>
                <span class="muted"> · 第 {{ row.repeatIndex }} 次</span>
              </template>
            </el-table-column>
            <el-table-column label="判定" width="170">
              <template #default="{ row }">
                <el-tag :type="PAIR_OUTCOME_TAGS[row.outcome as PairOutcome]" size="small">
                  {{ PAIR_OUTCOME_LABELS[row.outcome as PairOutcome] }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="基线" min-width="170">
              <template #default="{ row }">
                <router-link v-if="row.baselineRunId" class="mono" :to="`/runs/${row.baselineRunId}`">
                  {{ shortId(row.baselineRunId) }}
                </router-link>
                <span v-else class="muted">缺失</span>
                <el-tag v-if="row.baselineVerdict" :type="verdictTag(row.baselineVerdict)" size="small" style="margin-left: 6px">
                  {{ verdictText(row.baselineVerdict) }}
                </el-tag>
                <span class="muted"> {{ scoreText(row.baselineScore) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="候选" min-width="170">
              <template #default="{ row }">
                <router-link v-if="row.candidateRunId" class="mono" :to="`/runs/${row.candidateRunId}`">
                  {{ shortId(row.candidateRunId) }}
                </router-link>
                <span v-else class="muted">缺失</span>
                <el-tag v-if="row.candidateVerdict" :type="verdictTag(row.candidateVerdict)" size="small" style="margin-left: 6px">
                  {{ verdictText(row.candidateVerdict) }}
                </el-tag>
                <span class="muted"> {{ scoreText(row.candidateScore) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="分数变化" width="110">
              <template #default="{ row }">{{ deltaText(row.scoreDelta) }}</template>
            </el-table-column>
            <el-table-column label="判定依据" min-width="240">
              <template #default="{ row }">
                <span class="muted">{{ row.note }}</span>
              </template>
            </el-table-column>
          </el-table>

          <div class="page__pagination">
            <el-pagination
              v-model:current-page="query.page"
              :page-size="query.pageSize"
              :total="slotTotal"
              layout="total, prev, pager, next"
              small
            />
          </div>
        </template>
      </el-card>
    </section>

    <!-- 各 Target 的槽位级指标 -->
    <section v-if="report" class="page__card">
      <el-card shadow="never">
        <template #header><span>槽位级指标</span></template>

        <el-empty
          v-if="!report.targets.length"
          description="该实验还没有任何 Target"
          :image-size="72"
        />
        <el-tabs v-else type="border-card">
          <el-tab-pane v-for="target in report.targets" :key="target.targetKey" :name="target.targetKey">
            <template #label>
              <span>{{ target.targetKey }}</span>
              <el-tag v-if="!target.agentVersionKnown" type="warning" size="small" style="margin-left: 6px">
                版本未知
              </el-tag>
            </template>

            <el-alert
              v-if="!target.agentVersionKnown"
              type="warning"
              :closable="false"
              show-icon
              title="Agent 版本未知"
              description="该 Target 的 Agent 版本无法解析，报告只做描述性统计，不构成受控对比。"
              style="margin-bottom: 12px"
            />

            <el-descriptions :column="3" size="small" border>
              <el-descriptions-item label="Agent 版本">
                {{ target.agentVersionLabel ?? '未知' }}
              </el-descriptions-item>
              <el-descriptions-item label="应有槽位">{{ target.expectedSlotCount }}</el-descriptions-item>
              <el-descriptions-item label="已观察槽位">{{ target.observedSlotCount }}</el-descriptions-item>
              <el-descriptions-item label="槽位覆盖率">
                {{ ratioText(target.slotCoverage) }}
                <span v-if="ratioSub(target.slotCoverage)" class="muted">（{{ ratioSub(target.slotCoverage) }}）</span>
              </el-descriptions-item>
              <el-descriptions-item label="技术完成率">
                {{ ratioText(target.technicalCompletion) }}
                <span v-if="ratioSub(target.technicalCompletion)" class="muted">（{{ ratioSub(target.technicalCompletion) }}）</span>
              </el-descriptions-item>
              <el-descriptions-item label="任务成功率">
                {{ ratioText(target.taskSuccessRate) }}
                <span v-if="ratioSub(target.taskSuccessRate)" class="muted">（{{ ratioSub(target.taskSuccessRate) }}）</span>
              </el-descriptions-item>
              <el-descriptions-item label="运行状态分布">
                完成 {{ target.completedCount }} · 失败 {{ target.failedCount }} · 超时 {{ target.timedOutCount }} ·
                取消 {{ target.cancelledCount }} · 缺失 {{ target.missingCount }}
              </el-descriptions-item>
              <el-descriptions-item label="任务结果分布">
                SUCCESS {{ target.taskSuccessCount }} · FAILURE {{ target.taskFailureCount }} ·
                未知 {{ target.taskUnknownCount }} · 不适用 {{ target.taskNotApplicableCount }}
              </el-descriptions-item>
              <el-descriptions-item label="平均分">
                {{ averageText(target.score) }}
                <span class="muted">（有效样本 {{ target.score.validSampleCount }}，无分 {{ target.score.missingCount }}）</span>
              </el-descriptions-item>
              <el-descriptions-item label="耗时">{{ percentileText(target.durationMs) }}</el-descriptions-item>
              <el-descriptions-item label="最终观察成本">
                <div v-for="row in costRows(target.finalObservationCost)" :key="`f-${row.label}`" class="cost-row">
                  <span class="cost-row__label">{{ row.label }}</span>
                  <span>{{ row.value }}</span>
                  <span v-if="row.muted" class="muted">{{ row.muted }}</span>
                </div>
              </el-descriptions-item>
              <el-descriptions-item label="全部尝试成本">
                <div v-for="row in costRows(target.allAttemptsCost)" :key="`a-${row.label}`" class="cost-row">
                  <span class="cost-row__label">{{ row.label }}</span>
                  <span>{{ row.value }}</span>
                  <span v-if="row.muted" class="muted">{{ row.muted }}</span>
                </div>
              </el-descriptions-item>
            </el-descriptions>

            <h3 class="section-title">算子结论分布</h3>
            <el-empty
              v-if="!Object.keys(target.verdicts ?? {}).length"
              description="该 Target 还没有任何算子结论"
              :image-size="60"
            />
            <div v-for="(distribution, operatorKey) in target.verdicts" :key="operatorKey" class="operator">
              <div class="operator__head">
                <span class="mono">{{ operatorKey }}</span>
                <span class="muted">通过率 {{ ratioText(distribution.passRate) }}</span>
                <span v-if="ratioSub(distribution.passRate)" class="muted">
                  （{{ ratioSub(distribution.passRate) }}）
                </span>
              </div>
              <div class="operator__bars">
                <div v-for="row in verdictRows(distribution)" :key="row.label" class="bar">
                  <span class="bar__label">{{ row.label }}</span>
                  <div class="bar__track">
                    <div
                      class="bar__fill"
                      :class="`bar__fill--${row.type}`"
                      :style="{ width: barWidth(row.value, distribution) }"
                    />
                  </div>
                  <span class="bar__value">{{ row.value }}</span>
                </div>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </section>
  </div>
</template>

<style scoped>
.metric-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.metric {
  flex: 1 1 160px;
  padding: 10px 12px;
  border: 1px solid var(--rp-border);
  border-radius: 8px;
  background: #fff;
}

.metric__label {
  display: block;
  font-size: 12px;
  color: var(--rp-text-muted);
}

.metric__value {
  display: block;
  margin-top: 4px;
  font-size: 22px;
  font-weight: 600;
}

.metric__value--small {
  font-size: 15px;
}

.metric__value--danger {
  color: var(--el-color-danger);
}

.metric__value--success {
  color: var(--el-color-success);
}

.metric__value--warning {
  color: var(--el-color-warning-dark-2);
}

.finding-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 12px;
}

.finding {
  padding: 10px 12px;
  border: 1px solid var(--rp-border);
  border-radius: 8px;
  background: #fff;
}

.finding__title {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
}

.finding__title--danger {
  color: var(--el-color-danger);
}

.finding__title--warning {
  color: var(--el-color-warning-dark-2);
}

.finding__title--success {
  color: var(--el-color-success);
}

.finding__list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
}

.finding__list li {
  margin-bottom: 8px;
}

.finding__list p {
  margin: 2px 0 0;
}

.run-links {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.run-link {
  padding: 1px 4px;
  border: 1px solid var(--rp-border);
  border-radius: 4px;
  background: #f8fafc;
}

.run-link:hover {
  border-color: var(--el-color-primary);
}

.section-title {
  margin: 18px 0 10px;
  font-size: 14px;
  font-weight: 600;
}

.operator {
  padding: 10px 0;
  border-top: 1px solid var(--rp-border);
}

.operator__head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.operator__bars {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 6px 20px;
}

.bar {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.bar__label {
  width: 132px;
  color: var(--rp-text-muted);
}

.bar__track {
  flex: 1;
  height: 8px;
  border-radius: 4px;
  background: #eef2f7;
}

.bar__fill {
  height: 100%;
  border-radius: 4px;
}

.bar__fill--success {
  background: var(--el-color-success);
}

.bar__fill--danger {
  background: var(--el-color-danger);
}

.bar__fill--warning {
  background: var(--el-color-warning);
}

.bar__fill--info {
  background: var(--rp-text-muted);
}

.bar__value {
  width: 36px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.cost-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 2px 0;
}

.cost-row__label {
  min-width: 72px;
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, monospace;
  font-size: 12px;
}
</style>
