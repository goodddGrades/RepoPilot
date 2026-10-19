<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { CircleClose, Refresh, Upload, Warning } from '@element-plus/icons-vue'
import type { UploadFile } from 'element-plus'

import * as experimentApi from '@/api/experiments'
import * as importApi from '@/api/imports'
import type {
  ExperimentView,
  ImportBatchView,
  ImportErrorView,
  ImportStatus,
  ImportType,
} from '@/types/domain'
import { formatTime, toastError, toastSuccess } from '@/utils/feedback'

/**
 * 离线轨迹导入。
 *
 * 两段式：先上传原文件拿到 Artifact，再创建批次。批次受理后返回 202，
 * 真正的逐行处理在后端后台进行——所以这里靠轮询批次状态推进，
 * 而不是等一个可能几十秒的请求。
 *
 * 页面只展示服务端给出的数字：成功/失败/重复都来自批次计数，
 * 「重复」是成功的子集（同一条来源记录再次导入），不额外相加。
 */

const route = useRoute()

const IMPORT_STATUS_TEXT: Record<ImportStatus, string> = {
  PENDING: '待处理',
  RUNNING: '处理中',
  COMPLETED: '已完成',
  PARTIAL_FAILED: '部分失败',
  FAILED: '失败',
  CANCELLED: '已取消',
}

const IMPORT_STATUS_TAG: Record<ImportStatus, 'info' | 'primary' | 'warning' | 'success' | 'danger'> = {
  PENDING: 'info',
  RUNNING: 'primary',
  COMPLETED: 'success',
  PARTIAL_FAILED: 'warning',
  FAILED: 'danger',
  CANCELLED: 'info',
}

const loading = ref(false)
const rows = ref<ImportBatchView[]>([])
const total = ref(0)
const query = reactive({
  page: 1,
  pageSize: 10,
  status: '' as ImportStatus | '',
  experimentId: (route.query.experimentId as string | undefined) ?? '',
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await importApi.listImports({
      page: query.page,
      pageSize: query.pageSize,
      importType: 'OFFLINE_RUNS' as ImportType,
      status: query.status || undefined,
      experimentId: query.experimentId || undefined,
    })
    rows.value = page.items
    total.value = page.total
  } catch (error) {
    toastError(error, '加载导入批次失败')
  } finally {
    loading.value = false
  }
}

function search(): void {
  query.page = 1
  void load()
}

function reset(): void {
  query.status = ''
  query.experimentId = ''
  query.page = 1
  void load()
}

// ---------------------------------------------------------------------------
// 上传与创建批次
// ---------------------------------------------------------------------------

const uploadVisible = ref(false)
const uploading = ref(false)
const uploadProgress = ref(0)
const offlineExperiments = ref<ExperimentView[]>([])
const optionsLoading = ref(false)
const form = reactive({ experimentId: '', file: null as File | null })
const currentStatus = ref<ImportBatchView | null>(null)

/** 只有草稿态离线实验还能导入：开始之后批次就必须冻结，服务端也会拒绝。 */
const selectableExperiments = computed(() =>
  offlineExperiments.value.filter((item) => item.status === 'DRAFT'),
)

const selectedExperiment = computed(() =>
  offlineExperiments.value.find((item) => item.id === form.experimentId),
)

async function openUpload(): Promise<void> {
  form.experimentId = ''
  form.file = null
  uploadProgress.value = 0
  currentStatus.value = null
  uploadVisible.value = true
  optionsLoading.value = true
  try {
    const page = await experimentApi.listExperiments({ mode: 'OFFLINE', pageSize: 100 })
    offlineExperiments.value = page.items
  } catch (error) {
    toastError(error, '加载离线实验失败')
  } finally {
    optionsLoading.value = false
  }
}

/** el-upload 的 raw 才是真正的 File；auto-upload 关掉，选文件不等于开始上传。 */
function onFileChange(file: UploadFile): void {
  form.file = (file.raw as File | undefined) ?? null
}

async function submitUpload(): Promise<void> {
  if (!form.experimentId || !form.file) {
    toastError(new Error('请选择离线实验与 JSONL 文件'), '参数不完整')
    return
  }
  const experiment = selectedExperiment.value
  if (!experiment) {
    toastError(new Error('实验信息缺失'), '请重新选择实验')
    return
  }
  uploading.value = true
  uploadProgress.value = 0
  try {
    const artifact = await importApi.uploadImportFile(form.file, (percent) => {
      uploadProgress.value = percent
    })
    const batch = await importApi.createImport({
      importType: 'OFFLINE_RUNS',
      datasetVersionId: experiment.datasetVersionId,
      experimentId: experiment.id,
      fileArtifactId: artifact.id,
    })
    currentStatus.value = batch
    toastSuccess('批次已受理，正在逐行导入')
    await load()
    startPolling(batch.id)
  } catch (error) {
    toastError(error, '导入失败')
  } finally {
    uploading.value = false
  }
}

