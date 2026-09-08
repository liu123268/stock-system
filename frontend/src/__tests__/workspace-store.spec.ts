import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosHeaders, type AxiosResponse } from 'axios'
import {
  archiveWorkspace,
  createInvitation,
  createWorkspace,
  getCurrentInvitation,
  getWorkspace,
  joinWorkspace,
  leaveWorkspace,
  listInvitationHistory,
  listWorkspaceMembers,
  listWorkspaces,
  removeWorkspaceMember,
  restoreWorkspace,
  transferWorkspaceOwnership,
} from '@/api/workspace'
import type { ApiResult } from '@/api/auth'
import type {
  CurrentInvitationResponseVO,
  InvitationHistoryResponseVO,
  JoinWorkspaceResponseVO,
  WorkspaceDetailResponseVO,
  WorkspaceMemberResponseVO,
  WorkspaceSummaryResponseVO,
} from '@/api/workspace'
import { useWorkspaceStore } from '@/stores/workspace'

vi.mock('@/api/workspace')

function response<T>(data: T): AxiosResponse<T> {
  return {
    data,
    status: 200,
    statusText: 'OK',
    headers: {},
    config: { headers: new AxiosHeaders() },
  }
}

const summary: WorkspaceSummaryResponseVO = {
  workspaceId: 12,
  name: '家庭研究组',
  role: 'OWNER',
  status: 'ACTIVE',
  memberCount: 1,
  createdAt: '2026-09-03T16:00:00',
}

const detail: WorkspaceDetailResponseVO = {
  ...summary,
  createdByUserId: 9,
  ownerUserId: 9,
  archivedAt: null,
}

const currentInvitation: CurrentInvitationResponseVO = {
  invitationId: 31,
  inviteCode: 'A1B2C3',
  status: 'ACTIVE',
  usedCount: 1,
  remainingUses: 4,
  expiresAt: '2026-09-10T16:00:00',
  createdAt: '2026-09-03T16:00:00',
}

const invitationHistory: InvitationHistoryResponseVO[] = [
  currentInvitation,
  {
    invitationId: 30,
    status: 'REVOKED',
    usedCount: 2,
    remainingUses: 3,
    expiresAt: '2026-09-09T16:00:00',
    createdAt: '2026-09-02T16:00:00',
  },
]

const members: WorkspaceMemberResponseVO[] = [
  {
    userId: 9,
    username: 'owner_user',
    nickname: '所有者',
    role: 'OWNER',
    status: 'ACTIVE',
    firstJoinedAt: '2026-09-03T16:00:00',
    lastJoinedAt: '2026-09-03T16:00:00',
  },
  {
    userId: 10,
    username: 'member_user',
    nickname: '普通成员',
    role: 'MEMBER',
    status: 'ACTIVE',
    firstJoinedAt: '2026-09-04T10:00:00',
    lastJoinedAt: '2026-09-04T10:00:00',
  },
]

