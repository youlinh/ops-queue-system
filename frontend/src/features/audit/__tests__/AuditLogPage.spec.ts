import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import * as peopleApi from '@/features/people/people.api'
import AuditLogPage from '../AuditLogPage.vue'
import * as auditApi from '../audit.api'

vi.mock('@/features/people/people.api')
vi.mock('../audit.api')

function mountAuditWithOneEntry() {
  vi.mocked(peopleApi.listAccounts).mockResolvedValue([])
  vi.mocked(auditApi.searchAuditLogs).mockResolvedValue({
    content: [{
      id: 'audit-1', actorId: 'operator-1', action: 'TASK_CALLED',
      objectType: 'TASK', objectId: 'task-1', before: { status: 'PENDING' },
      after: { status: 'IN_PROGRESS' }, sourceIp: '127.0.0.1',
      occurredAt: '2026-08-01T14:00:00+08:00',
    }],
    page: 0, size: 20, totalElements: 1, totalPages: 1,
  })
  return mount(AuditLogPage)
}

beforeEach(() => vi.resetAllMocks())

it('opens long audit payload in an accessible detail surface', async () => {
  const wrapper = mountAuditWithOneEntry()
  await flushPromises()
  await wrapper.get('[data-testid="open-audit-detail"]').trigger('click')
  expect(document.body.querySelector('[role="dialog"]')?.textContent).toContain('\u53d8\u66f4\u8be6\u60c5')
  expect(document.body.querySelector('[role="dialog"]')?.textContent).toContain('TASK_CALLED')
})
