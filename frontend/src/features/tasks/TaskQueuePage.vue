<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { searchTasks } from './task.api'
import { shanghaiDate } from './shanghai-time'
import TaskFilters from './TaskFilters.vue'
import TaskRow from './TaskRow.vue'
import { type TaskPage, type TaskSearch } from './task.types'

const props = withDefaults(defineProps<{ dashboard?: boolean }>(), { dashboard: false })
const router = useRouter()
const loading = ref(false)
const errorMessage = ref('')
const emptyPage = (): TaskPage => ({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
const page = ref<TaskPage>(emptyPage())
let requestSequence = 0
let dateTimer: ReturnType<typeof setInterval> | null = null
let activeShanghaiDate = shanghaiDate()
const search = reactive<TaskSearch>({ operationDate: props.dashboard ? activeShanghaiDate : '', category: '', systemName: '', status: '', creatorId: '', assigneeId: '', page: 0, size: 20 })
const heading = computed(() => props.dashboard ? '今日任务队列' : '任务中心')
const hasResults = computed(() => page.value.content.length > 0)

async function load(next: TaskSearch = search): Promise<void> {
  const request = ++requestSequence
  loading.value = true
  errorMessage.value = ''
  Object.assign(search, next)
  const query = cleanSearch({ ...search })
  try {
    const result = await searchTasks(query)
    if (request === requestSequence) page.value = result
  } catch (error: unknown) {
    if (request === requestSequence) {
      page.value = emptyPage()
      errorMessage.value = apiErrorMessage(error, '任务列表加载失败')
    }
  } finally {
    if (request === requestSequence) loading.value = false
  }
}
function cleanSearch(source: TaskSearch): TaskSearch { return Object.fromEntries(Object.entries(source).filter(([, value]) => value !== '' && value !== undefined && value !== null)) as TaskSearch }
function applyFilters(filters: TaskSearch): void { load({ ...search, ...filters, page: 0 }) }
function resetFilters(): void { load({ operationDate: props.dashboard ? shanghaiDate() : '', category: '', systemName: '', status: '', creatorId: '', assigneeId: '', page: 0, size: 20 }) }
function changePage(next: number): void { if (next < 0 || next >= page.value.totalPages) return; load({ ...search, page: next }) }
function openTask(id: string): void { router.push({ name: 'task-detail', params: { id } }) }
const reload = () => load()
onMounted(() => {
  load()
  window.addEventListener('ops-task-changed', reload)
  dateTimer = setInterval(() => {
    const current = shanghaiDate()
    if (current !== activeShanghaiDate) { activeShanghaiDate = current; if (props.dashboard) resetFilters() }
  }, 60_000)
})
watch(() => props.dashboard, resetFilters)
onUnmounted(() => { requestSequence++; window.removeEventListener('ops-task-changed', reload); if (dateTimer) clearInterval(dateTimer) })
</script>
<template>
  <section class="content-panel queue-panel ui-panel">
    <div class="panel-heading queue-heading">
      <div><p class="eyebrow">{{ dashboard ? 'TODAY QUEUE' : 'ALL TASKS' }}</p><h2>{{ heading }}</h2><p>{{ dashboard ? '按操作开始时间排列当天全部任务。' : '开发人员查看本人任务；当天值班和组长可查看整体任务。' }}</p></div>
      <span class="queue-total">共 {{ page.totalElements }} 项</span>
    </div>
    <TaskFilters :initial="search" @search="applyFilters" @reset="resetFilters" />
    <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
    <p v-if="loading" class="queue-refresh-status" role="status">{{ hasResults ? '正在更新任务列表' : '正在加载任务' }}</p>
    <div class="task-list" data-testid="task-list" aria-live="polite">
      <p v-if="!loading && !hasResults" class="task-list__empty">当前筛选条件下暂无任务</p>
      <TaskRow v-for="task in page.content" :key="task.id" :task="task" @open="openTask" />
    </div>
    <footer v-if="page.totalPages > 1" class="pagination">
      <button class="ui-button" type="button" :disabled="page.page === 0" @click="changePage(page.page - 1)">上一页</button>
      <span>第 {{ page.page + 1 }} / {{ page.totalPages }} 页</span>
      <button class="ui-button" type="button" :disabled="page.page + 1 >= page.totalPages" @click="changePage(page.page + 1)">下一页</button>
    </footer>
  </section>
</template>
<style scoped>
.queue-panel { padding: var(--ui-space-5) 0 0; }.queue-heading { margin: 0 var(--ui-space-5); }.queue-total { color: var(--ui-text-secondary); font-size: .875rem; font-variant-numeric: tabular-nums; white-space: nowrap; }.queue-refresh-status { margin: 0 var(--ui-space-5) var(--ui-space-3); color: var(--ui-text-secondary); font-size: .875rem; }.task-list { min-width: 0; }.task-list__empty { display: grid; min-height: 10rem; margin: 0; place-items: center; padding: var(--ui-space-5); color: var(--ui-text-secondary); }.pagination { margin-top: var(--ui-space-2); padding: var(--ui-space-4) var(--ui-space-5); border-top: var(--ui-border-width) solid var(--ui-hairline); }@media (max-width: 680px) { .queue-heading { margin: 0 var(--ui-space-4); }.queue-total { padding-top: var(--ui-space-2); }.queue-refresh-status { margin-right: var(--ui-space-4); margin-left: var(--ui-space-4); }.pagination { justify-content: space-between; padding: var(--ui-space-4); } }
</style>