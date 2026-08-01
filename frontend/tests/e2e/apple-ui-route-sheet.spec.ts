import { expect, test, type Locator, type Page } from '@playwright/test'
import {
  apiCall,
  localDateTime,
  rolePage,
  todayInShanghai,
  type CreatedTask,
} from './helpers'

const desktop = { width: 1440, height: 900 }
const mobile = { width: 390, height: 844 }
const copy = {
  taskDetail: '\u4efb\u52a1\u8be6\u60c5',
  closeTaskDetail: '\u5173\u95ed\u4efb\u52a1\u8be6\u60c5',
  moreFilters: '\u66f4\u591a\u7b5b\u9009',
  search: '\u67e5\u8be2',
}

async function createTask(baseURL: string, suffix: string): Promise<CreatedTask> {
  const today = todayInShanghai()
  return apiCall<CreatedTask>(baseURL, 'dev1', 'POST', '/api/tasks', {
    category: 'VERSION_RELEASE',
    systemName: `Apple UI ${suffix}`,
    estimatedMinutes: 30,
    processNumber: `UI-${today}-${suffix}`,
    operationStart: localDateTime(today, '14:00'),
    operationEnd: localDateTime(today, '15:00'),
  })
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  await expect.poll(() => page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))).toEqual(expect.objectContaining({ scrollWidth: expect.any(Number) }))
  expect(await page.evaluate(() => (
    document.documentElement.scrollWidth <= document.documentElement.clientWidth
  ))).toBe(true)
}

async function expectMinimumTargetSize(locator: Locator): Promise<void> {
  const count = await locator.count()
  for (let index = 0; index < count; index += 1) {
    const target = locator.nth(index)
    if (!await target.isVisible()) continue
    const box = await target.boundingBox()
    expect(box, `visible target ${index} should have a layout box`).not.toBeNull()
    expect(Math.min(box!.width, box!.height),
      `visible target ${index} should provide a 44px hit area`).toBeGreaterThanOrEqual(44)
  }
}

test('task detail supports direct URL, refresh, close fallback, and mobile bottom sheet', async ({
  browser,
  baseURL,
}) => {
  const created = await createTask(baseURL!, 'MOBILE-001')
  const developer = await rolePage(browser, 'dev1', { viewport: mobile })
  const { page } = developer

  await page.goto(`/tasks/${created.id}`)
  const dialog = page.getByRole('dialog', { name: copy.taskDetail })
  await expect(dialog).toBeVisible()
  await expect(dialog).toHaveCSS('bottom', '0px')
  await page.reload()
  await expect(dialog).toBeVisible()
  await page.getByRole('button', { name: copy.closeTaskDetail }).click()
  await expect(page).toHaveURL(/\/tasks$/)
  await expectNoHorizontalOverflow(page)
  await developer.context.close()
})

test('task rows route to a desktop right sheet and restore the prior list state on Back', async ({
  browser,
  baseURL,
}) => {
  const created = await createTask(baseURL!, 'DESKTOP-001')
  const developer = await rolePage(browser, 'dev1', { viewport: desktop })
  const { page } = developer

  await page.goto('/tasks')
  await page.getByRole('button', { name: copy.moreFilters }).click()
  const search = page.locator('[data-testid="advanced-task-filters"] input[type="text"]')
  await search.fill('Apple UI DESKTOP-001')
  await page.getByRole('button', { name: copy.search }).click()
  const row = page.getByTestId('task-row').filter({ hasText: created.ticketNumber })
  await expect(row).toBeVisible()
  await row.focus()
  await row.click()

  await expect(page).toHaveURL(new RegExp(`/tasks/${created.id}$`))
  const dialog = page.getByRole('dialog', { name: copy.taskDetail })
  await expect(dialog).toBeVisible()
  const sheetBox = await dialog.boundingBox()
  expect(sheetBox).not.toBeNull()
  expect(sheetBox!.x).toBeGreaterThan(desktop.width / 2)
  expect(desktop.width - (sheetBox!.x + sheetBox!.width)).toBeLessThanOrEqual(24)

  await page.goBack()
  await expect(page).toHaveURL(/\/tasks$/)
  await expect(search).toHaveValue('Apple UI DESKTOP-001')
  await expect(row).toBeFocused()
  await expectNoHorizontalOverflow(page)
  await developer.context.close()
})

test('Escape closes the task sheet, mobile navigation stays compact, and targets remain touch sized', async ({
  browser,
  baseURL,
}) => {
  const created = await createTask(baseURL!, 'RESPONSIVE-001')
  const developer = await rolePage(browser, 'dev1', {
    viewport: mobile,
    reducedMotion: 'reduce',
  })
  const { page } = developer

  await page.goto('/tasks')
  const row = page.getByTestId('task-row').filter({ hasText: created.ticketNumber })
  await expect(row).toBeVisible()
  await row.focus()
  await row.click()
  const dialog = page.getByRole('dialog', { name: copy.taskDetail })
  await expect(dialog).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page).toHaveURL(/\/tasks$/)
  await expect(row).toBeFocused()

  const mobileNav = page.locator('.role-navigation--mobile')
  await expect(mobileNav).toBeVisible()
  expect(await mobileNav.locator('a, button').count()).toBeLessThanOrEqual(4)
  await expectMinimumTargetSize(page.locator('button, a[href]'))
  await expectNoHorizontalOverflow(page)

  await page.route('**/api/tasks**', async route => {
    await new Promise(resolve => setTimeout(resolve, 150))
    await route.continue()
  })
  await page.getByRole('button', { name: copy.search }).click()
  await expect(page.getByRole('status')).toBeVisible()
  await page.unroute('**/api/tasks**')

  await page.setViewportSize({ width: 320, height: 844 })
  await expectNoHorizontalOverflow(page)
  await developer.context.close()
})
