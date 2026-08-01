<script setup lang="ts">
import { apiErrorMessage } from '@/app/http'
import AppDialog from '@/components/ui/AppDialog.vue'
import type { Role } from '@/features/auth/auth.types'
import { shanghaiDate } from '@/features/tasks/shanghai-time'
import { onMounted, reactive, ref } from 'vue'
import {
  createAccount,
  disableAccount,
  listAccounts,
  replaceAccountRoles,
  resetAccountPassword,
} from './people.api'
import type { AccountView } from './people.types'
import RedistributionDialog from './RedistributionDialog.vue'
import UnavailabilityDialog from './UnavailabilityDialog.vue'

const roleLabels: Record<Role, string> = {
  DEVELOPER: '开发人员',
  OPERATOR: '运维管理员',
  LEADER: '运维组长',
}
const allRoles: Role[] = ['DEVELOPER', 'OPERATOR', 'LEADER']
const accounts = ref<AccountView[]>([])
const loading = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const form = reactive({
  username: '',
  displayName: '',
  initialPassword: '',
  roles: ['DEVELOPER'] as Role[],
})
const selected = ref<AccountView | null>(null)
const dialog = ref<'roles' | 'password' | 'unavailable' | 'redistribute' | null>(
  null,
)
const roleSelection = ref<Role[]>([])
const resetPassword = ref('')
const redistribution = reactive({ date: '', reason: '' })

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    accounts.value = await listAccounts()
  } catch (error: unknown) {
    errorMessage.value = apiErrorMessage(error, '账号列表加载失败')
  } finally {
    loading.value = false
  }
}

async function create(): Promise<void> {
  const errors: string[] = []
  if (!form.username.trim()) errors.push('请输入登录账号')
  if (!form.displayName.trim()) errors.push('请输入姓名')
  if (form.initialPassword.length < 12) errors.push('初始密码至少 12 位')
  if (!form.roles.length) errors.push('至少选择一个角色')
  errorMessage.value = errors.join('；')
  if (errors.length) return
  try {
    await createAccount({
      username: form.username.trim(),
      displayName: form.displayName.trim(),
      initialPassword: form.initialPassword,
      roles: form.roles,
    })
    form.username = ''
    form.displayName = ''
    form.initialPassword = ''
    form.roles = ['DEVELOPER']
    successMessage.value = '账号已创建，初始密码不会在页面保留'
    await load()
  } catch (error: unknown) {
    errorMessage.value = apiErrorMessage(error, '创建账号失败')
  }
}

function openRoles(account: AccountView): void {
  selected.value = account
  roleSelection.value = [...account.roles]
  dialog.value = 'roles'
}

function openPassword(account: AccountView): void {
  selected.value = account
  resetPassword.value = ''
  dialog.value = 'password'
}

function openUnavailable(account: AccountView): void {
  selected.value = account
  dialog.value = 'unavailable'
}

async function saveRoles(): Promise<void> {
  if (!selected.value || !roleSelection.value.length) {
    errorMessage.value = '至少选择一个角色'
    return
  }
  try {
    await replaceAccountRoles(selected.value.id, roleSelection.value)
    closeDialog()
    await load()
  } catch (error: unknown) {
    errorMessage.value = apiErrorMessage(error, '更新角色失败')
  }
}

async function savePassword(): Promise<void> {
  if (!selected.value || resetPassword.value.length < 12) {
    errorMessage.value = '初始密码至少 12 位'
    return
  }
  try {
    await resetAccountPassword(selected.value.id, resetPassword.value)
    resetPassword.value = ''
    successMessage.value = '密码已重置，用户下次登录必须修改'
    closeDialog()
    await load()
  } catch (error: unknown) {
    errorMessage.value = apiErrorMessage(error, '重置密码失败')
  }
}

async function disable(account: AccountView): Promise<void> {
  if (!window.confirm(`确认停用账号 ${account.username}？`)) return
  try {
    await disableAccount(account.id)
    await load()
  } catch (error: unknown) {
    errorMessage.value = apiErrorMessage(error, '停用账号失败')
  }
}

function unavailableSaved(date: string, reason: string): void {
  redistribution.date = date
  redistribution.reason = reason
  dialog.value = 'redistribute'
}

function closeDialog(): void {
  dialog.value = null
  selected.value = null
  resetPassword.value = ''
}

onMounted(load)
</script>

