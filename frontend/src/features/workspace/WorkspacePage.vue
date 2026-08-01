<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import { useAuthStore } from '@/features/auth/auth.store'
import TaskQueuePage from '@/features/tasks/TaskQueuePage.vue'
import { shanghaiDate } from '@/features/tasks/shanghai-time'
import { taskCounts } from '@/features/tasks/task.api'
import type { TaskCounts } from '@/features/tasks/task.types'
import { computed, onMounted, onUnmounted, ref } from 'vue'

const auth = useAuthStore()
const counts = ref<TaskCounts>({ pending: 0, inProgress: 0, manualAttention: 0 })
const errorMessage = ref('')
const refreshToken = ref(0)
const now = ref(new Date())
let activeDate = shanghaiDate(now.value)
let dateTimer: ReturnType<typeof setInterval> | null = null
const copy = { greeting: '\u4f60\u597d\uff0c', overview: '\u4eca\u65e5\u6982\u89c8', pending: '\u5f85\u6267\u884c', progress: '\u6267\u884c\u4e2d', attention: '\u4eba\u5de5\u5173\u6ce8', refreshError: '\u4eca\u65e5\u6982\u89c8\u52a0\u8f7d\u5931\u8d25' }
const greeting = computed(() => `${copy.greeting}${auth.user?.displayName || ''}`)
const todayLabel = computed(() => new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: 'long', day: 'numeric', weekday: 'short' }).format(now.value))
async function refreshWorkspace(): Promise<void> {
  errorMessage.value = ''
  try { counts.value = await taskCounts(shanghaiDate()) } catch (error: unknown) { errorMessage.value = apiErrorMessage(error, copy.refreshError) }
  refreshToken.value += 1
}
function onTaskChanged(): void { refreshWorkspace() }
onMounted(() => {
  refreshWorkspace()
  window.addEventListener('ops-task-changed', onTaskChanged)
  dateTimer = setInterval(() => {
    const nextDate = shanghaiDate()
    if (nextDate !== activeDate) { activeDate = nextDate; now.value = new Date(); refreshWorkspace() }
  }, 60_000)
})
onUnmounted(() => { window.removeEventListener('ops-task-changed', onTaskChanged); if (dateTimer) clearInterval(dateTimer) })
</script>
<template>
  <section class="workspace-page">
    <header class="workspace-page__heading"><p class="eyebrow">WORKSPACE</p><h2>{{ greeting }}</h2><p>{{ todayLabel }}</p></header>
    <section class="workspace-overview ui-panel" data-testid="workspace-overview" :aria-label="copy.overview">
      <div><span>{{ copy.pending }}</span><strong class="ui-number">{{ counts.pending }}</strong></div>
      <div><span>{{ copy.progress }}</span><strong class="ui-number">{{ counts.inProgress }}</strong></div>
      <div><span>{{ copy.attention }}</span><strong class="ui-number">{{ counts.manualAttention }}</strong></div>
    </section>
    <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
    <TaskQueuePage dashboard :refresh-token="refreshToken" />
  </section>
</template>
<style scoped>
.workspace-page { display: grid; gap: var(--ui-space-5); }.workspace-page__heading { padding: 0 var(--ui-space-1); }.workspace-page__heading h2 { margin: 0; color: var(--ui-text); font-size: clamp(1.5rem, 3vw, 2rem); letter-spacing: -.02em; }.workspace-page__heading p:last-child { margin: var(--ui-space-1) 0 0; color: var(--ui-text-secondary); }.workspace-overview { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); }.workspace-overview > div { display: grid; gap: var(--ui-space-2); padding: var(--ui-space-5); }.workspace-overview > div + div { border-left: var(--ui-border-width) solid var(--ui-hairline); }.workspace-overview span { color: var(--ui-text-secondary); font-size: .875rem; font-weight: 600; }.workspace-overview strong { color: var(--ui-text); font-size: clamp(1.75rem, 4vw, 2.5rem); letter-spacing: -.04em; }@media (max-width: 680px) { .workspace-overview > div { padding: var(--ui-space-4); }.workspace-overview strong { font-size: 1.75rem; } }
</style>