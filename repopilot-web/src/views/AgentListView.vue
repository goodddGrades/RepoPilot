<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'

import * as agentApi from '@/api/agents'
import type { AgentType, AgentVersionView, AgentView, RunnerType } from '@/types/domain'
import { formatTime, parseJsonText, shortHash, stringifyJson, toastError, toastSuccess } from '@/utils/feedback'

const loading = ref(false)
const rows = ref<AgentView[]>([])
const total = ref(0)
const query = reactive({ page: 1, pageSize: 10, keyword: '', status: '' })

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await agentApi.listAgents({
      page: query.page,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
      status: query.status || undefined,
    })
    rows.value = page.items
    total.value = page.total
  } catch (error) {
    toastError(error, '加载 Agent 列表失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

/* ---------------------------------------------------------------- 新建 Agent */

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({
  code: '',
  name: '',
  description: '',
  agentType: 'MOCK' as AgentType,
})

function openCreate(): void {
  createForm.code = ''
  createForm.name = ''
  createForm.description = ''
  createForm.agentType = 'MOCK'
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  creating.value = true
  try {
    await agentApi.createAgent({
      code: createForm.code.trim(),
      name: createForm.name.trim(),
      description: createForm.description.trim() || undefined,
      agentType: createForm.agentType,
    })
    toastSuccess('Agent 已创建')
    createVisible.value = false
    await load()
  } catch (error) {
    toastError(error, '创建 Agent 失败')
  } finally {
    creating.value = false
  }
}

/* ---------------------------------------------------------------- 版本抽屉 */

const versionVisible = ref(false)
const versionLoading = ref(false)
const currentAgent = ref<AgentView | null>(null)
const versions = ref<AgentVersionView[]>([])
const versionForm = reactive({
  runnerType: 'MOCK' as RunnerType,
  configText: '{\n  "model": "mock-1"\n}',
})

async function openVersions(agent: AgentView): Promise<void> {
  currentAgent.value = agent
  versionVisible.value = true
  versionForm.runnerType = 'MOCK'
  versionForm.configText = '{\n  "model": "mock-1"\n}'
  await loadVersions()
}

async function loadVersions(): Promise<void> {
  if (!currentAgent.value) {
    return
  }
  versionLoading.value = true
  try {
    const page = await agentApi.listAgentVersions(currentAgent.value.id, { pageSize: 100 })
    versions.value = page.items
  } catch (error) {
    toastError(error, '加载版本失败')
  } finally {
    versionLoading.value = false
  }
}

async function submitVersion(): Promise<void> {
  if (!currentAgent.value) {
    return
  }
  let config: unknown
  try {
    config = parseJsonText(versionForm.configText, 'config')
  } catch (error) {
    toastError(error)
    return
  }
  try {
    await agentApi.createAgentVersion(currentAgent.value.id, {
      runnerType: versionForm.runnerType,
      config: config ?? {},
    })
    toastSuccess('版本已创建（草稿）')
    await loadVersions()
  } catch (error) {
    toastError(error, '创建版本失败')
  }
}

async function publish(version: AgentVersionView): Promise<void> {
  try {
    await agentApi.publishAgentVersion(version.id)
    toastSuccess(`v${version.version} 已发布`)
    await loadVersions()
  } catch (error) {
    toastError(error, '发布失败')
  }
}

async function archive(version: AgentVersionView): Promise<void> {
  try {
    await agentApi.archiveAgentVersion(version.id)
    toastSuccess(`v${version.version} 已归档`)
    await loadVersions()
  } catch (error) {
    toastError(error, '归档失败')
  }
}

async function archiveAgent(agent: AgentView): Promise<void> {
  try {
    await agentApi.patchAgent(agent.id, { status: 'ARCHIVED' })
    toastSuccess('Agent 已归档')
    await load()
  } catch (error) {
    toastError(error, '归档失败')
  }
}

/* ---------------------------------------------------------------- 展示辅助 */

const lifecycleTag: Record<string, 'success' | 'info'> = {
  ACTIVE: 'success',
  ARCHIVED: 'info',
}

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

function search(): void {
  query.page = 1
  void load()
}

function reset(): void {
  query.keyword = ''
  query.status = ''
  query.page = 1
  void load()
}
</script>

<template>
  <div class="page">
    <header class="page__header">
      <div>
        <h1 class="page__title">Agent 管理</h1>
        <p class="page__subtitle">
          管理 Agent 身份与配置版本。草稿可编辑，发布后不可修改；归档后不能再创建新版本。
        </p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建 Agent</el-button>
    </header>

    <el-card class="page__card" shadow="never">
      <div class="page__toolbar">
        <div class="page__toolbar-group">
          <el-input
            v-model="query.keyword"
            placeholder="按 code 或名称搜索"
            clearable
            style="width: 220px"
            @keyup.enter="search"
          />
          <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px">
            <el-option label="ACTIVE" value="ACTIVE" />
            <el-option label="ARCHIVED" value="ARCHIVED" />
          </el-select>
          <el-button :icon="Search" @click="search">查询</el-button>
          <el-button :icon="Refresh" @click="reset">重置</el-button>
        </div>
        <span class="muted">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" stripe style="width: 100%">
        <el-table-column prop="code" label="code" min-width="180">
          <template #default="{ row }">
            <span class="mono">{{ row.code }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="agentType" label="类型" width="110" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="lifecycleTag[row.status]" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openVersions(row)">版本</el-button>
            <el-button
              link
              type="danger"
              :disabled="row.status === 'ARCHIVED'"
              @click="archiveAgent(row)"
            >
              归档
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有 Agent，点击右上角新建" />
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

    <el-dialog v-model="createVisible" title="新建 Agent" width="480px">
      <el-form label-width="96px">
        <el-form-item label="code" required>
          <el-input v-model="createForm.code" placeholder="仅字母、数字、点、下划线、连字符" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="createForm.agentType" style="width: 100%">
            <el-option label="MOCK（模拟执行）" value="MOCK" />
            <el-option label="CODING（代码任务）" value="CODING" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="createForm.description" type="textarea" :rows="3" maxlength="4000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="versionVisible" size="720px" :title="`配置版本 · ${currentAgent?.name ?? ''}`">
      <el-card shadow="never" class="drawer__section">
        <template #header>
          <strong>新建配置版本</strong>
          <span class="muted" style="margin-left: 8px">版本号由后端分配，新建即为草稿</span>
        </template>
        <el-form label-width="96px">
          <el-form-item label="runnerType">
            <el-select v-model="versionForm.runnerType" style="width: 220px">
              <el-option label="MOCK" value="MOCK" />
              <el-option label="CODEX" value="CODEX" />
              <el-option label="CUSTOM" value="CUSTOM" />
            </el-select>
          </el-form-item>
          <el-form-item label="config">
            <el-input v-model="versionForm.configText" type="textarea" :rows="6" class="mono" />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              :disabled="currentAgent?.status === 'ARCHIVED'"
              @click="submitVersion"
            >
              创建版本
            </el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-table v-loading="versionLoading" :data="versions" stripe style="width: 100%">
        <el-table-column type="expand">
          <template #default="{ row }">
            <pre class="json-block">{{ stringifyJson(row.config) }}</pre>
          </template>
        </el-table-column>
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
        <el-table-column label="配置摘要" min-width="150">
          <template #default="{ row }">
            <span class="mono">{{ shortHash(row.configHash) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" width="170">
          <template #default="{ row }">{{ formatTime(row.publishedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="publish(row)">
              发布
            </el-button>
            <el-button v-if="row.status !== 'ARCHIVED'" link type="danger" @click="archive(row)">
              归档
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>
