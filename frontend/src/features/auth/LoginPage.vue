<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import { useAuthStore } from './auth.store'
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const form = reactive({ username: '', password: '' })
const submitting = ref(false)
const errorMessage = ref('')
const canSubmit = computed(() =>
  form.username.trim().length > 0 && form.password.length > 0,
)

function safeRedirect(): string {
  const redirect = route.query.redirect
  return typeof redirect === 'string'
    && redirect.startsWith('/')
    && !redirect.startsWith('//')
    ? redirect
    : '/workspace'
}

async function submit(): Promise<void> {
  if (!canSubmit.value || submitting.value) {
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    const user = await auth.signIn({
      username: form.username.trim(),
      password: form.password,
    })
    await router.replace(
      user.mustChangePassword ? '/change-password' : safeRedirect(),
    )
  } catch (error: unknown) {
    const status = (error as { response?: { status?: number } })?.response?.status
    errorMessage.value = status === 401
      ? '账号或密码错误'
      : status === 429
        ? '登录尝试过于频繁，请稍后再试'
        : apiErrorMessage(error, '暂时无法登录，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-layout">
    <form class="auth-surface ui-panel" @submit.prevent="submit">
      <div class="brand-mark auth-surface__mark" aria-hidden="true">OPS</div>
      <p class="eyebrow">OPS QUEUE SYSTEM</p>
      <h1>登录运维叫号台</h1>
      <p class="page-summary">
        进入任务队列，查看分派并完成操作留痕。
      </p>

      <label class="ui-field-group">
        <span class="ui-field-label">账号</span>
        <input
          v-model="form.username"
          class="ui-field-control"
          name="username"
          autocomplete="username"
          maxlength="64"
          placeholder="请输入账号"
          autofocus
        />
      </label>
      <label class="ui-field-group">
        <span class="ui-field-label">密码</span>
        <input
          v-model="form.password"
          class="ui-field-control"
          name="password"
          type="password"
          autocomplete="current-password"
          placeholder="请输入密码"
        />
      </label>

      <p v-if="errorMessage" class="auth-error" role="alert">
        {{ errorMessage }}
      </p>
      <button
        class="ui-button ui-button--primary auth-submit"
        type="submit"
        :disabled="!canSubmit || submitting"
      >
        {{ submitting ? '正在登录…' : '登录' }}
      </button>
      <p class="auth-help">如需重置密码，请联系任一运维组长。</p>
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
  width: min(100%, 440px);
  padding: clamp(var(--ui-space-6), 5vw, 44px);
}

.auth-surface__mark {
  margin-bottom: var(--ui-space-5);
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
  margin: var(--ui-space-3) 0 var(--ui-space-6);
  color: var(--ui-text-body);
  line-height: 1.6;
}

.auth-error {
  margin: var(--ui-space-4) 0 0;
  color: var(--ui-attention);
  font-size: .875rem;
}

.auth-submit {
  width: 100%;
  margin-top: var(--ui-space-5);
}

.auth-submit:disabled {
  cursor: not-allowed;
  opacity: .55;
}

.auth-help {
  margin: var(--ui-space-4) 0 0;
  color: var(--ui-text-secondary);
  font-size: .8125rem;
  line-height: 1.5;
  text-align: center;
}
</style>
