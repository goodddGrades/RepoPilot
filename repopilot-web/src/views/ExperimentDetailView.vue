<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Refresh, Upload, VideoPlay } from '@element-plus/icons-vue'

import * as experimentApi from '@/api/experiments'
import * as evaluationApi from '@/api/evaluation'
import type {
  EvaluationBatchStatus,
  EvaluationBatchView,
  ExperimentStatus,
  ExperimentView,
  RunStatus,
  RunView,
} from '@/types/domain'
import { formatTime, toastError, toastSuccess } from '@/utils/feedback'

const route = useRoute()
const router = useRouter()
const experimentId = computed(() => String(route.params.id ?? ''))

const experiment = ref<ExperimentView | null>(null)
const loading = ref(false)
const submitting = ref(false)

const statusText = experimentApi.EXPERIMENT_STATUS_TEXT
const statusTag = experimentApi.EXPERIMENT_STATUS_TAG
const runStatusText = experimentApi.RUN_STATUS_TEXT
const runStatusTag = experimentApi.RUN_STATUS_TAG

const runs = ref<RunView[]>([])
const runTotal = ref(0)
const runLoading = ref(false)
const runQuery = reactive({ page: 1, pageSize: 20, status: '', caseKey: '' })

const runStatusOptions = Object.entries(runStatusText).map(([value, label]) => ({ value, label }))

async function loadExperiment(): Promise<void> {
  if (!experimentId.value) {
    return
  }
  loading.value = true
  try {
    experiment.value = await experimentApi.getExperiment(experimentId.value)
  } catch (error) {
    toastError(error, '加载实验失败')
  } finally {
    loading.value = false
  }
}

async function loadRuns(): Promise<void> {
  if (!experimentId.value) {
    return
  }
  runLoading.value = true
  try {
    const page = await experimentApi.listRuns(experimentId.value, {
      page: runQuery.page,
      pageSize: runQuery.pageSize,
      status: runQuery.status || undefined,
      caseKey: runQuery.caseKey || undefined,
    })
    runs.value = page.items
    runTotal.value = page.total
  } catch (error) {
    toastError(error, '加载 Run 列表失败')
  } finally {
    runLoading.value = false
  }
}

async function reload(): Promise<void> {
  await Promise.all([loadExperiment(), loadRuns(), loadBatches()])
}

onMounted(reload)

function searchRuns(): void {
  runQuery.page = 1
  void loadRuns()
}

function resetRuns(): void {
  runQuery.status = ''
  runQuery.caseKey = ''
  runQuery.page = 1
  void loadRuns()
}

async function start(): Promise<void> {
  if (!experiment.value) {
    return
  }
  submitting.value = true
  try {
    await experimentApi.startExperiment(experiment.value.id)
    toastSuccess('已提交：Run 槽位生成完毕，等待 Worker 领取')
    await reload()
  } catch (error) {
    toastError(error, '提交失败')
  } finally {
    submitting.value = false
  }
}

async function cancel(): Promise<void> {
  if (!experiment.value) {
    return
  }
  submitting.value = true
  try {
    await experimentApi.cancelExperiment(experiment.value.id, '前端取消')
    toastSuccess('实验已取消')
    await reload()
  } catch (error) {
    toastError(error, '取消失败')
  } finally {
    submitting.value = false
  }
}

/** 导入页带上实验 id：省掉用户在下拉里再找一遍。 */
function goImport(): void {
  void router.push({ path: '/imports', query: { experimentId: experimentId.value } })
}

function openRun(row: RunView): void {
  void router.push(`/runs/${row.id}`)
}

const canStart = computed(() => experiment.value?.status === 'DRAFT')
const canCancel = computed(
  () =>
    experiment.value?.status === 'QUEUED' ||
    experiment.value?.status === 'RUNNING' ||
    experiment.value?.status === 'DRAFT',
)

/** 状态分布：让"还有几个在等 Worker / 等评测"一眼可见，不用逐页翻。 */
const statusCounts = computed(() => {
  const counts: Record<string, number> = {}
  runs.value.forEach((run) => {
    counts[run.status] = (counts[run.status] ?? 0) + 1
  })
  return counts
})

