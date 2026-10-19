<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import * as annotationApi from '@/api/annotations'
import { ApiError } from '@/api/http'
import type {
  AnnotationRecordView,
  AnnotationTaskStatus,
  AnnotationTaskView,
  DispatchHint,
  FailureCategory,
} from '@/types/annotation'
import { FAILURE_CATEGORY_LABELS } from '@/types/annotation'
import { formatTime, stringifyJson, toastError, toastSuccess } from '@/utils/feedback'

/**
 * 人工标注工作台（TASK-009）。
 *
 * 三条设计约束直接来自后端语义，页面必须如实呈现而不是替它美化：
 *
 * 1. **人工结论不覆盖自动结论**。历史里每条记录都带 originalVerdict，
 *    页面显式展示"自动判 X → 人工判 Y"，而不是只显示后者。
 * 2. **并发靠版本号分胜负**。每个动作都带页面看到的 version；
 *    收到 409 说明别人先改了，页面提示刷新而不是重试覆盖。
 * 3. **证据缺失就是缺失**。没有证据引用时显示"未提供证据"，
 *    不显示 0 条、不编造引用。
 *
 * V1 没有真实鉴权：操作者由后端开发配置固定，页面上标明这一点，
 * 免得把"单人开发模式"误当成有审计的生产行为。
 */

const loading = ref(false)
const acting = ref(false)
const rows = ref<AnnotationTaskView[]>([])
const total = ref(0)
const query = reactive({
  page: 1,
  pageSize: 20,
  status: '' as AnnotationTaskStatus | '',
})

/** 派单建议：需要人看的 Run。 */
const candidates = ref<DispatchHint[]>([])
const candidatesLoading = ref(false)
const candidateExperimentId = ref('')

/** 右侧抽屉：当前查看/操作的任务。 */
const drawerOpen = ref(false)
const current = ref<AnnotationTaskView | null>(null)

/** 提交表单。 */
const form = reactive({
  verdict: 'FAIL' as 'PASS' | 'FAIL',
  failureCategory: '' as FailureCategory | '',
  reason: '',
  evidence: '',
})

const reviewForm = reactive({
  reason: '',
})

const statusOptions: { value: AnnotationTaskStatus; label: string }[] = [
  { value: 'PENDING', label: '待领取' },
  { value: 'CLAIMED', label: '标注中' },
  { value: 'SUBMITTED', label: '待审核' },
  { value: 'RETURNED', label: '已退回' },
  { value: 'REVIEWED', label: '已审核' },
  { value: 'CANCELLED', label: '已取消' },
]

const categoryOptions = Object.entries(FAILURE_CATEGORY_LABELS) as [FailureCategory, string][]

/** 判 FAIL 时必须给归因；UNKNOWN 是显式的"证据不足"，不是留空。 */
const categoryRequired = computed(() => form.verdict === 'FAIL')

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await annotationApi.listAnnotationTasks({
      page: query.page,
      pageSize: query.pageSize,
      status: query.status || undefined,
    })
    rows.value = page.items
    total.value = page.total
  } catch (error) {
    toastError(error, '加载标注任务失败')
  } finally {
    loading.value = false
  }
}

async function loadCandidates(): Promise<void> {
  if (!candidateExperimentId.value.trim()) {
    candidates.value = []
    return
  }
  candidatesLoading.value = true
  try {
    candidates.value = await annotationApi.listDispatchCandidates(candidateExperimentId.value.trim())
  } catch (error) {
    toastError(error, '加载派单建议失败')
  } finally {
    candidatesLoading.value = false
  }
}

onMounted(load)

watch(
  () => form.verdict,
  (verdict) => {
    // 判 PASS 不需要归因；切回来时清空，避免提交一个与判定矛盾的分类
    if (verdict === 'PASS') {
      form.failureCategory = ''
    }
  },
)

function search(): void {
  query.page = 1
  void load()
}

function reset(): void {
  query.status = ''
  query.page = 1
  void load()
}

// ------------------------------------------------------------------ 任务操作

