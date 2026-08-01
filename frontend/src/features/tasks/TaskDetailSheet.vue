<script setup lang="ts">
import AppIcon from '@/components/ui/AppIcon.vue'
import { containFocus } from '@/components/ui/useFocusContainment'
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import TaskDetailContent from './TaskDetailContent.vue'
import { useSpringSheet } from './useSpringSheet'

const props = defineProps<{ taskId: string }>()
const emit = defineEmits<{ close: [] }>()

const dialog = ref<HTMLElement | null>(null)
const isMobile = ref(typeof window !== 'undefined' && window.innerWidth <= 680)
const axis = isMobile.value ? 'y' : 'x'
let stopFocusContainment: (() => void) | undefined
let removeResize: (() => void) | undefined
let closeRequested = false

const sheet = useSpringSheet({
  axis,
  extent: () => isMobile.value ? Math.min(620, window.innerHeight || 620) : 520,
  onDismiss: () => {
    if (!closeRequested) emit('close')
  },
})

const transform = computed(() => axis === 'y'
  ? `translate3d(0, ${sheet.position.value}px, 0)`
  : `translate3d(${sheet.position.value}px, 0, 0)`)
const opacity = computed(() => String(Math.max(.18, sheet.progress.value)))

function requestClose(): void {
  if (closeRequested) return
  closeRequested = true
  sheet.close()
  emit('close')
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    requestClose()
  }
}

onMounted(async () => {
  const returnTarget = document.activeElement instanceof HTMLElement
    ? document.activeElement
    : null
  const updatePresentation = () => {
    isMobile.value = window.innerWidth <= 680
  }
  window.addEventListener('resize', updatePresentation, { passive: true })
  removeResize = () => window.removeEventListener('resize', updatePresentation)
  await nextTick()
  if (dialog.value) stopFocusContainment = containFocus(dialog.value, returnTarget)
  sheet.open()
})

onBeforeUnmount(() => {
  sheet.stop()
  stopFocusContainment?.()
  removeResize?.()
})
</script>

<template>
  <Teleport to="body">
    <div class="task-detail-sheet-backdrop" :style="{ opacity }" @click.self="requestClose">
      <section
        ref="dialog"
        class="task-detail-sheet"
        :class="{ 'task-detail-sheet--mobile': isMobile, 'task-detail-sheet--dragging': sheet.dragging.value }"
        :style="{ transform }"
        role="dialog"
        aria-modal="true"
        aria-labelledby="task-detail-sheet-title"
        tabindex="-1"
        @keydown="onKeydown"
      >
        <div
          class="task-detail-sheet__grab"
          aria-hidden="true"
          @pointerdown="sheet.onPointerDown"
          @pointermove="sheet.onPointerMove"
          @pointerup="sheet.onPointerUp"
          @pointercancel="sheet.onPointerUp"
        >
          <span />
        </div>
        <header class="task-detail-sheet__header">
          <div>
            <p class="eyebrow">TASK DETAIL</p>
            <h2 id="task-detail-sheet-title">任务详情</h2>
          </div>
          <button
            class="ui-button ui-button--quiet"
            type="button"
            aria-label="关闭任务详情"
            @click="requestClose"
          >
            <AppIcon name="close" decorative />
            <span>关闭</span>
          </button>
        </header>
        <div class="task-detail-sheet__body">
          <TaskDetailContent :task-id="props.taskId" />
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.task-detail-sheet-backdrop { position: fixed; z-index: 55; inset: 0; background: var(--ui-overlay-scrim); transition: opacity 180ms var(--ui-ease-out); }
.task-detail-sheet { position: absolute; top: var(--ui-space-3); right: var(--ui-space-3); bottom: var(--ui-space-3); display: flex; width: min(520px, calc(100vw - var(--ui-space-6))); flex-direction: column; overflow: hidden; border: var(--ui-border-width) solid var(--ui-overlay-border); border-radius: var(--ui-radius-sheet); background: var(--ui-overlay-surface); -webkit-backdrop-filter: var(--ui-overlay-filter); backdrop-filter: var(--ui-overlay-filter); box-shadow: var(--ui-shadow-overlay); will-change: transform; }
.task-detail-sheet--dragging { user-select: none; }
.task-detail-sheet__grab { display: grid; min-height: var(--ui-action-min-height); place-items: center; cursor: grab; touch-action: none; }
.task-detail-sheet__grab span { width: 42px; height: 4px; border-radius: var(--ui-radius-pill); background: var(--ui-hairline); }
.task-detail-sheet__header { display: flex; align-items: center; justify-content: space-between; gap: var(--ui-space-3); padding: 0 var(--ui-space-5) var(--ui-space-4); border-bottom: var(--ui-border-width) solid var(--ui-hairline); }
.task-detail-sheet__header h2 { margin: 0; font-size: 1.25rem; }
.task-detail-sheet__body { min-height: 0; overflow: auto; padding: var(--ui-space-5); }
.task-detail-sheet__body :deep(.task-detail-layout) { display: grid; gap: var(--ui-space-4); }
@media (max-width: 680px) { .task-detail-sheet { top: auto; right: 0; bottom: 0; left: 0; width: 100%; max-height: min(86vh, 680px); border-bottom: 0; border-radius: var(--ui-radius-sheet) var(--ui-radius-sheet) 0 0; } }
</style>