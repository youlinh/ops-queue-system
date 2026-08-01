<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import AppDialog from '@/components/ui/AppDialog.vue'
import { searchTasks } from '@/features/tasks/task.api'
import type { TaskRow } from '@/features/tasks/task.types'
import { onMounted, ref } from 'vue'
import { previewRedistribution, redistribute } from './people.api'
import type {
  AccountView,
  RedistributionItemResult,
  RedistributionTask,
} from './people.types'

const props = defineProps<{
  operator: AccountView
  date: string
  reason: string
}>()
const emit = defineEmits<{ close: []; completed: [] }>()
const pending = ref<RedistributionTask[]>([])
const executing = ref<TaskRow[]>([])
const results = ref<RedistributionItemResult[]>([])
const loading = ref(false)
const submitting = ref(false)
const errorMessage = ref('')

async function loadPreview(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const [pendingTasks, executingPage] = await Promise.all([
      previewRedistribution(props.operator.id, props.date),
      searchTasks({
        operationDate: props.date,
        assigneeId: props.operator.id,
        status: 'IN_PROGRESS',
        page: 0,
        size: 100,
      }),
    ])
    pending.value = pendingTasks
    executing.value = executingPage.content
  } catch (error: unknown) {
    errorMessage.value = apiErrorMessage(error, '重新分配预览加载失败')
  } finally {
    loading.value = false
  }
}

async function execute(): Promise<void> {
  if (submitting.value || pending.value.length === 0) return
  submitting.value = true
  errorMessage.value = ''
  try {
    const response = await redistribute({
      operatorId: props.operator.id,
      date: props.date,
      reason: props.reason,
    })
    results.value = response.items
    pending.value = []
    emit('completed')
  } catch (error: unknown) {
    errorMessage.value = apiErrorMessage(error, '重新分配执行失败')
  } finally {
    submitting.value = false
  }
}

onMounted(loadPreview)
</script>

<template>
  <AppDialog
    :open="true" labelled-by="redistribution-dialog-title"
    @close="emit('close')"
  >
    <p class="eyebrow">REDISTRIBUTE</p>
      <h3 id="redistribution-dialog-title">{{ operator.displayName }} · {{ date }} 任务重新分配</h3>
      <p v-if="loading" class="muted">正在检查任务…</p>
      <p v-if="errorMessage" class="form-error" role="alert">
        {{ errorMessage }}
      </p>

      <div v-if="pending.length" class="redistribution-section">
        <h4>待执行任务（{{ pending.length }}）</h4>
        <ul class="compact-task-list">
          <li v-for="task in pending" :key="task.taskId">
            <RouterLink :to="`/tasks/${task.taskId}`">
              {{ task.ticketNumber }}
            </RouterLink>
            <span>{{ task.systemName }}</span>
          </li>
        </ul>
      </div>

      <div v-if="executing.length" class="manual-notice" role="status">
        <strong>需手工调整：{{ executing.length }} 个执行中任务不会自动转移</strong>
        <RouterLink
          v-for="task in executing"
          :key="task.id"
          :to="`/tasks/${task.id}`"
        >
          {{ task.ticketNumber }}
        </RouterLink>
      </div>

      <div v-if="results.length" class="redistribution-results">
        <article
          v-for="item in results"
          :key="item.taskId"
          :class="item.success ? 'result-success' : 'result-failed'"
        >
          <RouterLink :to="`/tasks/${item.taskId}`">
            {{ item.ticketNumber }}
          </RouterLink>
          <span v-if="item.success">重新分配成功</span>
          <span v-else>{{ item.error || '重新分配失败，需人工处理' }}</span>
        </article>
      </div>

      <p
        v-if="!loading && !pending.length && !results.length"
        class="muted"
      >
        当前没有可自动重新分配的待执行任务。
      </p>
      <div class="dialog-actions">
        <button class="ui-button" type="button" @click="emit('close')">
          关闭
        </button>
        <button
          data-testid="execute-redistribution"
          class="ui-button ui-button--primary"
          type="button"
          :disabled="submitting || loading || pending.length === 0"
          @click="execute"
        >
          {{ submitting ? '重新分配中…' : '确认重新分配待执行任务' }}
        </button>
      </div>
  </AppDialog>
</template>