/**
 * 统一的动作执行。
 *
 * 409 单独处理：那是"别人先改了"而不是"请求错了"，提示刷新重试，
 * 并自动重新拉取当前任务，让页面立刻显示别人的最新结论。
 */
async function act<T>(fn: () => Promise<T>, successMessage: string): Promise<void> {
  acting.value = true
  try {
    await fn()
    toastSuccess(successMessage)
    await load()
    if (current.value) {
      await openTask(current.value.id)
    }
  } catch (error) {
    if (error instanceof ApiError && error.code === 'OPTIMISTIC_LOCK_CONFLICT') {
      ElMessage.warning('该任务已被他人更新，已为你刷新最新状态，请确认后再操作')
      if (current.value) {
        await openTask(current.value.id)
      }
    } else {
      toastError(error, '操作失败')
    }
  } finally {
    acting.value = false
  }
}

async function openTask(taskId: string): Promise<void> {
  try {
    current.value = await annotationApi.getAnnotationTask(taskId)
    drawerOpen.value = true
    resetForm()
  } catch (error) {
    toastError(error, '加载标注任务详情失败')
  }
}

function resetForm(): void {
  form.verdict = 'FAIL'
  form.failureCategory = ''
  form.reason = ''
  form.evidence = ''
  reviewForm.reason = ''
}

function claimTask(task: AnnotationTaskView): void {
  void act(
    () => annotationApi.claimAnnotationTask(task.id, task.version),
    '已领取该任务',
  )
}

function submitTask(): void {
  const task = current.value
  if (!task) {
    return
  }
  if (categoryRequired.value && !form.failureCategory) {
    ElMessage.warning('判定为未通过时必须给出失败分类；证据不足请选「证据不足，不归因」')
    return
  }
  if (!form.reason.trim()) {
    ElMessage.warning('必须说明判定依据')
    return
  }
  let evidence: unknown[] | undefined
  try {
    evidence = parseEvidence(form.evidence)
  } catch (error) {
    toastError(error, '证据格式不正确')
    return
  }
  void act(
    () =>
      annotationApi.submitAnnotation(task.id, {
        expectedVersion: task.version,
        verdict: form.verdict,
        failureCategory: form.failureCategory || undefined,
        reason: form.reason.trim(),
        evidence,
      }),
    '标注已提交，等待审核',
  )
}

/**
 * 证据引用：留空表示"没有引用"，提交空数组而不是编造内容。
 * 非空必须是数组，否则后端会拒——这里先给出更具体的提示。
 */
function parseEvidence(text: string): unknown[] | undefined {
  const trimmed = text.trim()
  if (trimmed === '') {
    return []
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(trimmed)
  } catch {
    throw new Error('证据必须是合法 JSON 数组，例如 [{"type":"event","eventId":"..."}]')
  }
  if (!Array.isArray(parsed)) {
    throw new Error('证据必须是 JSON 数组')
  }
  return parsed
}

function reviewTask(approved: boolean): void {
  const task = current.value
  if (!task) {
    return
  }
  if (!approved && !reviewForm.reason.trim()) {
    ElMessage.warning('退回时必须说明原因')
    return
  }
  void act(
    () =>
      annotationApi.reviewAnnotation(task.id, {
        expectedVersion: task.version,
        approved,
        reason: reviewForm.reason.trim() || undefined,
      }),
    approved ? '审核通过' : '已退回给标注者',
  )
}

function cancelTask(task: AnnotationTaskView): void {
  const reason = window.prompt('取消原因（必填）')
  if (reason === null) {
    return
  }
  if (!reason.trim()) {
    ElMessage.warning('取消必须说明原因')
    return
  }
  void act(
    () => annotationApi.cancelAnnotation(task.id, task.version, reason.trim()),
    '任务已取消',
  )
}

function dispatchCandidate(hint: DispatchHint): void {
  void act(
    () => annotationApi.createAnnotationTask(hint.runId, hint.evaluationJobId),
    '已创建人工复核任务',
  ).then(() => loadCandidates())
}

// ------------------------------------------------------------------ 展示

