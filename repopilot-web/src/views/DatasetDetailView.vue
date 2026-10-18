<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Delete, Plus, Refresh, Upload } from '@element-plus/icons-vue'
import type { UploadFile } from 'element-plus'

import * as datasetApi from '@/api/datasets'
import type {
  DatasetCaseView,
  DatasetVersionView,
  DatasetView,
  ImportReceipt,
  TaskType,
} from '@/types/domain'
import { formatTime, parseJsonText, shortHash, stringifyJson, toastError, toastSuccess } from '@/utils/feedback'

const route = useRoute()
const router = useRouter()
const datasetId = computed(() => String(route.params.id ?? ''))

const dataset = ref<DatasetView | null>(null)
const versions = ref<DatasetVersionView[]>([])
const selectedVersion = ref<DatasetVersionView | null>(null)
const loading = ref(false)

const cases = ref<DatasetCaseView[]>([])
const caseTotal = ref(0)
const caseQuery = reactive({ page: 1, pageSize: 10, keyword: '' })
const caseLoading = ref(false)

async function loadDataset(): Promise<void> {
  if (!datasetId.value) {
    return
  }
  loading.value = true
  try {
    dataset.value = await datasetApi.getDataset(datasetId.value)
    const page = await datasetApi.listDatasetVersions(datasetId.value, { pageSize: 100 })
    versions.value = page.items
    if (selectedVersion.value) {
      selectedVersion.value =
        page.items.find((item) => item.id === selectedVersion.value?.id) ?? null
    }
    // 支持 ?versionId=... 直接定位到某个版本：分享链接和排查问题时不用再点一次
    const fromQuery = route.query.versionId
    if (!selectedVersion.value && typeof fromQuery === 'string') {
      const matched = page.items.find((item) => item.id === fromQuery)
      if (matched) {
        await selectVersion(matched)
      }
    }
  } catch (error) {
    toastError(error, '加载数据集失败')
  } finally {
    loading.value = false
  }
}

async function loadCases(): Promise<void> {
  if (!selectedVersion.value) {
    return
  }
  caseLoading.value = true
  try {
    const page = await datasetApi.listCases(selectedVersion.value.id, {
      page: caseQuery.page,
      pageSize: caseQuery.pageSize,
      keyword: caseQuery.keyword || undefined,
    })
    cases.value = page.items
    caseTotal.value = page.total
  } catch (error) {
    toastError(error, '加载样本失败')
  } finally {
    caseLoading.value = false
  }
}

onMounted(loadDataset)

async function selectVersion(version: DatasetVersionView): Promise<void> {
  selectedVersion.value = version
  caseQuery.page = 1
  caseQuery.keyword = ''
  await loadCases()
}

/* ---------------------------------------------------------------- 版本操作 */

const versionVisible = ref(false)
const versionSaving = ref(false)
const versionForm = reactive({ description: '', cloneFromVersionId: '' })

function openVersionDialog(): void {
  versionForm.description = ''
  versionForm.cloneFromVersionId = ''
  versionVisible.value = true
}

async function submitVersion(): Promise<void> {
  versionSaving.value = true
  try {
    const created = await datasetApi.createDatasetVersion(datasetId.value, {
      description: versionForm.description.trim() || undefined,
      cloneFromVersionId: versionForm.cloneFromVersionId || undefined,
    })
    toastSuccess(`版本 v${created.version} 已创建（草稿）`)
    versionVisible.value = false
    await loadDataset()
    await selectVersion(created)
  } catch (error) {
    toastError(error, '创建版本失败')
  } finally {
    versionSaving.value = false
  }
}

async function publishVersion(version: DatasetVersionView): Promise<void> {
  try {
    const published = await datasetApi.publishDatasetVersion(version.id)
    toastSuccess(`v${version.version} 已发布，内容摘要 ${shortHash(published.contentHash)}`)
    await loadDataset()
  } catch (error) {
    toastError(error, '发布失败')
  }
}

async function archiveVersion(version: DatasetVersionView): Promise<void> {
  try {
    await datasetApi.archiveDatasetVersion(version.id)
    toastSuccess(`v${version.version} 已归档`)
    await loadDataset()
  } catch (error) {
    toastError(error, '归档失败')
  }
}

