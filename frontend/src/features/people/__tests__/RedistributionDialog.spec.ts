import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as taskApi from '@/features/tasks/task.api'
import * as peopleApi from '../people.api'
import RedistributionDialog from '../RedistributionDialog.vue'
import UnavailabilityDialog from '../UnavailabilityDialog.vue'
import type { AccountView } from '../people.types'

vi.mock('../people.api')
vi.mock('@/features/tasks/task.api')

function currentDialog(): HTMLElement {
  const dialogs = document.body.querySelectorAll<HTMLElement>('[role="dialog"]')
  const dialog = dialogs.item(dialogs.length - 1)
  if (!dialog) throw new Error('Expected an open dialog')
  return dialog
}
const operator: AccountView = {
  id: 'operator-1',
  username: 'ops1',
  displayName: '运维甲',
  roles: ['OPERATOR'],
  enabled: true,
  mustChangePassword: false,
}

describe('people redistribution workflow', () => {
  beforeEach(() => vi.resetAllMocks())

  it('requires a nonblank unavailability reason', async () => {
    const wrapper = mount(UnavailabilityDialog, {
      props: { operator, initialDate: '2026-07-26' },
    })
    currentDialog().querySelector<HTMLButtonElement>('[data-testid="save-unavailable"]')!.click()
    await nextTick()

    expect(currentDialog().textContent).toContain('请输入不可参与原因')
    expect(peopleApi.setUnavailable).not.toHaveBeenCalled()
  })

  it('separates executing tasks from pending redistribution preview', async () => {
    vi.mocked(peopleApi.previewRedistribution).mockResolvedValue([{
      taskId: 'pending-1',
      ticketNumber: 'OPS-PENDING',
      category: 'VERSION_RELEASE',
      systemName: '支付系统',
      operationStart: '2026-07-26T02:00:00Z',
      currentAssigneeId: operator.id,
    }])
    vi.mocked(taskApi.searchTasks).mockResolvedValue({
      content: [{
        id: 'running-1',
        ticketNumber: 'OPS-RUNNING',
        status: 'IN_PROGRESS',
      } as never],
      page: 0,
      size: 100,
      totalElements: 1,
      totalPages: 1,
    })

    const wrapper = mount(RedistributionDialog, {
      props: {
        operator,
        date: '2026-07-26',
        reason: '临时请假',
      },
      global: {
        stubs: {
          RouterLink: {
            props: ['to'],
            template: '<a :href="to"><slot /></a>',
          },
        },
      },
    })

    await vi.waitFor(() => expect(currentDialog().textContent).toContain('OPS-PENDING'))
    expect(currentDialog().textContent).toContain('需手工调整')
    expect(currentDialog().textContent).toContain('OPS-RUNNING')
  })

  it('keeps per-task failures visible and links to failed task detail', async () => {
    vi.mocked(peopleApi.previewRedistribution).mockResolvedValue([{
      taskId: 'failed-1',
      ticketNumber: 'OPS-FAILED',
      category: 'DATA_MAINTENANCE',
      systemName: '数据平台',
      operationStart: '2026-07-26T02:00:00Z',
      currentAssigneeId: operator.id,
    }])
    vi.mocked(taskApi.searchTasks).mockResolvedValue({
      content: [],
      page: 0,
      size: 100,
      totalElements: 0,
      totalPages: 0,
    })
    vi.mocked(peopleApi.redistribute).mockResolvedValue({
      sourceOperatorId: operator.id,
      date: '2026-07-26',
      items: [{
        taskId: 'failed-1',
        ticketNumber: 'OPS-FAILED',
        success: false,
        previousAssigneeId: operator.id,
        assigneeId: operator.id,
        needsManualAttention: true,
        error: '没有可用候选人',
      }],
    })
    const wrapper = mount(RedistributionDialog, {
      props: { operator, date: '2026-07-26', reason: '临时请假' },
      global: {
        stubs: {
          RouterLink: {
            props: ['to'],
            template: '<a :href="to"><slot /></a>',
          },
        },
      },
    })
    await vi.waitFor(() => expect(currentDialog().textContent).toContain('OPS-FAILED'))
    currentDialog().querySelector<HTMLButtonElement>('[data-testid="execute-redistribution"]')!.click()

    await vi.waitFor(() => expect(currentDialog().textContent).toContain('没有可用候选人'))
    expect(currentDialog().querySelector('a[href="/tasks/failed-1"]')?.getAttribute('href'))
      .toBe('/tasks/failed-1')
  })
  it('keeps redistribution failures inside the dialog until the user closes it', async () => {
    vi.mocked(peopleApi.previewRedistribution).mockResolvedValue([{
      taskId: 'failed-1', ticketNumber: 'OPS-FAILED', category: 'DATA_MAINTENANCE',
      systemName: '数据平台', operationStart: '2026-08-01T14:00:00+08:00',
      currentAssigneeId: operator.id,
    }])
    vi.mocked(taskApi.searchTasks).mockResolvedValue({
      content: [], page: 0, size: 100, totalElements: 0, totalPages: 0,
    })
    vi.mocked(peopleApi.redistribute).mockResolvedValue({
      sourceOperatorId: operator.id, date: '2026-08-01',
      items: [{
        taskId: 'failed-1', ticketNumber: 'OPS-FAILED', success: false,
        previousAssigneeId: operator.id, assigneeId: operator.id,
        needsManualAttention: true, error: '没有可用候选人',
      }],
    })
    const wrapper = mount(RedistributionDialog, {
      props: { operator, date: '2026-08-01', reason: '临时请假' },
      global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } },
    })

    await vi.waitFor(() => expect(currentDialog().textContent).toContain('OPS-FAILED'))
    currentDialog().querySelector<HTMLButtonElement>('[data-testid="execute-redistribution"]')!.click()
    await flushPromises()

    expect(currentDialog().textContent).toContain('没有可用候选人')
    expect(currentDialog().querySelector('a[href="/tasks/failed-1"]')).not.toBeNull()
  })
})
