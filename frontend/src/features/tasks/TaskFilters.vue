<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import { categoryLabels, statusLabels, type TaskSearch } from './task.types'
const props = defineProps<{ initial: TaskSearch }>()
const emit = defineEmits<{ search: [filters: TaskSearch]; reset: [] }>()
const filters = reactive<TaskSearch>({ ...props.initial })
const advancedOpen = ref(Boolean(props.initial.operationDate || props.initial.systemName || props.initial.creatorId || props.initial.assigneeId))
const statusOptions = computed(() => Object.entries(statusLabels))
const copy = { category: '\u7c7b\u522b', status: '\u72b6\u6001', all: '\u5168\u90e8', more: '\u66f4\u591a\u7b5b\u9009', less: '\u6536\u8d77\u66f4\u591a\u7b5b\u9009', date: '\u64cd\u4f5c\u65e5\u671f', system: '\u7cfb\u7edf\u540d\u79f0', creator: '\u521b\u5efa\u4eba ID', assignee: '\u8d1f\u8d23\u4eba ID', query: '\u67e5\u8be2', reset: '\u91cd\u7f6e', input: '\u8f93\u5165\u5173\u952e\u5b57', creatorHint: '\u6309\u521b\u5efa\u4eba\u7b5b\u9009', assigneeHint: '\u6309\u8d1f\u8d23\u4eba\u7b5b\u9009', taskStatus: '\u4efb\u52a1\u72b6\u6001' }
watch(() => props.initial, next => Object.assign(filters, next), { deep: true })
function reset(): void { Object.assign(filters, { operationDate: '', category: '', systemName: '', status: '', creatorId: '', assigneeId: '' }); emit('reset') }
function selectStatus(status: TaskSearch['status']): void { filters.status = filters.status === status ? '' : status }
</script>
<template>
  <form class="task-filters" @submit.prevent="emit('search', { ...filters })">
    <div class="task-filters__common" data-testid="common-task-filters">
      <label class="ui-field-group"><span class="ui-field-label"><AppIcon name="tasks" decorative />{{ copy.category }}</span><select v-model="filters.category" class="ui-field-control"><option value="">{{ copy.all }}{{ copy.category }}</option><option v-for="(label, value) in categoryLabels" :key="value" :value="value">{{ label }}</option></select></label>
      <fieldset class="task-filters__segments"><legend class="ui-field-label"><AppIcon name="filter" decorative />{{ copy.status }}</legend><div role="group" :aria-label="copy.taskStatus"><button class="task-filters__segment" type="button" :aria-pressed="filters.status === ''" @click="selectStatus('')">{{ copy.all }}</button><button v-for="[value, label] in statusOptions" :key="value" class="task-filters__segment" type="button" :aria-pressed="filters.status === value" @click="selectStatus(value as TaskSearch['status'])">{{ label }}</button></div></fieldset>
    </div>
    <button class="ui-button ui-button--quiet task-filters__disclosure" type="button" aria-controls="advanced-task-filters" :aria-expanded="advancedOpen" @click="advancedOpen = !advancedOpen"><AppIcon name="filter" decorative />{{ advancedOpen ? copy.less : copy.more }}</button>
    <div id="advanced-task-filters" class="task-filters__advanced" data-testid="advanced-task-filters" v-show="advancedOpen">
      <label class="ui-field-group"><span class="ui-field-label"><AppIcon name="roster" decorative />{{ copy.date }}</span><input v-model="filters.operationDate" class="ui-field-control" type="date" /></label>
      <label class="ui-field-group"><span class="ui-field-label"><AppIcon name="workspace" decorative />{{ copy.system }}</span><input v-model="filters.systemName" class="ui-field-control" maxlength="128" :placeholder="copy.input" /></label>
      <label class="ui-field-group"><span class="ui-field-label"><AppIcon name="people" decorative />{{ copy.creator }}</span><input v-model="filters.creatorId" class="ui-field-control" :placeholder="copy.creatorHint" /></label>
      <label class="ui-field-group"><span class="ui-field-label"><AppIcon name="people" decorative />{{ copy.assignee }}</span><input v-model="filters.assigneeId" class="ui-field-control" :placeholder="copy.assigneeHint" /></label>
    </div>
    <div class="task-filters__actions"><button class="ui-button ui-button--primary" type="submit"><AppIcon name="search" decorative />{{ copy.query }}</button><button class="ui-button" type="button" @click="reset"><AppIcon name="refresh" decorative />{{ copy.reset }}</button></div>
  </form>
