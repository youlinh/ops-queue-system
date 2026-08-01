<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import { computed, onMounted, ref } from 'vue'
import {
  confirmRoster,
  importHistory,
  previewRoster,
} from './roster.api'
import type {
  ImportBatch,
  RosterImportPreview,
} from './roster.types'
import RosterPreviewTable from './RosterPreviewTable.vue'

const preview = ref<RosterImportPreview | null>(null)
const history = ref<ImportBatch[]>([])
const selectedFilename = ref('')
const loading = ref(false)
const confirming = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const canConfirm = computed(() =>
  preview.value?.valid === true && !confirming.value,
)
const importStage = computed<'upload' | 'preview' | 'confirmed'>(() => {
  if (successMessage.value) return 'confirmed'
  if (preview.value) return 'preview'
  return 'upload'
})

async function loadHistory(): Promise<void> {
  try {
    history.value = (await importHistory()).content
  } catch (error: unknown) {
    errorMessage.value = apiErrorMessage(error, '导入历史加载失败')
  }
}

async function selectFile(event: Event): Promise<void> {
  const files = (event.target as HTMLInputElement).files
  const file = files?.[0]
  if (!file) return
  selectedFilename.value = file.name
  preview.value = null
  errorMessage.value = ''
  successMessage.value = ''
  loading.value = true
  try {
    preview.value = await previewRoster(file)
  } catch (error: unknown) {
    const responsePreview = (
      error as { response?: { data?: RosterImportPreview } }
    )?.response?.data
    if (responsePreview?.batchId) {
      preview.value = responsePreview
    } else {
      errorMessage.value = apiErrorMessage(error, '值班表预览失败')
    }
  } finally {
    loading.value = false
  }
}

async function confirm(): Promise<void> {
  if (!preview.value?.valid || confirming.value) return
  confirming.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await confirmRoster(preview.value.batchId)
    successMessage.value = '值班表已确认并立即生效'
    await loadHistory()
  } catch (error: unknown) {
    const response = (
      error as { response?: { status?: number; data?: unknown } }
    )?.response
    errorMessage.value = response?.status === 409
      ? typeof response.data === 'string'
        ? response.data
        : '该导入批次已被确认，请刷新后查看'
      : apiErrorMessage(error, '值班表确认失败')
    await loadHistory()
  } finally {
    confirming.value = false
  }
}

function statusLabel(status: string): string {
  return {
    VALIDATED: '待确认',
    IMPORTED: '已导入',
    FAILED: '校验失败',
  }[status] || status
}

function formatDateTime(value: string | null): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

onMounted(loadHistory)
</script>

<template>
  <div class="management-layout roster-management">
    <section class="content-panel ui-panel roster-import-panel">
      <div class="panel-heading">
        <div>
          <p class="eyebrow">DUTY ROSTER IMPORT</p>
          <h2>值班表导入</h2>
          <p>先下载标准模板，上传后核对解析结果，再确认生效。</p>
        </div>
        <a class="ui-button ui-button--quiet template-link" href="/api/rosters/template">
          下载标准模板
        </a>
      </div>

      <ol class="ui-stage-indicator" aria-label="导入进度">
        <li :aria-current="importStage === 'upload' ? 'step' : undefined">
          上传文件
        </li>
        <li :aria-current="importStage === 'preview' ? 'step' : undefined">
          预览校验
        </li>
        <li :aria-current="importStage === 'confirmed' ? 'step' : undefined">
          确认生效
        </li>
      </ol>

      <label class="file-drop">
        <input
          data-testid="roster-file"
          type="file"
          accept=".xlsx"
          @change="selectFile"
        />
        <strong>{{ loading ? '正在解析…' : '选择 .xlsx 值班表' }}</strong>
        <span>{{ selectedFilename || '文件仅用于预览，确认后才会生效' }}</span>
      </label>

      <section v-if="preview" data-testid="roster-preview" class="roster-preview-region" aria-live="polite">
        <div v-if="preview.errors.length" class="import-errors" role="alert">
          <strong>发现 {{ preview.errors.length }} 项问题</strong>
          <ul>
            <li v-for="item in preview.errors" :key="`${item.rowNumber}-${item.message}`">
              Excel 第 {{ item.rowNumber }} 行：{{ item.message }}
            </li>
          </ul>
        </div>
        <RosterPreviewTable v-if="preview.rows.length" :rows="preview.rows" />
      </section>
      <p v-if="errorMessage" class="form-error" role="alert">
        {{ errorMessage }}
      </p>
      <p v-if="successMessage" class="success-message" role="status">
        {{ successMessage }}
      </p>
      <div class="form-actions">
        <button
          data-testid="confirm-roster"
          class="ui-button ui-button--primary"
          type="button"
          :disabled="!canConfirm"
          @click="confirm"
        >
          {{ confirming ? '正在确认…' : '确认导入并生效' }}
        </button>
      </div>
    </section>

    <section class="content-panel ui-panel import-history-panel">
      <p class="eyebrow">IMPORT HISTORY</p>
      <h3>最近导入记录</h3>
      <div v-if="history.length" class="history-list">
        <article v-for="batch in history" :key="batch.id">
          <div>
            <strong>{{ batch.originalFilename }}</strong>
            <span>{{ formatDateTime(batch.createdAt) }} · {{ batch.rowCount }} 行</span>
          </div>
          <span class="status-pill" :class="`import-${batch.status}`">
            {{ statusLabel(batch.status) }}
          </span>
        </article>
      </div>
      <p v-else class="muted">暂无导入记录</p>
    </section>
  </div>
</template>

<style scoped>
.ui-stage-indicator {
  display: flex;
  gap: var(--ui-space-3);
  margin: 0 0 var(--ui-space-5);
  padding: 0;
  color: var(--ui-text-tertiary);
  font-size: .875rem;
  font-weight: 600;
  list-style: none;
}

.ui-stage-indicator li {
  display: inline-flex;
  min-height: var(--ui-action-min-height);
  align-items: center;
  gap: var(--ui-space-2);
}

.ui-stage-indicator li:not(:last-child)::after {
  width: var(--ui-space-5);
  height: 1px;
  background: var(--ui-hairline);
  content: '';
}

.ui-stage-indicator li[aria-current='step'] {
  color: var(--ui-accent-link);
}

.roster-preview-region {
  display: grid;
  gap: var(--ui-space-3);
  margin-top: var(--ui-space-5);
}

@media (max-width: 680px) {
  .ui-stage-indicator {
    gap: var(--ui-space-2);
    font-size: .8125rem;
  }

  .ui-stage-indicator li:not(:last-child)::after {
    width: var(--ui-space-3);
  }
}
</style>