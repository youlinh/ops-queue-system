import type { Router } from 'vue-router'

export function canGoBackToApp(back: unknown, currentPath: string): back is string {
  return typeof back === 'string'
    && back.startsWith('/')
    && !back.startsWith('//')
    && back !== currentPath
}

export async function closeTaskSheet(router: Router, currentPath: string): Promise<void> {
  const back = window.history.state?.back
  if (canGoBackToApp(back, currentPath)) {
    router.back()
    return
  }
  await router.replace({ name: 'tasks' })
}