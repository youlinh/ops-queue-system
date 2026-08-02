<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import { computed, onUnmounted, reactive, ref } from 'vue'
import {
  assignmentRuleLabel,
  categoryLabels,
  type CreatedTask,
  type TaskCategory,
} from './task.types'
import {
  createTask,
  notifyTaskChanged,
  suggestSystemNames,
} from './task.api'
import { toShanghaiInstant } from './shanghai-time'

const form = reactive({
  category: '' as TaskCategory | '',
  systemName: '',
  estimatedMinutes: 60,
  processNumber: '',
  operationStart: '',
  operationEnd: '',
})
const submitting = ref(false)
const errorMessage = ref('')
const result = ref<CreatedTask | null>(null)
const suggestions = ref<string[]>([])
let suggestionTimer: ReturnType<typeof setTimeout> | null = null
let suggestionRequest = 0

const processLabel = computed(() =>
  form.category === 'VERSION_RELEASE'
    ? '版本发布流程编号'
    : '数据维护流程编号',
)

function focusFirstInvalid(testId: string): string {
  const focus = () => document.querySelector<HTMLElement>(`[data-testid="${testId}"]`)?.focus()
  focus()
  requestAnimationFrame(focus)
  return testId
}

function validate(): string {
  if (!form.category) {
    focusFirstInvalid('category')
    return '请选择任务类别'
  }
  if (!form.systemName.trim()) {
    focusFirstInvalid('system-name')
    return '请输入系统名称'
  }
  if (!Number.isFinite(Number(form.estimatedMinutes)) || Number(form.estimatedMinutes) <= 0) {
    focusFirstInvalid('estimated-minutes')
    return '预计耗时必须大于 0'
  }
  if (Number(form.estimatedMinutes) > 1440) {
    focusFirstInvalid('estimated-minutes')
    return '预计耗时需在 1 到 1440 分钟之间'
  }
  if (!form.processNumber.trim()) {
    focusFirstInvalid('process-number')
    return '请输入操作流程编号'
  }
  if (!form.operationStart || !form.operationEnd) {
    focusFirstInvalid(!form.operationStart ? 'operation-start' : 'operation-end')
    return '请选择完整的操作时间范围'
  }
  if (new Date(toShanghaiInstant(form.operationEnd)).getTime()
      <= new Date(toShanghaiInstant(form.operationStart)).getTime()) {
    focusFirstInvalid('operation-end')
    return '操作结束时间必须晚于开始时间'
  }
  return ''
}

function scheduleSuggestions(): void {
  const request = ++suggestionRequest
  if (suggestionTimer) clearTimeout(suggestionTimer)
  if (form.systemName.trim().length < 2) {
    suggestions.value = []
    return
  }
  suggestionTimer = setTimeout(async () => {
    const query = form.systemName
    try {
      const next = await suggestSystemNames(query)
      if (request === suggestionRequest) suggestions.value = next
    } catch {
      if (request === suggestionRequest) suggestions.value = []
    }
  }, 250)
}

onUnmounted(() => {
  suggestionRequest++
  if (suggestionTimer) clearTimeout(suggestionTimer)
})

