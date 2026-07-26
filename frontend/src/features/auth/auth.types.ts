export type Role = 'DEVELOPER' | 'OPERATOR' | 'LEADER'

export interface CurrentUser {
  id: string
  username: string
  displayName: string
  roles: readonly Role[]
  mustChangePassword: boolean
}

export interface LoginCommand {
  username: string
  password: string
}

export interface ChangePasswordCommand {
  currentPassword: string
  newPassword: string
}
