import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import TaskFilters from '../TaskFilters.vue'
import TaskQueuePage from '../TaskQueuePage.vue'
import * as taskApi from '../task.api'
import type { TaskPage, TaskRow } from '../task.types'

vi.mock('../task.api')

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/tasks', name: 'tasks', component: TaskQueuePage },
      { path: '/tasks/:id', name: 'task-detail', component: TaskQueuePage },
    ],
  })
}

function makeTask(): TaskRow {
  return {
    id: 'task-1',
    ticketNumber: 'OPS-20260801-0001',
    category: 'VERSION_RELEASE',
    systemName: '支付系统',
    processNumber: 'REL-1',
    operationStart: '2026-08-01T14:00:00+08:00',
    operationEnd: '2026-08-01T15:00:00+08:00',
    creatorId: 'developer-1',
    creatorName: '开发甲',
    currentAssigneeId: 'operator-1',
    currentAssigneeName: '运维乙',
    status: 'PENDING',
    estimatedMinutes: 60,
    actualMinutes: null,
    assignmentRule: 'DAY_SECOND',
    canCall: false,
    canComplete: false,
    canTransfer: false,
    needsManualAttention: false,
    createdAt: '2026-08-01T09:00:00+08:00',
  }
}

function makePage(content: TaskRow[] = [makeTask()]): TaskPage {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length ? 1 : 0,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

async function mountQueue() {
  const router = makeRouter()
  await router.push('/tasks')
  await router.isReady()
  const wrapper = mount(TaskQueuePage, { global: { plugins: [router] } })
  await flushPromises()
  return { router, wrapper }
}

describe('TaskQueuePage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(taskApi.searchTasks).mockResolvedValue(makePage())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('opens a task row through the named task-detail route', async () => {
    const { router, wrapper } = await mountQueue()

    await wrapper.get('[data-testid="task-row"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('task-detail')
    expect(router.currentRoute.value.params.id).toBe('task-1')
  })

  it('keeps the current results visible while refresh is pending', async () => {
    const next = deferred<TaskPage>()
    vi.mocked(taskApi.searchTasks)
      .mockResolvedValueOnce(makePage())
      .mockReturnValueOnce(next.promise)
    const { wrapper } = await mountQueue()

    window.dispatchEvent(new Event('ops-task-changed'))
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('OPS-20260801-0001')
    expect(wrapper.get('[role="status"]').text()).toContain('正在更新')
    next.resolve(makePage())
    await flushPromises()
  })

  it('renders a responsive task list instead of a minimum-width data table', async () => {
    const { wrapper } = await mountQueue()

    expect(wrapper.find('[data-testid="task-list"]').exists()).toBe(true)
    expect(wrapper.find('.task-table').exists()).toBe(false)
    expect(wrapper.find('.task-table-wrap').exists()).toBe(false)
  })
})

describe('TaskFilters', () => {
  it('keeps common filters visible and discloses advanced filters accessibly', async () => {
    const wrapper = mount(TaskFilters, {
      props: {
        initial: { operationDate: '', category: '', systemName: '', status: '', creatorId: '', assigneeId: '' },
      },
    })

    expect(wrapper.get('[data-testid="common-task-filters"]').text()).toContain('类别')
    expect(wrapper.get('[data-testid="common-task-filters"]').text()).toContain('状态')
    expect(wrapper.find('[data-testid="advanced-task-filters"]').exists()).toBe(false)

    const disclosure = wrapper.get('[aria-controls="advanced-task-filters"]')
    expect(disclosure.attributes('aria-expanded')).toBe('false')
    await disclosure.trigger('click')

    expect(disclosure.attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('[data-testid="advanced-task-filters"]').text()).toContain('系统名称')
  })
})