</template>
<style scoped>
.task-filters {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--ui-space-4);
  margin: var(--ui-space-5);
  padding: var(--ui-space-5);
  border: var(--ui-border-width) solid var(--ui-hairline);
  border-radius: var(--ui-radius-card);
  background: var(--ui-surface);
  box-shadow: 0 10px 28px rgba(29, 29, 31, .04);
}
.task-filters__common {
  display: grid;
  grid-template-columns: minmax(180px, 220px) minmax(0, 1fr);
  align-items: end;
  gap: var(--ui-space-6);
  grid-column: 1;
}
.task-filters__common > .ui-field-group { width: auto; }
.task-filters__segments { min-width: 0; margin: 0; padding: 0; border: 0; }
.task-filters__segments [role='group'] { display: flex; flex-wrap: wrap; gap: var(--ui-space-2); margin-top: var(--ui-space-2); }
.task-filters__segment { min-height: var(--ui-action-min-height); padding: 0 var(--ui-space-4); border: var(--ui-border-width) solid var(--ui-hairline); border-radius: var(--ui-radius-pill); color: var(--ui-text-secondary); background: var(--ui-hover); cursor: pointer; font-weight: 600; transition: border-color var(--ui-ease-out), background var(--ui-ease-out), color var(--ui-ease-out), transform var(--ui-ease-out); }
.task-filters__segment:hover { border-color: color-mix(in srgb, var(--ui-accent) 35%, var(--ui-hairline)); background: var(--ui-surface); }
.task-filters__segment:active { transform: scale(.97); }
.task-filters__segment[aria-pressed='true'] { border-color: var(--ui-accent); color: var(--ui-accent-link); background: color-mix(in srgb, var(--ui-accent) 9%, var(--ui-surface)); }
.task-filters__disclosure { display: inline-flex; justify-self: start; grid-column: 1; padding: 0; }
.task-filters__advanced { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--ui-space-4); padding-top: var(--ui-space-5); border-top: var(--ui-border-width) solid var(--ui-hairline); grid-column: 1; }
.task-filters__actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: var(--ui-space-2); grid-column: 1; }
.task-filters :deep(.ui-field-label) { display: inline-flex; align-items: center; gap: var(--ui-space-2); }
.task-filters :deep(.ui-field-label svg) { width: 16px; height: 16px; color: var(--ui-accent-link); }
.task-filters :deep(.ui-field-control) { min-height: var(--ui-action-min-height); height: var(--ui-action-min-height); border-color: rgba(29, 29, 31, .16); background: var(--ui-hover); box-shadow: inset 0 1px 0 rgba(255, 255, 255, .7); }
.task-filters :deep(.ui-field-control:hover) { border-color: rgba(29, 29, 31, .28); background: var(--ui-surface); }
.task-filters :deep(.ui-field-control:focus) { border-color: var(--ui-accent); background: var(--ui-surface); box-shadow: 0 0 0 3px color-mix(in srgb, var(--ui-accent) 18%, transparent); }
.task-filters .ui-button svg { width: 17px; height: 17px; }
@media (max-width: 780px) {
  .task-filters__common { grid-template-columns: 1fr; gap: var(--ui-space-4); }
  .task-filters__advanced { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 680px) {
  .task-filters { margin: var(--ui-space-4); padding: var(--ui-space-4); }
  .task-filters__advanced { grid-template-columns: 1fr; }
  .task-filters__actions { justify-content: stretch; }
  .task-filters__actions .ui-button { flex: 1; }
}
</style>
