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
  renameWorkspace,
  restoreWorkspace,
  revokeInvitation,
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

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
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
  it('keeps the newest list and ignores a delayed list after logout', async () => {
    const older = deferred<AxiosResponse<ApiResult<WorkspaceSummaryResponseVO[]>>>()
    vi.mocked(listWorkspaces)
      .mockReturnValueOnce(older.promise)
      .mockResolvedValueOnce(
        response({ code: 'SUCCESS', message: '成功', data: [{ ...summary, workspaceId: 13 }] }),
      )
    const store = useWorkspaceStore()
    const oldLoad = store.loadWorkspaces()
    await store.loadWorkspaces()
    older.resolve(response({ code: 'SUCCESS', message: '成功', data: [summary] }))
    await oldLoad
    expect(store.workspaces[0]?.workspaceId).toBe(13)

    const pending = deferred<AxiosResponse<ApiResult<WorkspaceSummaryResponseVO[]>>>()
    vi.mocked(listWorkspaces).mockReturnValueOnce(pending.promise)
    const loading = store.loadWorkspaces()
    store.clearAll()
    pending.resolve(response({ code: 'SUCCESS', message: '成功', data: [summary] }))
    await loading
    expect(store.workspaces).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('does not refill invitations or members after switching away', async () => {
    const invitation = deferred<AxiosResponse<ApiResult<CurrentInvitationResponseVO | null>>>()
    const member = deferred<AxiosResponse<ApiResult<WorkspaceMemberResponseVO[]>>>()
    vi.mocked(getCurrentInvitation).mockReturnValueOnce(invitation.promise)
    vi.mocked(listInvitationHistory).mockResolvedValueOnce(
      response({ code: 'SUCCESS', message: '成功', data: invitationHistory }),
    )
    vi.mocked(listWorkspaceMembers).mockReturnValueOnce(member.promise)
    const store = useWorkspaceStore()
    const requests = [store.loadInvitations(12), store.loadMembers(12)]
    store.clearCurrent()
    invitation.resolve(response({ code: 'SUCCESS', message: '成功', data: currentInvitation }))
    member.resolve(response({ code: 'SUCCESS', message: '成功', data: members }))
    await Promise.all(requests)
    expect(store.currentInvitation).toBeNull()
    expect(store.invitationHistory).toEqual([])
    expect(store.members).toEqual([])
    expect(store.invitationLoading).toBe(false)
    expect(store.membersLoading).toBe(false)
  })

  it('preserves successful creation and joining when the list refresh fails', async () => {
    vi.mocked(createWorkspace).mockResolvedValueOnce(
      response({ code: 'SUCCESS', message: '成功', data: detail }),
    )
    vi.mocked(joinWorkspace).mockResolvedValueOnce(
      response({
        code: 'SUCCESS',
        message: '成功',
        data: { workspace: summary, newlyJoined: true },
      }),
    )
    vi.mocked(listWorkspaces).mockRejectedValue(new Error('列表暂时不可用'))
    const store = useWorkspaceStore()
    await expect(store.create('家庭研究组')).resolves.toEqual(detail)
    expect(store.currentWorkspace).toEqual(detail)
    await expect(store.join('A1B2C3')).resolves.toEqual({ workspace: summary, newlyJoined: true })
    expect(createWorkspace).toHaveBeenCalledTimes(1)
    expect(joinWorkspace).toHaveBeenCalledTimes(1)
    expect(store.errorMessage).toBe('列表暂时不可用')
  })

  it('does not restore a previous account from a delayed successful creation', async () => {
    const pending = deferred<AxiosResponse<ApiResult<WorkspaceDetailResponseVO>>>()
    vi.mocked(createWorkspace).mockReturnValueOnce(pending.promise)
    const store = useWorkspaceStore()
    const creating = store.create('家庭研究组')
    store.clearAll()
    pending.resolve(response({ code: 'SUCCESS', message: '成功', data: detail }))
    await creating
    expect(store.currentWorkspace).toBeNull()
    expect(store.workspaces).toEqual([])
    expect(listWorkspaces).not.toHaveBeenCalled()
  })

  it.each(['create', 'join'] as const)(
    'keeps the newly selected space when a delayed %s finishes after leaving the picker',
    async (action) => {
      const write = deferred<AxiosResponse<ApiResult<WorkspaceDetailResponseVO>>>()
      const joining = deferred<AxiosResponse<ApiResult<JoinWorkspaceResponseVO>>>()
      vi.mocked(createWorkspace).mockReturnValueOnce(write.promise)
      vi.mocked(joinWorkspace).mockReturnValueOnce(joining.promise)
      const next = { ...detail, workspaceId: 13, name: '后来进入的空间' }
      vi.mocked(getWorkspace).mockResolvedValueOnce(
        response({ code: 'SUCCESS', message: '成功', data: next }),
      )
      const store = useWorkspaceStore()
      const pending = action === 'create' ? store.create('家庭研究组') : store.join('A1B2C3')
      store.invalidateEntryRequests()
      await store.loadCurrent(13)
      write.resolve(response({ code: 'SUCCESS', message: '成功', data: detail }))
      joining.resolve(
        response({
          code: 'SUCCESS',
          message: '成功',
          data: { workspace: summary, newlyJoined: true },
        }),
      )
      await pending
      expect(store.currentWorkspace).toEqual(next)
      expect(store.errorMessage).toBe('')
      expect(listWorkspaces).not.toHaveBeenCalled()
    },
  )

  it.each(['resolve', 'reject'] as const)(
    'ignores a post-save selector refresh that %ss after leaving the picker',
    async (outcome) => {
      const refresh = deferred<AxiosResponse<ApiResult<WorkspaceSummaryResponseVO[]>>>()
      vi.mocked(createWorkspace).mockResolvedValueOnce(
        response({ code: 'SUCCESS', message: '成功', data: detail }),
      )
      vi.mocked(listWorkspaces).mockReturnValueOnce(refresh.promise)
      const store = useWorkspaceStore()
      store.workspaces = [summary]
      const pending = store.create('家庭研究组')
      // POST 已返回，但它发起的 GET 仍未返回，此时用户进入另一个空间。
      await vi.waitFor(() => expect(listWorkspaces).toHaveBeenCalledTimes(1))
      store.invalidateEntryRequests()
      const next = { ...detail, workspaceId: 13, name: '后来进入的空间' }
      vi.mocked(getWorkspace).mockResolvedValueOnce(
        response({ code: 'SUCCESS', message: '成功', data: next }),
      )
      await store.loadCurrent(13)
      if (outcome === 'resolve')
        refresh.resolve(response({ code: 'SUCCESS', message: '成功', data: [] }))
      else refresh.reject(new Error('旧列表读取失败'))
      await expect(pending).resolves.toEqual(detail)
      expect(store.currentWorkspace).toEqual(next)
      expect(store.workspaces).toEqual([summary])
      expect(store.errorMessage).toBe('')
      expect(store.loading).toBe(false)
    },
  )

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

  it('keeps the newest workspace when an older request finishes later', async () => {
    const olderRequest = deferred<AxiosResponse<ApiResult<WorkspaceDetailResponseVO>>>()
    const newerRequest = deferred<AxiosResponse<ApiResult<WorkspaceDetailResponseVO>>>()
    vi.mocked(getWorkspace)
      .mockReturnValueOnce(olderRequest.promise)
      .mockReturnValueOnce(newerRequest.promise)

    const store = useWorkspaceStore()
    const olderLoad = store.loadCurrent(12)
    const newerLoad = store.loadCurrent(13)
    newerRequest.resolve(
      response({
        code: 'SUCCESS',
        message: '成功',
        data: { ...detail, workspaceId: 13, name: '新空间' },
      }),
    )
    await newerLoad
    olderRequest.resolve(response({ code: 'SUCCESS', message: '成功', data: detail }))
    await olderLoad

    expect(store.currentWorkspace?.workspaceId).toBe(13)
    expect(store.currentWorkspace?.name).toBe('新空间')
    expect(store.loading).toBe(false)
  })

  it('reuses the current workspace detail until the route switches to another workspace', async () => {
    vi.mocked(getWorkspace)
      .mockResolvedValueOnce(
        response<ApiResult<WorkspaceDetailResponseVO>>({
          code: 'SUCCESS',
          message: '成功',
          data: detail,
        }),
      )
      .mockResolvedValueOnce(
        response<ApiResult<WorkspaceDetailResponseVO>>({
          code: 'SUCCESS',
          message: '成功',
          data: { ...detail, workspaceId: 13, name: '另一个空间' },
        }),
      )

    const store = useWorkspaceStore()
    await store.ensureCurrent(12)
    await store.ensureCurrent(12)
    await store.ensureCurrent(13)

    expect(getWorkspace).toHaveBeenCalledTimes(2)
    expect(store.currentWorkspace?.workspaceId).toBe(13)
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

  it('uses the renamed detail for the current space and selector', async () => {
    const renamed = { ...detail, name: '新名称' }
    vi.mocked(renameWorkspace).mockResolvedValue(
      response<ApiResult<WorkspaceDetailResponseVO>>({
        code: 'SUCCESS',
        message: '成功',
        data: renamed,
      }),
    )
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
    await store.rename(12, '新 名称')
    expect(renameWorkspace).toHaveBeenCalledWith(12, '新 名称')
    expect(store.currentWorkspace?.name).toBe('新名称')
    expect(store.workspaces[0]?.name).toBe('新名称')
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

  it('keeps the current invitation when only history reading fails', async () => {
    vi.mocked(getCurrentInvitation).mockResolvedValueOnce(
      response({ code: 'SUCCESS', message: '成功', data: currentInvitation }),
    )
    vi.mocked(listInvitationHistory).mockRejectedValueOnce(new Error('历史读取失败'))
    const store = useWorkspaceStore()
    await expect(store.loadInvitations(12)).rejects.toThrow('历史读取失败')
    expect(store.currentInvitation?.inviteCode).toBe('A1B2C3')
    expect(store.invitationError).toBe('历史读取失败')
    expect(store.invitationLoading).toBe(false)
  })

  it('keeps the saved invitation after refresh failure without submitting twice', async () => {
    vi.mocked(createInvitation).mockResolvedValueOnce(
      response({ code: 'SUCCESS', message: '成功', data: currentInvitation }),
    )
    vi.mocked(getCurrentInvitation).mockRejectedValueOnce(new Error('刷新失败'))
    vi.mocked(listInvitationHistory).mockRejectedValueOnce(new Error('刷新失败'))
    const store = useWorkspaceStore()
    await store.generateInvitation(12)
    expect(store.currentInvitation?.invitationId).toBe(31)
    expect(store.invitationError).toBe('刷新失败')
    expect(createInvitation).toHaveBeenCalledTimes(1)
  })

  it('clears the revoked invitation even if refresh fails', async () => {
    vi.mocked(revokeInvitation).mockResolvedValueOnce(
      response({ code: 'SUCCESS', message: '成功', data: null }),
    )
    vi.mocked(getCurrentInvitation).mockRejectedValueOnce(new Error('刷新失败'))
    vi.mocked(listInvitationHistory).mockRejectedValueOnce(new Error('刷新失败'))
    const store = useWorkspaceStore()
    store.currentInvitation = currentInvitation
    await store.stopInvitation(12)
    expect(store.currentInvitation).toBeNull()
    expect(store.invitationError).toBe('刷新失败')
    expect(revokeInvitation).toHaveBeenCalledWith(12, 31)
  })

  it('does not refresh or refill an invitation after its delayed creation leaves the page', async () => {
    const pending = deferred<AxiosResponse<ApiResult<CurrentInvitationResponseVO>>>()
    vi.mocked(createInvitation).mockReturnValueOnce(pending.promise)
    const store = useWorkspaceStore()
    const saving = store.generateInvitation(12)
    store.clearInvitations()
    store.currentInvitation = { ...currentInvitation, invitationId: 42, inviteCode: 'B7C8D9' }
    pending.resolve(response({ code: 'SUCCESS', message: '成功', data: currentInvitation }))
    await saving
    expect(store.currentInvitation?.invitationId).toBe(42)
    expect(getCurrentInvitation).not.toHaveBeenCalled()
    expect(listInvitationHistory).not.toHaveBeenCalled()
  })

  it('does not clear a new invitation after a delayed old revocation', async () => {
    const pending = deferred<AxiosResponse<ApiResult<null>>>()
    vi.mocked(revokeInvitation).mockReturnValueOnce(pending.promise)
    const store = useWorkspaceStore()
    store.currentInvitation = currentInvitation
    const stopping = store.stopInvitation(12)
    store.clearCurrent()
    store.currentInvitation = { ...currentInvitation, invitationId: 42, inviteCode: 'B7C8D9' }
    pending.resolve(response({ code: 'SUCCESS', message: '成功', data: null }))
    await stopping
    expect(store.currentInvitation?.invitationId).toBe(42)
    expect(getCurrentInvitation).not.toHaveBeenCalled()
    expect(listInvitationHistory).not.toHaveBeenCalled()
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

  it('keeps a saved removal and its local result when both refresh reads fail', async () => {
    vi.mocked(removeWorkspaceMember).mockResolvedValue(
      response({ code: 'SUCCESS', message: '成功', data: null }),
    )
    vi.mocked(getWorkspace).mockRejectedValue(new Error('详情读取失败'))
    vi.mocked(listWorkspaceMembers).mockRejectedValue(new Error('成员读取失败'))
    const store = useWorkspaceStore()
    store.currentWorkspace = { ...detail, memberCount: 2 }
    store.members = members
    await expect(store.removeMember(12, 10)).resolves.toBeUndefined()
    expect(store.members).toEqual([members[0]])
    expect(store.currentWorkspace?.memberCount).toBe(1)
    expect(store.errorMessage).toBe('详情读取失败')
    expect(store.membersError).toBe('成员读取失败')
    expect(removeWorkspaceMember).toHaveBeenCalledTimes(1)
  })

  it('keeps transferred roles and clears invitations even when follow-up reads fail', async () => {
    const transferred = { ...detail, role: 'MEMBER' as const, ownerUserId: 10 }
    vi.mocked(transferWorkspaceOwnership).mockResolvedValue(
      response({ code: 'SUCCESS', message: '成功', data: transferred }),
    )
    vi.mocked(listWorkspaces).mockRejectedValue(new Error('空间列表读取失败'))
    vi.mocked(listWorkspaceMembers).mockRejectedValue(new Error('成员读取失败'))
    const store = useWorkspaceStore()
    store.currentWorkspace = detail
    store.workspaces = [summary]
    store.members = members
    store.currentInvitation = currentInvitation
    await expect(store.transferOwnership(12, 10)).resolves.toEqual(transferred)
    expect(store.currentWorkspace?.role).toBe('MEMBER')
    expect(store.workspaces[0]?.role).toBe('MEMBER')
    expect(store.members.find((member) => member.userId === 10)?.role).toBe('OWNER')
    expect(store.currentInvitation).toBeNull()
  })

  it('completes leaving even when the selector refresh fails', async () => {
    vi.mocked(leaveWorkspace).mockResolvedValue(
      response({ code: 'SUCCESS', message: '成功', data: null }),
    )
    vi.mocked(listWorkspaces).mockRejectedValue(new Error('空间列表读取失败'))
    const store = useWorkspaceStore()
    store.currentWorkspace = detail
    store.members = members
    await expect(store.leave(12)).resolves.toBeUndefined()
    expect(store.currentWorkspace).toBeNull()
    expect(store.members).toEqual([])
    expect(store.errorMessage).toBe('空间列表读取失败')
  })

  it.each(['leave', 'remove', 'transfer', 'archive', 'restore', 'rename'] as const)(
    'ignores a delayed %s write after changing space and starts no obsolete refresh',
    async (action) => {
      const delayed = deferred<AxiosResponse<ApiResult<WorkspaceDetailResponseVO>>>()
      const emptyWrite = deferred<AxiosResponse<ApiResult<null>>>()
      const store = useWorkspaceStore()
      store.currentWorkspace = detail
      let pending: Promise<unknown>
      // 写入响应在服务器已成功，浏览器直到切到 B 后才收到。
      const success = response({ code: 'SUCCESS', message: '成功', data: detail })
      if (action === 'leave') {
        vi.mocked(leaveWorkspace).mockReturnValue(emptyWrite.promise)
        pending = store.leave(12)
      } else if (action === 'remove') {
        vi.mocked(removeWorkspaceMember).mockReturnValue(emptyWrite.promise)
        pending = store.removeMember(12, 10)
      } else if (action === 'transfer') {
        vi.mocked(transferWorkspaceOwnership).mockReturnValue(delayed.promise)
        pending = store.transferOwnership(12, 10)
      } else if (action === 'archive') {
        vi.mocked(archiveWorkspace).mockReturnValue(delayed.promise)
        pending = store.archive(12)
      } else if (action === 'restore') {
        vi.mocked(restoreWorkspace).mockReturnValue(delayed.promise)
        pending = store.restore(12)
      } else {
        vi.mocked(renameWorkspace).mockReturnValue(delayed.promise)
        pending = store.rename(12, '新名')
      }
      store.clearCurrent()
      const next = { ...detail, workspaceId: 13, name: '空间 B' }
      store.currentWorkspace = next
      store.workspaces = [{ ...summary, name: '未读的空间 A' }, next]
      store.members = members
      store.currentInvitation = currentInvitation
      delayed.resolve(success)
      emptyWrite.resolve(response({ code: 'SUCCESS', message: '成功', data: null }))
      await pending
      expect(store.currentWorkspace).toEqual(next)
      expect(store.members).toEqual(members)
      expect(store.currentInvitation).toEqual(currentInvitation)
      expect(store.workspaces[0]?.name).toBe('未读的空间 A')
      expect(getWorkspace).not.toHaveBeenCalled()
      expect(listWorkspaces).not.toHaveBeenCalled()
      expect(listWorkspaceMembers).not.toHaveBeenCalled()
    },
  )

  it('invalidates same-space management callbacks and reads when the page unmounts', async () => {
    const write = deferred<AxiosResponse<ApiResult<WorkspaceDetailResponseVO>>>()
    const read = deferred<AxiosResponse<ApiResult<WorkspaceMemberResponseVO[]>>>()
    vi.mocked(archiveWorkspace).mockReturnValue(write.promise)
    vi.mocked(listWorkspaceMembers).mockReturnValue(read.promise)
    const store = useWorkspaceStore()
    const saving = store.archive(12)
    const reading = store.loadMembers(12)
    store.invalidateManagement()
    store.currentWorkspace = detail
    store.members = members
    write.resolve(
      response({
        code: 'SUCCESS',
        message: '成功',
        data: { ...detail, status: 'ARCHIVED_BY_OWNER' },
      }),
    )
    read.resolve(response({ code: 'SUCCESS', message: '成功', data: [] }))
    await Promise.all([saving, reading])
    expect(store.currentWorkspace).toEqual(detail)
    expect(store.members).toEqual(members)
    expect(listWorkspaces).not.toHaveBeenCalled()
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
