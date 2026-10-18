import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { fetchHealth } from '@/api/health'
import { ApiError } from '@/api/http'
import type { HealthSnapshot } from '@/types/api'

/**
 * 后端健康状态。
 *
 * 数据全部来自真实 HTTP 调用，不做任何本地模拟；请求失败时保留错误信息，
 * 页面据此展示“后端不可达”，而不是显示一个假的正常状态。
 */
export const useHealthStore = defineStore('health', () => {
  const snapshot = ref<HealthSnapshot | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const lastUpdatedAt = ref<Date | null>(null)

  const overallStatus = computed(() => snapshot.value?.status ?? 'UNKNOWN')

  async function refresh(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      snapshot.value = await fetchHealth()
      lastUpdatedAt.value = new Date()
    } catch (e) {
      snapshot.value = null
      error.value =
        e instanceof ApiError ? `${e.code} · ${e.message}` : `未知错误：${String(e)}`
    } finally {
      loading.value = false
    }
  }

  return { snapshot, loading, error, lastUpdatedAt, overallStatus, refresh }
})