async function archiveDataset(): Promise<void> {
  try {
    await datasetApi.patchDataset(datasetId.value, { status: 'ARCHIVED' })
    toastSuccess('数据集已归档')
    await loadDataset()
  } catch (error) {
    toastError(error, '归档数据集失败')
  }
}

/* ---------------------------------------------------------------- 样本编辑 */

const caseVisible = ref(false)
const caseSaving = ref(false)
const editingCaseId = ref<string | null>(null)
const caseForm = reactive({
  caseKey: '',
  taskType: 'AGENT_TASK' as TaskType,
  inputText: '{\n  "prompt": "修复空指针"\n}',
  expectedText: '',
  metadataText: '',
})

function openCaseDialog(row?: DatasetCaseView): void {
  editingCaseId.value = row?.id ?? null
  caseForm.caseKey = row?.caseKey ?? ''
  caseForm.taskType = row?.taskType ?? 'AGENT_TASK'
  caseForm.inputText = row ? stringifyJson(row.input) : '{\n  "prompt": "修复空指针"\n}'
  caseForm.expectedText = row ? stringifyJson(row.expected) : ''
  caseForm.metadataText = row ? stringifyJson(row.metadata) : ''
  caseVisible.value = true
}

async function submitCase(): Promise<void> {
  if (!selectedVersion.value) {
    return
  }
  let input: unknown
  let expected: unknown
  let metadata: unknown
  try {
    input = parseJsonText(caseForm.inputText, 'input')
    expected = parseJsonText(caseForm.expectedText, 'expected')
    metadata = parseJsonText(caseForm.metadataText, 'metadata')
  } catch (error) {
    toastError(error)
    return
  }
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    toastError(new Error('input 必须是 JSON 对象'))
    return
  }

  caseSaving.value = true
  try {
    const payload = {
      caseKey: caseForm.caseKey.trim(),
      taskType: caseForm.taskType,
      input,
      expected,
      metadata,
    }
    if (editingCaseId.value) {
      await datasetApi.updateCase(editingCaseId.value, payload)
      toastSuccess('样本已更新')
    } else {
      await datasetApi.createCase(selectedVersion.value.id, payload)
      toastSuccess('样本已添加')
    }
    caseVisible.value = false
    await loadCases()
    await loadDataset()
  } catch (error) {
    toastError(error, '保存样本失败')
  } finally {
    caseSaving.value = false
  }
}

async function removeCase(row: DatasetCaseView): Promise<void> {
  try {
    await datasetApi.deleteCase(row.id)
    toastSuccess('样本已删除')
    await loadCases()
    await loadDataset()
  } catch (error) {
    toastError(error, '删除样本失败')
  }
}

/* ---------------------------------------------------------------- JSONL 导入 */

const importVisible = ref(false)
const importFile = ref<File | null>(null)
const importing = ref(false)
const importProgress = ref(0)
const lastReceipt = ref<ImportReceipt | null>(null)

function openImport(): void {
  importFile.value = null
  importProgress.value = 0
  lastReceipt.value = null
  importVisible.value = true
}

function onFileChange(file: UploadFile): void {
  // el-upload 的 raw 才是真正的 File；不自动上传，避免选中文件就开始传
  importFile.value = (file.raw as File | undefined) ?? null
}

async function submitImport(): Promise<void> {
  if (!selectedVersion.value) {
    return
  }
  if (!importFile.value) {
    toastError(new Error('请先选择 .jsonl 文件'))
    return
  }
  importing.value = true
  importProgress.value = 0
  try {
    const receipt = await datasetApi.importCases(
      selectedVersion.value.id,
      importFile.value,
      (percent) => {
        importProgress.value = percent
      },
    )
    lastReceipt.value = receipt
    toastSuccess(`导入成功：${receipt.importedCount} 条样本`)
    await loadCases()
    await loadDataset()
  } catch (error) {
    // 整批原子：这里报错就代表库里一条都没写，不需要再刷新列表
    toastError(error, '导入失败')
  } finally {
    importing.value = false
  }
}

