<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import AppIcon from '@/components/ui/AppIcon.vue'
import { useAuthStore } from '@/features/auth/auth.store'
import { listAccounts } from '@/features/people/people.api'
import type { AccountView } from '@/features/people/people.types'
import { shanghaiDate } from '@/features/tasks/shanghai-time'
import { computed, onMounted, reactive, ref } from 'vue'
import { dailyReport, monthlyReport, type OperatorMetrics } from './reporting.api'

const auth = useAuthStore()
const today = shanghaiDate()
const form = reactive({ operatorId: auth.user?.id || '', date: today, month: today.slice(0, 7) })
const operators = ref<AccountView[]>([])
const daily = ref<OperatorMetrics | null>(null)
const monthly = ref<OperatorMetrics | null>(null)
const updating = ref(false)
const errorMessage = ref('')
const isLeader = computed(() => auth.user?.roles.includes('LEADER') || false)
const hasMetrics = computed(() => Boolean(daily.value || monthly.value))

async function loadOperators(): Promise<void> {
  if (isLeader.value) {
    operators.value = (await listAccounts()).filter((account) => account.enabled && account.roles.includes('OPERATOR'))
    if (!operators.value.some((operator) => operator.id === form.operatorId)) form.operatorId = operators.value[0]?.id || ''
  } else if (auth.user) {
    operators.value = [{ id: auth.user.id, username: auth.user.username, displayName: auth.user.displayName, roles: [...auth.user.roles], enabled: true, mustChangePassword: auth.user.mustChangePassword }]
  }
}

async function loadReports(): Promise<void> {
  if (!form.operatorId) { errorMessage.value = '请选择运维管理员'; return }
  updating.value = hasMetrics.value
  errorMessage.value = ''
  try {
    const [nextDaily, nextMonthly] = await Promise.all([dailyReport(form.date, form.operatorId), monthlyReport(form.month, form.operatorId)])
    daily.value = nextDaily
    monthly.value = nextMonthly
  } catch (error: unknown) {
    errorMessage.value = apiErrorMessage(error, '统计数据加载失败')
  } finally {
    updating.value = false
  }
}

onMounted(async () => {
  try { await loadOperators(); await loadReports() } catch (error: unknown) { errorMessage.value = apiErrorMessage(error, '统计页面初始化失败') }
})
</script>

