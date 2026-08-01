<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
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
      <label class="ui-field-group"><span class="ui-field-label">{{ copy.category }}</span><select v-model="filters.category" class="ui-field-control"><option value="">{{ copy.all }}{{ copy.category }}</option><option v-for="(label, value) in categoryLabels" :key="value" :value="value">{{ label }}</option></select></label>
      <fieldset class="task-filters__segments"><legend class="ui-field-label">{{ copy.status }}</legend><div role="group" :aria-label="copy.taskStatus"><button class="task-filters__segment" type="button" :aria-pressed="filters.status === ''" @click="selectStatus('')">{{ copy.all }}</button><button v-for="[value, label] in statusOptions" :key="value" class="task-filters__segment" type="button" :aria-pressed="filters.status === value" @click="selectStatus(value as TaskSearch['status'])">{{ label }}</button></div></fieldset>
    </div>
    <button class="ui-button ui-button--quiet task-filters__disclosure" type="button" aria-controls="advanced-task-filters" :aria-expanded="advancedOpen" @click="advancedOpen = !advancedOpen">{{ advancedOpen ? copy.less : copy.more }}</button>
    <div id="advanced-task-filters" class="task-filters__advanced" data-testid="advanced-task-filters" v-show="advancedOpen">
      <label class="ui-field-group"><span class="ui-field-label">{{ copy.date }}</span><input v-model="filters.operationDate" class="ui-field-control" type="date" /></label>
      <label class="ui-field-group"><span class="ui-field-label">{{ copy.system }}</span><input v-model="filters.systemName" class="ui-field-control" maxlength="128" :placeholder="copy.input" /></label>
      <label class="ui-field-group"><span class="ui-field-label">{{ copy.creator }}</span><input v-model="filters.creatorId" class="ui-field-control" :placeholder="copy.creatorHint" /></label>
      <label class="ui-field-group"><span class="ui-field-label">{{ copy.assignee }}</span><input v-model="filters.assigneeId" class="ui-field-control" :placeholder="copy.assigneeHint" /></label>
    </div>
    <div class="task-filters__actions"><button class="ui-button ui-button--primary" type="submit">{{ copy.query }}</button><button class="ui-button" type="button" @click="reset">{{ copy.reset }}</button></div>
  </form>
</template>
<style scoped>
.task-filters { display: grid; gap: var(--ui-space-4); margin: var(--ui-space-5); padding: var(--ui-space-4); border: var(--ui-border-width) solid var(--ui-hairline); border-radius: var(--ui-radius-card); background: var(--ui-hover); }.task-filters__common { display: flex; align-items: end; gap: var(--ui-space-5); }.task-filters__common > .ui-field-group { width: min(100%, 15rem); }.task-filters__segments { min-width: 0; margin: 0; padding: 0; border: 0; }.task-filters__segments [role='group'] { display: flex; flex-wrap: wrap; gap: var(--ui-space-2); margin-top: var(--ui-space-2); }.task-filters__segment { min-height: var(--ui-action-min-height); padding: 0 var(--ui-space-3); border: var(--ui-border-width) solid var(--ui-hairline); border-radius: var(--ui-radius-pill); color: var(--ui-text-secondary); background: var(--ui-surface); cursor: pointer; font-weight: 600; }.task-filters__segment[aria-pressed='true'] { border-color: var(--ui-accent); color: var(--ui-accent-link); background: color-mix(in srgb, var(--ui-accent) 8%, var(--ui-surface)); }.task-filters__disclosure { justify-self: start; padding: 0; }.task-filters__advanced { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--ui-space-3); padding-top: var(--ui-space-4); border-top: var(--ui-border-width) solid var(--ui-hairline); }.task-filters__actions { display: flex; flex-wrap: wrap; gap: var(--ui-space-2); }@media (max-width: 680px) { .task-filters { margin: var(--ui-space-4); }.task-filters__common { display: grid; align-items: stretch; }.task-filters__common > .ui-field-group { width: 100%; }.task-filters__advanced { grid-template-columns: 1fr; } }
</style>