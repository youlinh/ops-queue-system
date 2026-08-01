import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RosterImportPage from '../RosterImportPage.vue'
import * as rosterApi from '../roster.api'

vi.mock('../roster.api')

async function selectFile(wrapper: ReturnType<typeof mount>, file: File) {
  const input = wrapper.get('[data-testid="roster-file"]')
  Object.defineProperty(input.element, 'files', {
    configurable: true,
    value: [file],
  })
  await input.trigger('change')
}

describe('RosterImportPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(rosterApi.importHistory).mockResolvedValue({
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    })
  })

  it('uploads a selected xlsx file for preview', async () => {
    vi.mocked(rosterApi.previewRoster).mockResolvedValue({
      batchId: 'batch-1',
      valid: false,
      rows: [],
      errors: [{ rowNumber: 3, message: '账号不存在' }],
    })
    const wrapper = mount(RosterImportPage)
    const file = new File(['xlsx'], 'duty.xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })

    await selectFile(wrapper, file)
    await vi.waitFor(() =>
      expect(rosterApi.previewRoster).toHaveBeenCalledWith(file),
    )
  })

  it('renders Excel row errors and keeps confirmation disabled', async () => {
    vi.mocked(rosterApi.previewRoster).mockResolvedValue({
      batchId: 'batch-1',
      valid: false,
      rows: [],
      errors: [{ rowNumber: 7, message: '三线管理员账号不存在或已停用' }],
    })
    const wrapper = mount(RosterImportPage)
    const file = new File(['xlsx'], 'duty.xlsx')

    await selectFile(wrapper, file)
    await vi.waitFor(() => expect(wrapper.text()).toContain('Excel 第 7 行'))
    expect(wrapper.text()).toContain('三线管理员账号不存在或已停用')
    expect(wrapper.get('[data-testid="confirm-roster"]').attributes('disabled'))
      .toBeDefined()
  })

  it('marks preview as the active import stage and keeps errors beside the preview', async () => {
    vi.mocked(rosterApi.previewRoster).mockResolvedValue({
      batchId: 'batch-1', valid: false, rows: [],
      errors: [{ rowNumber: 7, message: '账号不存在' }],
    })
    const wrapper = mount(RosterImportPage)
    const file = new File(['xlsx'], 'invalid-duty.xlsx')

    await selectFile(wrapper, file)
    await flushPromises()

    expect(wrapper.get('[aria-current="step"]').text()).toContain('预览校验')
    expect(wrapper.get('[data-testid="roster-preview"]').text()).toContain('Excel 第 7 行')
  })
  it('lists resolved duty rows and enables confirmation', async () => {
    vi.mocked(rosterApi.previewRoster).mockResolvedValue({
      batchId: 'batch-1',
      valid: true,
      errors: [],
      rows: [{
        sourceRowNumber: 2,
        dutyDate: '2026-07-27',
        secondLineUserId: 'op-1',
        secondLineDisplayName: '运维甲',
        thirdLineUserId: 'op-2',
        thirdLineDisplayName: '运维乙',
      }],
    })
    const wrapper = mount(RosterImportPage)
    const file = new File(['xlsx'], 'duty.xlsx')

    await selectFile(wrapper, file)
    await vi.waitFor(() => expect(wrapper.text()).toContain('2026-07-27'))
    expect(wrapper.text()).toContain('运维甲')
    expect(wrapper.text()).toContain('运维乙')
    expect(wrapper.get('[data-testid="confirm-roster"]').attributes('disabled'))
      .toBeUndefined()
  })

  it('shows a confirmation conflict and refreshes import history', async () => {
    vi.mocked(rosterApi.previewRoster).mockResolvedValue({
      batchId: 'batch-1',
      valid: true,
      errors: [],
      rows: [{
        sourceRowNumber: 2,
        dutyDate: '2026-07-27',
        secondLineUserId: 'op-1',
        secondLineDisplayName: '运维甲',
        thirdLineUserId: 'op-2',
        thirdLineDisplayName: '运维乙',
      }],
    })
    vi.mocked(rosterApi.confirmRoster).mockRejectedValue({
      response: { status: 409, data: '值班表导入批次已确认' },
    })
    const wrapper = mount(RosterImportPage)
    const file = new File(['xlsx'], 'duty.xlsx')
    await selectFile(wrapper, file)
    await vi.waitFor(() => expect(wrapper.text()).toContain('2026-07-27'))

    await wrapper.get('[data-testid="confirm-roster"]').trigger('click')

    await vi.waitFor(() =>
      expect(wrapper.text()).toContain('值班表导入批次已确认'),
    )
    expect(rosterApi.importHistory).toHaveBeenCalledTimes(2)
  })
})
