import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { Role } from '@/features/auth/auth.types'
import RoleNavigation from '../RoleNavigation.vue'
import roleNavigationSource from '../RoleNavigation.vue?raw'
import { mobileNavigationFor } from '../navigation'

describe('RoleNavigation', () => {
  it.each([
    [['DEVELOPER'], ['工作台', '我要取号', '任务中心'], ['值班管理', '统计']],
    [['OPERATOR'], ['工作台', '任务中心', '统计'], ['我要取号', '审计日志']],
    [['LEADER'], ['工作台', '任务中心', '值班管理', '人员与可用性', '统计', '审计日志'], ['我要取号']],
  ])('shows the exact navigation for %s', (roles, visible, hidden) => {
    const wrapper = mount(RoleNavigation, {
      props: { roles: roles as Role[] },
    })

    for (const label of visible) {
      expect(wrapper.text()).toContain(label)
    }
    for (const label of hidden) {
      expect(wrapper.text()).not.toContain(label)
    }
  })

  it('unions multi-role navigation without duplicate links', () => {
    const wrapper = mount(RoleNavigation, {
      props: { roles: ['DEVELOPER', 'OPERATOR', 'LEADER'] },
    })

    const labels = wrapper.findAll('[data-nav-label]').map((item) => item.text())
    expect(labels).toEqual([
      '工作台',
      '我要取号',
      '任务中心',
      '值班管理',
      '人员与可用性',
      '统计',
      '审计日志',
    ])
    expect(new Set(labels).size).toBe(labels.length)
  })

  it('maps leader navigation to three direct items and More overflow', () => {
    const result = mobileNavigationFor(['LEADER'])
    expect(result.primary.map(item => item.to)).toEqual(['/workspace', '/tasks', '/people'])
    expect(result.overflow.map(item => item.to)).toEqual(['/rosters', '/reports', '/audit'])
  })

  it('gives combined roles the developer create action before leader actions', () => {
    const result = mobileNavigationFor(['DEVELOPER', 'OPERATOR', 'LEADER'])
    expect(result.primary.map(item => item.to)).toEqual(['/workspace', '/tasks', '/tasks/new'])
    expect(result.overflow.map(item => item.to)).toEqual(['/rosters', '/people', '/reports', '/audit'])
  })

  it('resets mobile navigation padding and gap before reserving the safe area', () => {
    expect(roleNavigationSource).toMatch(/\.role-navigation--mobile\s*\{[^}]*padding:\s*0;[^}]*gap:\s*0;[^}]*padding-bottom:\s*env\(safe-area-inset-bottom\);/s)
  })
})