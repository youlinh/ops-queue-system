import type { CurrentUser, Role } from '@/features/auth/auth.types'
import { useAuthStore } from '@/features/auth/auth.store'
import AppLayout from '@/layout/AppLayout.vue'
import ChangePasswordPage from '@/features/auth/ChangePasswordPage.vue'
import LoginPage from '@/features/auth/LoginPage.vue'
import ForbiddenPage from '@/pages/ForbiddenPage.vue'
import NotFoundPage from '@/pages/NotFoundPage.vue'
import PlaceholderPage from '@/pages/PlaceholderPage.vue'
import TaskCreatePage from '@/features/tasks/TaskCreatePage.vue'
import TaskQueuePage from '@/features/tasks/TaskQueuePage.vue'
import WorkspacePage from '@/features/workspace/WorkspacePage.vue'
import RosterImportPage from '@/features/roster/RosterImportPage.vue'
import PeoplePage from '@/features/people/PeoplePage.vue'
import ReportingPage from '@/features/reporting/ReportingPage.vue'
import AuditLogPage from '@/features/audit/AuditLogPage.vue'
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
    taskSheet?: boolean
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
      {
        path: 'workspace',
        component: WorkspacePage,
        meta: {
          title: '工作台',
          roles: ['DEVELOPER', 'OPERATOR', 'LEADER'],
        },
      },
      {
        path: 'tasks/new',
        component: TaskCreatePage,
        meta: { title: '我要取号', roles: ['DEVELOPER'] },
      },
      {
        path: 'tasks',
        name: 'tasks',
        component: TaskQueuePage,
        meta: {
          title: '任务中心',
          roles: ['DEVELOPER', 'OPERATOR', 'LEADER'],
        },
      },
      {
        path: 'tasks/:id',
        name: 'task-detail',
        component: TaskQueuePage,
        props: { dashboard: false },
        meta: {
          taskSheet: true,
          title: '任务详情',
          roles: ['DEVELOPER', 'OPERATOR', 'LEADER'],
        },
      },
      {
        path: 'rosters',
        component: RosterImportPage,
        meta: { title: '值班管理', roles: ['LEADER'] },
      },
      {
        path: 'people',
        component: PeoplePage,
        meta: { title: '人员与可用性', roles: ['LEADER'] },
      },
      {
        path: 'reports',
        component: ReportingPage,
        meta: { title: '统计', roles: ['OPERATOR', 'LEADER'] },
      },
      {
        path: 'audit',
        component: AuditLogPage,
        meta: { title: '审计日志', roles: ['LEADER'] },
      },
      {
        path: '403',
        component: ForbiddenPage,
        meta: { title: '无权限' },
      },
      {
        path: '404',
        component: NotFoundPage,
        meta: { title: '页面未找到' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/404' },
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
