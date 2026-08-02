<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import {
  claimNotifications,
  type ClaimedNotification,
} from './notifications.api'

export interface NotificationToastsProps {
  /** Milliseconds before the first poll; keeps page load interactions clear. */
  initialDelayMs?: number
  pollIntervalMs?: number
  dismissAfterMs?: number
}

const props = withDefaults(defineProps<NotificationToastsProps>(), {
  initialDelayMs: 8_000,
  pollIntervalMs: 30_000,
  dismissAfterMs: 8_000,
})

const MAX_VISIBLE = 4

interface DisplayToast {
  id: string
  title: string
  detail: string
}

const toasts = ref<DisplayToast[]>([])
let initialTimer: ReturnType<typeof setTimeout> | null = null
let pollTimer: ReturnType<typeof setInterval> | null = null
const dismissTimers = new Map<string, ReturnType<typeof setTimeout>>()

function text(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function timeLabel(notification: ClaimedNotification): string {
  const raw = text(notification.payload.calledAt) || notification.createdAt
  const parsed = new Date(raw)
  if (Number.isNaN(parsed.getTime())) {
    return ''
  }
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    hour: '2-digit',
    minute: '2-digit',
  }).format(parsed)
}

function toToast(notification: ClaimedNotification): DisplayToast {
  const time = timeLabel(notification)
  if (notification.eventType === 'TASK_CALLED') {
    const ticket = text(notification.payload.ticketNumber) || '任务'
    const system = text(notification.payload.systemName)
    return {
      id: notification.id,
      title: `${ticket} 已开始处理`,
      detail: [system, time && `${time} 叫号`].filter(Boolean).join(' · '),
    }
  }
  return {
    id: notification.id,
    title: '系统通知',
    detail: time,
  }
}

function dismiss(id: string): void {
  toasts.value = toasts.value.filter((toast) => toast.id !== id)
  const timer = dismissTimers.get(id)
  if (timer) {
    clearTimeout(timer)
    dismissTimers.delete(id)
  }
}

function show(notification: ClaimedNotification): void {
  if (toasts.value.some((toast) => toast.id === notification.id)) {
    return
  }
  toasts.value = [...toasts.value, toToast(notification)].slice(-MAX_VISIBLE)
  dismissTimers.set(
    notification.id,
    setTimeout(() => dismiss(notification.id), props.dismissAfterMs),
  )
}

async function poll(): Promise<void> {
  if (document.hidden) {
    return
  }
  try {
    for (const notification of await claimNotifications()) {
      show(notification)
    }
  } catch {
    // Transient failures are fine; the next interval retries.
  }
}

onMounted(() => {
  initialTimer = setTimeout(() => {
    poll()
    pollTimer = setInterval(poll, props.pollIntervalMs)
  }, props.initialDelayMs)
})

onUnmounted(() => {
  if (initialTimer) clearTimeout(initialTimer)
  if (pollTimer) clearInterval(pollTimer)
  dismissTimers.forEach((timer) => clearTimeout(timer))
  dismissTimers.clear()
})
</script>

<template>
  <div class="notification-stack" aria-live="polite">
    <TransitionGroup name="toast">
      <div
        v-for="toast in toasts"
        :key="toast.id"
        class="notification-toast"
        role="status"
        :aria-label="toast.title"
      >
        <i class="notification-live-dot" aria-hidden="true" />
        <div class="notification-body">
          <strong>{{ toast.title }}</strong>
          <span v-if="toast.detail">{{ toast.detail }}</span>
        </div>
        <button
          class="notification-close"
          type="button"
          aria-label="关闭提醒"
          @click="dismiss(toast.id)"
        >
          ×
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>
