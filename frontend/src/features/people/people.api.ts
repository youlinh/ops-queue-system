import { http, unsafeRequest } from '@/app/http'
import type { Role } from '@/features/auth/auth.types'
import type {
  AccountView,
  RedistributionResult,
  RedistributionTask,
} from './people.types'

export async function listAccounts(): Promise<AccountView[]> {
  const response = await http.get<AccountView[]>('/admin/users')
  return response.data
}

export async function createAccount(command: {
  username: string
  displayName: string
  initialPassword: string
  roles: Role[]
}): Promise<AccountView> {
  const response = await unsafeRequest<AccountView>({
    method: 'post',
    url: '/admin/users',
    data: command,
  })
  return response.data
}

export async function disableAccount(id: string): Promise<void> {
  await unsafeRequest({ method: 'post', url: `/admin/users/${id}/disable` })
}

export async function resetAccountPassword(
  id: string,
  initialPassword: string,
): Promise<void> {
  await unsafeRequest({
    method: 'post',
    url: `/admin/users/${id}/reset-password`,
    data: { initialPassword },
  })
}

export async function replaceAccountRoles(
  id: string,
  roles: Role[],
): Promise<AccountView> {
  const response = await unsafeRequest<AccountView>({
    method: 'put',
    url: `/admin/users/${id}/roles`,
    data: { roles },
  })
  return response.data
}

export async function setUnavailable(command: {
  operatorId: string
  date: string
  reason: string
}): Promise<void> {
  await unsafeRequest({ method: 'post', url: '/unavailability', data: command })
}

export async function previewRedistribution(
  operatorId: string,
  date: string,
): Promise<RedistributionTask[]> {
  const response = await http.get<RedistributionTask[]>(
    '/assignments/redistribution/preview',
    { params: { operatorId, date } },
  )
  return response.data
}

export async function redistribute(command: {
  operatorId: string
  date: string
  reason: string
}): Promise<RedistributionResult> {
  const response = await unsafeRequest<RedistributionResult>({
    method: 'post',
    url: '/assignments/redistribution',
    data: command,
  })
  return response.data
}
