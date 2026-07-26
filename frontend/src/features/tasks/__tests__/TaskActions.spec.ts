import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { TaskDetail } from '../task.types'
import TaskActions from '../TaskActions.vue'
import * as taskApi from '../task.api'

vi.mock('../task.api')

const operatorId = 'operator-1'

function task(overrides: Partial<TaskDetail> = {}): TaskDetail {
  return {
    id: 'task-1',
    ticketNumber: 'OPS-20260725-0001',
    category: 'VERSION_RELEASE',
    systemName: '支付系统',
    processNumber: 'REL-1',
    operationStart: '2026-07-25T12:00:00Z',
    operationEnd: '2026-07-25T13:00:00Z',
    creatorId: 'dev-1',
    creatorName: '开发甲',
    currentAssigneeId: operatorId,
    currentAssigneeName: '运维甲',
    status: 'PENDING',
    estimatedMinutes: 60,
    actualMinutes: null,
    assignmentRule: 'DAY_SECOND',
    canCall: true,
    canComplete: false,
    canTransfer: true,
    needsManualAttention: false,
    createdAt: '2026-07-25T01:00:00Z',
    calledAt: null,
    calledByUserId: null,
    completedAt: null,
    completedByUserId: null,
    version: 0,
    assignmentTimeline: [],
    ...overrides,
  }
}

function mountActions(
  currentTask: TaskDetail,
  userId = operatorId,
  roles: Array<'DEVELOPER' | 'OPERATOR' | 'LEADER'> = ['OPERATOR'],
) {
  return mount(TaskActions, {
    props: {
      task: currentTask,
      currentUserId: userId,
      roles,
      operators: [
        { id: operatorId, displayName: '运维甲', available: true },
        { id: 'operator-2', displayName: '运维乙', available: true },
      ],
    },
  })
}

describe('TaskActions', () => {
  beforeEach(() => vi.resetAllMocks())

  it('shows call only to the current assignee of a pending task', () => {
    expect(mountActions(task()).find('[data-testid="call-task"]').exists())
      .toBe(true)
    expect(mountActions(task(), 'operator-2')
      .find('[data-testid="call-task"]').exists()).toBe(false)
  })

  it('shows completion only to the current assignee of an in-progress task', () => {
    const wrapper = mountActions(task({
      status: 'IN_PROGRESS',
      canCall: false,
      canComplete: true,
    }))

    expect(wrapper.find('[data-testid="complete-task"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="call-task"]').exists()).toBe(false)
  })

  it('shows no operational actions after completion', () => {
    const wrapper = mountActions(task({
      status: 'COMPLETED',
      canCall: false,
      canComplete: false,
      canTransfer: false,
    }))

    expect(wrapper.find('[data-testid="call-task"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="complete-task"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="transfer-task"]').exists()).toBe(false)
  })

  it('requires a target and nonblank reason before transfer', async () => {
    const wrapper = mountActions(task())
    await wrapper.get('[data-testid="transfer-task"]').trigger('click')
    await wrapper.get('[data-testid="confirm-transfer"]').trigger('click')

    expect(wrapper.text()).toContain('请选择转交管理员')
    expect(wrapper.text()).toContain('请输入转交原因')
    expect(taskApi.transferTask).not.toHaveBeenCalled()
  })

  it('never renders operational actions for a developer', () => {
    const wrapper = mountActions(task(), operatorId, ['DEVELOPER'])

    expect(wrapper.find('[data-testid="call-task"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="complete-task"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="transfer-task"]').exists()).toBe(false)
  })

  it('shows transfer warnings returned by the server', async () => {
    vi.mocked(taskApi.transferTask).mockResolvedValue({
      taskId: 'task-1',
      previousAssigneeId: operatorId,
      assigneeId: 'operator-2',
      warnings: ['目标人员是次日值班人员'],
      version: 1,
    })
    const wrapper = mountActions(task())
    await wrapper.get('[data-testid="transfer-task"]').trigger('click')
    await wrapper.get('[data-testid="transfer-target"]').setValue('operator-2')
    await wrapper.get('[data-testid="transfer-reason"]').setValue('临时调整')
    await wrapper.get('[data-testid="confirm-transfer"]').trigger('click')

    await vi.waitFor(() =>
      expect(wrapper.text()).toContain('目标人员是次日值班人员'),
    )
  })
})
