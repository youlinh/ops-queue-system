<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import AppDialog from '@/components/ui/AppDialog.vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import { listAccounts } from '@/features/people/people.api'
import type { AccountView } from '@/features/people/people.types'
import { toShanghaiInstant } from '@/features/tasks/shanghai-time'
import { onMounted, reactive, ref } from 'vue'
import { searchAuditLogs, type AuditLog, type AuditPage, type AuditSearch } from './audit.api'

const accounts = ref<AccountView[]>([])
const page = ref<AuditPage>({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
const filters = reactive({ actorId: '', action: '', objectType: '', objectId: '', from: '', to: '' })
const selected = ref<AuditLog | null>(null)
const loading = ref(false)
const errorMessage = ref('')
let requestSequence = 0

function query(pageNumber = 0): AuditSearch {
  return { actorId: filters.actorId || undefined, action: filters.action.trim() || undefined, objectType: filters.objectType.trim() || undefined, objectId: filters.objectId.trim() || undefined, from: filters.from ? toShanghaiInstant(filters.from) : undefined, to: filters.to ? toShanghaiInstant(filters.to) : undefined, page: pageNumber, size: 20 }
}
async function load(pageNumber = 0): Promise<void> {
  const request = ++requestSequence
  loading.value = true
  errorMessage.value = ''
  try { const result = await searchAuditLogs(query(pageNumber)); if (request === requestSequence) page.value = result } catch (error: unknown) { if (request === requestSequence) errorMessage.value = apiErrorMessage(error, '审计日志加载失败') } finally { if (request === requestSequence) loading.value = false }
}
function actorName(id: string): string { return accounts.value.find((account) => account.id === id)?.displayName || id }
function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value))
}
function closeDetail(): void { selected.value = null }
onMounted(async () => { try { accounts.value = await listAccounts() } catch { accounts.value = [] }; await load() })
</script>

<template>
  <section class="content-panel ui-panel audit-page">
    <div class="panel-heading"><div><p class="eyebrow"><AppIcon name="audit" decorative /> IMMUTABLE AUDIT</p><h2>审计日志</h2><p>查看账号、取号、叫号、完成、转交、值班表与重新分配的操作留痕。</p></div><span class="queue-total">共 {{ page.totalElements }} 条</span></div>
    <form class="audit-filters" @submit.prevent="load(0)">
      <label class="ui-field-group"><span class="ui-field-label">操作人</span><select v-model="filters.actorId" class="ui-field-control"><option value="">全部人员</option><option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.displayName }}</option></select></label>
      <label class="ui-field-group"><span class="ui-field-label">动作</span><input v-model="filters.action" class="ui-field-control" placeholder="如 TASK_CALLED" /></label>
      <label class="ui-field-group"><span class="ui-field-label">对象类型</span><input v-model="filters.objectType" class="ui-field-control" placeholder="如 TASK" /></label>
      <label class="ui-field-group"><span class="ui-field-label">对象 ID</span><input v-model="filters.objectId" class="ui-field-control" /></label>
      <label class="ui-field-group"><span class="ui-field-label">开始时间</span><input v-model="filters.from" class="ui-field-control" type="datetime-local" /></label>
      <label class="ui-field-group"><span class="ui-field-label">结束时间</span><input v-model="filters.to" class="ui-field-control" type="datetime-local" /></label>
      <button class="ui-button ui-button--primary" type="submit">查询</button>
    </form>
    <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p><p v-if="loading" class="audit-refresh-status" role="status">正在加载审计日志…</p>
    <div class="audit-log-list" aria-live="polite"><p v-if="!loading && !page.content.length" class="table-empty">暂无日志</p><article v-for="entry in page.content" v-else :key="entry.id" class="audit-log-list__item"><div class="audit-log-list__primary"><strong>{{ entry.action }}</strong><span>{{ entry.objectType }} · {{ entry.objectId }}</span></div><div class="audit-log-list__meta"><span>{{ formatDateTime(entry.occurredAt) }}</span><span>{{ actorName(entry.actorId) }}</span><span>IP {{ entry.sourceIp }}</span></div><button data-testid="open-audit-detail" class="ui-button ui-button--quiet text-button" type="button" @click="selected = entry">查看详情</button></article></div>
    <footer v-if="page.totalPages > 1" class="pagination"><button class="ui-button" type="button" :disabled="page.page === 0" @click="load(page.page - 1)">上一页</button><span>第 {{ page.page + 1 }} / {{ page.totalPages }} 页</span><button class="ui-button" type="button" :disabled="page.page + 1 >= page.totalPages" @click="load(page.page + 1)">下一页</button></footer>
  </section>
  <AppDialog v-if="selected" :open="true" labelled-by="audit-detail-dialog-title" @close="closeDetail">
    <p class="eyebrow">READ ONLY</p><h3 id="audit-detail-dialog-title">变更详情</h3>
    <dl class="audit-detail__summary"><div><dt>动作</dt><dd>{{ selected.action }}</dd></div><div><dt>对象</dt><dd>{{ selected.objectType }} · {{ selected.objectId }}</dd></div><div><dt>操作人</dt><dd>{{ actorName(selected.actorId) }}</dd></div><div><dt>发生时间</dt><dd>{{ formatDateTime(selected.occurredAt) }}</dd></div></dl>
    <div class="audit-detail__payloads"><section><h4>操作前</h4><pre>{{ JSON.stringify(selected.before, null, 2) }}</pre></section><section><h4>操作后</h4><pre>{{ JSON.stringify(selected.after, null, 2) }}</pre></section></div>
    <div class="dialog-actions"><button class="ui-button ui-button--primary" type="button" @click="closeDetail"><AppIcon name="close" decorative />关闭</button></div>
  </AppDialog>
