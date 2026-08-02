import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AppLayout from '../AppLayout.vue'
import appLayoutSource from '../AppLayout.vue?raw'

const mocks = vi.hoisted(() => ({
  auth: {
    user: {
      id: 'user-1',
      username: 'leader',
      displayName: '值班组长',
      roles: ['LEADER'] as const,
      mustChangePassword: false,
    },
    signOut: vi.fn(),
  },
  router: { replace: vi.fn() },
}))

vi.mock('@/features/auth/auth.store', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('@/features/tasks/task.api', () => ({
  taskCounts: vi.fn().mockResolvedValue({ pending: 0, inProgress: 0, manualAttention: 0 }),
}))
vi.mock('../duty.api', () => ({ todayDuty: vi.fn().mockResolvedValue({ configured: false }) }))
vi.mock('@/features/notifications/NotificationToasts.vue', () => ({
  default: { template: '<div data-notification-toasts />' },
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ meta: { title: '工作台' } }),
  useRouter: () => mocks.router,
}))

function setScrollY(value: number) {
  Object.defineProperty(window, 'scrollY', { configurable: true, value })
  window.dispatchEvent(new Event('scroll'))
}

afterEach(() => {
  setScrollY(0)
  vi.clearAllMocks()
})

describe('AppLayout', () => {
  it('adds a scroll edge only after the page scrolls beyond eight pixels', async () => {
    const wrapper = mount(AppLayout, { global: { stubs: { RouterView: true } } })

    expect(wrapper.find('.topbar').classes()).not.toContain('topbar--scrolled')
    setScrollY(8)
    await nextTick()
    expect(wrapper.find('.topbar').classes()).not.toContain('topbar--scrolled')
    setScrollY(9)
    await nextTick()
    expect(wrapper.find('.topbar').classes()).toContain('topbar--scrolled')
    setScrollY(0)
    await nextTick()
    expect(wrapper.find('.topbar').classes()).not.toContain('topbar--scrolled')
  })

  it('positions the topbar as the scroll edge positioning context', () => {
    expect(appLayoutSource).toMatch(/\.topbar\s*\{[^}]*position:\s*relative;/s)
  })
})