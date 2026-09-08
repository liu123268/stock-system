import type { Router } from 'vue-router'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'

export async function handleWorkspaceApiError(error: unknown, router: Router) {
  if (!(error instanceof ApiError)) return false

  const authStore = useAuthStore()
  const workspaceStore = useWorkspaceStore()
  if (
    error.status === 401 ||
    error.code === 'UNAUTHENTICATED' ||
    error.code === 'ACCOUNT_DISABLED'
  ) {
    authStore.logout()
    workspaceStore.clearAll()
    await router.replace({ name: 'login', query: { reason: 'expired' } })
    return true
  }

  if (error.code === 'WORKSPACE_MEMBERSHIP_INACTIVE' || error.code === 'WORKSPACE_NOT_FOUND') {
    workspaceStore.clearCurrent()
    try {
      await workspaceStore.loadWorkspaces()
    } catch {
      // 返回选择页后沿用 Store 的加载错误与重试入口。
    }
    await router.replace({ name: 'workspaces', query: { reason: 'access-lost' } })
    return true
  }

  if (error.code === 'WORKSPACE_ARCHIVED') {
    workspaceStore.clearCurrent()
    try {
      await workspaceStore.loadWorkspaces()
    } catch {
      // 返回选择页后沿用 Store 的加载错误与重试入口。
    }
    await router.replace({ name: 'workspaces', query: { reason: 'archived' } })
    return true
  }
  return false
}
