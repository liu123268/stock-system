import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
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
      meta: { requiresAuth: true },
    },
    {
      path: '/workspaces/:workspaceId/overview',
      name: 'workspace-overview',
      component: () => import('@/views/workspace/WorkspaceOverviewView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/workspaces/:workspaceId/stocks',
      name: 'workspace-stocks',
      component: () => import('@/views/workspace/WorkspaceStocksView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/workspaces/:workspaceId/members',
      name: 'workspace-members',
      component: () => import('@/views/workspace/WorkspaceMembersView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/workspaces/:workspaceId/settings',
      name: 'workspace-settings',
      component: () => import('@/views/workspace/WorkspaceSettingsView.vue'),
      meta: { requiresAuth: true },
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

router.beforeEach(async (to) => {
  const authStore = useAuthStore()
  const workspaceStore = useWorkspaceStore()

  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requiresAuth) {
    try {
      await authStore.refreshCurrentUser()
    } catch {
      authStore.logout()
      workspaceStore.clearAll()
      return { name: 'login', query: { redirect: to.fullPath, reason: 'expired' } }
    }
  }
  if (to.meta.guestOnly && authStore.isAuthenticated) {
    return { name: 'workspaces' }
  }

  return true
})

export default router
