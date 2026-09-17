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
})
