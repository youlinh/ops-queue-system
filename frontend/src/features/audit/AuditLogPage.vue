<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import { listAccounts } from '@/features/people/people.api'
import type { AccountView } from '@/features/people/people.types'
import { toShanghaiInstant } from '@/features/tasks/shanghai-time'
import { onMounted, reactive, ref } from 'vue'
import {
  searchAuditLogs,
  type AuditLog,
  type AuditPage,
  type AuditSearch,
} from './audit.api'

const accounts = ref<AccountView[]>([])
const page = ref<AuditPage>({
  content: [],
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
})
const filters = reactive({
  actorId: '',
  action: '',
  objectType: '',
  objectId: '',
  from: '',
  to: '',
})
const selected = ref<AuditLog | null>(null)
const loading = ref(false)
const errorMessage = ref('')
let requestSequence = 0

function query(pageNumber = 0): AuditSearch {
  return {
    actorId: filters.actorId || undefined,
    action: filters.action.trim() || undefined,
    objectType: filters.objectType.trim() || undefined,
    objectId: filters.objectId.trim() || undefined,
    from: filters.from ? toShanghaiInstant(filters.from) : undefined,
    to: filters.to ? toShanghaiInstant(filters.to) : undefined,
    page: pageNumber,
    size: 20,
  }
}

async function load(pageNumber = 0): Promise<void> {
  const request = ++requestSequence
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await searchAuditLogs(query(pageNumber))
    if (request === requestSequence) page.value = result
  } catch (error: unknown) {
    if (request === requestSequence) {
      errorMessage.value = apiErrorMessage(error, '审计日志加载失败')
    }
  } finally {
    if (request === requestSequence) loading.value = false
  }
}

function actorName(id: string): string {
  return accounts.value.find((account) => account.id === id)?.displayName || id
}

function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

onMounted(async () => {
  try {
    accounts.value = await listAccounts()
  } catch {
    accounts.value = []
  }
  await load()
})
</script>

<template>
  <section class="content-panel audit-page">
    <div class="panel-heading">
      <div>
        <p class="eyebrow">IMMUTABLE AUDIT</p>
        <h2>审计日志</h2>
        <p>查看账号、取号、叫号、完成、转交、值班表与重新分配的操作留痕。</p>
      </div>
      <span class="queue-total">共 {{ page.totalElements }} 条</span>
    </div>
    <form class="audit-filters" @submit.prevent="load(0)">
      <label>
        <span>操作人</span>
        <select v-model="filters.actorId">
          <option value="">全部人员</option>
          <option v-for="account in accounts" :key="account.id" :value="account.id">
            {{ account.displayName }}
          </option>
        </select>
      </label>
      <label><span>动作</span><input v-model="filters.action" placeholder="如 TASK_CALLED" /></label>
      <label><span>对象类型</span><input v-model="filters.objectType" placeholder="如 TASK" /></label>
      <label><span>对象 ID</span><input v-model="filters.objectId" /></label>
      <label><span>开始时间</span><input v-model="filters.from" type="datetime-local" /></label>
      <label><span>结束时间</span><input v-model="filters.to" type="datetime-local" /></label>
      <button class="action-button action-button--primary" type="submit">查询</button>
    </form>
    <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
    <div class="task-table-wrap">
      <table class="task-table">
        <thead><tr><th>时间</th><th>操作人</th><th>动作</th><th>对象</th><th>来源 IP</th><th>数据</th></tr></thead>
        <tbody>
          <tr v-if="loading"><td colspan="6" class="table-empty">正在加载审计日志…</td></tr>
          <tr v-else-if="!page.content.length"><td colspan="6" class="table-empty">暂无日志</td></tr>
          <tr v-for="entry in page.content" v-else :key="entry.id">
            <td>{{ formatDateTime(entry.occurredAt) }}</td>
            <td>{{ actorName(entry.actorId) }}</td>
            <td><strong>{{ entry.action }}</strong></td>
            <td>{{ entry.objectType }}<small>{{ entry.objectId }}</small></td>
            <td>{{ entry.sourceIp }}</td>
            <td><button class="text-button" type="button" @click="selected = entry">查看前后数据</button></td>
          </tr>
        </tbody>
      </table>
    </div>
    <footer v-if="page.totalPages > 1" class="pagination">
      <button class="action-button" type="button" :disabled="page.page === 0" @click="load(page.page - 1)">上一页</button>
      <span>第 {{ page.page + 1 }} / {{ page.totalPages }} 页</span>
      <button class="action-button" type="button" :disabled="page.page + 1 >= page.totalPages" @click="load(page.page + 1)">下一页</button>
    </footer>
  </section>

  <div v-if="selected" class="dialog-backdrop">
    <section class="task-dialog task-dialog--wide audit-drawer" role="dialog" aria-modal="true">
      <p class="eyebrow">READ ONLY</p>
      <h3>{{ selected.action }}</h3>
      <div class="audit-json-grid">
        <div><strong>操作前</strong><pre>{{ JSON.stringify(selected.before, null, 2) }}</pre></div>
        <div><strong>操作后</strong><pre>{{ JSON.stringify(selected.after, null, 2) }}</pre></div>
      </div>
      <div class="dialog-actions">
        <button class="action-button" type="button" @click="selected = null">关闭</button>
      </div>
    </section>
  </div>
</template>
