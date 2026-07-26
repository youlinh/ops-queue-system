import type { Role } from '@/features/auth/auth.types'

export interface AccountView {
  id: string
  username: string
  displayName: string
  roles: Role[]
  enabled: boolean
  mustChangePassword: boolean
}

export interface RedistributionTask {
  taskId: string
  ticketNumber: string
  category: string
  systemName: string
  operationStart: string
  currentAssigneeId: string
}

export interface RedistributionItemResult {
  taskId: string
  ticketNumber: string
  success: boolean
  previousAssigneeId: string
  assigneeId: string
  needsManualAttention: boolean
  error: string | null
}

export interface RedistributionResult {
  sourceOperatorId: string
  date: string
  items: RedistributionItemResult[]
}