async function submit(): Promise<void> {
  errorMessage.value = validate()
  result.value = null
  if (errorMessage.value || submitting.value || !form.category) return

  submitting.value = true
  try {
    result.value = await createTask({
      category: form.category,
      systemName: form.systemName.trim(),
      estimatedMinutes: Number(form.estimatedMinutes),
      processNumber: form.processNumber.trim(),
      operationStart: toShanghaiInstant(form.operationStart),
      operationEnd: toShanghaiInstant(form.operationEnd),
    })
    notifyTaskChanged()
  } catch (error: unknown) {
    errorMessage.value = apiErrorMessage(error, '取号失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="task-create-layout">
    <form class="task-form" @submit.prevent="submit">
      <header class="task-form__heading">
        <div>
          <p class="eyebrow">TAKE A NUMBER</p>
          <h2>提交操作任务</h2>
          <p>填写完整操作信息后，系统将按操作开始日期和排班规则自动分派。</p>
        </div>
        <span class="required-note">* 为必填项</span>
      </header>

      <section class="ui-panel task-form__panel" aria-labelledby="task-information-title">
        <header class="task-form__panel-heading">
          <h3 id="task-information-title">任务信息</h3>
          <p>确定任务类别、目标系统和流程编号。</p>
        </header>
        <div class="task-form__grid">
          <label class="ui-field-group">
            <span class="ui-field-label">任务类别 *</span>
            <select v-model="form.category" data-testid="category" class="ui-field-control">
              <option value="" disabled>请选择任务类别</option>
              <option v-for="(label, value) in categoryLabels" :key="value" :value="value">{{ label }}</option>
            </select>
          </label>
          <label class="ui-field-group">
            <span class="ui-field-label">系统名称 *</span>
            <input v-model="form.systemName" data-testid="system-name" class="ui-field-control" list="system-suggestions" maxlength="128" placeholder="例如：核心交易系统" @input="scheduleSuggestions">
            <datalist id="system-suggestions"><option v-for="name in suggestions" :key="name" :value="name" /></datalist>
          </label>
          <label class="ui-field-group task-form__full-width">
            <span class="ui-field-label">{{ processLabel }} *</span>
            <input v-model="form.processNumber" data-testid="process-number" class="ui-field-control" maxlength="128" placeholder="请输入对应流程编号">
          </label>
        </div>
      </section>

      <section class="ui-panel task-form__panel" aria-labelledby="task-window-title">
        <header class="task-form__panel-heading">
          <h3 id="task-window-title">操作窗口</h3>
          <p>系统会以操作开始时间匹配当日值班安排。</p>
        </header>
        <div class="task-form__grid">
          <label class="ui-field-group">
            <span class="ui-field-label">操作开始时间 *</span>
            <input v-model="form.operationStart" data-testid="operation-start" class="ui-field-control" type="datetime-local">
          </label>
          <label class="ui-field-group">
            <span class="ui-field-label">操作结束时间 *</span>
            <input v-model="form.operationEnd" data-testid="operation-end" class="ui-field-control" type="datetime-local">
          </label>
        </div>
      </section>

      <section class="ui-panel task-form__panel" aria-labelledby="task-support-title">
        <header class="task-form__panel-heading">
          <h3 id="task-support-title">支持信息</h3>
          <p>预计耗时用于排班负载评估。</p>
        </header>
        <label class="ui-field-group task-form__estimate">
          <span class="ui-field-label">预计耗时（分钟）*</span>
          <input v-model.number="form.estimatedMinutes" data-testid="estimated-minutes" class="ui-field-control ui-number" type="number" min="1" max="1440" step="1">
        </label>
      </section>

      <p v-if="errorMessage" class="form-error task-form__error" role="alert">{{ errorMessage }}</p>
      <div class="task-form__actions">
        <button data-testid="submit-task" class="ui-button ui-button--primary" type="submit" :disabled="submitting" @click.prevent="submit">
          {{ submitting ? '正在分派…' : '取号并自动分派' }}
        </button>
      </div>
    </form>

    <aside class="ui-panel allocation-guide" aria-label="分派说明">
      <p class="eyebrow">ASSIGNMENT GUIDE</p>
      <h3>系统如何分派</h3>
      <ol>
        <li><strong>按操作日期</strong><span>匹配当天二线和三线值班人员</span></li>
        <li><strong>白天优先二线</strong><span>不可参与时转入三线，再进入公平分配</span></li>
        <li><strong>晚间均衡</strong><span>二线、三线各 3 个后按负载公平分配</span></li>
        <li><strong>全程留痕</strong><span>分派规则和后续转交均记录在任务时间线</span></li>
      </ol>
    </aside>

    <section v-if="result" class="ui-panel allocation-result" aria-live="polite">
      <span class="ui-status-pill ui-status-pill--live">取号成功</span>
      <h3>{{ result.ticketNumber }}</h3>
      <dl>
        <div><dt>已分配负责人</dt><dd>{{ result.assigneeName }}</dd></div>
        <div><dt>分派规则</dt><dd>{{ assignmentRuleLabel(result.assignmentRule) }}</dd></div>
      </dl>
      <RouterLink :to="`/tasks/${result.id}`" class="inline-link">查看任务详情</RouterLink>
    </section>
  </section>
</template>

<style scoped>
.task-create-layout { display: grid; grid-template-columns: minmax(0, 1fr) 18rem; gap: var(--ui-space-5); align-items: start; }
.task-form { display: grid; gap: var(--ui-space-4); }
.task-form__heading, .task-form__panel-heading { display: flex; align-items: start; justify-content: space-between; gap: var(--ui-space-4); }
.task-form__heading { padding: var(--ui-space-2) var(--ui-space-1); }
.task-form__heading h2, .task-form__panel-heading h3, .allocation-guide h3, .allocation-result h3 { margin: 0; color: var(--ui-text); }
.task-form__heading p:not(.eyebrow), .task-form__panel-heading p { margin: var(--ui-space-2) 0 0; color: var(--ui-text-secondary); }
.required-note { color: var(--ui-text-secondary); font-size: .8125rem; white-space: nowrap; }
.task-form__panel, .allocation-guide, .allocation-result { padding: var(--ui-space-5); }
.task-form__panel-heading { display: block; padding-bottom: var(--ui-space-4); border-bottom: var(--ui-border-width) solid var(--ui-hairline); }
.task-form__panel-heading h3 { font-size: 1rem; }.task-form__panel-heading p { font-size: .875rem; }
.task-form__grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--ui-space-4); padding-top: var(--ui-space-4); }
.task-form__full-width { grid-column: 1 / -1; }.task-form__estimate { max-width: 16rem; padding-top: var(--ui-space-4); }
.task-form__error { margin: 0; }.task-form__actions { display: flex; justify-content: flex-end; }.task-form__actions .ui-button { min-width: 11.25rem; }
.allocation-guide { display: grid; gap: var(--ui-space-4); }.allocation-guide .eyebrow { margin: 0; }.allocation-guide ol { display: grid; gap: var(--ui-space-4); margin: 0; padding: 0; list-style: none; counter-reset: guide; }.allocation-guide li { counter-increment: guide; position: relative; padding-left: 2rem; }.allocation-guide li::before { position: absolute; left: 0; display: grid; width: 1.5rem; height: 1.5rem; place-items: center; border-radius: 50%; color: var(--ui-accent-link); background: var(--ui-hover); content: counter(guide); font-size: .75rem; font-weight: 700; }.allocation-guide strong, .allocation-guide span { display: block; }.allocation-guide strong { color: var(--ui-text); font-size: .875rem; }.allocation-guide span { margin-top: var(--ui-space-1); color: var(--ui-text-secondary); font-size: .8125rem; line-height: 1.5; }
.allocation-result { display: grid; gap: var(--ui-space-3); }.allocation-result h3 { font-size: 1.25rem; }.allocation-result dl { display: grid; gap: var(--ui-space-3); margin: 0; }.allocation-result dl > div { display: grid; gap: var(--ui-space-1); }.allocation-result dt { color: var(--ui-text-secondary); font-size: .8125rem; }.allocation-result dd { margin: 0; color: var(--ui-text); }.allocation-result .inline-link { justify-self: start; }
@media (max-width: 920px) { .task-create-layout { grid-template-columns: 1fr; }.allocation-guide { grid-template-columns: repeat(2, minmax(0, 1fr)); }.allocation-guide .eyebrow, .allocation-guide h3 { grid-column: 1 / -1; } }
@media (max-width: 680px) { .task-form__heading { display: block; }.required-note { display: block; margin-top: var(--ui-space-2); }.task-form__grid, .allocation-guide { grid-template-columns: 1fr; }.task-form__full-width { grid-column: auto; }.task-form__panel, .allocation-guide, .allocation-result { padding: var(--ui-space-4); }.task-form__actions .ui-button { width: 100%; } }
</style>
