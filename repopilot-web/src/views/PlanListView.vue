<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'

import * as planApi from '@/api/plans'
import type { EvaluationPlanView } from '@/types/domain'
import { formatTime, toastError, toastSuccess } from '@/utils/feedback'

const router = useRouter()

const loading = ref(false)
const rows = ref<EvaluationPlanView[]>([])
const total = ref(0)
const query = reactive({ page: 1, pageSize: 10, keyword: '' })

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await planApi.listPlans({
      page: query.page,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
    })
    rows.value = page.items
    total.value = page.total
  } catch (error) {
    toastError(error, '加载评测方案失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ code: '', name: '', description: '' })

function openCreate(): void {
  createForm.code = ''
  createForm.name = ''
  createForm.description = ''
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  creating.value = true
  try {
    const created = await planApi.createPlan({
      code: createForm.code.trim(),
      name: createForm.name.trim(),
      description: createForm.description.trim() || undefined,
    })
    toastSuccess('评测方案已创建')
    createVisible.value = false
    await load()
    await router.push(`/evaluation-plans/${created.id}`)
  } catch (error) {
    toastError(error, '创建评测方案失败')
  } finally {
    creating.value = false
  }
}

function search(): void {
  query.page = 1
  void load()
}

function reset(): void {
  query.keyword = ''
  query.page = 1
  void load()
}

function openDetail(row: EvaluationPlanView): void {
  void router.push(`/evaluation-plans/${row.id}`)
}
</script>

<template>
  <div class="page">
    <header class="page__header">
      <div>
        <h1 class="page__title">评测方案</h1>
        <p class="page__subtitle">
          方案版本固定"按顺序执行哪些算子、各自参数与是否必需"。发布后不可修改，历史批次靠它解释评分来源。
        </p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建方案</el-button>
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
          <el-button :icon="Search" @click="search">查询</el-button>
          <el-button :icon="Refresh" @click="reset">重置</el-button>
        </div>
        <span class="muted">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" stripe style="width: 100%">
        <el-table-column prop="code" label="code" min-width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.code }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column prop="description" label="说明" min-width="240" show-overflow-tooltip />
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">版本与算子</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有评测方案，点击右上角新建" />
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

    <el-dialog v-model="createVisible" title="新建评测方案" width="480px">
      <el-form label-width="96px">
        <el-form-item label="code" required>
          <el-input v-model="createForm.code" placeholder="仅字母、数字、点、下划线、连字符" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" maxlength="128" />
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
  </div>
</template>
