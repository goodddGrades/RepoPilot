<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Expand, Fold, Refresh } from '@element-plus/icons-vue'

import { navigationItems } from '@/config/navigation'
import { useAppStore } from '@/stores/app'
import { useHealthStore } from '@/stores/health'

const route = useRoute()
const appStore = useAppStore()
const healthStore = useHealthStore()

const currentTitle = computed(() => (route.meta.title as string | undefined) ?? 'RepoPilot')

const statusTagType = computed(() => {
  switch (healthStore.overallStatus) {
    case 'UP':
      return 'success'
    case 'DEGRADED':
      return 'warning'
    case 'DOWN':
      return 'danger'
    default:
      return 'info'
  }
})

const statusLabel = computed(() => {
  if (healthStore.loading) {
    return '检测中'
  }
  if (healthStore.error) {
    return '后端不可达'
  }
  return healthStore.overallStatus
})

onMounted(() => {
  void healthStore.refresh()
})
</script>

<template>
  <el-container class="layout">
    <el-aside :width="appStore.sidebarCollapsed ? '64px' : '220px'" class="layout__aside">
      <div class="brand">
        <span class="brand__mark">RP</span>
        <div v-if="!appStore.sidebarCollapsed" class="brand__text">
          <strong>RepoPilot</strong>
          <small>Agent 轨迹评测平台</small>
        </div>
      </div>

      <el-menu
        :default-active="route.path"
        :collapse="appStore.sidebarCollapsed"
        :collapse-transition="false"
        router
        class="layout__menu"
      >
        <el-menu-item
          v-for="item in navigationItems"
          :key="item.path"
          :index="item.path"
          :disabled="!item.available"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>
            <span class="menu-item__title">{{ item.title }}</span>
            <span v-if="!item.available" class="menu-item__badge">规划中</span>
          </template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header height="56px" class="layout__header">
        <div class="header__left">
          <el-button
            text
            :icon="appStore.sidebarCollapsed ? Expand : Fold"
            class="header__toggle"
            @click="appStore.toggleSidebar()"
          />
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>RepoPilot</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="header__right">
          <span v-if="healthStore.snapshot" class="header__meta">
            {{ healthStore.snapshot.application }} · v{{ healthStore.snapshot.version }} ·
            {{ healthStore.snapshot.profile }}
          </span>
          <el-tag :type="statusTagType" effect="light" round>{{ statusLabel }}</el-tag>
          <el-button
            text
            :icon="Refresh"
            :loading="healthStore.loading"
            @click="healthStore.refresh()"
          >
            刷新
          </el-button>
        </div>
      </el-header>

      <el-main class="layout__main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
  background: var(--rp-bg);
}

.layout__aside {
  transition: width 0.2s ease;
  background: #101a2c;
  color: #e6edf7;
  overflow-x: hidden;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 56px;
  padding: 0 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.brand__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #3b82f6, #22d3ee);
  font-weight: 700;
  font-size: 13px;
  color: #0b1220;
  flex: none;
}

.brand__text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
  white-space: nowrap;
}

.brand__text strong {
  font-size: 15px;
  color: #f8fafc;
}

.brand__text small {
  font-size: 11px;
  color: #8ba0bd;
}

.layout__menu {
  border-right: none;
  background: transparent;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #c3d0e3;
  --el-menu-hover-bg-color: rgba(59, 130, 246, 0.16);
  --el-menu-active-color: #93c5fd;
}

.menu-item__title {
  margin-right: 6px;
}

.menu-item__badge {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 6px;
  color: #8ba0bd;
  border: 1px solid rgba(139, 160, 189, 0.4);
}

.layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #ffffff;
  border-bottom: 1px solid var(--rp-border);
  padding: 0 16px;
}

.header__left,
.header__right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header__toggle {
  padding: 4px;
}

.header__meta {
  font-size: 12px;
  color: var(--rp-text-muted);
}

.layout__main {
  padding: 20px;
  overflow-y: auto;
}
</style>
