import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosHeaders, type AxiosResponse } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import {
  disableAdminUser,
  getAdminUsers,
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
      response<ApiResult<PageResponseVO<AdminUserSummaryResponseVO>>>(
        { code: 'SUCCESS', message: '成功', data: { items: [user], page: 1, size: 20, total: 1, totalPages: 1 } },
      ),
    )
    const disabled: AdminUserDetailResponseVO = {
      ...user,
      status: 'DISABLED',
      memberships: [],
    }
    vi.mocked(disableAdminUser).mockResolvedValue(
      response<ApiResult<AdminUserDetailResponseVO>>({ code: 'SUCCESS', message: '成功', data: disabled }),
    )

    const store = useAdminStore()
    await store.loadUsers({ page: 1, status: 'ACTIVE' })
    await store.setUserStatus(9, 'DISABLED')

    expect(getAdminUsers).toHaveBeenCalledWith({ page: 1, status: 'ACTIVE' })
    expect(store.users.items[0]?.status).toBe('DISABLED')
    expect(store.selectedUser?.status).toBe('DISABLED')
  })
})
