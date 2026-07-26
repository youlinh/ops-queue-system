<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import { useAuthStore } from '@/features/auth/auth.store'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { listOperators, taskDetail } from './task.api'
import TaskActions from './TaskActions.vue'
import {
  assignmentRuleLabel,
  categoryLabels,
  statusLabels,
  type OperatorOption,
  type TaskDetail,
} from './task.types'

const route = useRoute()
const auth = useAuthStore()
const task = ref<TaskDetail | null>(null)
const operators = ref<OperatorOption[]>([])
const loading = ref(false)
const errorMessage = ref('')
const operatorError = ref('')
const id = computed(() => String(route.params.id))
let requestSequence = 0

function formatDateTime(value: string | null): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

async function load(): Promise<void> {
  const request = ++requestSequence
  const requestedId = id.value
  loading.value = true
  errorMessage.value = ''
  operatorError.value = ''
  task.value = null
  operators.value = []
  try {
    const result = await taskDetail(requestedId)
    if (request !== requestSequence) return
    task.value = result
    if (result.canTransfer) {
      try {
        const options = await listOperators(requestedId)
        if (request === requestSequence) {
          operators.value = options
        }
      } catch (error: unknown) {
        if (request === requestSequence) {
          operatorError.value = apiErrorMessage(
            error,
            '转交管理员目录加载失败，请重试',
          )
        }
      }
    }
  } catch (error: unknown) {
    if (request === requestSequence) {
      task.value = null
      errorMessage.value = apiErrorMessage(error, '任务详情加载失败')
    }
  } finally {
    if (request === requestSequence) {
      loading.value = false
    }
  }
}

async function retryOperators(): Promise<void> {
  if (!task.value?.canTransfer) return
  const request = requestSequence
  operatorError.value = ''
  try {
    const options = await listOperators(task.value.id)
    if (request === requestSequence) operators.value = options
  } catch (error: unknown) {
    if (request === requestSequence) {
      operatorError.value = apiErrorMessage(
        error,
        '转交管理员目录加载失败，请重试',
      )
    }
  }
}

onMounted(load)
watch(id, load)
</script>

<template>
  <section v-if="loading" class="content-panel detail-loading">
    正在加载任务详情…
  </section>
  <section v-else-if="errorMessage" class="content-panel">
    <p class="form-error" role="alert">{{ errorMessage }}</p>
  </section>
  <div v-else-if="task" class="task-detail-layout">
    <section class="content-panel detail-main">
      <div class="detail-title">
        <div>
          <p class="eyebrow">TASK DETAIL</p>
          <h2>{{ task.ticketNumber }}</h2>
        </div>
        <span class="status-pill" :class="`status-${task.status}`">
          {{ statusLabels[task.status] }}
        </span>
      </div>

      <dl class="detail-grid">
        <div><dt>类别</dt><dd>{{ categoryLabels[task.category] }}</dd></div>
        <div><dt>系统名称</dt><dd>{{ task.systemName }}</dd></div>
        <div><dt>流程编号</dt><dd>{{ task.processNumber }}</dd></div>
        <div>
          <dt>操作时间</dt>
          <dd>
            {{ formatDateTime(task.operationStart) }}
            至 {{ formatDateTime(task.operationEnd) }}
          </dd>
        </div>
        <div><dt>创建人</dt><dd>{{ task.creatorName }}</dd></div>
        <div><dt>执行管理员</dt><dd>{{ task.currentAssigneeName }}</dd></div>
        <div><dt>预计耗时</dt><dd>{{ task.estimatedMinutes }} 分钟</dd></div>
        <div><dt>实际耗时</dt><dd>{{ task.actualMinutes ?? '—' }}{{ task.actualMinutes ? ' 分钟' : '' }}</dd></div>
        <div>
          <dt>分派规则</dt>
          <dd>{{ assignmentRuleLabel(task.assignmentRule) }}</dd>
        </div>
        <div><dt>叫号时间</dt><dd>{{ formatDateTime(task.calledAt) }}</dd></div>
      </dl>

      <TaskActions
        v-if="auth.user"
        :task="task"
        :current-user-id="auth.user.id"
        :roles="auth.user.roles"
        :operators="operators"
        :operator-directory-error="operatorError"
        @changed="load"
      />
      <p v-if="operatorError" class="form-error" role="alert">
        {{ operatorError }}
        <button class="text-button" type="button" @click="retryOperators">
          重新加载
        </button>
      </p>
    </section>

    <aside class="content-panel timeline-panel">
      <p class="eyebrow">ASSIGNMENT TIMELINE</p>
      <h3>分派时间线</h3>
      <ol v-if="task.assignmentTimeline.length">
        <li v-for="entry in task.assignmentTimeline" :key="`${entry.assignedAt}-${entry.newAssigneeId}`">
          <i />
          <div>
            <strong>{{ entry.newAssigneeName }}</strong>
            <span>{{ assignmentRuleLabel(entry.assignmentRule) }}</span>
            <p v-if="entry.reason">{{ entry.reason }}</p>
            <time>{{ formatDateTime(entry.assignedAt) }}</time>
          </div>
        </li>
      </ol>
      <p v-else class="muted">暂无分派记录</p>
    </aside>
  </div>
</template>
