<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import { useAuthStore } from './auth.store'
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

const auth = useAuthStore()
const router = useRouter()
const form = reactive({
  currentPassword: '',
  newPassword: '',
  confirmation: '',
})
const submitting = ref(false)
const errorMessage = ref('')
const canSubmit = computed(() =>
  form.currentPassword.length > 0
  && form.newPassword.length >= 12
  && form.newPassword === form.confirmation,
)

async function submit(): Promise<void> {
  errorMessage.value = ''
  if (form.newPassword !== form.confirmation) {
    errorMessage.value = '两次输入的新密码不一致'
    return
  }
  if (form.newPassword.length < 12) {
    errorMessage.value = '新密码至少需要 12 个字符'
    return
  }
  submitting.value = true
  try {
    await auth.changeOwnPassword({
      currentPassword: form.currentPassword,
      newPassword: form.newPassword,
    })
    await router.replace('/workspace')
  } catch (error: unknown) {
    errorMessage.value = apiErrorMessage(error, '密码修改失败，请检查原密码')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-page auth-page--single">
    <section class="auth-panel">
      <form class="auth-card" @submit.prevent="submit">
        <div class="brand-mark">OPS</div>
        <p class="eyebrow">ACCOUNT SECURITY</p>
        <h2>修改初始密码</h2>
        <p class="muted">首次登录后需要设置自己的密码，至少 12 个字符。</p>

        <label class="field">
          <span>当前密码</span>
          <input
            v-model="form.currentPassword"
            type="password"
            autocomplete="current-password"
          />
        </label>
        <label class="field">
          <span>新密码</span>
          <input
            v-model="form.newPassword"
            type="password"
            autocomplete="new-password"
          />
        </label>
        <label class="field">
          <span>确认新密码</span>
          <input
            v-model="form.confirmation"
            type="password"
            autocomplete="new-password"
          />
        </label>

        <p v-if="errorMessage" class="form-error" role="alert">
          {{ errorMessage }}
        </p>
        <button
          class="primary-button"
          type="submit"
          :disabled="!canSubmit || submitting"
        >
          {{ submitting ? '正在保存…' : '保存并进入系统' }}
        </button>
      </form>
    </section>
  </main>
</template>
