import {
  request,
  type APIRequestContext,
  type APIResponse,
} from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  authDirectory,
  createRosterWorkbook,
  passwords,
  todayInShanghai,
  tomorrowInShanghai,
  type E2eUser,
  type SeedUser,
} from './helpers'

interface CurrentUser extends SeedUser {
  roles: string[]
  mustChangePassword: boolean
}

interface Account extends CurrentUser {
  enabled: boolean
}

const accountDefinitions: Array<{
  username: Exclude<E2eUser, 'leader'>
  displayName: string
  initialPassword: string
  roles: string[]
}> = [
  {
    username: 'dev1',
    displayName: '开发人员一',
    initialPassword: 'Developer-Initial-Password-1!',
    roles: ['DEVELOPER'],
  },
  {
    username: 'ops1',
    displayName: '二线管理员一',
    initialPassword: 'Operator1-Initial-Password-1!',
    roles: ['OPERATOR'],
  },
  {
    username: 'ops2',
    displayName: '三线管理员二',
    initialPassword: 'Operator2-Initial-Password-1!',
    roles: ['OPERATOR'],
  },
  {
    username: 'ops3',
    displayName: '机动管理员三',
    initialPassword: 'Operator3-Initial-Password-1!',
    roles: ['OPERATOR'],
  },
]

async function responseError(response: APIResponse): Promise<string> {
  return `${response.status()} ${response.statusText()}: ${await response.text()}`
}

async function requireOk(response: APIResponse, action: string): Promise<void> {
  if (!response.ok()) {
    throw new Error(`${action} failed: ${await responseError(response)}`)
  }
}

async function csrf(api: APIRequestContext): Promise<string> {
  const response = await api.get('/api/auth/csrf')
  await requireOk(response, 'CSRF bootstrap')
  return ((await response.json()) as { token: string }).token
}

async function mutation(
  api: APIRequestContext,
  method: 'POST' | 'PUT',
  path: string,
  data?: unknown,
): Promise<APIResponse> {
  return await api.fetch(path, {
    method,
    data,
    headers: { 'X-XSRF-TOKEN': await csrf(api) },
  })
}

async function loginAny(
  baseURL: string,
  username: string,
  candidates: string[],
): Promise<{ api: APIRequestContext; user: CurrentUser; password: string }> {
  for (const password of candidates) {
    const api = await request.newContext({ baseURL })
    const response = await api.post('/api/auth/login', {
      data: { username, password },
    })
    if (response.ok()) {
      return {
        api,
        user: await response.json() as CurrentUser,
        password,
      }
    }
    await api.dispose()
  }
  throw new Error(`Unable to log in deterministic user '${username}'`)
}

async function changePassword(
  api: APIRequestContext,
  currentPassword: string,
  newPassword: string,
): Promise<void> {
  const response = await mutation(api, 'POST', '/api/auth/change-password', {
    currentPassword,
    newPassword,
  })
  await requireOk(response, 'password change')
}

async function leaderSession(baseURL: string): Promise<{
  api: APIRequestContext
  user: CurrentUser
}> {
  const username = process.env.BOOTSTRAP_LEADER_USERNAME || 'leader'
  const initial = process.env.BOOTSTRAP_LEADER_PASSWORD
    || 'E2e-Bootstrap-Password-1!'
  const loggedIn = await loginAny(
    baseURL,
    username,
    [passwords.leader, initial],
  )
  if (loggedIn.user.mustChangePassword) {
    await changePassword(loggedIn.api, loggedIn.password, passwords.leader)
  }
  return { api: loggedIn.api, user: { ...loggedIn.user, username: 'leader' } }
}

