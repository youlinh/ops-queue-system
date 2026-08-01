<script setup lang="ts">
import type { RosterPreviewRow } from './roster.types'

defineProps<{ rows: RosterPreviewRow[] }>()
</script>

<template>
  <div class="task-table-wrap" aria-label="值班表预览">
    <table class="task-table ui-table roster-preview-table">
      <thead>
        <tr>
          <th>Excel 行</th>
          <th>值班日期</th>
          <th>二线管理员</th>
          <th>三线管理员</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.sourceRowNumber">
          <td>{{ row.sourceRowNumber }}</td>
          <td>{{ row.dutyDate }}</td>
          <td>{{ row.secondLineDisplayName }}</td>
          <td>{{ row.thirdLineDisplayName }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