<template>
  <section class="people-layout ui-page-stack">
    <form class="content-panel ui-panel account-create" @submit.prevent="create">
      <div class="panel-heading">
        <div>
          <p class="eyebrow">LOCAL ACCOUNT</p>
          <h2>创建本地账号</h2>
          <p>组长可创建多角色账号；初始密码仅在此处输入，不会回显。</p>
        </div>
      </div>
      <div class="form-grid">
        <label class="field">
          <span>登录账号</span>
          <input v-model="form.username" maxlength="64" autocomplete="off" />
        </label>
        <label class="field">
          <span>姓名</span>
          <input v-model="form.displayName" maxlength="128" />
        </label>
        <label class="field">
          <span>初始密码</span>
          <input
            v-model="form.initialPassword"
            type="password"
            minlength="12"
            maxlength="72"
            autocomplete="new-password"
          />
        </label>
        <fieldset class="role-options">
          <legend>角色</legend>
          <label v-for="role in allRoles" :key="role">
            <input v-model="form.roles" type="checkbox" :value="role" />
            {{ roleLabels[role] }}
          </label>
        </fieldset>
      </div>
      <button class="ui-button ui-button--primary compact-button" type="submit">
        创建账号
      </button>
    </form>

    <section class="content-panel ui-panel account-list">
      <div class="panel-heading">
        <div>
          <p class="eyebrow">PEOPLE</p>
          <h2>人员与可用性</h2>
        </div>
        <span class="queue-total">共 {{ accounts.length }} 人</span>
      </div>
      <p v-if="errorMessage" class="form-error" role="alert">
        {{ errorMessage }}
      </p>
      <p v-if="successMessage" class="form-success" role="status">
        {{ successMessage }}
      </p>
      <p v-if="loading" class="muted">正在加载人员…</p>
      <div v-else class="people-table-wrap">
        <table class="task-table">
          <thead>
            <tr>
              <th>账号 / 姓名</th>
              <th>角色</th>
              <th>状态</th>
              <th>账号操作</th>
              <th>排班操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="account in accounts" :key="account.id">
              <td><strong>{{ account.displayName }}</strong><small>{{ account.username }}</small></td>
              <td>{{ account.roles.map((role) => roleLabels[role]).join(' / ') }}</td>
              <td>
                <span :class="account.enabled ? 'state-enabled' : 'state-disabled'">
                  {{ account.enabled ? '启用' : '已停用' }}
                </span>
                <small v-if="account.mustChangePassword">需修改初始密码</small>
              </td>
              <td class="table-actions">
                <button class="ui-button ui-button--quiet text-button" type="button" @click="openRoles(account)">
                  角色
                </button>
                <button class="ui-button ui-button--quiet text-button" type="button" @click="openPassword(account)">
                  重置密码
                </button>
                <button
                  v-if="account.enabled"
                  class="ui-button ui-button--quiet text-button text-button--danger"
                  type="button"
                  @click="disable(account)"
                >
                  停用
                </button>
              </td>
              <td>
                <button
                  v-if="account.enabled && account.roles.includes('OPERATOR')"
                  class="ui-button ui-button--quiet action-button"
                  type="button"
                  @click="openUnavailable(account)"
                >
                  今天不能参与 / 重新分配
                </button>
                <span v-else>—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>

  <AppDialog
    v-if="selected && (dialog === 'roles' || dialog === 'password')"
    :open="true"
    labelled-by="people-management-dialog-title"
    @close="closeDialog"
  >
    <template v-if="dialog === 'roles'">
        <p class="eyebrow">ROLES</p>
        <h3 id="people-management-dialog-title">调整 {{ selected.displayName }} 的角色</h3>
        <fieldset class="role-options role-options--dialog">
          <label v-for="role in allRoles" :key="role">
            <input v-model="roleSelection" type="checkbox" :value="role" />
            {{ roleLabels[role] }}
          </label>
        </fieldset>
      </template>
      <template v-else>
        <p class="eyebrow">RESET PASSWORD</p>
        <h3 id="people-management-dialog-title">重置 {{ selected.displayName }} 的密码</h3>
        <label class="field">
          <span>新初始密码（至少 12 位）</span>
          <input
            v-model="resetPassword"
            type="password"
            autocomplete="new-password"
          />
        </label>
      </template>
      <div class="dialog-actions">
        <button class="ui-button ui-button--quiet action-button" type="button" @click="closeDialog">
          取消
        </button>
        <button
          class="ui-button ui-button--primary"
          type="button"
          @click="dialog === 'roles' ? saveRoles() : savePassword()"
        >
          保存
        </button>
      </div>
  </AppDialog>

  <UnavailabilityDialog
    v-if="selected && dialog === 'unavailable'"
    :operator="selected"
    :initial-date="shanghaiDate()"
    @close="closeDialog"
    @saved="unavailableSaved"
  />
  <RedistributionDialog
    v-if="selected && dialog === 'redistribute'"
    :operator="selected"
    :date="redistribution.date"
    :reason="redistribution.reason"
    @close="closeDialog"
    @completed="load"
  />
</template>
