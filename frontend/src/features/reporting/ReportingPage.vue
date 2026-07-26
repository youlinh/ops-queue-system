<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import { useAuthStore } from '@/features/auth/auth.store'
import { listAccounts } from '@/features/people/people.api'
import type { AccountView } from '@/features/people/people.types'
import { shanghaiDate } from '@/features/tasks/shanghai-time'
import { computed, onMounted, reactive, ref } from 'vue'
import {
  dailyReport,
  monthlyReport,
  type OperatorMetrics,
} from './reporting.api'

const auth = useAuthStore()
const today = shanghaiDate()
const form = reactive({
  operatorId: auth.user?.id || '',
  date: today,
  month: today.slice(0, 7),
})
const operators = ref<AccountView[]>([])
const daily = ref<OperatorMetrics | null>(null)
const monthly = ref<OperatorMetrics | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const isLeader = computed(() => auth.user?.roles.includes('LEADER') || false)

async function loadOperators(): Promise<void> {
  if (isLeader.value) {
    operators.value = (await listAccounts())
      .filter((account) =>
        account.enabled && account.roles.includes('OPERATOR'),
      )
    if (!operators.value.some((operator) => operator.id === form.operatorId)) {
      form.operatorId = operators.value[0]?.id || ''
    }
  } else if (auth.user) {
    operators.value = [{
      id: auth.user.id,
      username: auth.user.username,
      displayName: auth.user.displayName,
      roles: [...auth.user.roles],
      enabled: true,
      mustChangePassword: auth.user.mustChangePassword,
    }]
  }
}

async function loadReports(): Promise<void> {
  if (!form.operatorId) {
    errorMessage.value = '请选择运维管理员'
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const [day, month] = await Promise.all([
      dailyReport(form.date, form.operatorId),
      monthlyReport(form.month, form.operatorId),
    ])
    daily.value = day
    monthly.value = month
  } catch (error: unknown) {
    errorMessage.value = apiErrorMessage(error, '统计数据加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    await loadOperators()
    await loadReports()
  } catch (error: unknown) {
    errorMessage.value = apiErrorMessage(error, '统计页面初始化失败')
  }
})
</script>

<template>
  <section class="content-panel reporting-page">
    <div class="panel-heading">
      <div>
        <p class="eyebrow">WORKLOAD</p>
        <h2>任务量与实际耗时</h2>
        <p>每日任务按当前负责人统计；月度时长只累计已完成任务的实际耗时。</p>
      </div>
    </div>
    <form class="report-filters" @submit.prevent="loadReports">
      <label>
        <span>运维管理员</span>
        <select v-model="form.operatorId" :disabled="!isLeader">
          <option
            v-for="operator in operators"
            :key="operator.id"
            :value="operator.id"
          >
            {{ operator.displayName }}
          </option>
        </select>
      </label>
      <label>
        <span>日报日期</span>
        <input v-model="form.date" type="date" />
      </label>
      <label>
        <span>月报月份</span>
        <input v-model="form.month" type="month" />
      </label>
      <button class="action-button action-button--primary" type="submit">
        {{ loading ? '查询中…' : '查询' }}
      </button>
    </form>
    <p v-if="errorMessage" class="form-error" role="alert">
      {{ errorMessage }}
    </p>

    <div class="report-grid">
      <article v-if="daily" class="metric-card">
        <p class="eyebrow">DAILY · {{ daily.date }}</p>
        <h3>{{ daily.totalTaskCount }} <small>个任务</small></h3>
        <dl>
          <div><dt>待执行</dt><dd>{{ daily.pendingCount }}</dd></div>
          <div><dt>执行中</dt><dd>{{ daily.inProgressCount }}</dd></div>
          <div><dt>已完成</dt><dd>{{ daily.completedCount }}</dd></div>
          <div><dt>预计时长</dt><dd>{{ daily.estimatedMinutes }} 分钟</dd></div>
          <div><dt>实际时长</dt><dd>{{ daily.completedActualMinutes }} 分钟</dd></div>
        </dl>
      </article>
      <article v-if="monthly" class="metric-card metric-card--accent">
        <p class="eyebrow">MONTHLY · {{ monthly.month }}</p>
        <h3>{{ monthly.completedActualMinutes }} <small>实际分钟</small></h3>
        <dl>
          <div><dt>任务总数</dt><dd>{{ monthly.totalTaskCount }}</dd></div>
          <div><dt>待执行</dt><dd>{{ monthly.pendingCount }}</dd></div>
          <div><dt>执行中</dt><dd>{{ monthly.inProgressCount }}</dd></div>
          <div><dt>已完成</dt><dd>{{ monthly.completedCount }}</dd></div>
          <div><dt>预计时长</dt><dd>{{ monthly.estimatedMinutes }} 分钟</dd></div>
        </dl>
      </article>
    </div>
  </section>
</template>
