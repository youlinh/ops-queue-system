import { unsafeRequest } from '@/app/http'

export interface ClaimedNotification {
  id: string
  eventType: string
  payload: Record<string, unknown>
  createdAt: string
}

/**
 * Claims the caller's pending outbox events. Claimed events are marked as
 * delivered server-side, so every event surfaces in exactly one poll.
 */
export async function claimNotifications(): Promise<ClaimedNotification[]> {
  const response = await unsafeRequest<ClaimedNotification[]>({
    method: 'post',
    url: '/notifications/claim',
  })
  return response.data
}
