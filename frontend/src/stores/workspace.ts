import { ref } from 'vue'
import { defineStore } from 'pinia'
import {
  archiveWorkspace,
  createInvitation,
  createWorkspace,
  getCurrentInvitation,
  getWorkspace,
  joinWorkspace,
  leaveWorkspace,
  listInvitationHistory,
  listWorkspaceMembers,
  listWorkspaces,
  removeWorkspaceMember,
  restoreWorkspace,
  revokeInvitation,
  transferWorkspaceOwnership,
  type CurrentInvitationResponseVO,
  type InvitationHistoryResponseVO,
  type WorkspaceDetailResponseVO,
  type WorkspaceMemberResponseVO,
  type WorkspaceSummaryResponseVO,
} from '@/api/workspace'

export const useWorkspaceStore = defineStore('workspace', () => {
  const workspaces = ref<WorkspaceSummaryResponseVO[]>([])
  const currentWorkspace = ref<WorkspaceDetailResponseVO | null>(null)
  const loading = ref(false)
  const errorMessage = ref('')
  const currentInvitation = ref<CurrentInvitationResponseVO | null>(null)
  const invitationHistory = ref<InvitationHistoryResponseVO[]>([])
  const invitationLoading = ref(false)
  const invitationError = ref('')
  const members = ref<WorkspaceMemberResponseVO[]>([])
  const membersLoading = ref(false)
  const membersError = ref('')
  let currentRequestVersion = 0

  async function loadWorkspaces() {
    loading.value = true
    errorMessage.value = ''
    try {
      const result = (await listWorkspaces()).data
      workspaces.value = result.data
      return result.data
    } catch (error) {
      errorMessage.value = error instanceof Error ? error.message : '空间列表加载失败'
      throw error
    } finally {
      loading.value = false
    }
  }

  async function create(name: string) {
    const result = (await createWorkspace(name)).data
    currentWorkspace.value = result.data
    await loadWorkspaces()
    return result.data
  }

  async function join(inviteCode: string) {
    const result = (await joinWorkspace(inviteCode)).data.data
    await loadWorkspaces()
    return result
  }

  async function loadCurrent(workspaceId: number) {
    const requestVersion = ++currentRequestVersion
    loading.value = true
    errorMessage.value = ''
    currentWorkspace.value = null
    try {
      const result = (await getWorkspace(workspaceId)).data
      if (requestVersion === currentRequestVersion) {
        currentWorkspace.value = result.data
      }
      return result.data
    } catch (error) {
      if (requestVersion === currentRequestVersion) {
        errorMessage.value = error instanceof Error ? error.message : '空间加载失败'
      }
      throw error
    } finally {
      if (requestVersion === currentRequestVersion) {
        loading.value = false
      }
    }
  }

  function ensureCurrent(workspaceId: number) {
    if (currentWorkspace.value?.workspaceId === workspaceId) {
      return Promise.resolve(currentWorkspace.value)
    }
    return loadCurrent(workspaceId)
  }

  function clearCurrent() {
    currentRequestVersion += 1
    currentWorkspace.value = null
    loading.value = false
    errorMessage.value = ''
    currentInvitation.value = null
    invitationHistory.value = []
    invitationError.value = ''
    members.value = []
    membersError.value = ''
  }

  async function loadInvitations(workspaceId: number) {
    invitationLoading.value = true
    invitationError.value = ''
    try {
      const [currentResult, historyResult] = await Promise.all([
        getCurrentInvitation(workspaceId),
        listInvitationHistory(workspaceId),
      ])
      currentInvitation.value = currentResult.data.data
      invitationHistory.value = historyResult.data.data
    } catch (error) {
      invitationError.value = error instanceof Error ? error.message : '邀请码加载失败'
      throw error
    } finally {
      invitationLoading.value = false
    }
  }

  async function generateInvitation(workspaceId: number) {
    const result = (
      await createInvitation(workspaceId, currentInvitation.value?.invitationId ?? null)
    ).data.data
    currentInvitation.value = result
    try {
      await loadInvitations(workspaceId)
    } catch {
      // 创建响应已经成功，保留新邀请码；历史刷新可由页面再次重试。
    }
    return result
  }

  async function stopInvitation(workspaceId: number) {
    if (!currentInvitation.value) return
    await revokeInvitation(workspaceId, currentInvitation.value.invitationId)
    currentInvitation.value = null
    try {
      await loadInvitations(workspaceId)
    } catch {
      // 停用已经成功，不让历史刷新失败把旧邀请码重新显示成可用。
    }
  }

  async function loadMembers(workspaceId: number) {
    membersLoading.value = true
    membersError.value = ''
    try {
      const result = (await listWorkspaceMembers(workspaceId)).data.data
      members.value = result
      return result
    } catch (error) {
      membersError.value = error instanceof Error ? error.message : '成员列表加载失败'
      throw error
    } finally {
      membersLoading.value = false
    }
  }

  async function leave(workspaceId: number) {
    await leaveWorkspace(workspaceId)
    clearCurrent()
    await loadWorkspaces()
  }

  async function removeMember(workspaceId: number, userId: number) {
    await removeWorkspaceMember(workspaceId, userId)
    await Promise.all([loadCurrent(workspaceId), loadMembers(workspaceId)])
  }

  async function transferOwnership(workspaceId: number, targetUserId: number) {
    const workspace = (await transferWorkspaceOwnership(workspaceId, targetUserId)).data.data
    currentWorkspace.value = workspace
    await Promise.all([loadWorkspaces(), loadMembers(workspaceId)])
    return workspace
  }

  async function archive(workspaceId: number) {
    const workspace = (await archiveWorkspace(workspaceId)).data.data
    currentWorkspace.value = workspace
    currentInvitation.value = null
    try {
      await loadWorkspaces()
    } catch {
      // 归档已经提交，列表可在返回选择页时重试，不能把刷新失败误报为归档失败。
    }
    return workspace
  }

  async function restore(workspaceId: number) {
    const workspace = (await restoreWorkspace(workspaceId)).data.data
    currentWorkspace.value = workspace
    try {
      await loadWorkspaces()
    } catch {
      // 恢复已经提交，保留后端返回的新状态。
    }
    return workspace
  }

  function clearAll() {
    currentRequestVersion += 1
    workspaces.value = []
    currentWorkspace.value = null
    currentInvitation.value = null
    invitationHistory.value = []
    errorMessage.value = ''
    invitationError.value = ''
    members.value = []
    membersError.value = ''
    loading.value = false
    invitationLoading.value = false
    membersLoading.value = false
  }

  return {
    workspaces,
    currentWorkspace,
    loading,
    errorMessage,
    currentInvitation,
    invitationHistory,
    invitationLoading,
    invitationError,
    members,
    membersLoading,
    membersError,
    loadWorkspaces,
    create,
    join,
    loadCurrent,
    ensureCurrent,
    loadInvitations,
    generateInvitation,
    stopInvitation,
    loadMembers,
    leave,
    removeMember,
    transferOwnership,
    archive,
    restore,
    clearCurrent,
    clearAll,
  }
})
