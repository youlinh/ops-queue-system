import type { Role } from '@/features/auth/auth.types'

export interface NavigationItem {
  label: string
  to: string
  roles: Role[]
  icon: string
}

export const navigationItems: NavigationItem[] = [
  {
    label: '工作台',
    to: '/workspace',
    roles: ['DEVELOPER', 'OPERATOR', 'LEADER'],
    icon: '⌂',
  },
  {
    label: '我要取号',
    to: '/tasks/new',
    roles: ['DEVELOPER'],
    icon: '+',
  },
  {
    label: '任务中心',
    to: '/tasks',
    roles: ['DEVELOPER', 'OPERATOR', 'LEADER'],
    icon: '≡',
  },
  {
    label: '值班管理',
    to: '/rosters',
    roles: ['LEADER'],
    icon: '日',
  },
  {
    label: '人员与可用性',
    to: '/people',
    roles: ['LEADER'],
    icon: '人',
  },
  {
    label: '统计',
    to: '/reports',
    roles: ['OPERATOR', 'LEADER'],
    icon: '▥',
  },
  {
    label: '审计日志',
    to: '/audit',
    roles: ['LEADER'],
    icon: '✓',
  },
]

export function navigationFor(roles: readonly Role[]): NavigationItem[] {
  const granted = new Set(roles)
  return navigationItems.filter((item) =>
    item.roles.some((role) => granted.has(role)),
  )
}
