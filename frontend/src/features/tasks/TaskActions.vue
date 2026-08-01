<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import AppDialog from '@/components/ui/AppDialog.vue'
import type { Role } from '@/features/auth/auth.types'
import { computed, ref } from 'vue'
import { callTask, completeTask, notifyTaskChanged, transferTask } from './task.api'
import type { OperatorOption, TaskDetail } from './task.types'

const props = defineProps<{
  task: TaskDetail
  currentUserId: string
  roles: readonly Role[]
  operators: OperatorOption[]
  operatorDirectoryError?: string
}>()
const emit = defineEmits<{ changed: [] }>()

const dialog = ref<'complete' | 'transfer' | null>(null)
const actualMinutes = ref<number | null>(null)
const targetId = ref('')
const reason = ref('')
const submitting = ref(false)
const errorMessage = ref('')
const successMessage = ref('')

const operationalRole = computed(() => props.roles.includes('OPERATOR') || props.roles.includes('LEADER'))
const currentAssignee = computed(() => props.currentUserId === props.task.currentAssigneeId)
const canCall = computed(() => operationalRole.value && currentAssignee.value && props.task.canCall)
const canComplete = computed(() => operationalRole.value && currentAssignee.value && props.task.canComplete)
const canTransfer = computed(() => operationalRole.value && currentAssignee.value && props.task.canTransfer)

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
  if (!actualMinutes.value || actualMinutes.value <= 0 || actualMinutes.value > 1440) {
    errorMessage.value = '实际耗时需在 1 到 1440 分钟之间'
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
    const result = await transferTask(props.task.id, targetId.value, reason.value.trim())
    successMessage.value = result.warnings.join('；') || '任务已转交'
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

function open(next: 'complete' | 'transfer', event: MouseEvent): void {
  if (event.currentTarget instanceof HTMLElement) event.currentTarget.focus()
  errorMessage.value = ''
  successMessage.value = ''
  dialog.value = next
}

function closeDialog(): void {
  dialog.value = null
  errorMessage.value = ''
}
</script>

<template>
  <div v-if="operationalRole" class="task-actions" aria-label="任务操作">
    <button v-if="canCall" data-testid="call-task" class="ui-button ui-button--primary" type="button" :disabled="submitting" @click="runCall">
      {{ submitting ? '叫号中…' : '叫号' }}
    </button>
    <button v-if="canComplete" data-testid="complete-task" class="ui-button ui-button--primary" type="button" @click="open('complete', $event)">
      填写实际耗时并完成
    </button>
    <button v-if="canTransfer" data-testid="transfer-task" class="ui-button" type="button" :disabled="submitting || Boolean(operatorDirectoryError)" :title="operatorDirectoryError || undefined" @click="open('transfer', $event)">
      转交
    </button>

    <p v-if="errorMessage && !dialog" class="form-error" role="alert">{{ errorMessage }}</p>
    <p v-if="successMessage && !dialog" class="form-success" role="status">{{ successMessage }}</p>

    <AppDialog :open="dialog === 'complete'" labelled-by="complete-task-dialog-title" @close="closeDialog">
      <div class="task-action-dialog">
        <p class="eyebrow">COMPLETE TASK</p>
        <h3 id="complete-task-dialog-title">填写实际耗时</h3>
        <p class="task-action-dialog__description">确认实际耗时后，任务状态将更新为已完成。</p>
        <label class="ui-field-group">
          <span class="ui-field-label">实际耗时（分钟）</span>
          <input v-model.number="actualMinutes" data-testid="actual-minutes" class="ui-field-control ui-number" type="number" min="1" max="1440">
        </label>
        <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
        <div class="task-action-dialog__actions">
          <button data-testid="cancel-dialog" class="ui-button" type="button" @click="closeDialog">取消</button>
          <button data-testid="confirm-complete" class="ui-button ui-button--primary" type="button" :disabled="submitting" @click="runComplete">确认完成</button>
        </div>
      </div>
    </AppDialog>

    <AppDialog :open="dialog === 'transfer'" labelled-by="transfer-task-dialog-title" @close="closeDialog">
      <div class="task-action-dialog">
        <p class="eyebrow">TRANSFER TASK</p>
        <h3 id="transfer-task-dialog-title">直接转交管理员</h3>
        <p class="task-action-dialog__description">转交会记录原因，并保留原负责人和目标负责人的审计轨迹。</p>
        <label class="ui-field-group">
          <span class="ui-field-label">目标管理员</span>
          <select v-model="targetId" data-testid="transfer-target" class="ui-field-control">
            <option value="">请选择</option>
            <option v-for="operator in operators" :key="operator.id" :value="operator.id" :disabled="!operator.available || operator.id === currentUserId">
              {{ operator.displayName }}{{ operator.available ? '' : '（操作日不可参与）' }}
            </option>
          </select>
        </label>
        <label class="ui-field-group">
          <span class="ui-field-label">转交原因</span>
          <textarea v-model="reason" data-testid="transfer-reason" class="ui-field-control task-action-dialog__textarea" maxlength="1000" rows="4" />
        </label>
        <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
        <div class="task-action-dialog__actions">
          <button data-testid="cancel-dialog" class="ui-button" type="button" @click="closeDialog">取消</button>
          <button data-testid="confirm-transfer" class="ui-button ui-button--primary" type="button" :disabled="submitting" @click="runTransfer">确认转交</button>
        </div>
      </div>
    </AppDialog>
  </div>
</template>

<style scoped>
.task-actions { display: flex; flex-wrap: wrap; align-items: center; gap: var(--ui-space-2); padding-top: var(--ui-space-4); }.task-actions > [role='alert'], .task-actions > [role='status'] { flex-basis: 100%; margin: var(--ui-space-1) 0 0; }.task-action-dialog { display: grid; gap: var(--ui-space-4); width: min(100%, 24rem); }.task-action-dialog .eyebrow, .task-action-dialog h3, .task-action-dialog__description { margin: 0; }.task-action-dialog h3 { color: var(--ui-text); font-size: 1.25rem; }.task-action-dialog__description { color: var(--ui-text-secondary); font-size: .875rem; line-height: 1.5; }.task-action-dialog__textarea { min-height: 7rem; padding-top: var(--ui-space-3); padding-bottom: var(--ui-space-3); resize: vertical; }.task-action-dialog__actions { display: flex; justify-content: flex-end; gap: var(--ui-space-2); padding-top: var(--ui-space-2); }
</style>
