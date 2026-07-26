import type { CurrentUser, Role } from '@/features/auth/auth.types'
import { useAuthStore } from '@/features/auth/auth.store'
import AppLayout from '@/layout/AppLayout.vue'
import ChangePasswordPage from '@/features/auth/ChangePasswordPage.vue'
import LoginPage from '@/features/auth/LoginPage.vue'
import ForbiddenPage from '@/pages/ForbiddenPage.vue'
import PlaceholderPage from '@/pages/PlaceholderPage.vue'
import {
  createRouter,
  createWebHistory,
  type RouteLocationNormalized,
  type RouteRecordRaw,
} from 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean
    roles?: Role[]
    title?: string
  }
}

type NavigationTarget = Pick<
  RouteLocationNormalized,
  'path' | 'fullPath' | 'meta'
>

export function decideNavigation(
  to: NavigationTarget,
  user: CurrentUser | null,
): true | string | { path: string; query: { redirect: string } } {
  if (!user) {
    if (to.meta.public) {
      return true
    }
    return {
      path: '/login',
      query: { redirect: to.fullPath },
    }
  }
  if (user.mustChangePassword && to.path !== '/change-password') {
    return '/change-password'
  }
  if (!user.mustChangePassword && to.path === '/change-password') {
    return '/workspace'
  }
  if (to.path === '/login') {
    return '/workspace'
  }
  if (to.meta.roles?.length
      && !to.meta.roles.some((role) => user.roles.includes(role))) {
    return '/403'
  }
  return true
}

const placeholders = (
  path: string,
  title: string,
  roles: Role[],
): RouteRecordRaw => ({
  path,
  component: PlaceholderPage,
  props: { title },
  meta: { title, roles },
})

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    component: LoginPage,
    meta: { public: true, title: '登录' },
  },
  {
    path: '/change-password',
    component: ChangePasswordPage,
    meta: { title: '修改初始密码' },
  },
  {
    path: '/',
    component: AppLayout,
    children: [
      { path: '', redirect: '/workspace' },
      placeholders(
        'workspace',
        '工作台',
        ['DEVELOPER', 'OPERATOR', 'LEADER'],
      ),
      placeholders('tasks/new', '我要取号', ['DEVELOPER']),
      placeholders(
        'tasks',
        '任务中心',
        ['DEVELOPER', 'OPERATOR', 'LEADER'],
      ),
      placeholders('rosters', '值班管理', ['LEADER']),
      placeholders('people', '人员与可用性', ['LEADER']),
      placeholders('reports', '统计', ['OPERATOR', 'LEADER']),
      placeholders('audit', '审计日志', ['LEADER']),
      {
        path: '403',
        component: ForbiddenPage,
        meta: { title: '无权限' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/workspace' },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.restored) {
    try {
      await auth.restore()
    } catch {
      if (!to.meta.public) {
        return {
          path: '/login',
          query: { redirect: to.fullPath },
        }
      }
    }
  }
  return decideNavigation(to, auth.user)
})

router.afterEach((to) => {
  document.title = to.meta.title
    ? `${to.meta.title} · 运维叫号台`
    : '运维叫号台'
})
