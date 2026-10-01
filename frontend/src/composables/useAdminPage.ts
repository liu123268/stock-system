import { onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import { useAdminStore } from '@/stores/admin'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import { isAuthenticationFailure } from '@/utils/authentication-error'

export function useAdminPage() {
  const store = useAdminStore()
  const auth = useAuthStore()
  const workspace = useWorkspaceStore()
  const router = useRouter()
  const route = useRoute()
  const session = auth.sessionVersion
  let disposed = false
  const active = () => !disposed && session === auth.sessionVersion
  store.clearAll()
  onBeforeUnmount(() => {
    disposed = true
    store.clearAll()
  })
  async function expire() {
    const redirect = route.fullPath
    auth.logout()
    workspace.clearAll()
    await router.replace({ name: 'login', query: { reason: 'expired', redirect } })
  }
  async function run<T>(operation: () => Promise<T>) {
    try {
      const value = await operation()
      return active() ? value : null
    } catch (error) {
      if (!active() || (error instanceof ApiError && error.staleSession)) return null
      if (isAuthenticationFailure(error)) await expire()
      // 业务 403（例如恢复来源不符）留在原页，仅 FORBIDDEN 重新核对治理身份。
      else if (error instanceof ApiError && error.code === 'FORBIDDEN') {
        try {
          await auth.refreshCurrentUser()
          if (active() && !auth.isSuperAdmin) {
            workspace.clearAll()
            await router.replace({ name: 'workspaces', query: { reason: 'access-lost' } })
          }
        } catch (verificationError) {
          if (active() && isAuthenticationFailure(verificationError)) await expire()
        }
      }
      return null
    }
  }
  return { store, active, run }
}
