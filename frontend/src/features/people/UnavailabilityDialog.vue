<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import AppDialog from '@/components/ui/AppDialog.vue'
import { reactive, ref } from 'vue'
import { setUnavailable } from './people.api'
import type { AccountView } from './people.types'

const props = defineProps<{
  operator: AccountView
  initialDate: string
}>()
const emit = defineEmits<{
  close: []
  saved: [date: string, reason: string]
}>()
const form = reactive({ date: props.initialDate, reason: '' })
const submitting = ref(false)
const errorMessage = ref('')

async function submit(): Promise<void> {
  const errors: string[] = []
  if (!form.date) errors.push('请选择不可参与日期')
  if (!form.reason.trim()) errors.push('请输入不可参与原因')
  errorMessage.value = errors.join('；')
  if (errors.length || submitting.value) return
  submitting.value = true
  try {
    await setUnavailable({
      operatorId: props.operator.id,
      date: form.date,
      reason: form.reason.trim(),
    })
    emit('saved', form.date, form.reason.trim())
  } catch (error: unknown) {
    errorMessage.value = apiErrorMessage(error, '标记不可参与失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AppDialog
    :open="true" labelled-by="unavailability-dialog-title"
    @close="emit('close')"
  >
    <p class="eyebrow">UNAVAILABLE</p>
      <h3 id="unavailability-dialog-title">标记 {{ operator.displayName }} 不可参与</h3>
      <label class="field">
        <span>日期</span>
        <input v-model="form.date" data-testid="unavailable-date" type="date" />
      </label>
      <label class="field">
        <span>原因</span>
        <textarea
          v-model="form.reason"
          data-testid="unavailable-reason"
          maxlength="255"
          rows="4"
        />
      </label>
      <p v-if="errorMessage" class="form-error" role="alert">
        {{ errorMessage }}
      </p>
      <div class="dialog-actions">
        <button class="ui-button" type="button" @click="emit('close')">
          取消
        </button>
        <button
          data-testid="save-unavailable"
          class="ui-button ui-button--primary"
          type="button"
          :disabled="submitting"
          @click="submit"
        >
          {{ submitting ? '保存中…' : '保存并预览重新分配' }}
        </button>
      </div>
  </AppDialog>
</template>
