import { beforeEach, describe, expect, it, vi } from 'vitest'
import { http, resetCsrf, unsafeRequest } from '../http'

describe('unsafeRequest', () => {
  beforeEach(() => {
    resetCsrf()
    vi.restoreAllMocks()
  })

  it('refreshes the CSRF cookie before each sequential write', async () => {
    const csrf = vi.spyOn(http, 'get').mockResolvedValue({ data: {} } as never)
    const request = vi.spyOn(http, 'request')
      .mockResolvedValue({ status: 204 } as never)

    await unsafeRequest({ method: 'post', url: '/tasks/one/call' })
    await unsafeRequest({ method: 'post', url: '/tasks/one/complete' })

    expect(request).toHaveBeenCalledTimes(2)
    expect(csrf).toHaveBeenCalledTimes(2)
  })
})
