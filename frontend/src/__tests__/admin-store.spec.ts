import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosHeaders, type AxiosResponse } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import {
  disableAdminUser,
  archiveAdminWorkspace,
  getAdminUsers,
  getAdminUser,
  getAdminWorkspaces,
  getAdminWorkspace,
  getAdminOperationLogs,
  restoreAdminUser,
  type AdminWorkspaceDetailResponseVO,
  type AdminUserDetailResponseVO,
  type AdminUserSummaryResponseVO,
  type PageResponseVO,
} from '@/api/admin'
import type { ApiResult } from '@/api/auth'
import { useAdminStore } from '@/stores/admin'

vi.mock('@/api/admin')

function response<T>(data: T): AxiosResponse<T> {
  return {
    data,
    status: 200,
    statusText: 'OK',
    headers: {},
    config: { headers: new AxiosHeaders() },
  }
}

const user: AdminUserSummaryResponseVO = {
  userId: 9,
  username: 'governance_user',
  nickname: '治理用户',
  status: 'ACTIVE',
  accountType: 'REGULAR',
  workspaceCount: 2,
  ownedWorkspaceCount: 1,
  createdAt: '2026-09-22T09:00:00',
}

describe('admin store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads a paged user list and replaces the changed user after disabling', async () => {
    vi.mocked(getAdminUsers).mockResolvedValue(
      response<ApiResult<PageResponseVO<AdminUserSummaryResponseVO>>>({
        code: 'SUCCESS',
        message: '成功',
        data: { items: [user], page: 1, size: 20, total: 1, totalPages: 1 },
      }),
    )
    const disabled: AdminUserDetailResponseVO = {
      ...user,
      status: 'DISABLED',
      memberships: [],
    }
    vi.mocked(disableAdminUser).mockResolvedValue(
      response<ApiResult<AdminUserDetailResponseVO>>({
        code: 'SUCCESS',
        message: '成功',
        data: disabled,
      }),
    )

    const store = useAdminStore()
    await store.loadUsers({ page: 1, status: 'ACTIVE' })
    await store.setUserStatus(9, 'DISABLED')

    expect(getAdminUsers).toHaveBeenCalledWith({ page: 1, status: 'ACTIVE' })
    expect(store.users.items[0]?.status).toBe('DISABLED')
    expect(store.selectedUser?.status).toBe('DISABLED')
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
const detail: AdminUserDetailResponseVO = { ...user, memberships: [] }
const workspace: AdminWorkspaceDetailResponseVO = {
  workspaceId: 30,
  name: '治理空间',
  status: 'ACTIVE',
  createdByUserId: 9,
  createdByUsername: user.username,
  ownerUserId: 9,
  ownerUsername: user.username,
  memberCount: 1,
  archivedAt: null,
  createdAt: user.createdAt,
  members: [],
}
const ok = <T>(value: T) =>
  response<ApiResult<T>>({ code: 'SUCCESS', message: '成功', data: value })
const paged = <T>(value: T) => ({ items: [value], page: 1, size: 20, total: 1, totalPages: 1 })

describe('admin request ownership', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })
  it.each(['users', 'user', 'workspaces', 'workspace', 'logs'] as const)(
    'does not restore %s after leaving or logging out',
    async (key) => {
      const pending = deferred<AxiosResponse<ApiResult<never>>>()
      const store = useAdminStore()
      let request: Promise<unknown>
      switch (key) {
        case 'users':
          vi.mocked(getAdminUsers).mockReturnValue(pending.promise)
          request = store.loadUsers()
          break
        case 'user':
          vi.mocked(getAdminUser).mockReturnValue(pending.promise)
          request = store.loadUser(9)
          break
        case 'workspaces':
          vi.mocked(getAdminWorkspaces).mockReturnValue(pending.promise)
          request = store.loadWorkspaces()
          break
        case 'workspace':
          vi.mocked(getAdminWorkspace).mockReturnValue(pending.promise)
          request = store.loadWorkspace(30)
          break
        case 'logs':
          vi.mocked(getAdminOperationLogs).mockReturnValue(pending.promise)
          request = store.loadOperationLogs()
          break
      }
      store.clearAll()
      pending.resolve(ok(null as never))
      expect(await request).toBeNull()
      expect(store.users.items).toEqual([])
      expect(store.workspaces.items).toEqual([])
      expect(store.operationLogs.items).toEqual([])
      expect(store.selectedUser).toBeNull()
      expect(store.selectedWorkspace).toBeNull()
      expect(store.loading).toBe(false)
    },
  )
  it.each(['user', 'workspace'] as const)(
    'keeps the latest %s detail when an earlier target returns later',
    async (key) => {
      const store = useAdminStore()
      if (key === 'user') {
        const pending = deferred<AxiosResponse<ApiResult<AdminUserDetailResponseVO>>>()
        vi.mocked(getAdminUser)
          .mockReturnValueOnce(pending.promise)
          .mockResolvedValueOnce(ok({ ...detail, userId: 10, nickname: '新目标' }))
        const old = store.loadUser(9)
        await store.loadUser(10)
        pending.resolve(ok(detail))
        await old
      } else {
        const pending = deferred<AxiosResponse<ApiResult<AdminWorkspaceDetailResponseVO>>>()
        vi.mocked(getAdminWorkspace)
          .mockReturnValueOnce(pending.promise)
          .mockResolvedValueOnce(ok({ ...workspace, workspaceId: 31 }))
        const old = store.loadWorkspace(30)
        await store.loadWorkspace(31)
        pending.resolve(ok(workspace))
        await old
      }
      expect(store.selectedUser?.userId).toBe(key === 'user' ? 10 : undefined)
      expect(store.selectedWorkspace?.workspaceId).toBe(key === 'workspace' ? 31 : undefined)
    },
  )
  it('keeps the newer list, loading and error when an old search fails', async () => {
    const store = useAdminStore()
    const old = deferred<AxiosResponse<ApiResult<PageResponseVO<AdminUserSummaryResponseVO>>>>()
    const current = deferred<AxiosResponse<ApiResult<PageResponseVO<AdminUserSummaryResponseVO>>>>()
    vi.mocked(getAdminUsers).mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const first = store.loadUsers({ keyword: 'old' })
    const second = store.loadUsers({ keyword: 'current' })
    old.reject(new Error('旧失败'))
    await first
    expect(store.error).toBe('')
    expect(store.loading).toBe(true)
    current.resolve(ok(paged({ ...user, userId: 10 })))
    await second
    expect(store.users.items[0]?.userId).toBe(10)
    expect(store.loading).toBe(false)
  })
  it.each(['user', 'workspace'] as const)(
    'ignores a delayed %s write after clearAll',
    async (key) => {
      const store = useAdminStore()
      let old: Promise<unknown>
      if (key === 'user') {
        const pending = deferred<AxiosResponse<ApiResult<AdminUserDetailResponseVO>>>()
        vi.mocked(disableAdminUser).mockReturnValue(pending.promise)
        old = store.setUserStatus(9, 'DISABLED')
        store.clearAll()
        pending.resolve(ok({ ...detail, status: 'DISABLED' }))
      } else {
        const pending = deferred<AxiosResponse<ApiResult<AdminWorkspaceDetailResponseVO>>>()
        vi.mocked(archiveAdminWorkspace).mockReturnValue(pending.promise)
        old = store.setWorkspaceStatus(30, 'ARCHIVED_BY_SUPER_ADMIN')
        store.clearAll()
        pending.resolve(ok({ ...workspace, status: 'ARCHIVED_BY_SUPER_ADMIN' }))
      }
      expect(await old).toBeNull()
      expect(store.selectedUser).toBeNull()
      expect(store.selectedWorkspace).toBeNull()
      expect(store.actionLoading).toBe(false)
    },
  )
  it('prevents a second POST while a status write is pending', async () => {
    const store = useAdminStore()
    const pending = deferred<AxiosResponse<ApiResult<AdminUserDetailResponseVO>>>()
    vi.mocked(disableAdminUser).mockReturnValue(pending.promise)
    const first = store.setUserStatus(9, 'DISABLED')
    expect(await store.setUserStatus(9, 'ACTIVE')).toBeNull()
    expect(restoreAdminUser).not.toHaveBeenCalled()
    pending.resolve(ok({ ...detail, status: 'DISABLED' }))
    await first
    expect(disableAdminUser).toHaveBeenCalledTimes(1)
    expect(store.selectedUser?.status).toBe('DISABLED')
  })
  it('does not let a pre-write GET overwrite the saved disabled status', async () => {
    const store = useAdminStore()
    const pending = deferred<AxiosResponse<ApiResult<AdminUserDetailResponseVO>>>()
    vi.mocked(getAdminUser).mockReturnValue(pending.promise)
    const old = store.loadUser(9)
    vi.mocked(disableAdminUser).mockResolvedValue(ok({ ...detail, status: 'DISABLED' }))
    await store.setUserStatus(9, 'DISABLED')
    pending.resolve(ok(detail))
    await old
    expect(store.selectedUser?.status).toBe('DISABLED')
  })
  it('updates the saved row without replacing another selected user', async () => {
    const store = useAdminStore()
    store.users = paged(user)
    const pending = deferred<AxiosResponse<ApiResult<AdminUserDetailResponseVO>>>()
    vi.mocked(disableAdminUser).mockReturnValue(pending.promise)
    const old = store.setUserStatus(9, 'DISABLED')
    vi.mocked(getAdminUser).mockResolvedValue(ok({ ...detail, userId: 10 }))
    await store.loadUser(10)
    pending.resolve(ok({ ...detail, status: 'DISABLED' }))
    await old
    expect(store.selectedUser?.userId).toBe(10)
    expect(store.users.items[0]?.status).toBe('DISABLED')
  })
  it('preserves a successful status write when its subsequent list refresh fails', async () => {
    const store = useAdminStore()
    store.users = paged(user)
    vi.mocked(disableAdminUser).mockResolvedValue(ok({ ...detail, status: 'DISABLED' }))
    await store.setUserStatus(9, 'DISABLED')
    vi.mocked(getAdminUsers).mockRejectedValue(new Error('列表不可用'))
    await expect(store.loadUsers()).rejects.toThrow('列表不可用')
    expect(store.selectedUser?.status).toBe('DISABLED')
    expect(store.users.items[0]?.status).toBe('DISABLED')
    expect(store.loading).toBe(false)
    expect(store.actionLoading).toBe(false)
  })
})