/* ---------------- 评测批次 ---------------- */

const batches = ref<EvaluationBatchView[]>([])
const batchLoading = ref(false)

/** INITIAL 批次随实验启动创建、随实验取消；页面只允许取消重评批次。 */
const BATCH_STATUS_TEXT: Record<EvaluationBatchStatus, string> = {
  PENDING: '等待执行',
  RUNNING: '评测中',
  COMPLETED: '已完成',
  PARTIAL_FAILED: '部分失败',
  FAILED: '失败',
  CANCELLED: '已取消',
}

const BATCH_STATUS_TAG: Record<
  EvaluationBatchStatus,
  'info' | 'primary' | 'success' | 'warning' | 'danger'
> = {
  PENDING: 'info',
  RUNNING: 'primary',
  COMPLETED: 'success',
  PARTIAL_FAILED: 'warning',
  FAILED: 'danger',
  CANCELLED: 'info',
}

const TRIGGER_TEXT: Record<string, string> = {
  INITIAL: '初始',
  REEVALUATION: '重评',
  MANUAL: '手动',
}

async function loadBatches(): Promise<void> {
  if (!experimentId.value) {
    return
  }
  batchLoading.value = true
  try {
    const page = await evaluationApi.listEvaluationBatches(experimentId.value, {
      page: 1,
      pageSize: 50,
    })
    batches.value = page.items
  } catch (error) {
    toastError(error, '加载评测批次失败')
  } finally {
    batchLoading.value = false
  }
}

/** 新建重评批次对话框。 */
const reevalVisible = ref(false)
const reevalSubmitting = ref(false)
const reevalForm = reactive({
  planVersionId: '',
  scope: 'ALL' as 'ALL' | 'SELECTED',
  runIds: [] as string[],
})

const canCreateBatch = computed(() =>
  ['QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED'].includes(
    experiment.value?.status ?? '',
  ),
)

function openReevalDialog(): void {
  reevalForm.planVersionId = experiment.value?.evaluationPlanVersionId ?? ''
  reevalForm.scope = 'ALL'
  reevalForm.runIds = []
  reevalVisible.value = true
}

/** 可勾选的是当前页里已封口的 Run——跨页选择做不到就明说，不假装支持。 */
const selectableRuns = computed(() =>
  runs.value.filter((run) => run.status === 'EVALUATING' || run.status === 'COMPLETED'),
)

async function createReevalBatch(): Promise<void> {
  reevalSubmitting.value = true
  try {
    const batch = await evaluationApi.createEvaluationBatch(experimentId.value, {
      planVersionId: reevalForm.planVersionId,
      triggerType: 'REEVALUATION',
      runIds: reevalForm.scope === 'SELECTED' ? reevalForm.runIds : undefined,
      filter: reevalForm.scope === 'ALL' ? {} : undefined,
    })
    reevalVisible.value = false
    toastSuccess(`重评批次已创建（${batch.runCount} 个 Run），等待评测 Worker 领取`)
    await loadBatches()
  } catch (error) {
    toastError(error, '创建重评批次失败')
  } finally {
    reevalSubmitting.value = false
  }
}

async function cancelBatch(batch: EvaluationBatchView): Promise<void> {
  try {
    await evaluationApi.cancelEvaluationBatch(batch.id, '前端取消')
    toastSuccess('批次已取消，未终态任务不再执行')
    await loadBatches()
  } catch (error) {
    toastError(error, '取消批次失败')
  }
}

function batchCancelable(batch: EvaluationBatchView): boolean {
  return (
    batch.triggerType !== 'INITIAL' &&
    (batch.status === 'PENDING' || batch.status === 'RUNNING')
  )
}
</script>

