import type { Router } from 'vue-router'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import { isAuthenticationFailure } from '@/utils/authentication-error'

export async function handleWorkspaceApiError(error: unknown, router: Router) {
  if (!(error instanceof ApiError)) return false

  const authStore = useAuthStore()
  const workspaceStore = useWorkspaceStore()
  // 已退出或切换账号的旧请求不能再清除新会话、改变当前导航。
  if (error.staleSession || (isAuthenticationFailure(error) && !authStore.isAuthenticated)) {
    return true
  }
  if (isAuthenticationFailure(error)) {
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
