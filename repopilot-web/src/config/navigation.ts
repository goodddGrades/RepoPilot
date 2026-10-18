import {
  Collection,
  Cpu,
  DataAnalysis,
  Odometer,
  Share,
  Stamp,
  VideoPlay,
} from '@element-plus/icons-vue'
import type { Component } from 'vue'

export interface NavItem {
  /** 路由路径 */
  path: string
  /** 菜单标题 */
  title: string
  /** 菜单图标 */
  icon: Component
  /**
   * 是否已实现。
   *
   * V1（TASK-001）只交付工作台骨架，其余模块的路由与后端接口尚不存在，
   * 因此菜单项显式置灰，避免点击后进入空白页造成“功能已完成”的误解。
   */
  available: boolean
}

export const navigationItems: NavItem[] = [
  { path: '/dashboard', title: '工作台', icon: Odometer, available: true },
  { path: '/agents', title: 'Agent 管理', icon: Cpu, available: false },
  { path: '/datasets', title: '数据集中心', icon: Collection, available: false },
  { path: '/experiments', title: '在线 / 离线评测', icon: VideoPlay, available: false },
  { path: '/traces', title: '轨迹分析', icon: Share, available: false },
  { path: '/evaluations', title: '评测中心', icon: DataAnalysis, available: false },
  { path: '/annotations', title: '标注与报告', icon: Stamp, available: false },
]