<template>
  <section class="reporting-page ui-page-stack">
    <div class="content-panel ui-panel report-controls">
      <div class="panel-heading"><div><p class="eyebrow"><AppIcon name="reports" decorative /> WORKLOAD</p><h2>任务量与实际耗时</h2><p>每日任务按当前负责人统计；月度时长只累计已完成任务的实际耗时。</p></div></div>
      <form class="report-filters" @submit.prevent="loadReports">
        <label class="ui-field-group"><span class="ui-field-label">运维管理员</span><select v-model="form.operatorId" class="ui-field-control" :disabled="!isLeader"><option v-for="operator in operators" :key="operator.id" :value="operator.id">{{ operator.displayName }}</option></select></label>
        <label class="ui-field-group"><span class="ui-field-label">日报日期</span><input v-model="form.date" class="ui-field-control" type="date" /></label>
        <label class="ui-field-group"><span class="ui-field-label">月报月份</span><input v-model="form.month" class="ui-field-control" type="month" /></label>
        <button data-testid="refresh-report" class="ui-button ui-button--primary" type="button" @click="loadReports">{{ updating ? '正在更新…' : '更新统计' }}</button>
      </form>
      <p v-if="updating" class="report-refresh-status" role="status">正在更新统计，当前结果仍可查看。</p>
      <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
    </div>

    <section v-if="hasMetrics" class="content-panel ui-panel report-metrics" aria-label="任务统计结果">
      <div class="report-grid">
        <article v-if="daily" class="report-metrics__group">
          <p class="eyebrow">DAILY · {{ daily.date }}</p><div class="report-metrics__hero"><span class="ui-number">{{ daily.totalTaskCount }}</span><span>个任务</span></div><p class="report-metrics__definition">按任务当前负责人统计当日工作量。</p>
          <dl class="report-metrics__values"><div><dt>待执行</dt><dd class="ui-number">{{ daily.pendingCount }} <small>个</small></dd></div><div><dt>执行中</dt><dd class="ui-number">{{ daily.inProgressCount }} <small>个</small></dd></div><div><dt>已完成</dt><dd class="ui-number">{{ daily.completedCount }} <small>个</small></dd></div><div><dt>预计时长</dt><dd class="ui-number">{{ daily.estimatedMinutes }} <small>分钟</small></dd></div><div><dt>实际时长</dt><dd class="ui-number">{{ daily.completedActualMinutes }} <small>分钟</small></dd></div></dl>
        </article>
        <article v-if="monthly" class="report-metrics__group report-metrics__group--monthly">
          <p class="eyebrow">MONTHLY · {{ monthly.month }}</p><div class="report-metrics__hero"><span class="ui-number">{{ monthly.completedActualMinutes }}</span><span>实际分钟</span></div><p class="report-metrics__definition">实际时长仅累加本月已经完成的任务。</p>
          <dl class="report-metrics__values"><div><dt>任务总数</dt><dd class="ui-number">{{ monthly.totalTaskCount }} <small>个</small></dd></div><div><dt>待执行</dt><dd class="ui-number">{{ monthly.pendingCount }} <small>个</small></dd></div><div><dt>执行中</dt><dd class="ui-number">{{ monthly.inProgressCount }} <small>个</small></dd></div><div><dt>已完成</dt><dd class="ui-number">{{ monthly.completedCount }} <small>个</small></dd></div><div><dt>预计时长</dt><dd class="ui-number">{{ monthly.estimatedMinutes }} <small>分钟</small></dd></div></dl>
        </article>
      </div>
    </section>
  </section>
</template>

<style scoped>
.report-controls,.report-metrics{padding:var(--ui-space-6)}.eyebrow :deep(svg){width:1rem;height:1rem;vertical-align:-.15em}.report-filters{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));align-items:end;gap:var(--ui-space-3);margin-top:var(--ui-space-5)}.report-refresh-status{margin:var(--ui-space-3) 0 0;color:var(--ui-text-secondary)}.report-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:var(--ui-space-6)}.report-metrics__group+.report-metrics__group{padding-left:var(--ui-space-6);border-left:var(--ui-border-width) solid var(--ui-hairline)}.report-metrics__hero{display:flex;align-items:baseline;gap:var(--ui-space-2);margin:var(--ui-space-3) 0 var(--ui-space-1);color:var(--ui-text-secondary)}.report-metrics__hero .ui-number{color:var(--ui-text);font-size:clamp(2.25rem,6vw,3.5rem);font-weight:700;letter-spacing:-.05em}.report-metrics__definition{margin:0;color:var(--ui-text-secondary);font-size:.875rem}.report-metrics__values{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:var(--ui-space-2);margin:var(--ui-space-5) 0 0}.report-metrics__values div{padding:var(--ui-space-3);border:var(--ui-border-width) solid var(--ui-hairline);border-radius:var(--ui-radius-control);background:var(--ui-hover)}.report-metrics__values dt{color:var(--ui-text-secondary);font-size:.8125rem}.report-metrics__values dd{margin:var(--ui-space-1) 0 0;color:var(--ui-text);font-size:1.125rem;font-weight:700}.report-metrics__values small{color:var(--ui-text-secondary);font-size:.75rem;font-weight:500}@media(max-width:680px){.report-controls,.report-metrics{padding:var(--ui-space-4)}.report-filters,.report-grid{grid-template-columns:1fr}.report-metrics__group+.report-metrics__group{padding:var(--ui-space-5) 0 0;border-top:var(--ui-border-width) solid var(--ui-hairline);border-left:0}.report-metrics__values{grid-template-columns:1fr 1fr}}
</style>
