import type { Role } from '@/features/auth/auth.types'

export type TaskCategory = 'VERSION_RELEASE' | 'DATA_MAINTENANCE'
export type TaskStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED'

export interface CreatedTask {
  id: string
  ticketNumber: string
  assigneeId: string
  assignmentRule: string
}

export interface CreateTaskCommand {
  category: TaskCategory
  systemName: string
  estimatedMinutes: number
  processNumber: string
  operationStart: string
  operationEnd: string
}

export interface TaskRow {
  id: string
  ticketNumber: string
  category: TaskCategory
  systemName: string
  processNumber: string
  operationStart: string
  operationEnd: string
  creatorId: string
  creatorName: string
  currentAssigneeId: string
  currentAssigneeName: string
  status: TaskStatus
  estimatedMinutes: number
  actualMinutes: number | null
  assignmentRule: string
  canCall: boolean
  canComplete: boolean
  canTransfer: boolean
  needsManualAttention: boolean
  createdAt: string
}

export interface AssignmentTimelineEntry {
  assignmentType: string
  oldAssigneeId: string | null
  oldAssigneeName: string | null
  newAssigneeId: string
  newAssigneeName: string
  assignmentRule: string
  reason: string | null
  actorId: string
  actorName: string
  assignedAt: string
}

export interface TaskDetail extends TaskRow {
  calledAt: string | null
  calledByUserId: string | null
  completedAt: string | null
  completedByUserId: string | null
  version: number
  assignmentTimeline: AssignmentTimelineEntry[]
}

export interface TaskPage {
  content: TaskRow[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface TaskSearch {
  operationDate?: string
  category?: TaskCategory | ''
  systemName?: string
  status?: TaskStatus | ''
  creatorId?: string
  assigneeId?: string
  page?: number
  size?: number
  sort?: string
}

export interface OperatorOption {
  id: string
  displayName: string
  available: boolean
}

export interface TaskActionContext {
  currentUserId: string
  roles: readonly Role[]
}

export const categoryLabels: Record<TaskCategory, string> = {
  VERSION_RELEASE: '版本发布',
  DATA_MAINTENANCE: '数据维护',
}

export const statusLabels: Record<TaskStatus, string> = {
  PENDING: '待执行',
  IN_PROGRESS: '执行中',
  COMPLETED: '已完成',
}

const ruleLabels: Record<string, string> = {
  DAY_SECOND: '白天二线优先',
  DAY_THIRD_FALLBACK: '白天转三线',
  EVENING_SECOND: '晚间优先二线',
  EVENING_THIRD: '晚间转三线',
  AFTER_21_SECOND: '21 点后当天二线优先',
  FAIR: '公平分配',
  MANUAL_TRANSFER: '管理员转交',
  LEADER_ADJUSTMENT: '组长调整',
}

export function assignmentRuleLabel(rule: string): string {
  return ruleLabels[rule] || rule
}
