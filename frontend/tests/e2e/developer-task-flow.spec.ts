import { expect, test } from '@playwright/test'
import {
  localDateTime,
  rolePage,
  todayInShanghai,
} from './helpers'

test('developer receives a number and sees the assigned operator', async ({
  browser,
}) => {
  const { context, page } = await rolePage(browser, 'dev1')
  const today = todayInShanghai()
  await page.goto('/tasks/new')
  await page.getByTestId('category').selectOption('VERSION_RELEASE')
  await page.getByTestId('system-name').fill('支付系统')
  await page.getByTestId('estimated-minutes').fill('60')
  await page.getByTestId('process-number').fill(`REL-${today}-001`)
  await page.getByTestId('operation-start').fill(localDateTime(today, '20:00'))
  await page.getByTestId('operation-end').fill(localDateTime(today, '21:00'))
  await page.getByTestId('submit-task').click()

  await expect(page.getByText(/OPS-\d{8}-\d{4}/)).toBeVisible()
  await expect(page.getByText('已分配负责人')).toBeVisible()
  await expect(page.getByText(
    /^(晚间优先二线|晚间转三线|公平分配)$/,
  )).toBeVisible()
  await context.close()
})
