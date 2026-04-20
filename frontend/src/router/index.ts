import { createRouter, createWebHistory, type RouteLocationNormalized } from 'vue-router'
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
          redirect: '/interfaces',
        },
        {
          path: 'interfaces',
          name: 'interfaces',
          component: () => import('@/pages/InterfaceList.vue'),
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

router.beforeEach(async (to: RouteLocationNormalized) => {
  const auth = useAuthStore()
  if (!auth.ready) {
    await auth.bootstrap()
  }
  const isPublic = to.meta.public === true
  if (!isPublic && !auth.authenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && auth.authenticated) {
    return { name: 'interfaces' }
  }
  return true
})
