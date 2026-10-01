import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError, TOKEN_STORAGE_KEY } from '@/api/http'
import { authenticationGuard } from '@/router'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'

function protectedRoute(path = '/workspaces') {
  return {
    fullPath: path,
    meta: { requiresAuth: true },
  }
}

describe('authentication route guard', () => {
  beforeEach(() => {
    window.localStorage.clear()
    window.localStorage.setItem(TOKEN_STORAGE_KEY, 'existing-token')
    setActivePinia(createPinia())
  })

  it('keeps the session and target route when current-user verification has a network failure', async () => {
    const authStore = useAuthStore()
    const workspaceStore = useWorkspaceStore()
    const clearAll = vi.spyOn(workspaceStore, 'clearAll')
    vi.spyOn(authStore, 'refreshCurrentUser').mockRejectedValue(new Error('Network Error'))

    await expect(authenticationGuard(protectedRoute())).resolves.toBe(true)
    expect(authStore.isAuthenticated).toBe(true)
    expect(window.localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('existing-token')
    expect(clearAll).not.toHaveBeenCalled()
  })

  it('clears account state and redirects when the backend confirms authentication is invalid', async () => {
    const authStore = useAuthStore()
    const workspaceStore = useWorkspaceStore()
    const clearAll = vi.spyOn(workspaceStore, 'clearAll')
    vi.spyOn(authStore, 'refreshCurrentUser').mockRejectedValue(
      new ApiError('UNAUTHENTICATED', '未登录或登录已过期', 401),
    )

    await expect(authenticationGuard(protectedRoute('/workspaces/9/overview'))).resolves.toEqual({
      name: 'login',
      query: { redirect: '/workspaces/9/overview', reason: 'expired' },
    })
    expect(authStore.isAuthenticated).toBe(false)
    expect(window.localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(clearAll).toHaveBeenCalledOnce()
  })

  it('reuses a successful session verification during later protected navigation', async () => {
    const authStore = useAuthStore()
    const refreshCurrentUser = vi
      .spyOn(authStore, 'refreshCurrentUser')
      .mockImplementation(async () => {
        authStore.sessionVerified = true
      })

    await expect(authenticationGuard(protectedRoute('/workspaces/9/overview'))).resolves.toBe(true)
    await expect(authenticationGuard(protectedRoute('/workspaces/9/members'))).resolves.toBe(true)

    expect(refreshCurrentUser).toHaveBeenCalledOnce()
  })

  it('routes super administrators away from regular workspaces', async () => {
    const authStore = useAuthStore()
    authStore.currentUser = {
      userId: 1,
      username: 'root_admin',
      nickname: '治理管理员',
      accountType: 'SUPER_ADMIN',
    }
    authStore.sessionVerified = true

    await expect(
      authenticationGuard({
        fullPath: '/workspaces',
        meta: { requiresAuth: true, requiresRegular: true },
      }),
    ).resolves.toEqual({ name: 'admin-users' })
  })

  it('routes regular users away from the governance backend', async () => {
    const authStore = useAuthStore()
    authStore.currentUser = {
      userId: 9,
      username: 'regular_user',
      nickname: '普通用户',
      accountType: 'REGULAR',
    }
    authStore.sessionVerified = true

    await expect(
      authenticationGuard({
        fullPath: '/admin/users',
        meta: { requiresAuth: true, requiresAdmin: true },
      }),
    ).resolves.toEqual({ name: 'workspaces' })
  })

  it.each(['success', '401'] as const)(
    'cancels old verification on %s after the session changes',
    async (result) => {
      const store = useAuthStore()
      let release!: () => void
      vi.spyOn(store, 'refreshCurrentUser').mockImplementation(
        () =>
          new Promise<void>((resolve, reject) => {
            release = () =>
              result === 'success'
                ? resolve()
                : reject(new ApiError('UNAUTHENTICATED', '旧会话失效', 401, true))
          }),
      )
      const pending = authenticationGuard(protectedRoute('/profile'))
      store.logout()
      // 模拟另一会话已登录，旧守卫不能清理它，也不能继续旧导航。
      store.accessToken = 'new-account-token'
      localStorage.setItem(TOKEN_STORAGE_KEY, 'new-account-token')
      const clearAll = vi.spyOn(useWorkspaceStore(), 'clearAll')
      release()
      expect(await pending).toBe(false)
      expect(store.isAuthenticated).toBe(true)
      expect(clearAll).not.toHaveBeenCalled()
    },
  )
})
