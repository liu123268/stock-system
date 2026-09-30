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
  renameWorkspace,
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
  let listRequestVersion = 0
  let invitationRequestVersion = 0
  let invitationMutationVersion = 0
  let membersRequestVersion = 0
  let managementVersion = 0
  let sessionVersion = 0

  async function loadWorkspaces() {
    const requestVersion = ++listRequestVersion
    loading.value = true
    errorMessage.value = ''
    try {
      const result = (await listWorkspaces()).data
      if (requestVersion === listRequestVersion) workspaces.value = result.data
      return result.data
    } catch (error) {
      if (requestVersion === listRequestVersion) {
        errorMessage.value = error instanceof Error ? error.message : '空间列表加载失败'
      }
      throw error
    } finally {
      if (requestVersion === listRequestVersion) loading.value = false
    }
  }

  async function create(name: string) {
    const requestSession = sessionVersion
    const result = (await createWorkspace(name)).data
    if (requestSession !== sessionVersion) return result.data
    currentWorkspace.value = result.data
    try {
      await loadWorkspaces()
    } catch {
      // 写入已成功，继续进入后端返回的空间，不让列表刷新失败诱发重复创建。
    }
    return result.data
  }

  async function join(inviteCode: string) {
    const requestSession = sessionVersion
    const result = (await joinWorkspace(inviteCode)).data.data
    if (requestSession !== sessionVersion) return result
    try {
      await loadWorkspaces()
    } catch {
      // 成员关系已保存，继续进入已加入空间；不重复提交邀请码。
    }
    return result
  }

  async function loadCurrent(workspaceId: number, preserveCurrent = false) {
    const requestVersion = ++currentRequestVersion
    loading.value = true
    errorMessage.value = ''
    if (!preserveCurrent || currentWorkspace.value?.workspaceId !== workspaceId)
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
    managementVersion += 1
    currentRequestVersion += 1
    clearInvitations()
    membersRequestVersion += 1
    currentWorkspace.value = null
    loading.value = false
    errorMessage.value = ''
    members.value = []
    membersError.value = ''
    membersLoading.value = false
  }

  function clearInvitations() {
    invitationRequestVersion += 1
    invitationMutationVersion += 1
    currentInvitation.value = null
    invitationHistory.value = []
    invitationError.value = ''
    invitationLoading.value = false
  }

  async function loadInvitations(workspaceId: number) {
    const requestVersion = ++invitationRequestVersion
    invitationLoading.value = true
    invitationError.value = ''
    try {
      const [currentResult, historyResult] = await Promise.allSettled([
        getCurrentInvitation(workspaceId),
        listInvitationHistory(workspaceId),
      ])
      if (requestVersion === invitationRequestVersion) {
        if (currentResult.status === 'fulfilled')
          currentInvitation.value = currentResult.value.data.data
        if (historyResult.status === 'fulfilled')
          invitationHistory.value = historyResult.value.data.data
      }
      if (currentResult.status === 'rejected') throw currentResult.reason
      if (historyResult.status === 'rejected') throw historyResult.reason
    } catch (error) {
      if (requestVersion === invitationRequestVersion) {
        invitationError.value = error instanceof Error ? error.message : '邀请码加载失败'
      }
      throw error
    } finally {
      if (requestVersion === invitationRequestVersion) invitationLoading.value = false
    }
  }

  async function generateInvitation(workspaceId: number) {
    const version = ++invitationMutationVersion
    invitationRequestVersion += 1
    const result = (
      await createInvitation(workspaceId, currentInvitation.value?.invitationId ?? null)
    ).data.data
    if (version !== invitationMutationVersion) return result
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
    const version = ++invitationMutationVersion
    invitationRequestVersion += 1
    await revokeInvitation(workspaceId, currentInvitation.value.invitationId)
    if (version !== invitationMutationVersion) return
    currentInvitation.value = null
    try {
      await loadInvitations(workspaceId)
    } catch {
      // 停用已经成功，不让历史刷新失败把旧邀请码重新显示成可用。
    }
  }

  async function loadMembers(workspaceId: number) {
    const requestVersion = ++membersRequestVersion
    membersLoading.value = true
    membersError.value = ''
    try {
      const result = (await listWorkspaceMembers(workspaceId)).data.data
      if (requestVersion === membersRequestVersion) members.value = result
      return result
    } catch (error) {
      if (requestVersion === membersRequestVersion) {
        membersError.value = error instanceof Error ? error.message : '成员列表加载失败'
      }
      throw error
    } finally {
      if (requestVersion === membersRequestVersion) membersLoading.value = false
    }
  }

  async function leave(workspaceId: number) {
    const version = beginManagement()
    await leaveWorkspace(workspaceId)
    if (version !== managementVersion) return
    clearCurrent()
    try {
      await loadWorkspaces()
    } catch {
      // 成员关系已退出，仍返回选择页；列表重试只读取，不再次退出。
    }
  }

  async function removeMember(workspaceId: number, userId: number) {
    const version = beginManagement()
    await removeWorkspaceMember(workspaceId, userId)
    if (version !== managementVersion) return
    const wasPresent = members.value.some((member) => member.userId === userId)
    members.value = members.value.filter((member) => member.userId !== userId)
    if (wasPresent && currentWorkspace.value?.workspaceId === workspaceId) {
      currentWorkspace.value = {
        ...currentWorkspace.value,
        memberCount: Math.max(1, currentWorkspace.value.memberCount - 1),
      }
      updateSummary(currentWorkspace.value)
    }
    // DELETE 已成功；读取失败不能向页面抛成移除失败。
    await Promise.allSettled([loadCurrent(workspaceId, true), loadMembers(workspaceId)])
  }

  async function transferOwnership(workspaceId: number, targetUserId: number) {
    const version = beginManagement()
    const workspace = (await transferWorkspaceOwnership(workspaceId, targetUserId)).data.data
    if (version !== managementVersion) return workspace
    currentWorkspace.value = workspace
    updateSummary(workspace)
    members.value = members.value.map((member) => ({
      ...member,
      role: member.userId === targetUserId ? 'OWNER' : 'MEMBER',
    }))
    clearInvitations()
    await Promise.allSettled([loadWorkspaces(), loadMembers(workspaceId)])
    return workspace
  }

  async function archive(workspaceId: number) {
    const version = beginManagement()
    const workspace = (await archiveWorkspace(workspaceId)).data.data
    if (version !== managementVersion) return workspace
    currentWorkspace.value = workspace
    updateSummary(workspace)
    clearInvitations()
    try {
      await loadWorkspaces()
    } catch {
      // 归档已经提交，列表可在返回选择页时重试，不能把刷新失败误报为归档失败。
    }
    return workspace
  }

  async function rename(workspaceId: number, name: string) {
    const version = beginManagement()
    const workspace = (await renameWorkspace(workspaceId, name)).data.data
    if (version !== managementVersion) return workspace
    if (currentWorkspace.value?.workspaceId === workspaceId) {
      currentWorkspace.value = workspace
    }
    updateSummary(workspace)
    return workspace
  }

  async function restore(workspaceId: number) {
    const version = beginManagement()
    const workspace = (await restoreWorkspace(workspaceId)).data.data
    if (version !== managementVersion) return workspace
    currentWorkspace.value = workspace
    updateSummary(workspace)
    try {
      await loadWorkspaces()
    } catch {
      // 恢复已经提交，保留后端返回的新状态。
    }
    return workspace
  }

  function clearAll() {
    managementVersion += 1
    sessionVersion += 1
    listRequestVersion += 1
    clearInvitations()
    membersRequestVersion += 1
    currentRequestVersion += 1
    workspaces.value = []
    currentWorkspace.value = null
    errorMessage.value = ''
    members.value = []
    membersError.value = ''
    loading.value = false
    membersLoading.value = false
  }

  function updateSummary(workspace: WorkspaceDetailResponseVO) {
    workspaces.value = workspaces.value.map((item) =>
      item.workspaceId === workspace.workspaceId ? { ...item, ...workspace } : item,
    )
  }

  // 同空间切成员／设置页也会卸载：旧治理响应及其刷新不得进入新页面。
  function invalidateManagement() {
    beginManagement()
  }

  function beginManagement() {
    managementVersion += 1
    currentRequestVersion += 1
    membersRequestVersion += 1
    listRequestVersion += 1
    loading.value = false
    membersLoading.value = false
    return managementVersion
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
    rename,
    restore,
    clearCurrent,
    clearInvitations,
    clearAll,
    invalidateManagement,
  }
})
