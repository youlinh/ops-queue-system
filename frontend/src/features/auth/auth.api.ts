import { http, resetCsrf, unsafeRequest } from '@/app/http'
import type {
  ChangePasswordCommand,
  CurrentUser,
  LoginCommand,
} from './auth.types'

export async function login(command: LoginCommand): Promise<CurrentUser> {
  const response = await http.post<CurrentUser>('/auth/login', command)
  resetCsrf()
  return response.data
}

export async function me(): Promise<CurrentUser> {
  const response = await http.get<CurrentUser>('/auth/me')
  return response.data
}

export async function logout(): Promise<void> {
  await unsafeRequest<void>({
    method: 'post',
    url: '/auth/logout',
  })
  resetCsrf()
}

export async function changePassword(
  command: ChangePasswordCommand,
): Promise<void> {
  await unsafeRequest<void>({
    method: 'post',
    url: '/auth/change-password',
    data: command,
  })
}