async function ensureAccounts(
  leader: APIRequestContext,
): Promise<Record<string, Account>> {
  const listResponse = await leader.get('/api/admin/users')
  await requireOk(listResponse, 'account listing')
  const existing = await listResponse.json() as Account[]
  const accounts = new Map(existing.map((account) => [account.username, account]))

  for (const definition of accountDefinitions) {
    const current = accounts.get(definition.username)
    if (!current) {
      const created = await mutation(leader, 'POST', '/api/admin/users', definition)
      await requireOk(created, `create ${definition.username}`)
      accounts.set(definition.username, await created.json() as Account)
      continue
    }
    if (!current.enabled) {
      throw new Error(`Deterministic account '${definition.username}' is disabled`)
    }
    const roles = await mutation(
      leader,
      'PUT',
      `/api/admin/users/${current.id}/roles`,
      { roles: definition.roles },
    )
    await requireOk(roles, `align roles for ${definition.username}`)
    const reset = await mutation(
      leader,
      'POST',
      `/api/admin/users/${current.id}/reset-password`,
      { initialPassword: definition.initialPassword },
    )
    await requireOk(reset, `reset ${definition.username}`)
    accounts.set(definition.username, await roles.json() as Account)
  }
  return Object.fromEntries(accounts)
}

async function activateAccounts(baseURL: string): Promise<void> {
  for (const definition of accountDefinitions) {
    const loggedIn = await loginAny(
      baseURL,
      definition.username,
      [definition.initialPassword, passwords[definition.username]],
    )
    if (loggedIn.user.mustChangePassword) {
      await changePassword(
        loggedIn.api,
        loggedIn.password,
        passwords[definition.username],
      )
    }
    await loggedIn.api.dispose()
  }
}

async function ensureRoster(leader: APIRequestContext): Promise<void> {
  const response = await leader.get('/api/rosters')
  await requireOk(response, 'roster listing')
  const existing = await response.json() as Array<{ dutyDate: string }>
  const existingDates = new Set(existing.map((item) => item.dutyDate))
  const dates = [todayInShanghai(), tomorrowInShanghai()]
    .filter((date) => !existingDates.has(date))
  if (dates.length === 0) return

  const workbook = await createRosterWorkbook(
    dates.map((date) => [date, 'ops1', 'ops2']),
  )
  const preview = await leader.post('/api/rosters/imports/preview', {
    multipart: {
      file: {
        name: 'e2e-roster.xlsx',
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        buffer: workbook,
      },
    },
    headers: { 'X-XSRF-TOKEN': await csrf(leader) },
  })
  await requireOk(preview, 'roster preview')
  const body = await preview.json() as { batchId: string; valid: boolean }
  if (!body.valid) throw new Error('Generated E2E roster did not validate')
  const confirm = await mutation(
    leader,
    'POST',
    `/api/rosters/imports/${body.batchId}/confirm`,
  )
  if (!confirm.ok() && confirm.status() !== 409) {
    throw new Error(`roster confirmation failed: ${await responseError(confirm)}`)
  }
}

export async function seedEnvironment(): Promise<void> {
  const baseURL = process.env.E2E_BASE_URL || 'http://127.0.0.1:18080'
  const leader = await leaderSession(baseURL)
  try {
    const accounts = await ensureAccounts(leader.api)
    await activateAccounts(baseURL)
    await ensureRoster(leader.api)
    const manifest = {} as Record<E2eUser, SeedUser>
    manifest.leader = {
      id: leader.user.id,
      username: 'leader',
      displayName: leader.user.displayName,
    }
    for (const definition of accountDefinitions) {
      const account = accounts[definition.username]
      manifest[definition.username] = {
        id: account.id,
        username: definition.username,
        displayName: definition.displayName,
      }
    }
    await mkdir(authDirectory, { recursive: true })
    await writeFile(
      resolve(authDirectory, 'seed.json'),
      `${JSON.stringify(manifest, null, 2)}\n`,
      'utf8',
    )
  } finally {
    await leader.api.dispose()
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  seedEnvironment().catch((error: unknown) => {
    console.error(error)
    process.exitCode = 1
  })
}
