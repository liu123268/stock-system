import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { Router } from 'vue-router'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import { handleWorkspaceApiError } from '@/utils/workspace-access'

describe('workspace access error handling', () => {
  beforeEach(() => {
    window.localStorage.clear()
    setActivePinia(createPinia())
  })

  it('clears the account session and workspace state for a protected 401', async () => {
    window.localStorage.setItem('stock-system-access-token', 'expired-token')
    const authStore = useAuthStore()
    const workspaceStore = useWorkspaceStore()
    workspaceStore.workspaces = [
      {
        workspaceId: 12,
        name: '测试空间',
        role: 'OWNER',
        status: 'ACTIVE',
        memberCount: 1,
        createdAt: '2026-09-04T12:00:00',
      },
    ]
    const replace = vi.fn<(to: unknown) => Promise<void>>().mockResolvedValue(undefined)

    const handled = await handleWorkspaceApiError(
      new ApiError('ACCOUNT_DISABLED', '账号当前不可用', 401),
      { replace } as unknown as Router,
    )

    expect(handled).toBe(true)
    expect(authStore.isAuthenticated).toBe(false)
    expect(workspaceStore.workspaces).toEqual([])
    expect(replace).toHaveBeenCalledWith({ name: 'login', query: { reason: 'expired' } })
  })

  it('returns to selection when the current membership is inactive', async () => {
    const workspaceStore = useWorkspaceStore()
    workspaceStore.currentWorkspace = {
      workspaceId: 12,
      name: '测试空间',
      role: 'MEMBER',
      status: 'ACTIVE',
      memberCount: 2,
      createdByUserId: 9,
      ownerUserId: 9,
      archivedAt: null,
      createdAt: '2026-09-04T12:00:00',
    }
    vi.spyOn(workspaceStore, 'loadWorkspaces').mockRejectedValue(new Error('network'))
    const replace = vi.fn<(to: unknown) => Promise<void>>().mockResolvedValue(undefined)

    const handled = await handleWorkspaceApiError(
      new ApiError('WORKSPACE_MEMBERSHIP_INACTIVE', '成员关系已失效', 403),
      { replace } as unknown as Router,
    )

    expect(handled).toBe(true)
    expect(workspaceStore.currentWorkspace).toBeNull()
    expect(replace).toHaveBeenCalledWith({
      name: 'workspaces',
      query: { reason: 'access-lost' },
    })
  })
})