/* ---------------------------------------------------------------- 展示辅助 */

const versionTag: Record<string, 'info' | 'success' | 'warning'> = {
  DRAFT: 'info',
  PUBLISHED: 'success',
  ARCHIVED: 'warning',
}

const versionLabel: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
}

const editable = computed(() => selectedVersion.value?.status === 'DRAFT')

function back(): void {
  void router.push('/datasets')
}

function searchCases(): void {
  caseQuery.page = 1
  void loadCases()
}
</script>

<template>
  <div class="page" v-loading="loading">
    <header class="page__header">
      <div>
        <el-button link :icon="ArrowLeft" @click="back">返回列表</el-button>
        <h1 class="page__title" style="margin-top: 8px">
          {{ dataset?.name ?? '数据集' }}
          <el-tag v-if="dataset" size="small" style="margin-left: 8px">{{ dataset.status }}</el-tag>
        </h1>
        <p class="page__subtitle">
          <span class="mono">{{ dataset?.code }}</span>
          <span v-if="dataset?.description"> · {{ dataset.description }}</span>
        </p>
      </div>
      <div class="page__toolbar-group">
        <el-button :icon="Refresh" @click="loadDataset">刷新</el-button>
        <el-button :disabled="dataset?.status === 'ARCHIVED'" @click="archiveDataset">
          归档数据集
        </el-button>
        <el-button type="primary" :icon="Plus" @click="openVersionDialog">新建版本</el-button>
      </div>
    </header>

    <el-card class="page__card" shadow="never">
      <template #header>
        <strong>版本</strong>
        <span class="muted" style="margin-left: 8px">
          发布后样本不可修改；克隆已发布版本可得到内容摘要相同的新草稿
        </span>
      </template>
      <el-table :data="versions" stripe style="width: 100%">
        <el-table-column label="版本" width="80">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="versionTag[row.status]" size="small">
              {{ versionLabel[row.status] ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="样本数" width="100" prop="caseCount" />
        <el-table-column label="内容摘要" min-width="160">
          <template #default="{ row }">
            <span class="mono">{{ shortHash(row.contentHash) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" width="180">
          <template #default="{ row }">{{ formatTime(row.publishedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="selectVersion(row)">查看样本</el-button>
            <el-button v-if="row.status === 'DRAFT'" link type="success" @click="publishVersion(row)">
              发布
            </el-button>
            <el-button
              v-if="row.status !== 'ARCHIVED'"
              link
              type="danger"
              @click="archiveVersion(row)"
            >
              归档
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有版本，先新建一个草稿版本" />
        </template>
      </el-table>
    </el-card>

    <el-card v-if="selectedVersion" class="page__card" shadow="never">
      <template #header>
        <div class="page__toolbar">
          <div>
            <strong>样本 · v{{ selectedVersion.version }}</strong>
            <el-tag :type="versionTag[selectedVersion.status]" size="small" style="margin-left: 8px">
              {{ versionLabel[selectedVersion.status] ?? selectedVersion.status }}
            </el-tag>
            <span v-if="!editable" class="muted" style="margin-left: 8px">
              非草稿版本只读
            </span>
          </div>
          <div class="page__toolbar-group">
            <el-input
              v-model="caseQuery.keyword"
              placeholder="按 caseKey 搜索"
              clearable
              style="width: 180px"
              @keyup.enter="searchCases"
            />
            <el-button :disabled="!editable" :icon="Plus" @click="openCaseDialog()">
              新增样本
            </el-button>
            <el-button :disabled="!editable" :icon="Upload" @click="openImport">
              导入 JSONL
            </el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="caseLoading" :data="cases" stripe style="width: 100%">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="case-detail">
              <div>
                <div class="muted">input</div>
                <pre class="json-block">{{ stringifyJson(row.input) }}</pre>
              </div>
              <div>
                <div class="muted">expected</div>
                <pre class="json-block">{{ stringifyJson(row.expected) }}</pre>
              </div>
              <div>
                <div class="muted">metadata</div>
                <pre class="json-block">{{ stringifyJson(row.metadata) }}</pre>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="caseKey" label="caseKey" min-width="180">
          <template #default="{ row }">
            <span class="mono">{{ row.caseKey }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="taskType" label="taskType" width="140" />
        <el-table-column label="更新时间" width="180">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="!editable" @click="openCaseDialog(row)">
              编辑
            </el-button>
            <el-button link type="danger" :disabled="!editable" :icon="Delete" @click="removeCase(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="这个版本还没有样本" />
        </template>
      </el-table>

      <div class="page__pagination">
        <el-pagination
          v-model:current-page="caseQuery.page"
          v-model:page-size="caseQuery.pageSize"
          :total="caseTotal"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadCases"
          @size-change="searchCases"
        />
      </div>
    </el-card>

    <el-dialog v-model="versionVisible" title="新建数据集版本" width="480px">
      <el-form label-width="120px">
        <el-form-item label="说明">
          <el-input v-model="versionForm.description" type="textarea" :rows="2" maxlength="4000" />
        </el-form-item>
        <el-form-item label="克隆自版本">
          <el-select v-model="versionForm.cloneFromVersionId" clearable placeholder="不克隆" style="width: 100%">
            <el-option
              v-for="item in versions"
              :key="item.id"
              :label="`v${item.version}（${item.caseCount} 条样本，${versionLabel[item.status]}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="versionVisible = false">取消</el-button>
        <el-button type="primary" :loading="versionSaving" @click="submitVersion">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="caseVisible"
      :title="editingCaseId ? '编辑样本' : '新增样本'"
      width="640px"
    >
      <el-form label-width="96px">
        <el-form-item label="caseKey" required>
          <el-input v-model="caseForm.caseKey" maxlength="128" placeholder="版本内唯一，大小写敏感" />
        </el-form-item>
        <el-form-item label="taskType" required>
          <el-select v-model="caseForm.taskType" style="width: 100%">
            <el-option label="AGENT_TASK（完整 Agent 任务）" value="AGENT_TASK" />
            <el-option label="LLM_CALL（单次模型调用）" value="LLM_CALL" />
          </el-select>
        </el-form-item>
        <el-form-item label="input" required>
          <el-input v-model="caseForm.inputText" type="textarea" :rows="5" class="mono" />
        </el-form-item>
        <el-form-item label="expected">
          <el-input v-model="caseForm.expectedText" type="textarea" :rows="3" class="mono" />
        </el-form-item>
        <el-form-item label="metadata">
          <el-input v-model="caseForm.metadataText" type="textarea" :rows="3" class="mono" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="caseVisible = false">取消</el-button>
        <el-button type="primary" :loading="caseSaving" @click="submitCase">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="导入 JSONL 样本" width="640px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="整批原子导入"
        description="任何一行校验失败都会整批拒绝：不写样本、不建批次、不保存原文件。行号是编辑器中的物理行号。"
      />
      <el-upload
        :auto-upload="false"
        :limit="1"
        accept=".jsonl,.ndjson,application/x-ndjson"
        :on-change="onFileChange"
        style="margin-top: 16px"
      >
        <el-button :icon="Upload">选择 JSONL 文件</el-button>
        <template #tip>
          <div class="muted">文件 ≤ 20 MiB、非空行 ≤ 10000、单行 ≤ 64 KiB</div>
        </template>
      </el-upload>
      <el-progress
        v-if="importing"
        :percentage="importProgress"
        :stroke-width="12"
        style="margin-top: 16px"
      />
      <el-descriptions v-if="lastReceipt" :column="2" border style="margin-top: 16px">
        <el-descriptions-item label="批次">{{ lastReceipt.importBatchId }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ lastReceipt.status }}</el-descriptions-item>
        <el-descriptions-item label="导入条数">{{ lastReceipt.importedCount }}</el-descriptions-item>
        <el-descriptions-item label="文件大小">{{ lastReceipt.sizeBytes }} B</el-descriptions-item>
        <el-descriptions-item label="sha256" :span="2">
          <span class="mono">{{ lastReceipt.sha256 }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="importVisible = false">关闭</el-button>
        <el-button type="primary" :loading="importing" @click="submitImport">开始导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.case-detail {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  padding: 8px 16px 16px;
}
</style>
