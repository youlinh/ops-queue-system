export interface RosterImportError {
  rowNumber: number
  message: string
}

export interface RosterPreviewRow {
  sourceRowNumber: number
  dutyDate: string
  secondLineUserId: string
  secondLineDisplayName: string
  thirdLineUserId: string
  thirdLineDisplayName: string
}

export interface RosterImportPreview {
  batchId: string
  valid: boolean
  errors: RosterImportError[]
  rows: RosterPreviewRow[]
}

export type ImportStatus = 'VALIDATED' | 'IMPORTED' | 'FAILED'

export interface ImportBatch {
  id: string
  status: ImportStatus
  originalFilename: string
  fileSha256: string
  rowCount: number
  uploadedByUserId: string
  createdAt: string
  importedByUserId: string | null
  importedAt: string | null
  errorCount: number
}

export interface ImportHistoryPage {
  content: ImportBatch[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
