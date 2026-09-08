import { http } from './http'
import type { ApiResult } from './auth'

export type WorkspaceRole = 'OWNER' | 'MEMBER'
export type WorkspaceStatus = 'ACTIVE' | 'ARCHIVED_BY_OWNER' | 'ARCHIVED_BY_SUPER_ADMIN'

export interface WorkspaceSummaryResponseVO {
  workspaceId: number
  name: string
  role: WorkspaceRole
  status: WorkspaceStatus
  memberCount: number
  createdAt: string
}

export interface WorkspaceDetailResponseVO extends WorkspaceSummaryResponseVO {
  createdByUserId: number
  ownerUserId: number
  archivedAt: string | null
}

export type InvitationStatus = 'ACTIVE' | 'REVOKED' | 'EXPIRED' | 'EXHAUSTED'

export interface CurrentInvitationResponseVO {
  invitationId: number
  inviteCode: string
  status: InvitationStatus
  usedCount: number
  remainingUses: number
  expiresAt: string
  createdAt: string
}

export interface InvitationHistoryResponseVO {
  invitationId: number
  status: InvitationStatus
  usedCount: number
  remainingUses: number
  expiresAt: string
  createdAt: string
}

export interface JoinWorkspaceResponseVO {
  workspace: WorkspaceSummaryResponseVO
  newlyJoined: boolean
}

export interface WorkspaceMemberResponseVO {
  userId: number
  username: string
  nickname: string
  role: WorkspaceRole
  status: 'ACTIVE'
  firstJoinedAt: string
  lastJoinedAt: string
}

export function listWorkspaces() {
  return http.get<ApiResult<WorkspaceSummaryResponseVO[]>>('/workspaces')
}

export function createWorkspace(name: string) {
  return http.post<ApiResult<WorkspaceDetailResponseVO>>('/workspaces', { name })
}

export function getWorkspace(workspaceId: number) {
  return http.get<ApiResult<WorkspaceDetailResponseVO>>(`/workspaces/${workspaceId}`)
}

export function archiveWorkspace(workspaceId: number) {
  return http.post<ApiResult<WorkspaceDetailResponseVO>>(`/workspaces/${workspaceId}/archive`)
}

export function restoreWorkspace(workspaceId: number) {
  return http.post<ApiResult<WorkspaceDetailResponseVO>>(`/workspaces/${workspaceId}/restore`)
}

export function joinWorkspace(inviteCode: string) {
  return http.post<ApiResult<JoinWorkspaceResponseVO>>('/workspaces/join', { inviteCode })
}

export function getCurrentInvitation(workspaceId: number) {
  return http.get<ApiResult<CurrentInvitationResponseVO | null>>(
    `/workspaces/${workspaceId}/invitations/current`,
  )
}

export function listInvitationHistory(workspaceId: number) {
  return http.get<ApiResult<InvitationHistoryResponseVO[]>>(
    `/workspaces/${workspaceId}/invitations`,
  )
}

export function createInvitation(workspaceId: number, currentInvitationId: number | null) {
  return http.post<ApiResult<CurrentInvitationResponseVO>>(
    `/workspaces/${workspaceId}/invitations`,
    { currentInvitationId },
  )
}

export function revokeInvitation(workspaceId: number, invitationId: number) {
  return http.delete<ApiResult<null>>(`/workspaces/${workspaceId}/invitations/${invitationId}`)
}

export function listWorkspaceMembers(workspaceId: number) {
  return http.get<ApiResult<WorkspaceMemberResponseVO[]>>(`/workspaces/${workspaceId}/members`)
}

export function leaveWorkspace(workspaceId: number) {
  return http.post<ApiResult<null>>(`/workspaces/${workspaceId}/leave`)
}

export function removeWorkspaceMember(workspaceId: number, userId: number) {
  return http.delete<ApiResult<null>>(`/workspaces/${workspaceId}/members/${userId}`)
}

export function transferWorkspaceOwnership(workspaceId: number, targetUserId: number) {
  return http.post<ApiResult<WorkspaceDetailResponseVO>>(
    `/workspaces/${workspaceId}/ownership-transfer`,
    { targetUserId },
  )
}
