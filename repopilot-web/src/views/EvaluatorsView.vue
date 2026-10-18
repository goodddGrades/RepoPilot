<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'

import * as planApi from '@/api/plans'
import type { EvaluatorType, EvaluatorView } from '@/types/domain'
import { formatTime, stringifyJson, toastError } from '@/utils/feedback'

const loading = ref(false)
const rows = ref<EvaluatorView[]>([])
const total = ref(0)
const query = reactive({
  page: 1,
  pageSize: 20,
  key: '',
  type: '' as EvaluatorType | '',
  status: '',
})

const typeOptions: EvaluatorType[] = ['RULE', 'TRACE', 'COST', 'TEST', 'LLM_JUDGE']

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await planApi.listEvaluators({
      page: query.page,
      pageSize: query.pageSize,
      key: query.key || undefined,
      type: query.type || undefined,
      status: query.status || undefined,
    })
    rows.value = page.items
    total.value = page.total
  } catch (error) {
    toastError(error, '加载算子清单失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

const statusTag: Record<string, 'info' | 'success' | 'warning'> = {
  DRAFT: 'info',
  PUBLISHED: 'success',
  ARCHIVED: 'warning',
}

const typeTag: Record<string, 'primary' | 'success' | 'warning' | 'info' | 'danger'> = {
  RULE: 'primary',
  TRACE: 'info',
  COST: 'warning',
  TEST: 'success',
  LLM_JUDGE: 'danger',
}

function search(): void {
  query.page = 1
  void load()
}

function reset(): void {
  query.key = ''
  query.type = ''
  query.status = ''
  query.page = 1
  void load()
}
</script>

<template>
  <div class="page">
    <header class="page__header">
      <div>
        <h1 class="page__title">算子清单</h1>
        <p class="page__subtitle">
          算子定义随代码版本管理，启动时由注册清单幂等导入，只读。修改算子契约必须发布新版本。
        </p>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </header>

    <el-card class="page__card" shadow="never">
      <div class="page__toolbar">
        <div class="page__toolbar-group">
          <el-input
            v-model="query.key"
            placeholder="算子 key（精确匹配）"
            clearable
            style="width: 220px"
            @keyup.enter="search"
          />
          <el-select v-model="query.type" placeholder="全部类型" clearable style="width: 150px">
            <el-option v-for="item in typeOptions" :key="item" :label="item" :value="item" />
          </el-select>
          <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px">
            <el-option label="DRAFT" value="DRAFT" />
            <el-option label="PUBLISHED" value="PUBLISHED" />
            <el-option label="ARCHIVED" value="ARCHIVED" />
          </el-select>
          <el-button :icon="Search" @click="search">查询</el-button>
          <el-button :icon="Refresh" @click="reset">重置</el-button>
        </div>
        <span class="muted">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" stripe style="width: 100%">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="schema-grid">
              <div>
                <div class="muted">inputSchema</div>
                <pre class="json-block">{{ stringifyJson(row.inputSchema) }}</pre>
              </div>
              <div>
                <div class="muted">outputSchema</div>
                <pre class="json-block">{{ stringifyJson(row.outputSchema) }}</pre>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="key" min-width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.evaluatorKey }}</span>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="80">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column prop="displayName" label="名称" min-width="160" />
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-tag :type="typeTag[row.evaluatorType]" size="small">{{ row.evaluatorType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="260" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag[row.status]" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" width="180">
          <template #default="{ row }">{{ formatTime(row.publishedAt) }}</template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有匹配的算子" />
        </template>
      </el-table>

      <div class="page__pagination">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.schema-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding: 8px 16px 16px;
}
</style>
