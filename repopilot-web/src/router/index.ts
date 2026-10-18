import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import DefaultLayout from '@/layouts/DefaultLayout.vue'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: DefaultLayout,
    children: [
      {
        path: '',
        redirect: '/dashboard',
      },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '工作台' },
      },
      {
        path: 'agents',
        name: 'agents',
        component: () => import('@/views/AgentListView.vue'),
        meta: { title: 'Agent 管理' },
      },
      {
        path: 'datasets',
        name: 'datasets',
        component: () => import('@/views/DatasetListView.vue'),
        meta: { title: '数据集中心' },
      },
      {
        path: 'datasets/:id',
        name: 'dataset-detail',
        component: () => import('@/views/DatasetDetailView.vue'),
        meta: { title: '数据集详情' },
      },
      {
        path: 'evaluation-plans',
        name: 'evaluation-plans',
        component: () => import('@/views/PlanListView.vue'),
        meta: { title: '评测方案' },
      },
      {
        path: 'evaluation-plans/:id',
        name: 'evaluation-plan-detail',
        component: () => import('@/views/PlanDetailView.vue'),
        meta: { title: '方案详情' },
      },
      {
        path: 'evaluators',
        name: 'evaluators',
        component: () => import('@/views/EvaluatorsView.vue'),
        meta: { title: '算子清单' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · RepoPilot` : 'RepoPilot'
})

export default router