describe('workspace store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads the workspace list and current workspace from real API shapes', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue(
      response<ApiResult<WorkspaceSummaryResponseVO[]>>({
        code: 'SUCCESS',
        message: '成功',
        data: [summary],
      }),
    )
    vi.mocked(getWorkspace).mockResolvedValue(
      response<ApiResult<WorkspaceDetailResponseVO>>({
        code: 'SUCCESS',
        message: '成功',
        data: detail,
      }),
    )

    const store = useWorkspaceStore()
    await store.loadWorkspaces()
    await store.loadCurrent(12)

    expect(store.workspaces).toEqual([summary])
    expect(store.currentWorkspace?.name).toBe('家庭研究组')
  })

  it('creates a workspace and refreshes the selectable list', async () => {
    vi.mocked(createWorkspace).mockResolvedValue(
      response<ApiResult<WorkspaceDetailResponseVO>>({
        code: 'SUCCESS',
        message: '成功',
        data: detail,
      }),
    )
    vi.mocked(listWorkspaces).mockResolvedValue(
      response<ApiResult<WorkspaceSummaryResponseVO[]>>({
        code: 'SUCCESS',
        message: '成功',
        data: [summary],
      }),
    )

    const store = useWorkspaceStore()
    const created = await store.create('家庭研究组')

    expect(createWorkspace).toHaveBeenCalledWith('家庭研究组')
    expect(created.workspaceId).toBe(12)
    expect(store.workspaces).toHaveLength(1)
  })

  it('joins with an invitation and refreshes the selectable list', async () => {
    vi.mocked(joinWorkspace).mockResolvedValue(
      response<ApiResult<JoinWorkspaceResponseVO>>({
        code: 'SUCCESS',
        message: '成功',
        data: { workspace: summary, newlyJoined: true },
      }),
    )
    vi.mocked(listWorkspaces).mockResolvedValue(
      response<ApiResult<WorkspaceSummaryResponseVO[]>>({
        code: 'SUCCESS',
        message: '成功',
        data: [summary],
      }),
    )

    const store = useWorkspaceStore()
    const joined = await store.join('A1B2C3')

    expect(joinWorkspace).toHaveBeenCalledWith('A1B2C3')
    expect(joined.newlyJoined).toBe(true)
    expect(store.workspaces).toEqual([summary])
  })

  it('loads and regenerates the owner invitation using the current invitation id', async () => {
    vi.mocked(getCurrentInvitation)
      .mockResolvedValueOnce(
        response<ApiResult<CurrentInvitationResponseVO | null>>({
          code: 'SUCCESS',
          message: '成功',
          data: currentInvitation,
        }),
      )
      .mockResolvedValueOnce(
        response<ApiResult<CurrentInvitationResponseVO | null>>({
          code: 'SUCCESS',
          message: '成功',
          data: { ...currentInvitation, invitationId: 32, inviteCode: 'D4E5F6' },
        }),
      )
    vi.mocked(listInvitationHistory).mockResolvedValue(
      response<ApiResult<InvitationHistoryResponseVO[]>>({
        code: 'SUCCESS',
        message: '成功',
        data: invitationHistory,
      }),
    )
    vi.mocked(createInvitation).mockResolvedValue(
      response<ApiResult<CurrentInvitationResponseVO>>({
        code: 'SUCCESS',
        message: '成功',
        data: { ...currentInvitation, invitationId: 32, inviteCode: 'D4E5F6' },
      }),
    )

    const store = useWorkspaceStore()
    await store.loadInvitations(12)
    await store.generateInvitation(12)

    expect(createInvitation).toHaveBeenCalledWith(12, 31)
    expect(store.currentInvitation?.inviteCode).toBe('D4E5F6')
    expect(store.invitationHistory).toHaveLength(2)
  })

  it('loads members and refreshes workspace state after removing a member', async () => {
    vi.mocked(listWorkspaceMembers)
      .mockResolvedValueOnce(
        response<ApiResult<WorkspaceMemberResponseVO[]>>({
          code: 'SUCCESS',
          message: '成功',
          data: members,
        }),
      )
      .mockResolvedValueOnce(
        response<ApiResult<WorkspaceMemberResponseVO[]>>({
          code: 'SUCCESS',
          message: '成功',
          data: [members[0]!],
        }),
      )
    vi.mocked(removeWorkspaceMember).mockResolvedValue(
      response<ApiResult<null>>({ code: 'SUCCESS', message: '成功', data: null }),
    )
    vi.mocked(getWorkspace).mockResolvedValue(
      response<ApiResult<WorkspaceDetailResponseVO>>({
        code: 'SUCCESS',
        message: '成功',
        data: { ...detail, memberCount: 1 },
      }),
    )

    const store = useWorkspaceStore()
    await store.loadMembers(12)
    await store.removeMember(12, 10)

    expect(removeWorkspaceMember).toHaveBeenCalledWith(12, 10)
    expect(store.members).toEqual([members[0]])
    expect(store.currentWorkspace?.memberCount).toBe(1)
  })

  it('uses backend transfer results and clears current state after leaving', async () => {
    const transferred: WorkspaceDetailResponseVO = {
      ...detail,
      role: 'MEMBER',
      ownerUserId: 10,
    }
    vi.mocked(transferWorkspaceOwnership).mockResolvedValue(
      response<ApiResult<WorkspaceDetailResponseVO>>({
        code: 'SUCCESS',
        message: '成功',
        data: transferred,
      }),
    )
    vi.mocked(listWorkspaceMembers).mockResolvedValue(
      response<ApiResult<WorkspaceMemberResponseVO[]>>({
        code: 'SUCCESS',
        message: '成功',
        data: [
          { ...members[1]!, role: 'OWNER' },
          { ...members[0]!, role: 'MEMBER' },
        ],
      }),
    )
    vi.mocked(listWorkspaces).mockResolvedValue(
      response<ApiResult<WorkspaceSummaryResponseVO[]>>({
        code: 'SUCCESS',
        message: '成功',
        data: [{ ...summary, role: 'MEMBER' }],
      }),
    )
    vi.mocked(leaveWorkspace).mockResolvedValue(
      response<ApiResult<null>>({ code: 'SUCCESS', message: '成功', data: null }),
    )

    const store = useWorkspaceStore()
    await store.transferOwnership(12, 10)
    expect(store.currentWorkspace?.role).toBe('MEMBER')

    await store.leave(12)
    expect(leaveWorkspace).toHaveBeenCalledWith(12)
    expect(store.currentWorkspace).toBeNull()
    expect(store.members).toEqual([])
  })

  it('uses backend archive and restore results even when list refresh fails', async () => {
    const archived: WorkspaceDetailResponseVO = {
      ...detail,
      status: 'ARCHIVED_BY_OWNER',
      archivedAt: '2026-09-04T18:00:00',
    }
    vi.mocked(archiveWorkspace).mockResolvedValue(
      response<ApiResult<WorkspaceDetailResponseVO>>({
        code: 'SUCCESS',
        message: '成功',
        data: archived,
      }),
    )
    vi.mocked(restoreWorkspace).mockResolvedValue(
      response<ApiResult<WorkspaceDetailResponseVO>>({
        code: 'SUCCESS',
        message: '成功',
        data: detail,
      }),
    )
    vi.mocked(listWorkspaces).mockRejectedValue(new Error('列表刷新失败'))

    const store = useWorkspaceStore()
    store.currentInvitation = currentInvitation

    await expect(store.archive(12)).resolves.toEqual(archived)
    expect(store.currentWorkspace?.status).toBe('ARCHIVED_BY_OWNER')
    expect(store.currentInvitation).toBeNull()

    await expect(store.restore(12)).resolves.toEqual(detail)
    expect(store.currentWorkspace?.status).toBe('ACTIVE')
    expect(store.currentWorkspace?.archivedAt).toBeNull()
  })

  it('clears every workspace cache at the account logout boundary', () => {
    const store = useWorkspaceStore()
    store.workspaces = [summary]
    store.currentWorkspace = detail
    store.currentInvitation = currentInvitation
    store.invitationHistory = invitationHistory
    store.members = members

    store.clearAll()

    expect(store.workspaces).toEqual([])
    expect(store.currentWorkspace).toBeNull()
    expect(store.currentInvitation).toBeNull()
    expect(store.invitationHistory).toEqual([])
    expect(store.members).toEqual([])
  })
})
