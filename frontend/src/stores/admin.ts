import { ref } from 'vue'
import { defineStore } from 'pinia'
import {
  archiveAdminWorkspace,
  disableAdminUser,
  getAdminOperationLogs,
  getAdminUser,
  getAdminUsers,
  getAdminWorkspace,
  getAdminWorkspaces,
  restoreAdminUser,
  restoreAdminWorkspace,
  type AdminListQuery,
  type AdminOperationLogResponseVO,
  type AdminUserDetailResponseVO,
  type AdminUserSummaryResponseVO,
  type AdminWorkspaceDetailResponseVO,
  type AdminWorkspaceSummaryResponseVO,
  type PageResponseVO,
} from '@/api/admin'

function emptyPage<T>(): PageResponseVO<T> {
  return { items: [], page: 1, size: 20, total: 0, totalPages: 0 }
}

export const useAdminStore = defineStore('admin', () => {
  const users = ref<PageResponseVO<AdminUserSummaryResponseVO>>(emptyPage())
  const selectedUser = ref<AdminUserDetailResponseVO | null>(null)
  const workspaces = ref<PageResponseVO<AdminWorkspaceSummaryResponseVO>>(emptyPage())
  const selectedWorkspace = ref<AdminWorkspaceDetailResponseVO | null>(null)
  const operationLogs = ref<PageResponseVO<AdminOperationLogResponseVO>>(emptyPage())
  const loading = ref(false)
  const actionLoading = ref(false)
  const error = ref('')

  async function run<T>(operation: () => Promise<T>) {
    loading.value = true
    error.value = ''
    try {
      return await operation()
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : '加载失败，请稍后重试'
      throw caught
    } finally {
      loading.value = false
    }
  }

  async function loadUsers(query: AdminListQuery = {}) {
    return run(async () => {
      users.value = (await getAdminUsers(query)).data.data
    })
  }

  async function loadUser(userId: number) {
    return run(async () => {
      selectedUser.value = (await getAdminUser(userId)).data.data
    })
  }

  async function setUserStatus(userId: number, status: 'ACTIVE' | 'DISABLED') {
    actionLoading.value = true
    error.value = ''
    try {
      selectedUser.value = (
        await (status === 'DISABLED' ? disableAdminUser(userId) : restoreAdminUser(userId))
      ).data.data
      users.value.items = users.value.items.map((item) =>
        item.userId === userId && selectedUser.value ? selectedUser.value : item,
      )
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : '操作失败，请稍后重试'
      throw caught
    } finally {
      actionLoading.value = false
    }
  }

  async function loadWorkspaces(query: AdminListQuery = {}) {
    return run(async () => {
      workspaces.value = (await getAdminWorkspaces(query)).data.data
    })
  }

  async function loadWorkspace(workspaceId: number) {
    return run(async () => {
      selectedWorkspace.value = (await getAdminWorkspace(workspaceId)).data.data
    })
  }

  async function setWorkspaceStatus(
    workspaceId: number,
    status: 'ACTIVE' | 'ARCHIVED_BY_SUPER_ADMIN',
  ) {
    actionLoading.value = true
    error.value = ''
    try {
      selectedWorkspace.value = (
        await (status === 'ARCHIVED_BY_SUPER_ADMIN'
          ? archiveAdminWorkspace(workspaceId)
          : restoreAdminWorkspace(workspaceId))
      ).data.data
      workspaces.value.items = workspaces.value.items.map((item) =>
        item.workspaceId === workspaceId && selectedWorkspace.value
          ? selectedWorkspace.value
          : item,
      )
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : '操作失败，请稍后重试'
      throw caught
    } finally {
      actionLoading.value = false
    }
  }

  async function loadOperationLogs(query: {
    page?: number
    size?: number
    action?: string
    result?: string
  } = {}) {
    return run(async () => {
      operationLogs.value = (await getAdminOperationLogs(query)).data.data
    })
  }

  function clearAll() {
    users.value = emptyPage()
    selectedUser.value = null
    workspaces.value = emptyPage()
    selectedWorkspace.value = null
    operationLogs.value = emptyPage()
    loading.value = false
    actionLoading.value = false
    error.value = ''
  }

  return {
    users,
    selectedUser,
    workspaces,
    selectedWorkspace,
    operationLogs,
    loading,
    actionLoading,
    error,
    loadUsers,
    loadUser,
    setUserStatus,
    loadWorkspaces,
    loadWorkspace,
    setWorkspaceStatus,
    loadOperationLogs,
    clearAll,
  }
})
