import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TaskCreatePage from '../TaskCreatePage.vue'
import * as taskApi from '../task.api'

vi.mock('../task.api')

function mountPage() {
  return mount(TaskCreatePage)
}

async function fillValidForm(wrapper: ReturnType<typeof mountPage>) {
  await wrapper.get('[data-testid="category"]')
    .setValue('DATA_MAINTENANCE')
  await wrapper.get('[data-testid="system-name"]').setValue('数据平台')
  await wrapper.get('[data-testid="estimated-minutes"]').setValue('45')
  await wrapper.get('[data-testid="process-number"]').setValue('DATA-2026-001')
  await wrapper.get('[data-testid="operation-start"]')
    .setValue('2026-07-25T20:00')
  await wrapper.get('[data-testid="operation-end"]')
    .setValue('2026-07-25T20:45')
}

describe('TaskCreatePage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(taskApi.suggestSystemNames).mockResolvedValue([])
  })

  it('requires a process number for data maintenance', async () => {
    const wrapper = mountPage()
    await wrapper.get('[data-testid="category"]')
      .setValue('DATA_MAINTENANCE')
    await wrapper.get('[data-testid="system-name"]').setValue('数据平台')
    await wrapper.get('[data-testid="estimated-minutes"]').setValue('45')
    await wrapper.get('[data-testid="operation-start"]')
      .setValue('2026-07-25T20:00')
    await wrapper.get('[data-testid="operation-end"]')
      .setValue('2026-07-25T20:45')
    await wrapper.get('[data-testid="submit-task"]').trigger('click')

    expect(wrapper.text()).toContain('请输入操作流程编号')
    expect(taskApi.createTask).not.toHaveBeenCalled()
  })

  it('rejects an end time that is not after the start time', async () => {
    const wrapper = mountPage()
    await fillValidForm(wrapper)
    await wrapper.get('[data-testid="operation-end"]')
      .setValue('2026-07-25T19:59')
    await wrapper.get('[data-testid="submit-task"]').trigger('click')

    expect(wrapper.text()).toContain('操作结束时间必须晚于开始时间')
    expect(taskApi.createTask).not.toHaveBeenCalled()
  })

  it('requires a positive estimate', async () => {
    const wrapper = mountPage()
    await fillValidForm(wrapper)
    await wrapper.get('[data-testid="estimated-minutes"]').setValue('0')
    await wrapper.get('[data-testid="submit-task"]').trigger('click')

    expect(wrapper.text()).toContain('预计耗时必须大于 0')
    expect(taskApi.createTask).not.toHaveBeenCalled()
  })

  it('shows the allocated ticket, assignee and rule after success', async () => {
    vi.mocked(taskApi.createTask).mockResolvedValue({
      id: 'task-1',
      ticketNumber: 'OPS-20260725-0001',
      assigneeId: 'operator-1',
      assigneeName: '二线管理员一',
      assignmentRule: 'AFTER_HOURS_SECOND',
    })
    const wrapper = mountPage()
    await fillValidForm(wrapper)
    await wrapper.get('[data-testid="submit-task"]').trigger('click')
    await vi.waitFor(() => expect(taskApi.createTask).toHaveBeenCalledOnce())
    expect(taskApi.createTask).toHaveBeenCalledWith(expect.objectContaining({
      operationStart: '2026-07-25T20:00:00+08:00',
      operationEnd: '2026-07-25T20:45:00+08:00',
    }))

    expect(wrapper.text()).toContain('OPS-20260725-0001')
    expect(wrapper.text()).toContain('二线管理员一')
    expect(wrapper.text()).not.toContain('operator-1')
    expect(wrapper.text()).toContain('晚间优先二线')
  })

  it('shows a clear missing-roster response', async () => {
    vi.mocked(taskApi.createTask).mockRejectedValue({
      response: {
        data: {
          code: 'DUTY_ROSTER_MISSING',
          message: '2026-07-25 尚未配置值班表',
        },
      },
    })
    const wrapper = mountPage()
    await fillValidForm(wrapper)
    await wrapper.get('[data-testid="submit-task"]').trigger('click')

    await vi.waitFor(() =>
      expect(wrapper.text()).toContain('2026-07-25 尚未配置值班表'),
    )
  })
})
