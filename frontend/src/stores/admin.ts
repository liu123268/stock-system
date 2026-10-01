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
  let lifetime = 0
  const versions = { users: 0, user: 0, workspaces: 0, workspace: 0, logs: 0 }
  const reads = new Set<keyof typeof versions>()

  async function read<T>(
    key: keyof typeof versions,
    operation: () => Promise<T>,
    apply: (value: T) => void,
  ) {
    const scope = lifetime
    const version = ++versions[key]
    const current = () => scope === lifetime && version === versions[key]
    reads.add(key)
    loading.value = true
    error.value = ''
    try {
      const value = await operation()
      if (!current()) return null
      apply(value)
      return value
    } catch (caught) {
      if (!current()) return null
      error.value = caught instanceof Error ? caught.message : '加载失败，请稍后重试'
      throw caught
    } finally {
      if (current()) {
        reads.delete(key)
        loading.value = reads.size > 0
      }
    }
  }

  function clearSelection(kind: 'user' | 'workspace') {
    versions[kind] += 1
    reads.delete(kind)
    loading.value = reads.size > 0
    if (kind === 'user') selectedUser.value = null
    else selectedWorkspace.value = null
  }
  function loadUsers(query: AdminListQuery = {}) {
    return read(
      'users',
      async () => (await getAdminUsers(query)).data.data,
      (value) => {
        users.value = value
      },
    )
  }
  function loadUser(userId: number) {
    selectedUser.value = null
    return read(
      'user',
      async () => (await getAdminUser(userId)).data.data,
      (value) => {
        selectedUser.value = value
      },
    )
  }
  function loadWorkspaces(query: AdminListQuery = {}) {
    return read(
      'workspaces',
      async () => (await getAdminWorkspaces(query)).data.data,
      (value) => {
        workspaces.value = value
      },
    )
  }
  function loadWorkspace(workspaceId: number) {
    selectedWorkspace.value = null
    return read(
      'workspace',
      async () => (await getAdminWorkspace(workspaceId)).data.data,
      (value) => {
        selectedWorkspace.value = value
      },
    )
  }
  function loadOperationLogs(
    query: { page?: number; size?: number; action?: string; result?: string } = {},
  ) {
    return read(
      'logs',
      async () => (await getAdminOperationLogs(query)).data.data,
      (value) => {
        operationLogs.value = value
      },
    )
  }

  async function write<T>(operation: () => Promise<T>, apply: (value: T) => void) {
    if (actionLoading.value) return null
    const scope = lifetime
    actionLoading.value = true
    error.value = ''
    try {
      const value = await operation()
      if (scope !== lifetime) return null
      apply(value)
      return value
    } catch (caught) {
      if (scope !== lifetime) return null
      error.value = caught instanceof Error ? caught.message : '操作失败，请稍后重试'
      throw caught
    } finally {
      if (scope === lifetime) actionLoading.value = false
    }
  }
  function setUserStatus(userId: number, status: 'ACTIVE' | 'DISABLED') {
    if (actionLoading.value) return Promise.resolve(null)
    versions.users += 1
    reads.delete('users')
    versions.user += 1
    reads.delete('user')
    loading.value = reads.size > 0
    const selection = versions.user
    return write(
      async () =>
        (await (status === 'DISABLED' ? disableAdminUser(userId) : restoreAdminUser(userId))).data
          .data,
      (value) => {
        // 写入更新对应列表行；新选中的另一目标不得被旧写入改成详情。
        if (
          selection === versions.user &&
          (!selectedUser.value || selectedUser.value.userId === userId)
        )
          selectedUser.value = value
        users.value.items = users.value.items.map((item) => (item.userId === userId ? value : item))
      },
    )
  }
  function setWorkspaceStatus(workspaceId: number, status: 'ACTIVE' | 'ARCHIVED_BY_SUPER_ADMIN') {
    if (actionLoading.value) return Promise.resolve(null)
    versions.workspaces += 1
    reads.delete('workspaces')
    versions.workspace += 1
    reads.delete('workspace')
    loading.value = reads.size > 0
    const selection = versions.workspace
    return write(
      async () =>
        (
          await (status === 'ARCHIVED_BY_SUPER_ADMIN'
            ? archiveAdminWorkspace(workspaceId)
            : restoreAdminWorkspace(workspaceId))
        ).data.data,
      (value) => {
        if (
          selection === versions.workspace &&
          (!selectedWorkspace.value || selectedWorkspace.value.workspaceId === workspaceId)
        )
          selectedWorkspace.value = value
        workspaces.value.items = workspaces.value.items.map((item) =>
          item.workspaceId === workspaceId ? value : item,
        )
      },
    )
  }

  function clearAll() {
    lifetime += 1
    for (const key of Object.keys(versions) as (keyof typeof versions)[]) versions[key] += 1
    reads.clear()
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
    loadWorkspaces,
    loadWorkspace,
    loadOperationLogs,
    setUserStatus,
    setWorkspaceStatus,
    clearSelection,
    clearAll,
  }
})
