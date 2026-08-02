export type AppIconName =
  | 'workspace' | 'create' | 'tasks' | 'roster'
  | 'people' | 'reports' | 'audit' | 'more' | 'close'
  | 'filter' | 'search' | 'refresh' | 'key'

export const iconPaths: Record<AppIconName, readonly string[]> = {
  workspace: ['M4 13h6V4H4z', 'M14 20h6v-9h-6z', 'M4 20h6v-3H4z', 'M14 7h6V4h-6z'],
  create: ['M12 5v14', 'M5 12h14'],
  tasks: ['M7 5h13', 'M7 12h13', 'M7 19h13', 'M3.5 5h.01', 'M3.5 12h.01', 'M3.5 19h.01'],
  roster: ['M5 3v3', 'M19 3v3', 'M4 9h16', 'M5 5h14a1 1 0 0 1 1 1v14H4V6a1 1 0 0 1 1-1z'],
  people: ['M16 20v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2', 'M9 10a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M16 4a4 4 0 0 1 0 8', 'M18 14a4 4 0 0 1 4 4v2'],
  reports: ['M4 20V10', 'M10 20V4', 'M16 20v-7', 'M20 20H2'],
  audit: ['M6 3h12v18H6z', 'M9 8h6', 'M9 12h6', 'M9 16h4'],
  more: ['M5 12h.01', 'M12 12h.01', 'M19 12h.01'],
  close: ['M6 6l12 12', 'M18 6L6 18'],
  filter: ['M4 5h16l-6.5 7.5v5l-3 1.5v-6.5z'],
  search: ['M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z', 'M16.5 16.5L21 21'],
  refresh: ['M20 11a8 8 0 0 0-14.9-4L3 10', 'M3 5v5h5', 'M4 13a8 8 0 0 0 14.9 4L21 14', 'M21 19v-5h-5'],
  key: ['M14.5 9.5a4.5 4.5 0 1 0-8.9 1.2A4.5 4.5 0 0 0 10 15.5h2v2h2v-2h2v-2h-5.5']
}
