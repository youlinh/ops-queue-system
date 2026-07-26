import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as authApi from '../auth.api'
import { useAuthStore } from '../auth.store'

vi.mock('../auth.api')

const user = {
  id: '00000000-0000-0000-0000-000000000001',
  username: 'operator',
  displayName: '值班运维',
  roles: ['OPERATOR'] as const,
  mustChangePassword: false,
}

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })

  it('restores an authenticated session', async () => {
    vi.mocked(authApi.me).mockResolvedValue(user)
    const store = useAuthStore()

    await store.restore()

    expect(store.user).toEqual(user)
    expect(store.restored).toBe(true)
  })

  it('clears stale state after a 401 restore response', async () => {
    vi.mocked(authApi.me).mockRejectedValue({
      response: { status: 401 },
    })
    const store = useAuthStore()
    store.user = user

    await store.restore()

    expect(store.user).toBeNull()
    expect(store.restored).toBe(true)
  })
})
