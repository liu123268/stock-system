import { createRouter, createWebHistory, type RouteLocationNormalized } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import { isAuthenticationFailure } from '@/utils/authentication-error'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { top: 0 }
  },
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/LandingView.vue'),
    },
    {
      path: '/workspace',
      redirect: '/workspaces',
    },
    {
      path: '/workspaces',
      name: 'workspaces',
      component: () => import('@/views/workspace/WorkspaceListView.vue'),
      meta: { requiresAuth: true, requiresRegular: true },
    },
    {
      path: '/workspaces/:workspaceId',
      component: () => import('@/components/workspace/WorkspaceLayout.vue'),
      meta: { requiresAuth: true, requiresRegular: true },
      children: [
        { path: '', redirect: { name: 'workspace-overview' } },
        {
          path: 'overview',
          name: 'workspace-overview',
          component: () => import('@/views/workspace/WorkspaceOverviewView.vue'),
        },
        {
          path: 'stocks',
          name: 'workspace-stocks',
          component: () => import('@/views/workspace/WorkspaceStocksView.vue'),
        },
        {
          path: 'stocks/market/:code',
          name: 'workspace-stock-market',
          component: () => import('@/views/workspace/WorkspaceStockMarketView.vue'),
        },
        {
          path: 'stocks/:workspaceStockId',
          name: 'workspace-stock-research',
          component: () => import('@/views/workspace/WorkspaceStockResearchView.vue'),
        },
        {
          path: 'members',
          name: 'workspace-members',
          component: () => import('@/views/workspace/WorkspaceMembersView.vue'),
        },
        {
          path: 'settings',
          name: 'workspace-settings',
          component: () => import('@/views/workspace/WorkspaceSettingsView.vue'),
        },
      ],
    },
    {
      path: '/profile',
      name: 'profile',
      component: () => import('@/views/ProfileView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/admin',
      component: () => import('@/components/admin/AdminLayout.vue'),
      meta: { requiresAuth: true, requiresAdmin: true },
      children: [
        { path: '', redirect: { name: 'admin-users' } },
        {
          path: 'users',
          name: 'admin-users',
          component: () => import('@/views/admin/AdminUsersView.vue'),
        },
        {
          path: 'workspaces',
          name: 'admin-workspaces',
          component: () => import('@/views/admin/AdminWorkspacesView.vue'),
        },
        {
          path: 'operation-logs',
          name: 'admin-operation-logs',
          component: () => import('@/views/admin/AdminOperationLogsView.vue'),
        },
      ],
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { guestOnly: true },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { guestOnly: true },
    },
    {
      path: '/design-preview',
      name: 'design-preview',
      component: () => import('@/views/DesignPreviewView.vue'),
    },
  ],
})

export async function authenticationGuard(
  to: Pick<RouteLocationNormalized, 'fullPath' | 'meta'>,
) {
  const authStore = useAuthStore()
  const workspaceStore = useWorkspaceStore()

  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requiresAuth && !authStore.sessionVerified) {
    try {
      await authStore.refreshCurrentUser()
    } catch (error) {
      if (isAuthenticationFailure(error)) {
        authStore.logout()
        workspaceStore.clearAll()
        return { name: 'login', query: { redirect: to.fullPath, reason: 'expired' } }
      }

      // 网络中断、超时或服务暂不可用不能证明会话失效。继续进入目标页面，
      // 由页面已有的加载失败状态保留当前会话并提供重试入口。
      return true
    }
  }
  if (to.meta.requiresAdmin && !authStore.isSuperAdmin) {
    return { name: 'workspaces' }
  }
  if (to.meta.requiresRegular && authStore.isSuperAdmin) {
    return { name: 'admin-users' }
  }
  if (to.meta.guestOnly && authStore.isAuthenticated) {
    return { name: authStore.isSuperAdmin ? 'admin-users' : 'workspaces' }
  }

  return true
}

router.beforeEach(authenticationGuard)

export default router
