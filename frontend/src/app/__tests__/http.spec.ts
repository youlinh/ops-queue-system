import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ensureCsrf, http, resetCsrf, unsafeRequest } from '../http'

describe('CSRF-aware HTTP client', () => {
  beforeEach(() => {
    resetCsrf()
    vi.restoreAllMocks()
  })

  it('bootstraps CSRF before the first unsafe request and reuses it', async () => {
    const order: string[] = []
    vi.spyOn(http, 'get').mockImplementation(async () => {
      order.push('csrf')
      return { data: { token: 'token' } } as never
    })
    vi.spyOn(http, 'request').mockImplementation(async () => {
      order.push('mutation')
      return { data: undefined } as never
    })

    await unsafeRequest({ method: 'post', url: '/auth/logout' })
    await unsafeRequest({ method: 'put', url: '/admin/users/id/roles' })

    expect(order).toEqual(['csrf', 'mutation', 'mutation'])
  })

  it('allows an explicit CSRF bootstrap without a mutation', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({
      data: { token: 'token' },
    })

    await ensureCsrf()

    expect(get).toHaveBeenCalledWith('/auth/csrf')
  })
})