const statusLabel: Record<AnnotationTaskStatus, string> = {
  PENDING: '待领取',
  CLAIMED: '标注中',
  SUBMITTED: '待审核',
  REVIEWED: '已审核',
  RETURNED: '已退回',
  CANCELLED: '已取消',
}

const statusTag: Record<AnnotationTaskStatus, 'info' | 'warning' | 'success' | 'danger'> = {
  PENDING: 'info',
  CLAIMED: 'warning',
  SUBMITTED: 'warning',
  REVIEWED: 'success',
  RETURNED: 'danger',
  CANCELLED: 'info',
}

const actionLabel: Record<string, string> = {
  CLAIM: '领取',
  SUBMIT: '提交标注',
  REVIEW: '审核通过',
  RETURN: '退回',
  CANCEL: '取消',
}

function verdictTag(verdict?: string): 'success' | 'danger' | 'warning' | 'info' {
  switch (verdict) {
    case 'PASS':
      return 'success'
    case 'FAIL':
      return 'danger'
    case 'INDETERMINATE':
      return 'warning'
    default:
      return 'info'
  }
}

function categoryLabel(category?: string): string {
  if (!category) {
    return '-'
  }
  return FAILURE_CATEGORY_LABELS[category as FailureCategory] ?? category
}

/** 自动结论：缺失就是"没有结论"，不显示成 PASS 或 0 分。 */
function autoVerdict(task: AnnotationTaskView): string {
  const latest = task.records[task.records.length - 1]
  return latest?.originalVerdict ?? ''
}

/** 人工是否改写了自动结论——这正是标注工作台存在的意义。 */
function diverged(task: AnnotationTaskView): boolean {
  const submit = [...task.records].reverse().find((r) => r.action === 'SUBMIT')
  if (!submit?.verdict || !submit.originalVerdict) {
    return false
  }
  return submit.verdict !== submit.originalVerdict
}

function latestSubmit(task: AnnotationTaskView): AnnotationRecordView | undefined {
  return [...task.records].reverse().find((r) => r.action === 'SUBMIT')
}

function shortId(value?: string): string {
  return value ? `${value.slice(0, 10)}…` : '-'
}
</script>

