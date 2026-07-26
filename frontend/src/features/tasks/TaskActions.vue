<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import type { Role } from '@/features/auth/auth.types'
import { computed, ref } from 'vue'
import {
  callTask,
  completeTask,
  notifyTaskChanged,
  transferTask,
} from './task.api'
import type { OperatorOption, TaskDetail } from './task.types'

const props = defineProps<{
  task: TaskDetail
  currentUserId: string
  roles: readonly Role[]
  operators: OperatorOption[]
}>()
const emit = defineEmits<{ changed: [] }>()

const dialog = ref<'complete' | 'transfer' | null>(null)
const actualMinutes = ref<number | null>(null)
const targetId = ref('')
const reason = ref('')
const submitting = ref(false)
const errorMessage = ref('')

const operationalRole = computed(() =>
  props.roles.includes('OPERATOR') || props.roles.includes('LEADER'),
)
const currentAssignee = computed(() =>
  props.currentUserId === props.task.currentAssigneeId,
)
const canCall = computed(() =>
  operationalRole.value && currentAssignee.value && props.task.canCall,
)
const canComplete = computed(() =>
  operationalRole.value && currentAssignee.value && props.task.canComplete,
)
const canTransfer = computed(() =>
  operationalRole.value && currentAssignee.value && props.task.canTransfer,
)

async function runCall(): Promise<void> {
  if (submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await callTask(props.task.id)
    changed()
  } catch (error: unknown) {
    errorMessage.value = apiErrorMessage(error, '叫号失败，请刷新后重试')
  } finally {
    submitting.value = false
  }
}

async function runComplete(): Promise<void> {
  errorMessage.value = ''
  if (!actualMinutes.value || actualMinutes.value <= 0) {
    errorMessage.value = '实际耗时必须大于 0'
    return
  }
  submitting.value = true
  try {
    await completeTask(props.task.id, actualMinutes.value)
    dialog.value = null
    changed()
  } catch (error: unknown) {
    errorMessage.value = apiErrorMessage(error, '完成任务失败，请刷新后重试')
  } finally {
    submitting.value = false
  }
}

async function runTransfer(): Promise<void> {
  const errors: string[] = []
  if (!targetId.value) errors.push('请选择转交管理员')
  if (!reason.value.trim()) errors.push('请输入转交原因')
  errorMessage.value = errors.join('；')
  if (errors.length > 0) return

  submitting.value = true
  try {
    await transferTask(props.task.id, targetId.value, reason.value.trim())
    dialog.value = null
    changed()
  } catch (error: unknown) {
    errorMessage.value = apiErrorMessage(error, '任务转交失败，请刷新后重试')
  } finally {
    submitting.value = false
  }
}

function changed(): void {
  notifyTaskChanged()
  emit('changed')
}

function open(next: 'complete' | 'transfer'): void {
  errorMessage.value = ''
  dialog.value = next
}
</script>

<template>
  <div v-if="operationalRole" class="task-actions">
    <button
      v-if="canCall"
      data-testid="call-task"
      class="action-button action-button--primary"
      type="button"
      :disabled="submitting"
      @click="runCall"
    >
      {{ submitting ? '叫号中…' : '叫号' }}
    </button>
    <button
      v-if="canComplete"
      data-testid="complete-task"
      class="action-button action-button--primary"
      type="button"
      @click="open('complete')"
    >
      填写实际耗时并完成
    </button>
    <button
      v-if="canTransfer"
      data-testid="transfer-task"
      class="action-button"
      type="button"
      @click="open('transfer')"
    >
      转交
    </button>

    <p v-if="errorMessage && !dialog" class="form-error" role="alert">
      {{ errorMessage }}
    </p>

    <div v-if="dialog" class="dialog-backdrop">
      <section class="task-dialog" role="dialog" aria-modal="true">
        <template v-if="dialog === 'complete'">
          <p class="eyebrow">COMPLETE TASK</p>
          <h3>填写实际耗时</h3>
          <label class="field">
            <span>实际耗时（分钟）</span>
            <input
              v-model.number="actualMinutes"
              data-testid="actual-minutes"
              type="number"
              min="1"
            />
          </label>
        </template>

        <template v-else>
          <p class="eyebrow">TRANSFER TASK</p>
          <h3>直接转交管理员</h3>
          <label class="field">
            <span>目标管理员</span>
            <select v-model="targetId" data-testid="transfer-target">
              <option value="">请选择</option>
              <option
                v-for="operator in operators"
                :key="operator.id"
                :value="operator.id"
                :disabled="!operator.available || operator.id === currentUserId"
              >
                {{ operator.displayName }}
                {{ operator.available ? '' : '（操作日不可参与）' }}
              </option>
            </select>
          </label>
          <label class="field">
            <span>转交原因</span>
            <textarea
              v-model="reason"
              data-testid="transfer-reason"
              maxlength="1000"
              rows="4"
            />
          </label>
        </template>

        <p v-if="errorMessage" class="form-error" role="alert">
          {{ errorMessage }}
        </p>
        <div class="dialog-actions">
          <button
            class="action-button"
            type="button"
            :disabled="submitting"
            @click="dialog = null"
          >
            取消
          </button>
          <button
            v-if="dialog === 'complete'"
            data-testid="confirm-complete"
            class="action-button action-button--primary"
            type="button"
            :disabled="submitting"
            @click="runComplete"
          >
            确认完成
          </button>
          <button
            v-else
            data-testid="confirm-transfer"
            class="action-button action-button--primary"
            type="button"
            :disabled="submitting"
            @click="runTransfer"
          >
            确认转交
          </button>
        </div>
      </section>
    </div>
  </div>
</template>
