import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useAuthStore } from '@/features/auth/auth.store'
import WorkspacePage from '../WorkspacePage.vue'
import * as taskApi from '@/features/tasks/task.api'

vi.mock('@/features/tasks/task.api')

describe('WorkspacePage', () => {
  beforeEach(() => vi.resetAllMocks())

  it('renders all daily counts in one overview surface above the dashboard queue', async () => {
    vi.mocked(taskApi.taskCounts).mockResolvedValue({ pending: 12, inProgress: 4, manualAttention: 2 })
    vi.mocked(taskApi.searchTasks).mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    const pinia = createPinia()
    useAuthStore(pinia).user = { id: 'developer-1', username: 'dev1', displayName: 'Developer', roles: ['DEVELOPER'], mustChangePassword: false }
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: WorkspacePage }, { path: '/tasks/:id', name: 'task-detail', component: WorkspacePage }] })
    const wrapper = mount(WorkspacePage, { global: { plugins: [pinia, router] } })
    await flushPromises()

    const overview = wrapper.get('[data-testid="workspace-overview"]')
    expect(overview.text()).toContain('12')
    expect(overview.text()).toContain('4')
    expect(overview.text()).toContain('2')
    expect(wrapper.findAll('.metric-card')).toHaveLength(0)
    expect(wrapper.find('.queue-panel').exists()).toBe(true)
  })
})