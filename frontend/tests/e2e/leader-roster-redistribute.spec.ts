import { expect, test } from '@playwright/test'
import {
  addDays,
  apiCall,
  createRosterWorkbook,
  operationDateTime,
  rolePage,
  seedUsers,
  todayInShanghai,
  type CreatedTask,
  type TaskCommand,
} from './helpers'

test('leader imports roster and redistributes pending tasks only', async ({
  browser,
  baseURL,
}) => {
  const url = baseURL!
  const today = todayInShanghai()
  const commands: TaskCommand[] = [
    {
      category: 'VERSION_RELEASE',
      systemName: '重分配待执行系统',
      estimatedMinutes: 30,
      processNumber: `REDIS-PENDING-${today}`,
      operationStart: operationDateTime(today, '10:00'),
      operationEnd: operationDateTime(today, '10:30'),
    },
    {
      category: 'DATA_MAINTENANCE',
      systemName: '重分配执行中系统',
      estimatedMinutes: 30,
      processNumber: `REDIS-RUNNING-${today}`,
      operationStart: operationDateTime(today, '11:00'),
      operationEnd: operationDateTime(today, '11:30'),
    },
  ]
  const pending = await apiCall<CreatedTask>(url, 'dev1', 'POST', '/api/tasks', commands[0])
  const executing = await apiCall<CreatedTask>(url, 'dev1', 'POST', '/api/tasks', commands[1])
  expect(pending.assigneeId).toBe(executing.assigneeId)
  const users = await seedUsers()
  const executingAssignee = Object.values(users).find((user) => user.id === executing.assigneeId)
  expect(executingAssignee).toBeDefined()
  const alternateAssignee = Object.values(users).find((user) =>
    user.username.startsWith('ops') && user.username !== executingAssignee!.username)
  expect(alternateAssignee).toBeDefined()
  await apiCall<void>(url, executingAssignee!.username, 'POST', `/api/tasks/${executing.id}/call`)

  const leader = await rolePage(browser, 'leader')
  await leader.page.goto('/rosters')
  const workbook = await createRosterWorkbook([
    [today, executingAssignee!.username, alternateAssignee!.username],
    [addDays(today, 1), alternateAssignee!.username, executingAssignee!.username],
  ])
  await leader.page.getByTestId('roster-file').setInputFiles({
    name: 'leader-e2e-roster.xlsx',
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: workbook,
  })
  await expect(leader.page.getByTestId('roster-preview')).toBeVisible()
  await leader.page.getByTestId('confirm-roster').click()
  await expect(leader.page.getByText('值班表已确认并立即生效')).toBeVisible()

  await leader.page.goto('/people')
  const assigneeRow = leader.page.getByRole('row').filter({ hasText: executingAssignee!.username })
  await assigneeRow.getByRole('button', { name: /今天不能参与/ }).click()
  await leader.page.getByTestId('unavailable-date').fill(today)
  await leader.page.getByTestId('unavailable-reason').fill('端到端验收不可参与')
  await leader.page.getByTestId('save-unavailable').click()

  const dialog = leader.page.getByRole('dialog')
  await expect(dialog.getByText(pending.ticketNumber)).toBeVisible()
  await expect(dialog.getByText(executing.ticketNumber)).toBeVisible()
  await expect(dialog.getByText(/执行中任务不会自动转移/)).toBeVisible()
  const executeRedistribution = dialog.getByTestId('execute-redistribution')
  await executeRedistribution.scrollIntoViewIfNeeded()
  await executeRedistribution.click({ force: true })
  await expect(dialog.getByRole('article')
    .filter({ hasText: pending.ticketNumber })
    .getByText('重新分配成功')).toBeVisible()

  const pendingDetail = await apiCall<{
    currentAssigneeId: string
  }>(url, 'leader', 'GET', `/api/tasks/${pending.id}`)
  const executingDetail = await apiCall<{
    currentAssigneeId: string
    status: string
  }>(url, 'leader', 'GET', `/api/tasks/${executing.id}`)
  expect(pendingDetail.currentAssigneeId).not.toBe(pending.assigneeId)
  expect(executingDetail.currentAssigneeId).toBe(executing.assigneeId)
  expect(executingDetail.status).toBe('IN_PROGRESS')
  await leader.context.close()
})
