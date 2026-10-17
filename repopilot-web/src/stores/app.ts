import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 应用级 UI 状态（侧边栏折叠等），与业务数据无关。 */
export const useAppStore = defineStore('app', () => {
  const sidebarCollapsed = ref(false)

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  function setSidebarCollapsed(value: boolean): void {
    sidebarCollapsed.value = value
  }

  return { sidebarCollapsed, toggleSidebar, setSidebarCollapsed }
})
