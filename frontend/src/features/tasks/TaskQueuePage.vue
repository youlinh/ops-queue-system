<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import {
  computed,
  onMounted,
  onUnmounted,
  reactive,
  ref,
  watch,
} from 'vue'
import { useRouter } from 'vue-router'
import { searchTasks } from './task.api'
import { shanghaiDate } from './shanghai-time'
import TaskFilters from './TaskFilters.vue'
import {
  categoryLabels,
  statusLabels,
  type TaskPage,
  type TaskSearch,
} from './task.types'

const props = withDefaults(defineProps<{ dashboard?: boolean }>(), {
  dashboard: false,
})
const router = useRouter()
const loading = ref(false)
const errorMessage = ref('')
const emptyPage = (): TaskPage => ({
  content: [],
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
})
const page = ref<TaskPage>(emptyPage())
let requestSequence = 0
let dateTimer: ReturnType<typeof setInterval> | null = null
let activeShanghaiDate = shanghaiDate()

const search = reactive<TaskSearch>({
  operationDate: props.dashboard ? activeShanghaiDate : '',
  category: '',
  systemName: '',
  status: '',
  creatorId: '',
  assigneeId: '',
  page: 0,
  size: 20,
})
const heading = computed(() => props.dashboard ? '今日任务队列' : '任务中心')

async function load(next: TaskSearch = search): Promise<void> {
  const request = ++requestSequence
  loading.value = true
  errorMessage.value = ''
  Object.assign(search, next)
  const query = cleanSearch({ ...search })
  try {
    const result = await searchTasks(query)
    if (request === requestSequence) {
      page.value = result
    }
  } catch (error: unknown) {
    if (request === requestSequence) {
      page.value = emptyPage()
      errorMessage.value = apiErrorMessage(error, '任务列表加载失败')
    }
  } finally {
    if (request === requestSequence) {
      loading.value = false
    }
  }
}

function cleanSearch(source: TaskSearch): TaskSearch {
  return Object.fromEntries(
    Object.entries(source).filter(([, value]) =>
      value !== '' && value !== undefined && value !== null,
    ),
  ) as TaskSearch
}

function applyFilters(filters: TaskSearch): void {
  load({ ...search, ...filters, page: 0 })
}

function resetFilters(): void {
  load({
    operationDate: props.dashboard ? shanghaiDate() : '',
    category: '',
    systemName: '',
    status: '',
    creatorId: '',
    assigneeId: '',
    page: 0,
    size: 20,
  })
}

function changePage(next: number): void {
  if (next < 0 || next >= page.value.totalPages) return
  load({ ...search, page: next })
}

function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

const reload = () => load()
onMounted(() => {
  load()
  window.addEventListener('ops-task-changed', reload)
  dateTimer = setInterval(() => {
    const current = shanghaiDate()
    if (current !== activeShanghaiDate) {
      activeShanghaiDate = current
      if (props.dashboard) resetFilters()
    }
  }, 60_000)
})
watch(() => props.dashboard, resetFilters)
onUnmounted(() => {
  requestSequence++
  window.removeEventListener('ops-task-changed', reload)
  if (dateTimer) clearInterval(dateTimer)
})
</script>

<template>
  <section class="content-panel queue-panel">
    <div class="panel-heading queue-heading">
      <div>
        <p class="eyebrow">{{ dashboard ? 'TODAY QUEUE' : 'ALL TASKS' }}</p>
        <h2>{{ heading }}</h2>
        <p>
          {{ dashboard
            ? '按操作开始时间排列当天全部任务。'
            : '开发人员查看本人任务；当天值班和组长可查看整体任务。' }}
        </p>
      </div>
      <span class="queue-total">共 {{ page.totalElements }} 项</span>
    </div>

    <TaskFilters
      :initial="search"
      @search="applyFilters"
      @reset="resetFilters"
    />
    <p v-if="errorMessage" class="form-error" role="alert">
      {{ errorMessage }}
    </p>

    <div class="task-table-wrap">
      <table class="task-table">
        <thead>
          <tr>
            <th>叫号单</th>
            <th>类别 / 系统</th>
            <th>流程编号</th>
            <th>操作时间范围</th>
            <th>状态</th>
            <th>创建人</th>
            <th>执行管理员</th>
            <th>预计 / 实际</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading">
            <td colspan="8" class="table-empty">正在加载任务…</td>
          </tr>
          <tr v-else-if="page.content.length === 0">
            <td colspan="8" class="table-empty">当前筛选条件下暂无任务</td>
          </tr>
          <template v-else>
            <tr
              v-for="task in page.content"
              :key="task.id"
              class="task-row"
              tabindex="0"
              @click="router.push(`/tasks/${task.id}`)"
              @keydown.enter="router.push(`/tasks/${task.id}`)"
            >
              <td>
                <strong>{{ task.ticketNumber }}</strong>
                <small v-if="task.needsManualAttention">需人工处理</small>
              </td>
              <td>
                <span>{{ categoryLabels[task.category] }}</span>
                <small>{{ task.systemName }}</small>
              </td>
              <td>{{ task.processNumber }}</td>
              <td class="time-range">
                <span>{{ formatDateTime(task.operationStart) }}</span>
                <small>至 {{ formatDateTime(task.operationEnd) }}</small>
              </td>
              <td>
                <span class="status-pill" :class="`status-${task.status}`">
                  {{ statusLabels[task.status] }}
                </span>
              </td>
              <td>{{ task.creatorName }}</td>
              <td>{{ task.currentAssigneeName }}</td>
              <td>
                {{ task.estimatedMinutes }} 分钟
                <small>实际 {{ task.actualMinutes ?? '—' }}</small>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <footer v-if="page.totalPages > 1" class="pagination">
      <button
        class="action-button"
        type="button"
        :disabled="page.page === 0"
        @click="changePage(page.page - 1)"
      >
        上一页
      </button>
      <span>第 {{ page.page + 1 }} / {{ page.totalPages }} 页</span>
      <button
        class="action-button"
        type="button"
        :disabled="page.page + 1 >= page.totalPages"
        @click="changePage(page.page + 1)"
      >
        下一页
      </button>
    </footer>
  </section>
</template>
