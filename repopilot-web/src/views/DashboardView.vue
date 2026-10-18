<script setup lang="ts">
import { computed } from 'vue'
import { Refresh } from '@element-plus/icons-vue'

import { useHealthStore } from '@/stores/health'
import type { HealthStatus } from '@/types/api'

const healthStore = useHealthStore()

const statusText: Record<HealthStatus, string> = {
  UP: '正常',
  DOWN: '不可用',
  DEGRADED: '降级',
  UNKNOWN: '未知',
}

const statusType: Record<HealthStatus, 'success' | 'danger' | 'warning' | 'info'> = {
  UP: 'success',
  DOWN: 'danger',
  DEGRADED: 'warning',
  UNKNOWN: 'info',
}

const collectedAt = computed(() =>
  healthStore.snapshot ? new Date(healthStore.snapshot.timestamp).toLocaleString('zh-CN') : '—',
)

const endpoints = [
  { name: '后端健康检查', path: '/api/health', note: '统一响应结构，含各依赖组件明细' },
  { name: '运维健康端点', path: '/actuator/health', note: '供探针/监控使用' },
  { name: 'Worker 健康检查', path: 'http://127.0.0.1:8000/health', note: 'Python FastAPI' },
  { name: 'Worker 接口文档', path: 'http://127.0.0.1:8000/docs', note: 'OpenAPI 自动生成' },
]
</script>

<template>
  <div class="page">
    <header class="page__header">
      <div>
        <h1 class="page__title">工作台</h1>
        <p class="page__subtitle">
          RepoPilot 面向 Agent 的轨迹评测与质量分析平台 · 当前为 TASK-001 基础工程骨架
        </p>
      </div>
      <el-button :icon="Refresh" :loading="healthStore.loading" @click="healthStore.refresh()">
        重新检测
      </el-button>
    </header>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="本阶段不包含任何业务数据"
      description="Agent 管理、数据集、在线/离线评测、轨迹分析、标注与实验报告将在后续 TASK 中逐步接入；此处展示的服务状态全部来自真实接口调用。"
    />

    <el-row :gutter="16" class="page__grid">
      <el-col :xs="24" :lg="15">
        <el-card shadow="never" class="card">
          <template #header>
            <div class="card__header">
              <span class="card__title">后端服务状态</span>
              <span class="card__meta">采集时间：{{ collectedAt }}</span>
            </div>
          </template>

          <el-alert
            v-if="healthStore.error"
            type="error"
            :closable="false"
            show-icon
            :title="healthStore.error"
            description="请确认 repopilot-server 已启动，且前端代理目标（VITE_API_PROXY_TARGET）指向正确端口。"
          />

          <el-descriptions
            v-if="healthStore.snapshot"
            :column="2"
            border
            size="small"
            class="status-summary"
          >
            <el-descriptions-item label="聚合状态">
              <el-tag :type="statusType[healthStore.snapshot.status]" effect="light" round>
                {{ healthStore.snapshot.status }} · {{ statusText[healthStore.snapshot.status] }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="服务">
              {{ healthStore.snapshot.application }} v{{ healthStore.snapshot.version }}
            </el-descriptions-item>
            <el-descriptions-item label="Profile">
              {{ healthStore.snapshot.profile || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="组件数">
              {{ healthStore.snapshot.components.length }}
            </el-descriptions-item>
          </el-descriptions>

          <el-table
            v-if="healthStore.snapshot"
            :data="healthStore.snapshot.components"
            size="small"
            :border="false"
            class="component-table"
          >
            <el-table-column prop="name" label="组件" width="120" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="statusType[row.status as HealthStatus]" effect="light" size="small">
                  {{ row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="必需" width="80">
              <template #default="{ row }">{{ row.required ? '是' : '否' }}</template>
            </el-table-column>
            <el-table-column prop="latencyMs" label="耗时(ms)" width="100" />
            <el-table-column prop="detail" label="说明" min-width="200" show-overflow-tooltip />
          </el-table>

          <el-empty
            v-if="!healthStore.snapshot && !healthStore.error"
            description="尚未获取到健康数据"
            :image-size="72"
          />
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="9">
        <el-card shadow="never" class="card">
          <template #header>
            <span class="card__title">开发入口</span>
          </template>
          <ul class="endpoint-list">
            <li v-for="item in endpoints" :key="item.path">
              <div class="endpoint-list__name">{{ item.name }}</div>
              <code class="endpoint-list__path">{{ item.path }}</code>
              <div class="endpoint-list__note">{{ item.note }}</div>
            </li>
          </ul>
        </el-card>

        <el-card shadow="never" class="card">
          <template #header>
            <span class="card__title">业务模块</span>
          </template>
          <el-empty description="业务功能尚未开发" :image-size="64" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.page__title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--rp-text);
}

.page__subtitle {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--rp-text-muted);
}

.page__grid {
  row-gap: 16px;
}

.card {
  border-radius: 10px;
  height: 100%;
}

.card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--rp-text);
}

.card__meta {
  font-size: 12px;
  color: var(--rp-text-muted);
}

.status-summary {
  margin-bottom: 12px;
}

.component-table {
  width: 100%;
}

.endpoint-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.endpoint-list__name {
  font-size: 13px;
  font-weight: 500;
  color: var(--rp-text);
}

.endpoint-list__path {
  display: inline-block;
  margin-top: 4px;
  padding: 2px 6px;
  border-radius: 4px;
  background: #f1f5f9;
  font-size: 12px;
  color: #0f172a;
}

.endpoint-list__note {
  margin-top: 4px;
  font-size: 12px;
  color: var(--rp-text-muted);
}
</style>
