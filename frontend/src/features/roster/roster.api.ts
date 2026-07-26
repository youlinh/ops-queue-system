import { http, unsafeRequest } from '@/app/http'
import type {
  ImportHistoryPage,
  RosterImportPreview,
} from './roster.types'

export async function previewRoster(
  file: File,
): Promise<RosterImportPreview> {
  const data = new FormData()
  data.append('file', file)
  const response = await unsafeRequest<RosterImportPreview>({
    method: 'post',
    url: '/rosters/imports/preview',
    data,
  })
  return response.data
}

export async function confirmRoster(batchId: string): Promise<void> {
  await unsafeRequest({
    method: 'post',
    url: `/rosters/imports/${batchId}/confirm`,
  })
}

export async function importHistory(
  page = 0,
  size = 20,
): Promise<ImportHistoryPage> {
  const response = await http.get<ImportHistoryPage>('/rosters/imports', {
    params: { page, size },
  })
  return response.data
}
