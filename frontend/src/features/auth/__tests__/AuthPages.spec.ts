import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, type Pinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LoginPage from '../LoginPage.vue'
import ForbiddenPage from '@/pages/ForbiddenPage.vue'
import NotFoundPage from '@/pages/NotFoundPage.vue'
import { useAuthStore } from '../auth.store'

let pinia: Pinia
let router: Router

beforeEach(async () => {
  vi.restoreAllMocks()
  pinia = createPinia()
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: LoginPage },
      { path: '/workspace', component: { template: '<div />' } },
      { path: '/change-password', component: { template: '<div />' } },
    ],
  })
  await router.push('/login')
  await router.isReady()
})

function mountLoginPage() {
  return mount(LoginPage, { global: { plugins: [pinia, router] } })
}

async function fillAndSubmit(wrapper: VueWrapper) {
  await wrapper.get('[autocomplete="username"]').setValue('dev1')
  await wrapper.get('[autocomplete="current-password"]').setValue('wrong-password')
  await wrapper.get('form').trigger('submit')
}

describe('authentication surfaces', () => {
  it('renders login as one form surface without the legacy dark brand split', () => {
    const wrapper = mountLoginPage()

    expect(wrapper.get('form').classes()).toContain('auth-surface')
    expect(wrapper.find('.auth-brand').exists()).toBe(false)
    expect(wrapper.find('[autocomplete="username"]').exists()).toBe(true)
    expect(wrapper.find('[autocomplete="current-password"]').exists()).toBe(true)
  })

  it('keeps recovery pages to one clear route back to the workspace', () => {
    const global = {
      stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } },
    }

    for (const Page of [ForbiddenPage, NotFoundPage]) {
      const wrapper = mount(Page, { global })
      expect(wrapper.get('.recovery-page').classes()).toContain('ui-panel')
      expect(wrapper.findAll('a')).toHaveLength(1)
      expect(wrapper.get('a').attributes('href')).toBe('/workspace')
    }
  })
  it('keeps the 401 message inline and does not navigate', async () => {
    vi.spyOn(useAuthStore(pinia), 'signIn').mockRejectedValue({ response: { status: 401 } })
    const wrapper = mountLoginPage()

    await fillAndSubmit(wrapper)

    await vi.waitFor(() => {
      expect(wrapper.get('[role="alert"]').text()).toContain('账号或密码错误')
    })
    expect(router.currentRoute.value.path).toBe('/login')
  })
})
