import { describe, expect, it } from 'vitest'
import type { CurrentUser } from '@/features/auth/auth.types'
import { decideNavigation, routes } from '../router'

const operator: CurrentUser = {
  id: '00000000-0000-0000-0000-000000000001',
  username: 'operator',
  displayName: '鍊肩彮杩愮淮',
  roles: ['OPERATOR'],
  mustChangePassword: false,
}

describe('route authorization', () => {
  it('renders the task list beneath the named route-backed detail sheet', () => {
    const shell = routes.find(route => route.path === '/')!
    const detail = shell.children!.find(route => route.path === 'tasks/:id')!

    expect(detail.name).toBe('task-detail')
    expect(detail.meta?.taskSheet).toBe(true)
  })

  it('preserves a protected destination through login', () => {
    expect(decideNavigation(
      { path: '/tasks', fullPath: '/tasks?status=PENDING', meta: {} },
      null,
    )).toEqual({
      path: '/login',
      query: { redirect: '/tasks?status=PENDING' },
    })
  })

  it('forces initial password change before business routes', () => {
    expect(decideNavigation(
      { path: '/tasks', fullPath: '/tasks', meta: {} },
      { ...operator, mustChangePassword: true },
    )).toBe('/change-password')
  })

  it('redirects an authenticated user away from login', () => {
    expect(decideNavigation(
      { path: '/login', fullPath: '/login', meta: { public: true } },
      operator,
    )).toBe('/workspace')
  })

  it('denies routes outside the current role set', () => {
    expect(decideNavigation(
      {
        path: '/rosters',
        fullPath: '/rosters',
        meta: { roles: ['LEADER'] },
      },
      operator,
    )).toBe('/403')
  })
})
