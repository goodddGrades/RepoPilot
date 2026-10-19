<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Refresh, Search, VideoPlay } from '@element-plus/icons-vue'

import * as agentApi from '@/api/agents'
import * as datasetApi from '@/api/datasets'
import * as experimentApi from '@/api/experiments'
import * as planApi from '@/api/plans'
import type {
  AgentVersionView,
  AgentView,
  DatasetVersionView,
  DatasetView,
  EvaluationPlanView,
  ExperimentStatus,
  ExperimentView,
  PlanVersionView,
} from '@/types/domain'
import { formatTime, toastError, toastSuccess } from '@/utils/feedback'

const router = useRouter()

const loading = ref(false)
const rows = ref<ExperimentView[]>([])
const total = ref(0)
const query = reactive({ page: 1, pageSize: 10, status: '', keyword: '' })

const statusOptions = Object.entries(experimentApi.EXPERIMENT_STATUS_TEXT).map(([value, label]) => ({
  value,
  label,
}))
const statusText = experimentApi.EXPERIMENT_STATUS_TEXT
const statusTag = experimentApi.EXPERIMENT_STATUS_TAG

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await experimentApi.listExperiments({
      page: query.page,
      pageSize: query.pageSize,
      status: query.status || undefined,
      keyword: query.keyword || undefined,
    })
    rows.value = page.items
    total.value = page.total
  } catch (error) {
    toastError(error, '加载实验失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

function search(): void {
  query.page = 1
  void load()
}

function reset(): void {
  query.status = ''
  query.keyword = ''
  query.page = 1
  void load()
}

function openDetail(row: ExperimentView): void {
  void router.push(`/experiments/${row.id}`)
}

// ---------------------------------------------------------------------------
// 新建实验
//
// 只让用户选"已发布"的版本：草稿版本提交时一定会被后端拒绝，先在选项里去掉，
// 比让用户点提交再吃一个 400 更省事。这里不复制后端的完整校验（样本非空、Runner 支持
// taskType 等），最后一道判定仍在服务端。
// ---------------------------------------------------------------------------

const createVisible = ref(false)
const creating = ref(false)
const optionsLoading = ref(false)

const datasets = ref<DatasetView[]>([])
const datasetVersions = ref<DatasetVersionView[]>([])
const plans = ref<EvaluationPlanView[]>([])
const planVersions = ref<PlanVersionView[]>([])
const agents = ref<AgentView[]>([])
const agentVersions = ref<AgentVersionView[]>([])

const createForm = reactive({
  name: '',
  description: '',
  datasetId: '',
  datasetVersionId: '',
  planId: '',
  planVersionId: '',
  agentId: '',
  agentVersionId: '',
  repeatCount: 1,
})

const publishedDatasetVersions = computed(() =>
  datasetVersions.value.filter((item) => item.status === 'PUBLISHED'),
)
const publishedPlanVersions = computed(() =>
  planVersions.value.filter((item) => item.status === 'PUBLISHED'),
)
const publishedAgentVersions = computed(() =>
  agentVersions.value.filter((item) => item.status === 'PUBLISHED'),
)

const canSubmit = computed(
  () =>
    createForm.name.trim() !== '' &&
    createForm.datasetVersionId !== '' &&
    createForm.planVersionId !== '' &&
    createForm.agentVersionId !== '',
)

async function openCreate(): Promise<void> {
  createForm.name = ''
  createForm.description = ''
  createForm.datasetId = ''
  createForm.datasetVersionId = ''
  createForm.planId = ''
  createForm.planVersionId = ''
  createForm.agentId = ''
  createForm.agentVersionId = ''
  createForm.repeatCount = 1
  datasetVersions.value = []
  planVersions.value = []
  agentVersions.value = []
  createVisible.value = true
  optionsLoading.value = true
  try {
    const [datasetPage, planPage, agentPage] = await Promise.all([
      datasetApi.listDatasets({ pageSize: 100 }),
      planApi.listPlans({ pageSize: 100 }),
      agentApi.listAgents({ pageSize: 100 }),
    ])
    datasets.value = datasetPage.items
    plans.value = planPage.items
    agents.value = agentPage.items
  } catch (error) {
    toastError(error, '加载可选数据集 / 方案 / Agent 失败')
  } finally {
    optionsLoading.value = false
  }
}

watch(
  () => createForm.datasetId,
  async (datasetId) => {
    createForm.datasetVersionId = ''
    datasetVersions.value = []
    if (!datasetId) {
      return
    }
    try {
      const page = await datasetApi.listDatasetVersions(datasetId, { pageSize: 100 })
      datasetVersions.value = page.items
    } catch (error) {
      toastError(error, '加载数据集版本失败')
    }
  },
)

watch(
  () => createForm.planId,
  async (planId) => {
    createForm.planVersionId = ''
    planVersions.value = []
    if (!planId) {
      return
    }
    try {
      const page = await planApi.listPlanVersions(planId, { pageSize: 100 })
      planVersions.value = page.items
    } catch (error) {
      toastError(error, '加载方案版本失败')
    }
  },
)

watch(
  () => createForm.agentId,
  async (agentId) => {
    createForm.agentVersionId = ''
    agentVersions.value = []
    if (!agentId) {
      return
    }
    try {
      const page = await agentApi.listAgentVersions(agentId, { pageSize: 100 })
      agentVersions.value = page.items
    } catch (error) {
      toastError(error, '加载 Agent 版本失败')
    }
  },
)

async function submitCreate(): Promise<void> {
  creating.value = true
  try {
    const created = await experimentApi.createExperiment({
      name: createForm.name.trim(),
      description: createForm.description.trim() || undefined,
      mode: 'ONLINE',
      datasetVersionId: createForm.datasetVersionId,
      evaluationPlanVersionId: createForm.planVersionId,
      config: { budget: { maxCostUsd: 1 } },
      targets: [
        {
          targetKey: 'target-1',
          displayName: 'Target 1',
          agentVersionId: createForm.agentVersionId,
          repeatCount: createForm.repeatCount,
        },
      ],
    })
    toastSuccess('实验草稿已创建')
    createVisible.value = false
    await load()
    await router.push(`/experiments/${created.id}`)
  } catch (error) {
    toastError(error, '创建实验失败')
  } finally {
    creating.value = false
  }
}

async function start(row: ExperimentView): Promise<void> {
  try {
    await experimentApi.startExperiment(row.id)
    toastSuccess('已提交，Run 槽位生成完毕')
    await load()
  } catch (error) {
    toastError(error, '提交实验失败')
  }
}
</script>

<template>
  <div class="page">
    <header class="page__header">
      <div>
        <h1 class="page__title">在线 / 离线评测</h1>
        <p class="page__subtitle">
          提交后按「Target × 样本 × 重复次数」生成 Run 槽位，由 Worker 领取执行。
          Run 执行结束进入「待评测」，评分由后续任务完成——本页不显示未产生的分数。
        </p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建实验</el-button>
    </header>

    <el-card class="page__card" shadow="never">
      <div class="page__toolbar">
        <div class="page__toolbar-group">
          <el-input
            v-model="query.keyword"
            placeholder="按名称搜索"
            clearable
            style="width: 200px"
            @keyup.enter="search"
          />
          <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px">
            <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-button :icon="Search" @click="search">查询</el-button>
          <el-button :icon="Refresh" @click="reset">重置</el-button>
        </div>
        <span class="muted">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" stripe style="width: 100%">
        <el-table-column prop="name" label="实验" min-width="220">
          <template #default="{ row }">
            <div class="cell-title">{{ row.name }}</div>
            <div class="mono muted">{{ row.id }}</div>
          </template>
        </el-table-column>
        <el-table-column label="模式" width="90">
          <template #default="{ row }">{{ row.mode === 'ONLINE' ? '在线' : '离线' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag[row.status as ExperimentStatus]" effect="light">
              {{ statusText[row.status as ExperimentStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Target" width="90">
          <template #default="{ row }">{{ row.targets.length }}</template>
        </el-table-column>
        <el-table-column prop="expectedRunCount" label="Run 数" width="90" />
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 'DRAFT'"
              link
              type="primary"
              :icon="VideoPlay"
              @click="start(row)"
            >
              提交
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有实验，点击右上角新建" />
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
          @size-change="search"
        />
      </div>
    </el-card>

    <el-dialog v-model="createVisible" title="新建在线评测实验" width="560px">
      <el-form v-loading="optionsLoading" label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" maxlength="128" placeholder="例如：Mock Runner 冒烟" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="createForm.description" type="textarea" :rows="2" maxlength="4000" />
        </el-form-item>
        <el-form-item label="数据集" required>
          <el-select v-model="createForm.datasetId" filterable placeholder="选择数据集" style="width: 100%">
            <el-option v-for="item in datasets" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据集版本" required>
          <el-select
            v-model="createForm.datasetVersionId"
            placeholder="仅列出已发布版本"
            style="width: 100%"
            :disabled="!createForm.datasetId"
          >
            <el-option
              v-for="item in publishedDatasetVersions"
              :key="item.id"
              :label="`v${item.version} · ${item.caseCount} 样本`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="评测方案" required>
          <el-select v-model="createForm.planId" filterable placeholder="选择方案" style="width: 100%">
            <el-option v-for="item in plans" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="方案版本" required>
          <el-select
            v-model="createForm.planVersionId"
            placeholder="仅列出已发布版本"
            style="width: 100%"
            :disabled="!createForm.planId"
          >
            <el-option
              v-for="item in publishedPlanVersions"
              :key="item.id"
              :label="`v${item.version} · ${item.items.length} 个算子`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="Agent" required>
          <el-select v-model="createForm.agentId" filterable placeholder="选择 Agent" style="width: 100%">
            <el-option v-for="item in agents" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="Agent 版本" required>
          <el-select
            v-model="createForm.agentVersionId"
            placeholder="仅列出已发布版本"
            style="width: 100%"
            :disabled="!createForm.agentId"
          >
            <el-option
              v-for="item in publishedAgentVersions"
              :key="item.id"
              :label="`v${item.version} · Runner ${item.runnerType}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="重复次数">
          <el-input-number v-model="createForm.repeatCount" :min="1" :max="20" />
          <span class="muted" style="margin-left: 8px">每个样本跑几次，用于观察稳定性</span>
        </el-form-item>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="创建后处于草稿状态：点「提交」才会生成 Run 槽位；提交时服务端会重新校验版本状态与数量。"
        />
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" :disabled="!canSubmit" @click="submitCreate">
          创建草稿
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cell-title {
  font-weight: 500;
}
</style>
