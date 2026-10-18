<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Delete, Plus, Refresh } from '@element-plus/icons-vue'

import * as planApi from '@/api/plans'
import type { EvaluationPlanView, EvaluatorView, PlanItemView, PlanVersionView } from '@/types/domain'
import { formatTime, parseJsonText, shortHash, stringifyJson, toastError, toastSuccess } from '@/utils/feedback'

const route = useRoute()
const router = useRouter()
const planId = computed(() => String(route.params.id ?? ''))

const plan = ref<EvaluationPlanView | null>(null)
const versions = ref<PlanVersionView[]>([])
const evaluators = ref<EvaluatorView[]>([])
const loading = ref(false)
const selectedVersion = ref<PlanVersionView | null>(null)

/** 只允许选择已发布的算子：草稿算子会让方案无法发布。 */
const publishedEvaluators = computed(() => evaluators.value.filter((item) => item.status === 'PUBLISHED'))

const evaluatorById = computed(() => {
  const map = new Map<string, EvaluatorView>()
  evaluators.value.forEach((item) => map.set(item.id, item))
  return map
})

async function load(): Promise<void> {
  if (!planId.value) {
    return
  }
  loading.value = true
  try {
    plan.value = await planApi.getPlan(planId.value)
    const [versionPage, evaluatorPage] = await Promise.all([
      planApi.listPlanVersions(planId.value, { pageSize: 100 }),
      planApi.listEvaluators({ pageSize: 100 }),
    ])
    versions.value = versionPage.items
    evaluators.value = evaluatorPage.items
    if (selectedVersion.value) {
      selectedVersion.value =
        versionPage.items.find((item) => item.id === selectedVersion.value?.id) ?? null
    }
    // 支持 ?versionId=... 直接定位到某个版本
    const fromQuery = route.query.versionId
    if (!selectedVersion.value && typeof fromQuery === 'string') {
      selectedVersion.value = versionPage.items.find((item) => item.id === fromQuery) ?? null
    }
  } catch (error) {
    toastError(error, '加载评测方案失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

/* ---------------------------------------------------------------- 新建版本 */

interface DraftItem {
  evaluatorDefinitionId: string
  executionOrder: number
  required: boolean
  configText: string
}

const versionVisible = ref(false)
const versionSaving = ref(false)
const versionForm = reactive({ configText: '{\n  "gate": {\n    "maxRegressions": 0\n  }\n}' })
const draftItems = ref<DraftItem[]>([])

function openVersionDialog(cloneFrom?: PlanVersionView): void {
  versionForm.configText = cloneFrom
    ? stringifyJson(cloneFrom.config)
    : '{\n  "gate": {\n    "maxRegressions": 0\n  }\n}'
  draftItems.value = cloneFrom
    ? cloneFrom.items.map((item, index) => ({
        evaluatorDefinitionId: item.evaluatorDefinitionId,
        executionOrder: item.executionOrder ?? index + 1,
        required: item.required,
        configText: stringifyJson(item.config) || '{}',
      }))
    : []
  if (draftItems.value.length === 0) {
    addDraftItem()
  }
  versionVisible.value = true
}

function addDraftItem(): void {
  draftItems.value.push({
    evaluatorDefinitionId: '',
    executionOrder: draftItems.value.length + 1,
    required: true,
    configText: '{}',
  })
}

function removeDraftItem(index: number): void {
  draftItems.value.splice(index, 1)
}

async function submitVersion(): Promise<void> {
  let config: unknown
  try {
    config = parseJsonText(versionForm.configText, 'config')
  } catch (error) {
    toastError(error)
    return
  }
  if (!config || typeof config !== 'object' || Array.isArray(config)) {
    toastError(new Error('config 必须是 JSON 对象'))
    return
  }

  const items: planApi.PlanItemPayload[] = []
  for (const [index, item] of draftItems.value.entries()) {
    if (!item.evaluatorDefinitionId) {
      toastError(new Error(`第 ${index + 1} 个算子还没有选择`))
      return
    }
    let itemConfig: unknown
    try {
      itemConfig = parseJsonText(item.configText, `第 ${index + 1} 个算子的 config`)
    } catch (error) {
      toastError(error)
      return
    }
    items.push({
      evaluatorDefinitionId: item.evaluatorDefinitionId,
      executionOrder: item.executionOrder,
      required: item.required,
      config: itemConfig ?? {},
    })
  }
  if (items.length === 0) {
    toastError(new Error('方案版本至少需要一个算子'))
    return
  }

  versionSaving.value = true
  try {
    const created = await planApi.createPlanVersion(planId.value, { config, items })
    toastSuccess(`版本 v${created.version} 已创建（草稿）`)
    versionVisible.value = false
    await load()
    selectedVersion.value = created
  } catch (error) {
    toastError(error, '创建方案版本失败')
  } finally {
    versionSaving.value = false
  }
}

/* ---------------------------------------------------------------- 发布/归档 */

async function publish(version: PlanVersionView): Promise<void> {
  try {
    const published = await planApi.publishPlanVersion(version.id)
    toastSuccess(`v${version.version} 已发布，内容摘要 ${shortHash(published.contentHash)}`)
    await load()
  } catch (error) {
    toastError(error, '发布失败')
  }
}

async function archive(version: PlanVersionView): Promise<void> {
  try {
    await planApi.archivePlanVersion(version.id)
    toastSuccess(`v${version.version} 已归档`)
    await load()
  } catch (error) {
    toastError(error, '归档失败')
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

function evaluatorLabel(id: string): string {
  const found = evaluatorById.value.get(id)
  return found ? `${found.evaluatorKey} v${found.version}` : id
}

function viewItems(version: PlanVersionView): void {
  selectedVersion.value = version
}

function back(): void {
  void router.push('/evaluation-plans')
}

function itemRowKey(item: PlanItemView): string {
  return item.id
}
</script>

<template>
  <div class="page" v-loading="loading">
    <header class="page__header">
      <div>
        <el-button link :icon="ArrowLeft" @click="back">返回列表</el-button>
        <h1 class="page__title" style="margin-top: 8px">{{ plan?.name ?? '评测方案' }}</h1>
        <p class="page__subtitle">
          <span class="mono">{{ plan?.code }}</span>
          <span v-if="plan?.description"> · {{ plan.description }}</span>
        </p>
      </div>
      <div class="page__toolbar-group">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openVersionDialog()">新建版本</el-button>
      </div>
    </header>

    <el-card class="page__card" shadow="never">
      <template #header>
        <strong>版本</strong>
        <span class="muted" style="margin-left: 8px">
          发布时校验所引用的算子均已发布；发布后 config 与 items 不可修改
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
        <el-table-column label="算子数" width="90">
          <template #default="{ row }">{{ row.items.length }}</template>
        </el-table-column>
        <el-table-column label="内容摘要" min-width="160">
          <template #default="{ row }">
            <span class="mono">{{ shortHash(row.contentHash) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" width="180">
          <template #default="{ row }">{{ formatTime(row.publishedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewItems(row)">查看算子</el-button>
            <el-button v-if="row.status === 'DRAFT'" link type="success" @click="publish(row)">
              发布
            </el-button>
            <el-button v-if="row.status !== 'ARCHIVED'" link type="danger" @click="archive(row)">
              归档
            </el-button>
            <el-button v-if="row.status !== 'DRAFT'" link @click="openVersionDialog(row)">
              复制为新草稿
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
        <strong>方案内容 · v{{ selectedVersion.version }}</strong>
        <el-tag :type="versionTag[selectedVersion.status]" size="small" style="margin-left: 8px">
          {{ versionLabel[selectedVersion.status] ?? selectedVersion.status }}
        </el-tag>
      </template>

      <el-table :data="selectedVersion.items" :row-key="itemRowKey" style="width: 100%">
        <el-table-column prop="executionOrder" label="顺序" width="80" />
        <el-table-column label="算子" min-width="240">
          <template #default="{ row }">
            <span class="mono">{{ evaluatorLabel(row.evaluatorDefinitionId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="必需" width="90">
          <template #default="{ row }">
            <el-tag :type="row.required ? 'success' : 'info'" size="small">
              {{ row.required ? '必需' : '可选' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="config" min-width="260">
          <template #default="{ row }">
            <pre class="json-block">{{ stringifyJson(row.config) }}</pre>
          </template>
        </el-table-column>
      </el-table>

      <div style="margin-top: 16px">
        <div class="muted">方案 config（门禁规则）</div>
        <pre class="json-block">{{ stringifyJson(selectedVersion.config) }}</pre>
      </div>
    </el-card>

    <el-dialog v-model="versionVisible" title="新建方案版本" width="760px">
      <el-form label-width="96px">
        <el-form-item label="config">
          <el-input v-model="versionForm.configText" type="textarea" :rows="5" class="mono" />
        </el-form-item>
        <el-form-item label="算子列表">
          <div class="draft-items">
            <div v-for="(item, index) in draftItems" :key="index" class="draft-item">
              <el-select
                v-model="item.evaluatorDefinitionId"
                placeholder="选择已发布算子"
                style="width: 200px"
              >
                <el-option
                  v-for="evaluator in publishedEvaluators"
                  :key="evaluator.id"
                  :label="`${evaluator.evaluatorKey} v${evaluator.version}`"
                  :value="evaluator.id"
                />
              </el-select>
              <el-input-number v-model="item.executionOrder" :min="1" :max="99" controls-position="right" />
              <el-switch v-model="item.required" active-text="必需" />
              <el-input v-model="item.configText" type="textarea" :rows="2" class="mono" style="flex: 1" />
              <el-button link type="danger" :icon="Delete" @click="removeDraftItem(index)" />
            </div>
            <el-button :icon="Plus" @click="addDraftItem">添加算子</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="versionVisible = false">取消</el-button>
        <el-button type="primary" :loading="versionSaving" @click="submitVersion">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.draft-items {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.draft-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
</style>
