import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/features/auth/auth.store'
import * as peopleApi from '@/features/people/people.api'
import ReportingPage from '../ReportingPage.vue'
import * as reportingApi from '../reporting.api'
import type { OperatorMetrics } from '../reporting.api'

vi.mock('@/features/people/people.api')
vi.mock('../reporting.api')

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function metrics(totalTaskCount: number): OperatorMetrics {
  return {
    operatorId: 'operator-1', date: '2026-08-01', month: '2026-08',
    totalTaskCount, pendingCount: 1, inProgressCount: 1, completedCount: totalTaskCount - 2,
    estimatedMinutes: 120, completedActualMinutes: 90,
  }
}

function mountReportingPage() {
  const pinia = createPinia()
  useAuthStore(pinia).user = {
    id: 'operator-1', username: 'ops1', displayName: '\u8fd0\u7ef4\u7532',
    roles: ['OPERATOR'], mustChangePassword: false,
  }
  vi.mocked(peopleApi.listAccounts).mockResolvedValue([])
  return mount(ReportingPage, { global: { plugins: [pinia] } })
}

beforeEach(() => vi.resetAllMocks())

it('keeps the current metrics visible while a refreshed request is pending', async () => {
  const next = deferred<OperatorMetrics>()
  vi.mocked(reportingApi.dailyReport)
    .mockResolvedValueOnce(metrics(5))
    .mockReturnValueOnce(next.promise)
  vi.mocked(reportingApi.monthlyReport).mockResolvedValue(metrics(8))

  const wrapper = mountReportingPage()
  await vi.waitFor(() => expect(wrapper.text()).toContain('5'))
  await wrapper.get('[data-testid="refresh-report"]').trigger('click')

  expect(wrapper.text()).toContain('5')
  expect(wrapper.get('[role="status"]').text()).toContain('\u6b63\u5728\u66f4\u65b0')
  next.resolve(metrics(6))
  await flushPromises()
  expect(wrapper.text()).toContain('6')
})
