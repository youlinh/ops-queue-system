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
  <main class="auth-layout auth-layout--compact">
    <form class="auth-surface auth-surface--password ui-panel" @submit.prevent="submit">
      <header class="auth-heading">
        <div class="brand-mark auth-surface__mark" aria-hidden="true">OPS</div>
        <div>
          <p class="eyebrow">ACCOUNT SECURITY</p>
          <h1>修改初始密码</h1>
          <p class="page-summary">首次登录后需要设置自己的密码。</p>
        </div>
      </header>

      <div class="password-layout">
        <div>
          <label class="ui-field-group">
            <span class="ui-field-label">当前密码</span>
            <input
              v-model="form.currentPassword"
              class="ui-field-control"
              type="password"
              autocomplete="current-password"
            />
          </label>
          <label class="ui-field-group">
            <span class="ui-field-label">新密码</span>
            <input
              v-model="form.newPassword"
              class="ui-field-control"
              type="password"
              autocomplete="new-password"
            />
          </label>
          <label class="ui-field-group">
            <span class="ui-field-label">确认新密码</span>
            <input
              v-model="form.confirmation"
              class="ui-field-control"
              type="password"
              autocomplete="new-password"
            />
          </label>
        </div>
        <aside class="password-rules" aria-label="密码规则">
          <strong>密码规则</strong>
          <p>新密码至少需要 12 个字符，并且两次输入保持一致。</p>
        </aside>
      </div>

      <p v-if="errorMessage" class="auth-error" role="alert">
        {{ errorMessage }}
      </p>
      <button
        class="ui-button ui-button--primary auth-submit"
        type="submit"
        :disabled="!canSubmit || submitting"
      >
        {{ submitting ? '正在保存…' : '保存并进入系统' }}
      </button>
    </form>
  </main>
</template>

<style scoped>
.auth-layout {
  display: grid;
  min-height: 100vh;
  place-items: center;
  padding: var(--ui-space-6);
  background: var(--ui-ground);
}

.auth-surface {
  width: min(100%, 720px);
  padding: clamp(var(--ui-space-6), 5vw, 44px);
}

.auth-heading {
  display: flex;
  align-items: flex-start;
  gap: var(--ui-space-4);
  margin-bottom: var(--ui-space-6);
}

.auth-surface__mark {
  flex: none;
  border-color: var(--ui-hairline);
  border-radius: var(--ui-radius-small);
}

h1 {
  margin: 0;
  color: var(--ui-text);
  font-size: clamp(1.75rem, 4vw, 2.25rem);
  letter-spacing: -.03em;
}

.page-summary {
  margin: var(--ui-space-2) 0 0;
  color: var(--ui-text-body);
  line-height: 1.6;
}

.password-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(180px, .55fr);
  gap: var(--ui-space-6);
  align-items: start;
}

.password-rules {
  padding: var(--ui-space-4);
  border-radius: var(--ui-radius-card);
  color: var(--ui-text-body);
  background: var(--ui-hover);
  font-size: .875rem;
  line-height: 1.6;
}

.password-rules p {
  margin: var(--ui-space-2) 0 0;
}

.auth-error {
  margin: var(--ui-space-4) 0 0;
  color: var(--ui-attention);
  font-size: .875rem;
}

.auth-submit {
  margin-top: var(--ui-space-5);
}

.auth-submit:disabled {
  cursor: not-allowed;
  opacity: .55;
}

@media (max-width: 680px) {
  .password-layout {
    grid-template-columns: 1fr;
  }
}
</style>
