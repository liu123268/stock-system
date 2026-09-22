import { http } from './http'
import type { ApiResult } from './auth'

export type AccountStatus = 'ACTIVE' | 'DISABLED'
export type AccountType = 'REGULAR' | 'SUPER_ADMIN'
export type WorkspaceStatus = 'ACTIVE' | 'ARCHIVED_BY_OWNER' | 'ARCHIVED_BY_SUPER_ADMIN'

export interface PageResponseVO<T> {
  items: T[]
  page: number
  size: number
  total: number
  totalPages: number
}

export interface AdminUserSummaryResponseVO {
  userId: number
  username: string
  nickname: string
  status: AccountStatus
  accountType: AccountType
  workspaceCount: number
  ownedWorkspaceCount: number
  createdAt: string
}

export interface AdminMembershipResponseVO {
  workspaceId: number
  workspaceName: string
  workspaceStatus: WorkspaceStatus
  role: 'OWNER' | 'MEMBER'
  membershipStatus: 'ACTIVE' | 'LEFT' | 'REMOVED'
  firstJoinedAt: string
  lastJoinedAt: string
  lastInactiveAt: string | null
}

export interface AdminUserDetailResponseVO extends AdminUserSummaryResponseVO {
  memberships: AdminMembershipResponseVO[]
}

export interface AdminWorkspaceSummaryResponseVO {
  workspaceId: number
  name: string
  status: WorkspaceStatus
  createdByUserId: number
  createdByUsername: string
  ownerUserId: number
  ownerUsername: string
  memberCount: number
  archivedAt: string | null
  createdAt: string
}

export interface AdminWorkspaceMemberResponseVO {
  userId: number
  username: string
  nickname: string
  accountStatus: AccountStatus
  role: 'OWNER' | 'MEMBER'
  membershipStatus: 'ACTIVE' | 'LEFT' | 'REMOVED'
  firstJoinedAt: string
  lastJoinedAt: string
  lastInactiveAt: string | null
}

export interface AdminWorkspaceDetailResponseVO extends AdminWorkspaceSummaryResponseVO {
  members: AdminWorkspaceMemberResponseVO[]
}

export interface AdminOperationLogResponseVO {
  operationLogId: number
  action: 'DISABLE_USER' | 'RESTORE_USER' | 'ARCHIVE_WORKSPACE' | 'RESTORE_WORKSPACE'
  targetId: number
  targetLabel: string | null
  beforeStatus: string | null
  afterStatus: string | null
  result: 'SUCCESS' | 'FAILED'
  createdAt: string
}

export interface AdminListQuery {
  page?: number
  size?: number
  status?: string
  keyword?: string
}

export function getAdminUsers(params: AdminListQuery) {
  return http.get<ApiResult<PageResponseVO<AdminUserSummaryResponseVO>>>('/admin/users', { params })
}

export function getAdminUser(userId: number) {
  return http.get<ApiResult<AdminUserDetailResponseVO>>(`/admin/users/${userId}`)
}

export function disableAdminUser(userId: number) {
  return http.post<ApiResult<AdminUserDetailResponseVO>>(`/admin/users/${userId}/disable`)
}

export function restoreAdminUser(userId: number) {
  return http.post<ApiResult<AdminUserDetailResponseVO>>(`/admin/users/${userId}/restore`)
}

export function getAdminWorkspaces(params: AdminListQuery) {
  return http.get<ApiResult<PageResponseVO<AdminWorkspaceSummaryResponseVO>>>(
    '/admin/workspaces',
    { params },
  )
}

export function getAdminWorkspace(workspaceId: number) {
  return http.get<ApiResult<AdminWorkspaceDetailResponseVO>>(`/admin/workspaces/${workspaceId}`)
}

export function archiveAdminWorkspace(workspaceId: number) {
  return http.post<ApiResult<AdminWorkspaceDetailResponseVO>>(
    `/admin/workspaces/${workspaceId}/archive`,
  )
}

export function restoreAdminWorkspace(workspaceId: number) {
  return http.post<ApiResult<AdminWorkspaceDetailResponseVO>>(
    `/admin/workspaces/${workspaceId}/restore`,
  )
}

export function getAdminOperationLogs(params: {
  page?: number
  size?: number
  action?: string
  result?: string
}) {
  return http.get<ApiResult<PageResponseVO<AdminOperationLogResponseVO>>>(
    '/admin/operation-logs',
    { params },
  )
}
