import axios, { type AxiosRequestConfig, type AxiosResponse } from 'axios'

export const http = axios.create({
  baseURL: '/api',
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  timeout: 15_000,
})

let csrfBootstrap: Promise<void> | null = null

export function ensureCsrf(): Promise<void> {
  if (!csrfBootstrap) {
    csrfBootstrap = http.get('/auth/csrf')
      .then(() => undefined)
      .catch((error: unknown) => {
        csrfBootstrap = null
        throw error
      })
  }
  return csrfBootstrap
}

export function resetCsrf(): void {
  csrfBootstrap = null
}

export async function unsafeRequest<T = unknown>(
  config: AxiosRequestConfig,
): Promise<AxiosResponse<T>> {
  await ensureCsrf()
  return http.request<T>(config)
}

export function apiErrorMessage(
  error: unknown,
  fallback = '操作失败，请稍后重试',
): string {
  if (!axios.isAxiosError(error)) {
    return fallback
  }
  const data = error.response?.data as { message?: string; code?: string } | undefined
  if (data?.code === 'PASSWORD_CHANGE_REQUIRED') {
    return '首次登录需要先修改密码'
  }
  return data?.message || fallback
}
