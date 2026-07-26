<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import { useAuthStore } from '@/features/auth/auth.store'
import type { Role } from '@/features/auth/auth.types'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import RoleNavigation from './RoleNavigation.vue'
import { todayDuty, type DutySummary } from './duty.api'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const mobileOpen = ref(false)
const duty = ref<DutySummary | null>(null)
const dutyError = ref('')
const signingOut = ref(false)

const roleLabels: Record<Role, string> = {
  DEVELOPER: '开发人员',
  OPERATOR: '运维管理员',
  LEADER: '运维组长',
}
const user = computed(() => auth.user)
const pageTitle = computed(() => route.meta.title || '工作台')
const todayLabel = new Intl.DateTimeFormat('zh-CN', {
  month: 'long',
  day: 'numeric',
  weekday: 'short',
}).format(new Date())

onMounted(async () => {
  try {
    duty.value = await todayDuty()
  } catch (error: unknown) {
    dutyError.value = apiErrorMessage(error, '今日值班信息加载失败')
  }
})

async function signOut(): Promise<void> {
  signingOut.value = true
  try {
    await auth.signOut()
    await router.replace('/login')
  } finally {
    signingOut.value = false
  }
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar" :class="{ 'sidebar--open': mobileOpen }">
      <div class="sidebar-brand">
        <div class="brand-mark">OPS</div>
        <div>
          <strong>运维叫号台</strong>
          <span>任务协同中心</span>
        </div>
      </div>
      <RoleNavigation
        v-if="user"
        :roles="user.roles"
        @navigate="mobileOpen = false"
      />
      <div class="sidebar-footer">
        <span class="system-dot" />
        <div>
          <strong>系统服务</strong>
          <span>连接状态正常</span>
        </div>
      </div>
    </aside>
    <button
      v-if="mobileOpen"
      class="sidebar-scrim"
      aria-label="关闭导航"
      @click="mobileOpen = false"
    />

    <div class="shell-content">
      <header class="topbar">
        <div class="topbar-title">
          <button
            class="menu-button"
            type="button"
            aria-label="打开导航"
            @click="mobileOpen = true"
          >
            ☰
          </button>
          <div>
            <span>{{ todayLabel }}</span>
            <h1>{{ pageTitle }}</h1>
          </div>
        </div>

        <div class="topbar-actions">
          <div class="duty-today">
            <template v-if="duty?.configured">
              <span>今日值班</span>
              <strong>二线 {{ duty.secondLine?.displayName || '未配置' }}</strong>
              <i />
              <strong>三线 {{ duty.thirdLine?.displayName || '未配置' }}</strong>
            </template>
            <span v-else-if="dutyError" class="duty-warning">{{ dutyError }}</span>
            <span v-else-if="duty">今日值班未配置</span>
            <span v-else>正在加载今日值班…</span>
          </div>

          <div v-if="user" class="user-summary">
            <div class="avatar">{{ user.displayName.slice(0, 1) }}</div>
            <div>
              <strong>{{ user.displayName }}</strong>
              <span>{{ user.roles.map((role) => roleLabels[role]).join(' / ') }}</span>
            </div>
          </div>
          <button
            class="text-button"
            type="button"
            :disabled="signingOut"
            @click="signOut"
          >
            {{ signingOut ? '退出中…' : '退出' }}
          </button>
        </div>
      </header>

      <section class="status-strip" aria-label="任务状态概览">
        <div>
          <span>待执行</span>
          <strong>—</strong>
        </div>
        <div>
          <span>执行中</span>
          <strong>—</strong>
        </div>
        <div>
          <span>需人工处理</span>
          <strong>—</strong>
        </div>
        <p>任务数据将在任务模块加载后显示</p>
      </section>

      <main class="page-content">
        <RouterView />
      </main>
    </div>
  </div>
</template>
