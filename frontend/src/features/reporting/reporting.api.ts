import { http } from '@/app/http'

export interface OperatorMetrics {
  operatorId: string
  date?: string
  month?: string
  totalTaskCount: number
  pendingCount: number
  inProgressCount: number
  completedCount: number
  estimatedMinutes: number
  completedActualMinutes: number
}

export async function dailyReport(
  date: string,
  operatorId: string,
): Promise<OperatorMetrics> {
  const response = await http.get<OperatorMetrics>('/reports/daily', {
    params: { date, operatorId },
  })
  return response.data
}

export async function monthlyReport(
  month: string,
  operatorId: string,
): Promise<OperatorMetrics> {
  const response = await http.get<OperatorMetrics>('/reports/monthly', {
    params: { month, operatorId },
  })
  return response.data
}
