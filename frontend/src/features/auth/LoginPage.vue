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
  <main class="auth-page">
    <section class="auth-brand" aria-label="系统介绍">
      <div class="brand-mark brand-mark--large">OPS</div>
      <p class="eyebrow">RELEASE &amp; DATA OPERATIONS</p>
      <h1>运维叫号台</h1>
      <p class="brand-lead">
        让版本发布与数据维护任务有序进入队列，清楚分派，完整留痕。
      </p>
      <div class="brand-rule" />
      <p class="brand-note">统一取号 · 智能分派 · 全程可追溯</p>
    </section>

    <section class="auth-panel">
      <form class="auth-card" @submit.prevent="submit">
        <p class="eyebrow">WELCOME BACK</p>
        <h2>登录系统</h2>
        <p class="muted">使用组长为你创建的本地账号登录</p>

        <label class="field">
          <span>账号</span>
          <input
            v-model="form.username"
            name="username"
            autocomplete="username"
            maxlength="64"
            placeholder="请输入账号"
            autofocus
          />
        </label>
        <label class="field">
          <span>密码</span>
          <input
            v-model="form.password"
            name="password"
            type="password"
            autocomplete="current-password"
            placeholder="请输入密码"
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
          {{ submitting ? '正在登录…' : '登录' }}
        </button>
        <p class="auth-help">如需重置密码，请联系任一运维组长。</p>
      </form>
    </section>
  </main>
</template>
