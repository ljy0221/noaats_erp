import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/pages/Login.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: () => import('@/components/AppShell.vue'),
      children: [
        {
          path: '',
          redirect: '/dashboard',
        },
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('@/pages/Dashboard.vue'),
        },
        {
          path: 'interfaces',
          name: 'interfaces',
          component: () => import('@/pages/InterfaceList.vue'),
        },
        {
          path: 'history',
          name: 'history',
          component: () => import('@/pages/ExecutionHistory.vue'),
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/pages/NotFound.vue'),
      meta: { public: true },
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.ready) {
    await auth.bootstrap()
  }
  // M7: 새 탭 첫 방문(from.name === undefined)도 정리되도록 분기 단순화.
  // 추가 안전장치는 auth.clear에 통합되어 있다 (이중 안전).
  if (to.name === 'login') {
    sessionStorage.removeItem('sse.clientId')
  }
  const isPublic = to.meta.public === true
  if (!isPublic && !auth.authenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && auth.authenticated) {
    return { name: 'dashboard' }
  }
  return true
})
