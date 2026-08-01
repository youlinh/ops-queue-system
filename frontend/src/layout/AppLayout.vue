<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import { useAuthStore } from '@/features/auth/auth.store'
import type { Role } from '@/features/auth/auth.types'
import { shanghaiDate } from '@/features/tasks/shanghai-time'
import NotificationToasts from '@/features/notifications/NotificationToasts.vue'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import RoleNavigation from './RoleNavigation.vue'
import { todayDuty, type DutySummary } from './duty.api'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const isScrolled = ref(false)
const duty = ref<DutySummary | null>(null)
const dutyError = ref('')
const signingOut = ref(false)
const now = ref(new Date())
let dateTimer: ReturnType<typeof setInterval> | null = null
let activeShanghaiDate = shanghaiDate(now.value)
const roleLabels: Record<Role, string> = {
  DEVELOPER: '开发人员',
  OPERATOR: '运维管理员',
  LEADER: '运维组长',
}
const user = computed(() => auth.user)
const pageTitle = computed(() => route.meta.title || '工作台')
const todayLabel = computed(() => new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  month: 'long',
  day: 'numeric',
  weekday: 'short',
}).format(now.value))

function updateScrollEdge(): void {
  isScrolled.value = window.scrollY > 8
}

async function loadDuty(): Promise<void> {
  dutyError.value = ''
  try {
    duty.value = await todayDuty()
  } catch (error: unknown) {
    duty.value = null
    dutyError.value = apiErrorMessage(error, '今日值班信息加载失败')
  }
}

function scheduleDateCalibration(): void {
  dateTimer = setInterval(() => {
    const current = shanghaiDate()
    if (current !== activeShanghaiDate) {
      activeShanghaiDate = current
      now.value = new Date()
      loadDuty()
    }
  }, 60_000)
}

onMounted(async () => {
  window.addEventListener('scroll', updateScrollEdge, { passive: true })
  updateScrollEdge()
  loadDuty()
  scheduleDateCalibration()
})
onUnmounted(() => {
  window.removeEventListener('scroll', updateScrollEdge)
  if (dateTimer) clearInterval(dateTimer)
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
    <aside class="sidebar">
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

      />
      <div class="sidebar-footer">
        <span class="system-dot" />
        <div>
          <strong>系统服务</strong>
          <span>连接状态正常</span>
        </div>
      </div>
    </aside>
    <div class="shell-content">
      <header class="topbar" :class="{ 'topbar--scrolled': isScrolled }">
        <div class="topbar-title">
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
      <main class="page-content">
        <RouterView />
      </main>
    </div>

    <NotificationToasts v-if="user" />
  </div>
</template>

<style scoped>
.topbar { transition: background-color 160ms ease, box-shadow 160ms ease; }
.topbar--scrolled { background: color-mix(in srgb, var(--ui-surface) 84%, transparent); box-shadow: 0 10px 24px rgba(18, 26, 42, .08); backdrop-filter: blur(20px) saturate(160%); }
.topbar--scrolled::after { position: absolute; right: 0; bottom: -16px; left: 0; height: 16px; pointer-events: none; background: linear-gradient(to bottom, rgba(18, 26, 42, .08), transparent); content: ''; }
@media (min-width: 681px) and (max-width: 920px) { .sidebar { width: 72px; transform: none; } .shell-content { margin-left: 72px; } .sidebar-brand { min-height: var(--ui-topbar-height); justify-content: center; padding: 0; } .sidebar-brand > div:not(.brand-mark), .sidebar-footer > div { display: none; } .sidebar-footer { justify-content: center; margin-right: var(--ui-space-2); margin-left: var(--ui-space-2); padding: var(--ui-space-3) 0; } }
@media (max-width: 680px) { .sidebar { position: static; width: 0; transform: none; transition: none; } .sidebar-brand, .sidebar-footer { display: none; } .shell-content { margin-left: 0; padding-bottom: calc(64px + env(safe-area-inset-bottom)); } .topbar { min-height: 58px; } .topbar-actions { gap: var(--ui-space-2); } .duty-today, .user-summary > div:not(.avatar) { display: none; } }
</style>