<script setup lang="ts">
import {
  categoryLabels,
  statusLabels,
  type TaskRow as TaskRowItem,
} from './task.types'

defineProps<{ task: TaskRowItem }>()
const emit = defineEmits<{ open: [id: string] }>()

function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value))
}
</script>

<template>
  <button
    class="task-row"
    type="button"
    data-testid="task-row"
    :aria-label="`???? ${task.ticketNumber}`"
    @click="emit('open', task.id)"
  >
    <span class="task-row__identity">
      <strong>{{ task.ticketNumber }}</strong>
      <small v-if="task.needsManualAttention" class="task-row__attention">?????</small>
      <small v-else>{{ categoryLabels[task.category] }}</small>
    </span>
    <span class="task-row__summary">
      <strong>{{ task.systemName }}</strong>
      <small>{{ task.processNumber || '???????' }}</small>
    </span>
    <span class="task-row__time">
      <strong>{{ formatDateTime(task.operationStart) }}</strong>
      <small>? {{ formatDateTime(task.operationEnd) }}</small>
    </span>
    <span class="ui-status-pill" :class="`ui-status-pill--${task.status.toLowerCase()}`">
      {{ statusLabels[task.status] }}
    </span>
    <span class="task-row__people">
      <strong>{{ task.currentAssigneeName || '???' }}</strong>
      <small>??? {{ task.creatorName }}</small>
    </span>
    <span class="task-row__duration">
      <strong>{{ task.estimatedMinutes }} ??</strong>
      <small>?? {{ task.actualMinutes ?? '?' }}</small>
    </span>
  </button>
</template>

<style scoped>
.task-row { display: grid; width: 100%; grid-template-columns: minmax(10rem, 1.2fr) minmax(9rem, 1.2fr) minmax(8.5rem, 1fr) auto minmax(8rem, 1fr) auto; align-items: center; gap: var(--ui-space-4); padding: var(--ui-space-4) var(--ui-space-5); border: 0; border-top: var(--ui-border-width) solid var(--ui-hairline); color: var(--ui-text); background: transparent; cursor: pointer; text-align: left; transition: background var(--ui-ease-out), transform var(--ui-ease-out); }
.task-row:hover { background: var(--ui-hover); }
.task-row:active { transform: scale(.995); }
.task-row span { min-width: 0; }
.task-row strong, .task-row small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.task-row strong { color: var(--ui-text); font-size: .9375rem; }
.task-row small { margin-top: var(--ui-space-1); color: var(--ui-text-secondary); font-size: .8125rem; }
.task-row__attention { color: var(--ui-attention) !important; font-weight: 600; }
.task-row__time { font-variant-numeric: tabular-nums; }
.ui-status-pill--pending { color: var(--ui-attention); }
.ui-status-pill--in_progress { color: var(--ui-accent); }
.ui-status-pill--completed { color: var(--ui-live); }
@media (max-width: 920px) { .task-row { grid-template-columns: minmax(9rem, 1fr) minmax(8rem, 1fr) auto; } .task-row__time, .task-row__duration { display: none; } }
@media (max-width: 680px) { .task-row { grid-template-columns: minmax(0, 1fr) auto; gap: var(--ui-space-3); padding: var(--ui-space-4); } .task-row__summary { grid-column: 1; grid-row: 2; } .task-row__people { display: none; } .task-row__time { display: block; grid-column: 1; grid-row: 3; } }
</style>