<template>
  <div class="page">
    <header class="page__header">
      <div>
        <h1 class="page__title">人工标注与失败归因</h1>
        <p class="page__subtitle">
          自动评测判未通过或算不出结论的 Run 会进入这里。人工结论单独存放，
          <strong>不会覆盖</strong>自动评测结果——两者都要能被追溯。
        </p>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </header>

    <el-alert type="info" :closable="false" show-icon>
      <template #title>开发模式：操作者身份由服务端配置固定</template>
      当前版本没有接入真实用户鉴权，页面上所有操作都会记为同一个后端配置的 operator。
      这不是完整的审计能力，接入鉴权后需要改为按登录用户记录。
    </el-alert>

    <!-- 派单建议 -->
    <section class="page__card">
      <el-card shadow="never">
        <template #header>
          <div class="card-header">
            <span>派单建议</span>
            <div class="page__toolbar-group">
              <el-input
                v-model="candidateExperimentId"
                placeholder="输入实验 ID，列出需要人工复核的 Run"
                clearable
                style="width: 420px"
              />
              <el-button :icon="Search" :loading="candidatesLoading" @click="loadCandidates">
                查询
              </el-button>
            </div>
          </div>
        </template>

        <el-empty
          v-if="!candidates.length"
          description="暂无建议。输入实验 ID 后，只有技术失败或自动判未通过的 Run 会出现在这里。"
          :image-size="72"
        />
        <el-table v-else :data="candidates" size="small">
          <el-table-column prop="runId" label="Run" width="150">
            <template #default="{ row }">
              <router-link class="mono" :to="`/runs/${row.runId}`">{{ shortId(row.runId) }}</router-link>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="为什么需要人看" min-width="180" />
          <el-table-column label="自动结论" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.autoVerdict" :type="verdictTag(row.autoVerdict)" size="small">
                {{ row.autoVerdict }}
              </el-tag>
              <span v-else class="muted">无结论（技术失败）</span>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="Run 创建时间" width="180">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110" align="right">
            <template #default="{ row }">
              <el-button type="primary" size="small" @click="dispatchCandidate(row)">派单</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </section>

    <!-- 任务列表 -->
    <section class="page__card">
      <el-card shadow="never">
        <template #header>
          <div class="page__toolbar">
            <span>标注任务</span>
            <div class="page__toolbar-group">
              <el-select
                v-model="query.status"
                placeholder="全部状态"
                clearable
                style="width: 140px"
                @change="search"
              >
                <el-option
                  v-for="option in statusOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
              <el-button @click="reset">重置</el-button>
            </div>
          </div>
        </template>

        <el-empty
          v-if="!rows.length && !loading"
          description="暂无标注任务。先从上面的派单建议里挑需要人看的 Run。"
          :image-size="72"
        />
        <el-table v-else v-loading="loading" :data="rows" size="small">
          <el-table-column label="Run" width="140">
            <template #default="{ row }">
              <router-link class="mono" :to="`/runs/${row.runId}`">{{ shortId(row.runId) }}</router-link>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTag[row.status as AnnotationTaskStatus]" size="small">
                {{ statusLabel[row.status as AnnotationTaskStatus] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="自动结论" width="130">
            <template #default="{ row }">
              <el-tag v-if="autoVerdict(row)" :type="verdictTag(autoVerdict(row))" size="small">
                {{ autoVerdict(row) }}
              </el-tag>
              <span v-else class="muted">无结论</span>
              <el-tag v-if="diverged(row)" type="warning" size="small" style="margin-left: 4px">
                人工已改判
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="人工结论" min-width="200">
            <template #default="{ row }">
              <template v-if="latestSubmit(row)">
                <el-tag :type="verdictTag(latestSubmit(row)?.verdict)" size="small">
                  {{ latestSubmit(row)?.verdict }}
                </el-tag>
                <span class="muted" style="margin-left: 6px">
                  {{ categoryLabel(latestSubmit(row)?.failureCategory) }}
                </span>
              </template>
              <span v-else class="muted">尚未提交</span>
            </template>
          </el-table-column>
          <el-table-column label="版本" width="70">
            <template #default="{ row }">
              <span class="mono">v{{ row.version }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180" align="right">
            <template #default="{ row }">
              <el-button size="small" @click="openTask(row.id)">查看</el-button>
              <el-button
                v-if="row.status === 'PENDING' || row.status === 'RETURNED'"
                type="primary"
                size="small"
                :disabled="acting"
                @click="claimTask(row)"
              >
                领取
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="page__pagination">
          <el-pagination
            v-model:current-page="query.page"
            v-model:page-size="query.pageSize"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @current-change="load"
            @size-change="search"
          />
        </div>
      </el-card>
    </section>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawerOpen" title="标注任务详情" size="620px">
      <template v-if="current">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="Run">
            <router-link class="mono" :to="`/runs/${current.runId}`">{{ shortId(current.runId) }}</router-link>
          </el-descriptions-item>
          <el-descriptions-item label="评测 Job">
            <span class="mono">{{ shortId(current.evaluationJobId) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag[current.status]" size="small">
              {{ statusLabel[current.status] }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="版本">
            <span class="mono">v{{ current.version }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="领取人">{{ current.assigneeId ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(current.createdAt) }}</el-descriptions-item>
        </el-descriptions>

        <!-- 提交 / 重新提交 -->
        <section v-if="current.status === 'CLAIMED'" class="drawer-section">
          <h3 class="drawer-section__title">提交标注</h3>
          <el-form label-width="88px" size="small">
            <el-form-item label="人工判定">
              <el-radio-group v-model="form.verdict">
                <el-radio-button value="PASS">通过</el-radio-button>
                <el-radio-button value="FAIL">未通过</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="失败归因">
              <el-select
                v-model="form.failureCategory"
                :disabled="!categoryRequired"
                :placeholder="categoryRequired ? '判未通过时必填' : '判通过时不需要'"
                clearable
                style="width: 100%"
              >
                <el-option
                  v-for="[value, label] in categoryOptions"
                  :key="value"
                  :label="label"
                  :value="value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="判定依据">
              <el-input v-model="form.reason" type="textarea" :rows="3" placeholder="必填：为什么这么判" />
            </el-form-item>
            <el-form-item label="证据引用">
              <el-input
                v-model="form.evidence"
                type="textarea"
                :rows="3"
                placeholder="可选，JSON 数组，如 [{&quot;type&quot;:&quot;event&quot;,&quot;eventId&quot;:&quot;...&quot;}]"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="acting" @click="submitTask">提交</el-button>
              <el-button
                type="danger"
                plain
                :loading="acting"
                @click="cancelTask(current)"
              >
                取消任务
              </el-button>
            </el-form-item>
          </el-form>
        </section>

        <!-- 审核 -->
        <section v-if="current.status === 'SUBMITTED'" class="drawer-section">
          <h3 class="drawer-section__title">审核</h3>
          <el-alert type="info" :closable="false" show-icon>
            审核通过后进入 REVIEWED 终态；退回会保留全部历史，标注者可以重新提交。
          </el-alert>
          <el-form label-width="88px" size="small" style="margin-top: 10px">
            <el-form-item label="审核意见">
              <el-input v-model="reviewForm.reason" type="textarea" :rows="2" placeholder="退回时必填" />
            </el-form-item>
            <el-form-item>
              <el-button type="success" :loading="acting" @click="reviewTask(true)">审核通过</el-button>
              <el-button type="warning" :loading="acting" @click="reviewTask(false)">退回</el-button>
            </el-form-item>
          </el-form>
        </section>

        <!-- 退回说明 -->
        <el-alert
          v-if="current.status === 'RETURNED'"
          type="warning"
          :closable="false"
          show-icon
          title="该任务已被退回，请补充证据后重新提交"
        />

        <!-- 历史 -->
        <section class="drawer-section">
          <h3 class="drawer-section__title">操作历史（只追加，不覆盖）</h3>
          <el-empty v-if="!current.records.length" description="暂无历史" :image-size="60" />
          <el-timeline v-else>
            <el-timeline-item
              v-for="record in current.records"
              :key="record.id"
              :timestamp="formatTime(record.createdAt)"
              placement="top"
            >
              <div class="record">
                <div class="record__head">
                  <el-tag size="small">{{ actionLabel[record.action] ?? record.action }}</el-tag>
                  <span class="muted">{{ record.operatorId }}</span>
                </div>
                <div v-if="record.verdict" class="record__verdict">
                  人工判定
                  <el-tag :type="verdictTag(record.verdict)" size="small">{{ record.verdict }}</el-tag>
                  <span v-if="record.failureCategory" class="muted">
                    {{ categoryLabel(record.failureCategory) }}
                  </span>
                </div>
                <div v-if="record.originalVerdict" class="record__auto">
                  自动评测结论：
                  <el-tag :type="verdictTag(record.originalVerdict)" size="small" effect="plain">
                    {{ record.originalVerdict }}
                  </el-tag>
                  <span v-if="record.verdict && record.verdict !== record.originalVerdict" class="record__diverge">
                    人工改判（原结论保留不变）
                  </span>
                </div>
                <div v-if="!record.originalVerdict && record.action !== 'CLAIM'" class="muted">
                  该 Run 没有自动评测结论（技术失败），此处只有人工判断
                </div>
                <p v-if="record.reason" class="record__reason">{{ record.reason }}</p>
                <pre v-if="record.evidence && JSON.stringify(record.evidence) !== '[]'" class="json-block">{{
                  stringifyJson(record.evidence)
                }}</pre>
              </div>
            </el-timeline-item>
          </el-timeline>
        </section>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.drawer-section {
  margin-top: 20px;
}

.drawer-section__title {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--rp-text);
}

.record__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.record__verdict,
.record__auto {
  margin-top: 6px;
  font-size: 13px;
  color: var(--rp-text);
}

.record__diverge {
  margin-left: 8px;
  color: var(--el-color-warning-dark-2);
}

.record__reason {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--rp-text);
  white-space: pre-wrap;
}
</style>
