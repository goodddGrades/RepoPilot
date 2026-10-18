import {
  Collection,
  Cpu,
  DataAnalysis,
  Odometer,
  SetUp,
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
   * 未实现的模块显式置灰，避免点击后进入空白页造成"功能已完成"的误解。
   * 当前可用项对应 TASK-003 交付范围：工作台、Agent、数据集、评测方案、算子。
   */
  available: boolean
}

export const navigationItems: NavItem[] = [
  { path: '/dashboard', title: '工作台', icon: Odometer, available: true },
  { path: '/agents', title: 'Agent 管理', icon: Cpu, available: true },
  { path: '/datasets', title: '数据集中心', icon: Collection, available: true },
  { path: '/evaluation-plans', title: '评测方案', icon: DataAnalysis, available: true },
  { path: '/evaluators', title: '算子清单', icon: SetUp, available: true },
  { path: '/experiments', title: '在线 / 离线评测', icon: VideoPlay, available: false },
  { path: '/traces', title: '轨迹分析', icon: Share, available: false },
  { path: '/annotations', title: '标注与报告', icon: Stamp, available: false },
]
