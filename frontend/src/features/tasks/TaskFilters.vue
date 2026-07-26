<script setup lang="ts">
import { reactive, watch } from 'vue'
import {
  categoryLabels,
  statusLabels,
  type TaskSearch,
} from './task.types'

const props = defineProps<{ initial: TaskSearch }>()
const emit = defineEmits<{
  search: [filters: TaskSearch]
  reset: []
}>()
const filters = reactive<TaskSearch>({ ...props.initial })

watch(
  () => props.initial,
  (next) => Object.assign(filters, next),
  { deep: true },
)

function reset(): void {
  Object.assign(filters, {
    operationDate: '',
    category: '',
    systemName: '',
    status: '',
    creatorId: '',
    assigneeId: '',
  })
  emit('reset')
}
</script>

<template>
  <form class="task-filters" @submit.prevent="emit('search', { ...filters })">
    <label>
      <span>操作日期</span>
      <input v-model="filters.operationDate" type="date" />
    </label>
    <label>
      <span>类别</span>
      <select v-model="filters.category">
        <option value="">全部类别</option>
        <option
          v-for="(label, value) in categoryLabels"
          :key="value"
          :value="value"
        >
          {{ label }}
        </option>
      </select>
    </label>
    <label>
      <span>状态</span>
      <select v-model="filters.status">
        <option value="">全部状态</option>
        <option
          v-for="(label, value) in statusLabels"
          :key="value"
          :value="value"
        >
          {{ label }}
        </option>
      </select>
    </label>
    <label class="filter-system">
      <span>系统名称</span>
      <input
        v-model="filters.systemName"
        maxlength="128"
        placeholder="输入关键字"
      />
    </label>
    <label>
      <span>创建人 ID</span>
      <input
        v-model="filters.creatorId"
        placeholder="按创建人筛选"
      />
    </label>
    <label>
      <span>负责人 ID</span>
      <input
        v-model="filters.assigneeId"
        placeholder="按负责人筛选"
      />
    </label>
    <button class="action-button action-button--primary" type="submit">
      查询
    </button>
    <button class="action-button" type="button" @click="reset">
      重置
    </button>
  </form>
</template>