</template>

<style scoped>
.audit-page{padding:var(--ui-space-6)}.eyebrow :deep(svg){width:1rem;height:1rem;vertical-align:-.15em}.audit-filters{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:var(--ui-space-3);margin:var(--ui-space-5) 0}.audit-refresh-status{margin:0 0 var(--ui-space-3);color:var(--ui-text-secondary)}.audit-log-list{display:grid;border-top:var(--ui-border-width) solid var(--ui-hairline)}.audit-log-list__item{display:grid;grid-template-columns:minmax(12rem,1.1fr) minmax(0,1.8fr) auto;gap:var(--ui-space-4);align-items:center;padding:var(--ui-space-4) 0;border-bottom:var(--ui-border-width) solid var(--ui-hairline)}.audit-log-list__primary,.audit-log-list__meta{display:grid;gap:var(--ui-space-1);min-width:0}.audit-log-list__primary span,.audit-log-list__meta{color:var(--ui-text-secondary);font-size:.875rem}.audit-log-list__meta{grid-template-columns:repeat(3,minmax(0,1fr))}.audit-detail__summary{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:var(--ui-space-3);margin:var(--ui-space-5) 0}.audit-detail__summary div{padding:var(--ui-space-3);border-radius:var(--ui-radius-control);background:var(--ui-hover)}.audit-detail__summary dt{color:var(--ui-text-secondary);font-size:.8125rem}.audit-detail__summary dd{margin:var(--ui-space-1) 0 0}.audit-detail__payloads{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:var(--ui-space-3)}.audit-detail__payloads h4{margin:0 0 var(--ui-space-2);font-size:.875rem}.audit-detail__payloads pre{max-height:18rem;overflow:auto;margin:0;padding:var(--ui-space-3);border:var(--ui-border-width) solid var(--ui-hairline);border-radius:var(--ui-radius-control);background:var(--ui-hover);color:var(--ui-text);font-size:.75rem;white-space:pre-wrap;word-break:break-word}@media(max-width:920px){.audit-filters{grid-template-columns:repeat(2,minmax(0,1fr))}.audit-log-list__item{grid-template-columns:1fr auto}.audit-log-list__meta{grid-column:1/-1}.audit-detail__payloads{grid-template-columns:1fr}}@media(max-width:680px){.audit-page{padding:var(--ui-space-4)}.audit-filters,.audit-detail__summary{grid-template-columns:1fr}.audit-log-list__item{grid-template-columns:1fr;gap:var(--ui-space-2)}.audit-log-list__meta{grid-column:auto;grid-template-columns:1fr}.audit-log-list__item .ui-button{justify-self:start}}
</style>
