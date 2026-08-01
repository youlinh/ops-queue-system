import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import NotificationToasts from '../NotificationToasts.vue'
import * as api from '../notifications.api'

vi.mock('../notifications.api', () => ({
  claimNotifications: vi.fn(),
}))

const claimNotifications = vi.mocked(api.claimNotifications)

function mountToasts(props: {
  initialDelayMs: number
  pollIntervalMs: number
  dismissAfterMs: number
}) {
  // TransitionGroup keeps leaving nodes in the DOM under jsdom (no
  // transitionend events), so stub it for assertions on removal.
  return mount(NotificationToasts, {
    props,
    global: { stubs: { 'transition-group': true } },
  })
}

function claimed(id: string, ticket: string): api.ClaimedNotification {
  return {
    id,
    eventType: 'TASK_CALLED',
    payload: {
      ticketNumber: ticket,
      systemName: '计费系统',
      calledAt: '2026-07-25T13:00:00Z',
    },
    createdAt: '2026-07-25T13:00:00Z',
  }
}

describe('NotificationToasts', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    claimNotifications.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('polls after the initial delay and renders claimed events', async () => {
    claimNotifications.mockResolvedValueOnce([claimed('n1', 'OPS-20260725-0001')])
    const wrapper = mountToasts({ initialDelayMs: 1000, pollIntervalMs: 5000, dismissAfterMs: 8000 })

    expect(claimNotifications).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()

    expect(claimNotifications).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('OPS-20260725-0001 已开始处理')
    expect(wrapper.text()).toContain('计费系统')
    expect(wrapper.text()).toContain('21:00 叫号')
    wrapper.unmount()
  })

  it('keeps polling on the interval and auto-dismisses toasts', async () => {
    claimNotifications
      .mockResolvedValueOnce([claimed('n1', 'OPS-1')])
      .mockResolvedValue([])
    const wrapper = mountToasts({ initialDelayMs: 1000, pollIntervalMs: 5000, dismissAfterMs: 3000 })

    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(wrapper.text()).toContain('OPS-1 已开始处理')

    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(wrapper.text()).not.toContain('OPS-1')

    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(claimNotifications).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('dismisses a toast when its close button is clicked', async () => {
    claimNotifications.mockResolvedValueOnce([claimed('n1', 'OPS-9')])
    const wrapper = mountToasts({ initialDelayMs: 1000, pollIntervalMs: 5000, dismissAfterMs: 60000 })

    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(wrapper.text()).toContain('OPS-9')

    await wrapper.get('button[aria-label="关闭提醒"]').trigger('click')
    expect(wrapper.text()).not.toContain('OPS-9')
    wrapper.unmount()
  })

  it('exposes status semantics and auto-dismisses even when reduced motion is preferred', async () => {
    vi.stubGlobal('matchMedia', vi.fn().mockImplementation((query: string) => ({
      matches: query === '(prefers-reduced-motion: reduce)',
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })))
    claimNotifications.mockResolvedValueOnce([claimed('n-reduced', 'OPS-REDUCED')])
    const wrapper = mountToasts({ initialDelayMs: 1000, pollIntervalMs: 5000, dismissAfterMs: 3000 })

    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()

    const toast = wrapper.get('[role="status"]')
    expect(toast.attributes('aria-label')).toContain('OPS-REDUCED')
    expect(wrapper.find('button[aria-label]').exists()).toBe(true)

    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('survives claim failures and retries on the next tick', async () => {
    claimNotifications
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce([claimed('n2', 'OPS-2')])
    const wrapper = mountToasts({ initialDelayMs: 1000, pollIntervalMs: 5000, dismissAfterMs: 8000 })

    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(wrapper.text()).not.toContain('OPS-2')

    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(wrapper.text()).toContain('OPS-2 已开始处理')
    wrapper.unmount()
  })
})
