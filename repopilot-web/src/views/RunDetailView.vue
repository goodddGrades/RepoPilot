<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Refresh } from '@element-plus/icons-vue'

import * as experimentApi from '@/api/experiments'
import type { AttemptStatus, JobStatus, RunDetail, RunStatus } from '@/types/domain'
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

    <el-card class="page__card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>评测任务</span>
          <span class="muted">按批次分组；本阶段只生成 PENDING，不写入任何评分</span>
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

      <el-table :data="jobs" size="small" style="width: 100%; margin-top: 12px">
        <el-table-column prop="executionOrder" label="顺序" width="70" />
        <el-table-column prop="evaluatorKey" label="算子" min-width="200" />
        <el-table-column prop="evaluatorVersion" label="算子版本" width="100" />
        <el-table-column label="必需" width="80">
          <template #default="{ row }">{{ row.required ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">{{ JOB_STATUS_TEXT[row.status as JobStatus] }}</template>
        </el-table-column>
        <el-table-column prop="attemptCount" label="尝试" width="80" />
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
