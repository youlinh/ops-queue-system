import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { Role } from '@/features/auth/auth.types'
import RoleNavigation from '../RoleNavigation.vue'

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
})