// ---------------------------------------------------------------------------
// 轮询批次进度
//
// 只轮询一个批次且到终态即停：页面切走后必须清掉定时器，
// 否则每次进入页面都会多出一个永不停止的轮询。
// ---------------------------------------------------------------------------

let timer: number | undefined

function startPolling(batchId: string): void {
  stopPolling()
  timer = window.setInterval(async () => {
    try {
      const latest = await importApi.getImport(batchId)
      currentStatus.value = latest
      const index = rows.value.findIndex((item) => item.id === latest.id)
      if (index >= 0) {
        rows.value[index] = latest
      }
      if (importApi.isTerminal(latest.status)) {
        stopPolling()
        await load()
      }
    } catch {
      stopPolling()
    }
  }, 1500)
}

function stopPolling(): void {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

onBeforeUnmount(stopPolling)

const progressPercent = computed(() => {
  const batch = currentStatus.value
  if (!batch || batch.totalCount <= 0) {
    return 0
  }
  return Math.min(100, Math.round((batch.processedCount / batch.totalCount) * 100))
})

// ---------------------------------------------------------------------------
// 错误详情
// ---------------------------------------------------------------------------

const errorsVisible = ref(false)
const errorsLoading = ref(false)
const errors = ref<ImportErrorView[]>([])
const errorTotal = ref(0)
const errorQuery = reactive({ page: 1, pageSize: 20, batchId: '' })
const errorBatchLabel = ref('')

async function openErrors(row: ImportBatchView): Promise<void> {
  errorQuery.batchId = row.id
  errorQuery.page = 1
  errorBatchLabel.value = row.id
  errorsVisible.value = true
  await loadErrors()
}

async function loadErrors(): Promise<void> {
  errorsLoading.value = true
  try {
    const page = await importApi.listImportErrors(errorQuery.batchId, {
      page: errorQuery.page,
      pageSize: errorQuery.pageSize,
    })
    errors.value = page.items
    errorTotal.value = page.total
  } catch (error) {
    toastError(error, '加载错误明细失败')
  } finally {
    errorsLoading.value = false
  }
}

// ---------------------------------------------------------------------------
// 取消
// ---------------------------------------------------------------------------

async function cancelBatch(row: ImportBatchView): Promise<void> {
  try {
    const updated = await importApi.cancelImport(row.id)
    toastSuccess('已取消，已提交的行保留')
    if (currentStatus.value?.id === updated.id) {
      currentStatus.value = updated
      stopPolling()
    }
    await load()
  } catch (error) {
    toastError(error, '取消失败')
  }
}

watch(
  () => route.query.experimentId,
  (value) => {
    query.experimentId = (value as string | undefined) ?? ''
    query.page = 1
    void load()
  },
)

onMounted(load)

defineExpose({ load })
</script>

<template>
  <div class="page">
    <header class="page__header">
      <div>
        <h1 class="page__title">离线轨迹导入</h1>
        <p class="page__subtitle">
          把外部 JSONL 轨迹标准化成统一的 Run / Trace。每一行独立事务：成功行入库，失败行只留错误摘要与行号，
          本页不展示被拒绝的原始内容。
        </p>
      </div>
      <div class="page__toolbar-group">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Upload" @click="openUpload">导入轨迹</el-button>
      </div>
    </header>

    <el-card class="page__card" shadow="never">
      <div class="page__toolbar">
        <div class="page__toolbar-group">
          <el-input
            v-model="query.experimentId"
            placeholder="按实验 ID 过滤"
            clearable
            style="width: 260px"
            @keyup.enter="search"
          />
          <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 150px">
            <el-option
              v-for="(label, value) in IMPORT_STATUS_TEXT"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column prop="id" label="批次" width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="IMPORT_STATUS_TAG[row.status as ImportStatus]" size="small">
              {{ IMPORT_STATUS_TEXT[row.status as ImportStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="成功 / 失败" width="140">
          <template #default="{ row }">
            <span>{{ row.successCount }} / {{ row.failedCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="重复" width="90">
          <template #default="{ row }">
            <span>{{ row.duplicateCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="已处理行" width="120">
          <template #default="{ row }">
            <span>
              {{ row.lastLineNo }}
              <template v-if="row.totalKnown"> / {{ row.totalCount }}</template>
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="experimentId" label="实验" width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.experimentId || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="row.failedCount === 0" @click="openErrors(row)">
              错误明细（{{ row.failedCount }}）
            </el-button>
            <el-button
              v-if="!importApi.isTerminal(row.status as ImportStatus)"
              link
              type="danger"
              :icon="CircleClose"
              @click="cancelBatch(row)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有导入批次" />
        </template>
      </el-table>

      <div class="page__pagination">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="load"
        />
      </div>
    </el-card>

    <!-- 上传 -->
    <el-dialog v-model="uploadVisible" title="导入离线轨迹" width="680px">
      <el-form label-width="110px">
        <el-form-item label="离线实验" required>
          <el-select
            v-model="form.experimentId"
            :loading="optionsLoading"
            placeholder="选择草稿态的离线实验"
            style="width: 100%"
          >
            <el-option
              v-for="item in selectableExperiments"
              :key="item.id"
              :label="`${item.name}（${item.id.slice(-6)}）`"
              :value="item.id"
            />
          </el-select>
          <div class="hint">
            只有草稿态的离线实验可以导入；实验开始后批次清单会被冻结，服务端会拒绝新批次。
          </div>
        </el-form-item>
        <el-form-item label="JSONL 文件" required>
          <el-upload
            :auto-upload="false"
            :limit="1"
            accept=".jsonl,.json,text/plain"
            :on-change="onFileChange"
            :on-remove="() => (form.file = null)"
          >
            <el-button :icon="Upload">选择文件</el-button>
            <template #tip>
              <div class="hint">
                每行一个 JSON 对象，单行 ≤ 1MiB、文件 ≤ 20MiB；时间必须带时区。
              </div>
            </template>
          </el-upload>
        </el-form-item>
        <el-form-item v-if="uploading" label="上传进度">
          <el-progress :percentage="uploadProgress" style="width: 100%" />
        </el-form-item>
        <el-form-item v-if="currentStatus" label="批次状态">
          <div class="status-box">
            <el-tag :type="IMPORT_STATUS_TAG[currentStatus.status]" size="small">
              {{ IMPORT_STATUS_TEXT[currentStatus.status] }}
            </el-tag>
            <el-progress
              v-if="currentStatus.totalKnown"
              :percentage="progressPercent"
              :stroke-width="10"
              style="flex: 1"
            />
            <span v-else class="mono">已处理 {{ currentStatus.lastLineNo }} 行</span>
          </div>
          <el-descriptions :column="4" border size="small" style="width: 100%; margin-top: 8px">
            <el-descriptions-item label="成功">{{ currentStatus.successCount }}</el-descriptions-item>
            <el-descriptions-item label="失败">{{ currentStatus.failedCount }}</el-descriptions-item>
            <el-descriptions-item label="重复">{{ currentStatus.duplicateCount }}</el-descriptions-item>
            <el-descriptions-item label="总行数">
              {{ currentStatus.totalKnown ? currentStatus.totalCount : '待统计' }}
            </el-descriptions-item>
          </el-descriptions>
          <el-alert
            v-if="currentStatus.status === 'PARTIAL_FAILED'"
            type="warning"
            :closable="false"
            show-icon
            style="width: 100%; margin-top: 8px"
            title="部分行被拒绝：成功行已入库，失败行只保留错误摘要，需要逐行修正后重导"
          />
          <el-alert
            v-if="currentStatus.status === 'FAILED'"
            type="error"
            :closable="false"
            show-icon
            style="width: 100%; margin-top: 8px"
            :title="`整批没有成功行，共 ${currentStatus.failedCount} 行被拒绝`"
          />
          <el-button
            v-if="currentStatus.failedCount > 0"
            link
            type="primary"
            :icon="Warning"
            style="margin-top: 6px"
            @click="openErrors(currentStatus)"
          >
            查看错误明细
          </el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">关闭</el-button>
        <el-button
          type="primary"
          :loading="uploading"
          :disabled="!form.experimentId || !form.file"
          @click="submitUpload"
        >
          上传并导入
        </el-button>
      </template>
    </el-dialog>

    <!-- 错误明细 -->
    <el-dialog v-model="errorsVisible" title="导入错误明细" width="720px">
      <p class="hint">
        批次 <span class="mono">{{ errorBatchLabel }}</span> 的行级错误。行号对应原文件的物理行号，
        可直接跳到编辑器定位；这里不回显原始行内容。
      </p>
      <el-table v-loading="errorsLoading" :data="errors" size="small" max-height="360">
        <el-table-column prop="lineNo" label="行号" width="90" />
        <el-table-column prop="errorCode" label="错误码" width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.errorCode }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="说明" show-overflow-tooltip />
        <template #empty>
          <el-empty description="没有错误行" />
        </template>
      </el-table>
      <div class="page__pagination">
        <el-pagination
          v-model:current-page="errorQuery.page"
          v-model:page-size="errorQuery.pageSize"
          :total="errorTotal"
          layout="total, prev, pager, next"
          @current-change="loadErrors"
        />
      </div>
      <template #footer>
        <el-button @click="errorsVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hint {
  color: var(--rp-text-muted);
  font-size: 12px;
  line-height: 1.6;
}

.status-box {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}
</style>
