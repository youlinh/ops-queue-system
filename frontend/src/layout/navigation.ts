import type { Role } from '@/features/auth/auth.types'
import type { AppIconName } from '@/components/ui/icons'

export interface NavigationItem {
  label: string
  to: string
  roles: Role[]
  icon: AppIconName
}

export const navigationItems: NavigationItem[] = [
  { label: '工作台', to: '/workspace', roles: ['DEVELOPER', 'OPERATOR', 'LEADER'], icon: 'workspace' },
  { label: '我要取号', to: '/tasks/new', roles: ['DEVELOPER'], icon: 'create' },
  { label: '任务中心', to: '/tasks', roles: ['DEVELOPER', 'OPERATOR', 'LEADER'], icon: 'tasks' },
  { label: '值班管理', to: '/rosters', roles: ['LEADER'], icon: 'roster' },
  { label: '人员与可用性', to: '/people', roles: ['LEADER'], icon: 'people' },
  { label: '统计', to: '/reports', roles: ['OPERATOR', 'LEADER'], icon: 'reports' },
  { label: '审计日志', to: '/audit', roles: ['LEADER'], icon: 'audit' },
]

export function navigationFor(roles: readonly Role[]): NavigationItem[] {
  const granted = new Set(roles)
  return navigationItems.filter((item) => item.roles.some((role) => granted.has(role)))
}

export function mobileNavigationFor(roles: readonly Role[]) {
  const granted = navigationFor(roles)
  const directTarget = roles.includes('DEVELOPER')
    ? '/tasks/new'
    : roles.includes('LEADER') ? '/people' : '/reports'
  const directPaths = ['/workspace', '/tasks', directTarget]

  return {
    primary: directPaths.map(path => granted.find(item => item.to === path)).filter(Boolean) as NavigationItem[],
    overflow: granted.filter(item => !directPaths.includes(item.to)),
  }
}