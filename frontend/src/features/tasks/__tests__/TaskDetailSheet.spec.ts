import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import TaskDetailSheet from '../TaskDetailSheet.vue'
import { canGoBackToApp, closeTaskSheet } from '../useRouteSheet'
import * as taskApi from '../task.api'

vi.mock('../task.api')

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/tasks', name: 'tasks', component: { template: '<div />' } },
      { path: '/tasks/:id', name: 'task-detail', component: { template: '<div />' } },
    ],
  })
}

function sheetDialog(): HTMLElement {
  const dialog = document.body.querySelector<HTMLElement>('[role="dialog"]')
  if (!dialog) throw new Error('task detail dialog is not mounted')
  return dialog
}

function mountSheet(taskId = 'task-1') {
  return mount(TaskDetailSheet, {
    attachTo: document.body,
    props: { taskId },
    global: { plugins: [createPinia(), makeRouter()] },
  })
}

describe('TaskDetailSheet', () => {
  beforeEach(() => vi.resetAllMocks())

  it('keeps a failed detail request open and exposes retry', async () => {
    vi.mocked(taskApi.taskDetail).mockRejectedValue(new Error('offline'))
    vi.mocked(taskApi.listOperators).mockResolvedValue([])
    const wrapper = mountSheet()
    await flushPromises()

    expect(sheetDialog().getAttribute('aria-modal')).toBe('true')
    expect(sheetDialog().querySelector('[role="alert"]')?.textContent).toContain('任务详情加载失败')
    expect(sheetDialog().querySelector('[data-testid="retry-task-detail"]')).not.toBeNull()
    wrapper.unmount()
  })

  it('emits close from Escape and returns focus to the task row', async () => {
    const trigger = document.createElement('button')
    document.body.append(trigger)
    trigger.focus()
    vi.mocked(taskApi.taskDetail).mockRejectedValue(new Error('offline'))
    const wrapper = mountSheet()
    await flushPromises()

    sheetDialog().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
    expect(document.activeElement).toBe(trigger)
    trigger.remove()
  })

  it('uses app history only for a safe in-app return and otherwise falls back to tasks', async () => {
    expect(canGoBackToApp('/tasks', '/tasks/task-1')).toBe(true)
    expect(canGoBackToApp('//outside.example', '/tasks/task-1')).toBe(false)
    expect(canGoBackToApp('/tasks/task-1', '/tasks/task-1')).toBe(false)

    const router = makeRouter()
    await router.push('/tasks/task-1')
    await router.isReady()
    Object.defineProperty(window.history, 'state', {
      configurable: true,
      value: { back: null },
    })
    await closeTaskSheet(router, '/tasks/task-1')
    expect(router.currentRoute.value.name).toBe('tasks')
  })

  it('uses router.back for an in-app entry', async () => {
    const router = makeRouter()
    const back = vi.spyOn(router, 'back')
    Object.defineProperty(window.history, 'state', {
      configurable: true,
      value: { back: '/tasks' },
    })

    await closeTaskSheet(router, '/tasks/task-1')
    expect(back).toHaveBeenCalledOnce()
  })

  it('reloads task detail when the route id changes without remounting the sheet', async () => {
    vi.mocked(taskApi.taskDetail).mockImplementation(async id => ({
      id,
      ticketNumber: id,
      category: 'VERSION_RELEASE',
      systemName: 'system',
      processNumber: 'process',
      operationStart: '2026-08-01T10:00:00Z',
      operationEnd: '2026-08-01T11:00:00Z',
      creatorId: 'creator',
      creatorName: 'creator',
      currentAssigneeId: 'operator',
      currentAssigneeName: 'operator',
      status: 'PENDING',
      estimatedMinutes: 30,
      actualMinutes: null,
      assignmentRule: 'DAY_SECOND',
      canCall: false,
      canComplete: false,
      canTransfer: false,
      needsManualAttention: false,
      createdAt: '2026-08-01T09:00:00Z',
      calledAt: null,
      calledByUserId: null,
      completedAt: null,
      completedByUserId: null,
      version: 0,
      assignmentTimeline: [],
    }))
    const wrapper = mountSheet('task-1')
    await flushPromises()
    await wrapper.setProps({ taskId: 'task-2' })
    await flushPromises()

    expect(taskApi.taskDetail).toHaveBeenLastCalledWith('task-2')
    expect(document.body.querySelectorAll('[role="dialog"]')).toHaveLength(1)
    wrapper.unmount()
  })
})
