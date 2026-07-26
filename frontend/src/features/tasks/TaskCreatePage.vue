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
  category: 'VERSION_RELEASE' as TaskCategory,
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

function validate(): string {
  if (!form.systemName.trim()) {
    return '请输入系统名称'
  }
  if (!Number.isFinite(Number(form.estimatedMinutes))
      || Number(form.estimatedMinutes) <= 0) {
    return '预计耗时必须大于 0'
  }
  if (!form.processNumber.trim()) {
    return '请输入操作流程编号'
  }
  if (!form.operationStart || !form.operationEnd) {
    return '请选择完整的操作时间范围'
  }
  if (new Date(toShanghaiInstant(form.operationEnd)).getTime()
      <= new Date(toShanghaiInstant(form.operationStart)).getTime()) {
    return '操作结束时间必须晚于开始时间'
  }
  return ''
}

function scheduleSuggestions(): void {
  const request = ++suggestionRequest
  if (suggestionTimer) {
    clearTimeout(suggestionTimer)
  }
  if (form.systemName.trim().length < 2) {
    suggestions.value = []
    return
  }
  suggestionTimer = setTimeout(async () => {
    const query = form.systemName
    try {
      const result = await suggestSystemNames(query)
      if (request === suggestionRequest) {
        suggestions.value = result
      }
    } catch {
      if (request === suggestionRequest) {
        suggestions.value = []
      }
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
  if (errorMessage.value || submitting.value) {
    return
  }
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
    <form class="content-panel task-form" @submit.prevent="submit">
      <div class="panel-heading">
        <div>
          <p class="eyebrow">TAKE A NUMBER</p>
          <h2>提交操作任务</h2>
          <p>填写完整操作信息后，系统将按操作开始日期和值班规则自动分派。</p>
        </div>
        <span class="required-note">* 为必填项</span>
      </div>

      <div class="form-grid">
        <label class="field">
          <span>任务类别 *</span>
          <select v-model="form.category" data-testid="category">
            <option
              v-for="(label, value) in categoryLabels"
              :key="value"
              :value="value"
            >
              {{ label }}
            </option>
          </select>
        </label>

        <label class="field">
          <span>系统名称 *</span>
          <input
            v-model="form.systemName"
            data-testid="system-name"
            list="system-suggestions"
            maxlength="128"
            placeholder="例如：核心交易系统"
            @input="scheduleSuggestions"
          />
          <datalist id="system-suggestions">
            <option v-for="name in suggestions" :key="name" :value="name" />
          </datalist>
        </label>

        <label class="field">
          <span>预计耗时（分钟）*</span>
          <input
            v-model.number="form.estimatedMinutes"
            data-testid="estimated-minutes"
            type="number"
            min="1"
            step="1"
          />
        </label>

        <label class="field">
          <span>{{ processLabel }} *</span>
          <input
            v-model="form.processNumber"
            data-testid="process-number"
            maxlength="128"
            placeholder="请输入对应流程编号"
          />
        </label>

        <label class="field">
          <span>操作开始时间 *</span>
          <input
            v-model="form.operationStart"
            data-testid="operation-start"
            type="datetime-local"
          />
        </label>

        <label class="field">
          <span>操作结束时间 *</span>
          <input
            v-model="form.operationEnd"
            data-testid="operation-end"
            type="datetime-local"
          />
        </label>
      </div>

      <p v-if="errorMessage" class="form-error" role="alert">
        {{ errorMessage }}
      </p>
      <div class="form-actions">
        <button
          data-testid="submit-task"
          class="primary-button compact-button"
          type="submit"
          :disabled="submitting"
          @click.prevent="submit"
        >
          {{ submitting ? '正在分派…' : '取号并自动分派' }}
        </button>
      </div>
    </form>

    <aside class="content-panel allocation-guide">
      <p class="eyebrow">ASSIGNMENT GUIDE</p>
      <h3>系统如何分派</h3>
      <ol>
        <li><strong>按操作日期</strong><span>匹配当天二线和三线值班人员</span></li>
        <li><strong>白天优先二线</strong><span>不可参与时转三线，再进入公平分配</span></li>
        <li><strong>晚间均衡</strong><span>二线、三线各 3 个后按负载公平分配</span></li>
        <li><strong>全程留痕</strong><span>分派规则和后续转交均记录在任务时间线</span></li>
      </ol>
    </aside>

    <section v-if="result" class="content-panel allocation-result">
      <p class="result-kicker">取号成功</p>
      <h3>{{ result.ticketNumber }}</h3>
      <dl>
        <div><dt>分派管理员</dt><dd>{{ result.assigneeId }}</dd></div>
        <div>
          <dt>分派规则</dt>
          <dd>{{ assignmentRuleLabel(result.assignmentRule) }}</dd>
        </div>
      </dl>
      <RouterLink :to="`/tasks/${result.id}`" class="inline-link">
        查看任务详情
      </RouterLink>
    </section>
  </section>
</template>
