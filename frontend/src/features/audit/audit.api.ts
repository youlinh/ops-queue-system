import { http } from '@/app/http'

export interface AuditLog {
  id: string
  actorId: string
  action: string
  objectType: string
  objectId: string
  before: Record<string, unknown>
  after: Record<string, unknown>
  sourceIp: string
  occurredAt: string
}

export interface AuditPage {
  content: AuditLog[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface AuditSearch {
  actorId?: string
  action?: string
  objectType?: string
  objectId?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

export async function searchAuditLogs(
  search: AuditSearch,
): Promise<AuditPage> {
  const response = await http.get<AuditPage>('/audit-logs', { params: search })
  return response.data
}