<template>
  <div class="page" v-loading="loading">
    <header class="page__header">
      <div>
        <el-button link :icon="ArrowLeft" @click="router.push('/experiments')">返回实验列表</el-button>
        <h1 class="page__title" style="margin-top: 8px">{{ experiment?.name ?? '实验详情' }}</h1>
        <p class="page__subtitle">
          <span class="mono">{{ experimentId }}</span>
          <span v-if="experiment"> · {{ experiment.mode === 'ONLINE' ? '在线' : '离线' }}</span>
          <span v-if="experiment && experiment.mode === 'ONLINE'">
            · 计划 {{ experiment.expectedRunCount }} 个 Run
          </span>
          <span v-if="experiment && experiment.mode === 'OFFLINE'">
            · 已导入 {{ experiment.importedRunCount }} / 计划 {{ experiment.expectedRunCount }} 个 Run
            <template v-if="experiment.missingRunCount > 0">
              （缺 {{ experiment.missingRunCount }} 个槽位）
            </template>
          </span>
        </p>
      </div>
      <div class="page__toolbar-group">
        <el-tag v-if="experiment" :type="statusTag[experiment.status as ExperimentStatus]" effect="dark">
          {{ statusText[experiment.status as ExperimentStatus] }}
        </el-tag>
        <el-button :icon="Refresh" @click="reload">刷新</el-button>
        <el-button
          v-if="canStart"
          type="primary"
          :icon="VideoPlay"
          :loading="submitting"
          @click="start"
        >
          提交
        </el-button>
        <el-button v-if="canCancel" :loading="submitting" @click="cancel">取消实验</el-button>
      </div>
    </header>

    <el-card v-if="experiment" class="page__card" shadow="never">
      <el-descriptions :column="3" border>
        <el-descriptions-item label="数据集版本">
          <span class="mono">{{ experiment.datasetVersionId }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="方案版本">
          <span class="mono">{{ experiment.evaluationPlanVersionId }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">
          {{ formatTime(experiment.createdAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="开始时间">
          {{ formatTime(experiment.startedAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="结束时间">
          {{ formatTime(experiment.finishedAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="Target">
          <span v-for="target in experiment.targets" :key="target.id" class="target-chip">
            {{ target.targetKey }} × {{ target.repeatCount }}
          </span>
        </el-descriptions-item>
      </el-descriptions>
      <el-alert
        v-if="experiment.status === 'DRAFT' && experiment.mode === 'ONLINE'"
        style="margin-top: 12px"
        type="info"
        show-icon
        :closable="false"
        title="草稿阶段还没有 Run。点「提交」会校验版本状态、Runner 能力与数量上限，然后一次性生成槽位。"
      />
      <el-alert
        v-if="experiment.status === 'DRAFT' && experiment.mode === 'OFFLINE'"
        style="margin-top: 12px"
        type="info"
        show-icon
        :closable="false"
        title="离线实验的 Run 来自导入的历史轨迹，不会由平台生成。缺失的槽位只记录为缺失，不补造标准答案。"
      >
        <template #default>
          <div style="margin-top: 6px">
            <el-button type="primary" link :icon="Upload" @click="goImport">去导入轨迹</el-button>
            <span class="muted" style="margin-left: 8px">
              已导入 {{ experiment.importedRunCount }} 条，还差 {{ experiment.missingRunCount }} 个槽位
            </span>
          </div>
        </template>
      </el-alert>
    </el-card>

    <el-card class="page__card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>评测批次</span>
          <span class="muted">
            初始批次随实验启动创建；重评会新增批次，历史结果原样保留
          </span>
          <el-button
            v-if="canCreateBatch"
            style="margin-left: auto"
            @click="openReevalDialog"
          >
            新建重评批次
          </el-button>
        </div>
      </template>
      <el-table v-loading="batchLoading" :data="batches" size="small" style="width: 100%">
        <el-table-column prop="batchNo" label="批次" width="80" />
        <el-table-column label="来源" width="90">
          <template #default="{ row }">{{ TRIGGER_TEXT[row.triggerType] ?? row.triggerType }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="BATCH_STATUS_TAG[row.status as EvaluationBatchStatus]" effect="light">
              {{ BATCH_STATUS_TEXT[row.status as EvaluationBatchStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="runCount" label="Run 数" width="90" />
        <el-table-column prop="expectedJobCount" label="任务总数" width="100" />
        <el-table-column label="任务进度（待/中/成/败/跳）" min-width="200">
          <template #default="{ row }">
            <span class="mono">
              {{ row.jobs.total > 0
                ? `${row.jobs.pending} / ${row.jobs.running} / ${row.jobs.completed} / ${row.jobs.failed} / ${row.jobs.skipped}`
                : '-' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="方案版本" min-width="220">
          <template #default="{ row }">
            <span class="mono">{{ row.evaluationPlanVersionId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="batchCancelable(row)"
              link
              type="danger"
              @click="cancelBatch(row)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有评测批次（实验未提交）" />
        </template>
      </el-table>
    </el-card>

    <el-dialog v-model="reevalVisible" title="新建重评批次" width="560px">
      <el-form label-width="100px">
        <el-form-item label="方案版本">
          <el-input v-model="reevalForm.planVersionId" placeholder="评测方案版本 ID" />
          <div class="muted">
            默认沿用实验创建时的方案版本；换成新版本即可对比"同一批 Run、两套算法"的结论
          </div>
        </el-form-item>
        <el-form-item label="重评范围">
          <el-radio-group v-model="reevalForm.scope">
            <el-radio value="ALL">全部可重评 Run（已封口）</el-radio>
            <el-radio value="SELECTED">从下方 Run 列表勾选</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="reevalForm.scope === 'SELECTED'" label="选择 Run">
          <el-select
            v-model="reevalForm.runIds"
            multiple
            filterable
            placeholder="只列出当前页已封口的 Run"
            style="width: 100%"
          >
            <el-option
              v-for="run in selectableRuns"
              :key="run.id"
              :label="`${run.caseKey ?? run.datasetCaseId} #${run.repeatIndex}（${runStatusText[run.status as RunStatus]}）`"
              :value="run.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reevalVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="reevalSubmitting"
          :disabled="reevalForm.scope === 'SELECTED' && reevalForm.runIds.length === 0"
          @click="createReevalBatch"
        >
          创建
        </el-button>
      </template>
    </el-dialog>

    <el-card class="page__card" shadow="never">
      <div class="page__toolbar">
        <div class="page__toolbar-group">
          <el-select v-model="runQuery.status" placeholder="全部状态" clearable style="width: 140px">
            <el-option v-for="item in runStatusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-input
            v-model="runQuery.caseKey"
            placeholder="按 caseKey 过滤"
            clearable
            style="width: 180px"
            @keyup.enter="searchRuns"
          />
          <el-button @click="searchRuns">查询</el-button>
          <el-button :icon="Refresh" @click="resetRuns">重置</el-button>
        </div>
        <div class="page__toolbar-group">
          <span v-for="(count, status) in statusCounts" :key="status" class="muted">
            {{ runStatusText[status as RunStatus] }} {{ count }}
          </span>
          <span class="muted">共 {{ runTotal }} 个 Run</span>
        </div>
      </div>

      <el-table v-loading="runLoading" :data="runs" stripe style="width: 100%">
        <el-table-column label="样本 / 重复" min-width="180">
          <template #default="{ row }">
            <div class="cell-title">{{ row.caseKey ?? row.datasetCaseId }}</div>
            <div class="muted">#{{ row.repeatIndex }} · {{ row.targetKey ?? row.experimentTargetId }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="runStatusTag[row.status as RunStatus]" effect="light">
              {{ runStatusText[row.status as RunStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="100">
          <template #default="{ row }">
            <span :class="{ muted: row.taskOutcome === 'UNKNOWN' }">{{ row.taskOutcome }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="attemptCount" label="尝试" width="80" />
        <el-table-column label="开始" width="180">
          <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="结束" width="180">
          <template #default="{ row }">{{ formatTime(row.finishedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openRun(row)">执行证据</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有 Run：实验未提交，或 Worker 尚未领取" />
        </template>
      </el-table>

      <div class="page__pagination">
        <el-pagination
          v-model:current-page="runQuery.page"
          v-model:page-size="runQuery.pageSize"
          :total="runTotal"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadRuns"
          @size-change="searchRuns"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.target-chip {
  display: inline-block;
  margin-right: 8px;
  padding: 0 8px;
  border: 1px solid var(--rp-border);
  border-radius: 10px;
  font-size: 12px;
}

.cell-title {
  font-weight: 500;
}
</style>
