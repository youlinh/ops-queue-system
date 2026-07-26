import { expect, request, type Browser, type Page } from '@playwright/test'
import ExcelJS from 'exceljs'
import { readFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
export const authDirectory = resolve(here, '../../playwright/.auth')

export const passwords = {
  leader: 'Leader-Password-1!',
  dev1: 'Developer-Password-1!',
  ops1: 'Operator1-Password-1!',
  ops2: 'Operator2-Password-1!',
  ops3: 'Operator3-Password-1!',
} as const

export type E2eUser = keyof typeof passwords

export function storageStatePath(user: E2eUser): string {
  return resolve(authDirectory, `${user}.json`)
}

export interface SeedUser {
  id: string
  username: E2eUser
  displayName: string
}

export async function seedUsers(): Promise<Record<E2eUser, SeedUser>> {
  return JSON.parse(
    await readFile(resolve(authDirectory, 'seed.json'), 'utf8'),
  ) as Record<E2eUser, SeedUser>
}

export async function login(
  page: Page,
  username: E2eUser,
  password = passwords[username],
): Promise<void> {
  await page.goto('/login')
  await page.getByLabel('账号').fill(username)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/workspace$/)
}

export async function rolePage(
  browser: Browser,
  user: E2eUser,
): Promise<{ context: Awaited<ReturnType<Browser['newContext']>>; page: Page }> {
  const context = await browser.newContext({
    storageState: storageStatePath(user),
  })
  return { context, page: await context.newPage() }
}

export function todayInShanghai(): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date())
}

export function addDays(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00Z`)
  value.setUTCDate(value.getUTCDate() + days)
  return value.toISOString().slice(0, 10)
}

export function tomorrowInShanghai(): string {
  return addDays(todayInShanghai(), 1)
}

export function operationDateTime(date: string, time: string): string {
  return `${date}T${time}:00+08:00`
}

export function localDateTime(date: string, time: string): string {
  return `${date}T${time}`
}

export async function createRosterWorkbook(
  rows: Array<[date: string, secondLine: string, thirdLine: string]>,
): Promise<Buffer> {
  const workbook = new ExcelJS.Workbook()
  const sheet = workbook.addWorksheet('值班表')
  sheet.addRow(['值班日期', '二线管理员账号', '三线管理员账号'])
  for (const row of rows) sheet.addRow(row)
  return Buffer.from(await workbook.xlsx.writeBuffer())
}

export interface TaskCommand {
  category: 'VERSION_RELEASE' | 'DATA_MAINTENANCE'
  systemName: string
  estimatedMinutes: number
  processNumber: string
  operationStart: string
  operationEnd: string
}

export interface CreatedTask {
  id: string
  ticketNumber: string
  assigneeId: string
  assigneeName: string
  assignmentRule: string
}

export async function apiCall<T>(
  baseURL: string,
  user: E2eUser,
  method: 'GET' | 'POST',
  path: string,
  data?: unknown,
): Promise<T> {
  const api = await request.newContext({
    baseURL,
    storageState: storageStatePath(user),
  })
  try {
    const csrf = await api.get('/api/auth/csrf')
    expect(csrf.ok()).toBeTruthy()
    const token = ((await csrf.json()) as { token: string }).token
    const response = await api.fetch(path, {
      method,
      data,
      headers: method === 'POST' ? { 'X-XSRF-TOKEN': token } : undefined,
    })
    expect(response.ok(), await response.text()).toBeTruthy()
    if (response.status() === 204) return undefined as T
    return await response.json() as T
  } finally {
    await api.dispose()
  }
}
