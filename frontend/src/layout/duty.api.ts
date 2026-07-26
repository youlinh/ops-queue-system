import { http } from '@/app/http'

export interface DutyUser {
  id: string
  displayName: string
}

export interface DutySummary {
  dutyDate: string
  configured: boolean
  secondLine: DutyUser | null
  thirdLine: DutyUser | null
}

export async function todayDuty(): Promise<DutySummary> {
  const response = await http.get<DutySummary>('/duty/today')
  return response.data
}
