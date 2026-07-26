import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as authApi from './auth.api'
import type {
  ChangePasswordCommand,
  CurrentUser,
  LoginCommand,
  Role,
} from './auth.types'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  const restored = ref(false)
  const restoring = ref(false)

  const authenticated = computed(() => user.value !== null)

  function hasAnyRole(required: readonly Role[]): boolean {
    if (required.length === 0) {
      return true
    }
    return required.some((role) => user.value?.roles.includes(role))
  }

  async function restore(): Promise<void> {
    if (restoring.value) {
      return
    }
    restoring.value = true
    try {
      user.value = await authApi.me()
    } catch (error: unknown) {
      const status = (error as { response?: { status?: number } })?.response?.status
      user.value = null
      if (status !== 401) {
        throw error
      }
    } finally {
      restoring.value = false
      restored.value = true
    }
  }

  async function signIn(command: LoginCommand): Promise<CurrentUser> {
    const current = await authApi.login(command)
    user.value = current
    restored.value = true
    return current
  }

  async function signOut(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      user.value = null
      restored.value = true
    }
  }

  async function changeOwnPassword(
    command: ChangePasswordCommand,
  ): Promise<void> {
    await authApi.changePassword(command)
    user.value = await authApi.me()
  }

  return {
    user,
    restored,
    restoring,
    authenticated,
    hasAnyRole,
    restore,
    signIn,
    signOut,
    changeOwnPassword,
  }
})
