import { expect, test } from '@playwright/test'
import {
  rolePage,
  seedUsers,
  localDateTime,
  todayInShanghai,
  type E2eUser,
} from './helpers'

test('final assignee calls and completes a task with actual duration', async ({
  browser,
}) => {
  const developer = await rolePage(browser, 'dev1')
  const today = todayInShanghai()
  await developer.page.goto('/tasks/new')
  await developer.page.getByTestId('category').selectOption('DATA_MAINTENANCE')
  await developer.page.getByTestId('system-name').fill('清算系统')
  await developer.page.getByTestId('estimated-minutes').fill('45')
  await developer.page.getByTestId('process-number').fill(`DATA-${today}-001`)
  await developer.page.getByTestId('operation-start').fill(localDateTime(today, '14:00'))
  await developer.page.getByTestId('operation-end').fill(localDateTime(today, '15:00'))
  const responsePromise = developer.page.waitForResponse(
    (response) => response.url().endsWith('/api/tasks')
      && response.request().method() === 'POST',
  )
  await developer.page.getByTestId('submit-task').click()
  const created = await (await responsePromise).json() as {
    id: string
    assigneeId: string
  }

  const users = await seedUsers()
  const assignee = Object.values(users)
    .find((user) => user.id === created.assigneeId)
  expect(assignee).toBeTruthy()
  const operator = assignee!.username as E2eUser
  expect(operator).toBeTruthy()

  const operatorSession = await rolePage(browser, operator)
  await operatorSession.page.goto(`/tasks/${created.id}`)
  await operatorSession.page.getByTestId('call-task').click()
  await expect(operatorSession.page.getByText('执行中', { exact: true })).toBeVisible()
  await operatorSession.page.getByTestId('complete-task').click()
  await operatorSession.page.getByTestId('actual-minutes').fill('60')
  await operatorSession.page.getByTestId('confirm-complete').click()
  await expect(operatorSession.page.getByText('已完成', { exact: true })).toBeVisible()
  await expect(operatorSession.page.getByText('60 分钟', { exact: true })).toBeVisible()

  await developer.page.goto(`/tasks/${created.id}`)
  await expect(developer.page.getByText('已完成', { exact: true })).toBeVisible()
  await operatorSession.context.close()
  await developer.context.close()
})
