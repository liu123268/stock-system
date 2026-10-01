<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { WorkspaceMemberResponseVO } from '@/api/workspace'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import { handleWorkspaceApiError } from '@/utils/workspace-access'

type PendingAction =
  | { type: 'leave'; workspaceId: number }
  | { type: 'remove' | 'transfer'; workspaceId: number; member: WorkspaceMemberResponseVO }
const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const store = useWorkspaceStore()
const workspaceId = computed(() => Number(route.params.workspaceId))
const workspace = computed(() =>
  store.currentWorkspace?.workspaceId === workspaceId.value ? store.currentWorkspace : null,
)
const isOwner = computed(() => workspace.value?.role === 'OWNER')
const isArchived = computed(() => workspace.value?.status !== 'ACTIVE')
const pendingAction = ref<PendingAction | null>(null)
const actionLoading = ref(false)
const actionMessage = ref('')
const actionError = ref('')
const refreshWarning = ref('')
const pageLoading = ref(false)
const pageError = ref('')
const confirmation = ref<HTMLElement | null>(null)
let returnFocus: HTMLElement | null = null
let pageVersion = 0
let disposed = false
const isCurrent = (id: number, version: number) =>
  !disposed && id === workspaceId.value && version === pageVersion

async function load() {
  const id = workspaceId.value
  const version = ++pageVersion
  if (!Number.isSafeInteger(id) || id <= 0) {
    await router.replace('/workspaces')
    return
  }
  pageLoading.value = true
  pageError.value = ''
  pendingAction.value = null
  try {
    // 等两路读取都结束，避免一路失败后另一路仍改变权限或列表。
    const results = await Promise.allSettled([store.loadCurrent(id), store.loadMembers(id)])
    if (!isCurrent(id, version)) return
    const failed = results.find((result) => result.status === 'rejected')
    if (failed?.status === 'rejected') throw failed.reason
    refreshWarning.value = ''
  } catch (error) {
    if (!isCurrent(id, version)) return
    if (await handleWorkspaceApiError(error, router)) return
    pageError.value = error instanceof Error ? error.message : '成员列表加载失败'
  } finally {
    if (isCurrent(id, version)) pageLoading.value = false
  }
}
function isCurrentUser(member: WorkspaceMemberResponseVO) {
  return member.userId === authStore.currentUser?.userId
}
async function requestAction(
  type: 'leave' | 'remove' | 'transfer',
  event: Event,
  member?: WorkspaceMemberResponseVO,
) {
  if (actionLoading.value || pageLoading.value || refreshWarning.value) return
  if (type === 'leave') {
    if (!workspace.value || isOwner.value) return
    pendingAction.value = { type, workspaceId: workspaceId.value }
  } else {
    if (!isOwner.value || isArchived.value || !member || member.role !== 'MEMBER') return
    pendingAction.value = { type, member: { ...member }, workspaceId: workspaceId.value }
  }
  actionMessage.value = ''
  actionError.value = ''
  returnFocus = event.currentTarget as HTMLElement
  await nextTick()
  confirmation.value?.focus()
}
function cancelAction() {
  if (actionLoading.value) return
  pendingAction.value = null
  returnFocus?.focus()
}
const confirmationText = computed(() => {
  const action = pendingAction.value
  if (!action) return ''
  if (action.type === 'leave') return '退出后将立即失去访问权，需要有效邀请码才能重新加入。'
  if (action.type === 'remove')
    return `移除 ${action.member.nickname} 后，对方只能使用移除后新生成的邀请码重新加入。`
  return `所有权将立即转让给 ${action.member.nickname}，你会变为普通成员。`
})
const confirmationLabel = computed(() =>
  pendingAction.value?.type === 'leave'
    ? '确认退出空间'
    : pendingAction.value?.type === 'remove'
      ? '确认移除成员'
      : '确认转让所有权',
)
async function confirmAction() {
  const action = pendingAction.value
  const version = pageVersion
  if (!action || actionLoading.value || !isCurrent(action.workspaceId, version)) return
  actionLoading.value = true
  actionError.value = ''
  try {
    if (action.type === 'leave') {
      await store.leave(action.workspaceId)
      if (!isCurrent(action.workspaceId, version)) return
      await router.replace({ path: '/workspaces', query: { reason: 'left' } })
      return
    }
    if (action.type === 'remove') await store.removeMember(action.workspaceId, action.member.userId)
    else await store.transferOwnership(action.workspaceId, action.member.userId)
    if (!isCurrent(action.workspaceId, version)) return
    actionMessage.value =
      action.type === 'remove'
        ? `${action.member.nickname} 已被移出空间`
        : `所有权已转让给 ${action.member.nickname}`
    pendingAction.value = null
    if (store.errorMessage || store.membersError)
      refreshWarning.value = '操作已完成，但页面刷新失败。请重新读取，勿重复提交。'
  } catch (error) {
    if (!isCurrent(action.workspaceId, version)) return
    if (await handleWorkspaceApiError(error, router)) return
    actionError.value = error instanceof Error ? error.message : '成员操作失败'
  } finally {
    if (isCurrent(action.workspaceId, version)) actionLoading.value = false
  }
}
watch(
  workspaceId,
  () => {
    actionMessage.value = ''
    actionError.value = ''
    refreshWarning.value = ''
    actionLoading.value = false
    void load()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  disposed = true
  pageVersion += 1
  store.invalidateManagement()
})
</script>
<template>
  <section v-if="pageLoading" class="content-state" aria-live="polite">
    <div class="loading-line"></div>
    <div class="loading-line short"></div>
    <p>正在读取成员关系</p>
  </section>
  <section v-else-if="pageError" class="content-state" role="alert">
    <h1>成员列表暂时没有加载出来</h1>
    <p>{{ pageError }}</p>
    <p v-if="actionMessage" class="feedback">{{ actionMessage }}</p>
    <button class="ui-button primary" type="button" @click="load">重新加载</button>
  </section>
  <template v-else-if="workspace">
    <section class="members-heading management-heading">
      <p class="section-label">( <span>03</span> — 空间成员 )</p>
      <h1>{{ workspace.name }}</h1>
      <p>当前共有 {{ store.members.length }} 名有效成员。邀请码由 OWNER 在概览页管理。</p>
    </section>
    <section v-if="isArchived" class="archive-notice" aria-live="polite">
      <strong>已归档空间只保留读取和普通成员主动退出。</strong
      ><span>移除成员与转让所有权需先由有权限的人恢复空间。</span>
    </section>
    <section
      v-if="pendingAction"
      ref="confirmation"
      class="confirmation"
      tabindex="-1"
      aria-labelledby="member-confirm-title"
    >
      <div>
        <h2 id="member-confirm-title">{{ confirmationLabel }}？</h2>
        <p>{{ confirmationText }}</p>
      </div>
      <div class="confirmation-actions">
        <button class="ui-button" type="button" :disabled="actionLoading" @click="cancelAction">
          取消</button
        ><button
          class="ui-button danger-action"
          type="button"
          :disabled="actionLoading"
          @click="confirmAction"
        >
          {{ actionLoading ? '正在处理…' : confirmationLabel }}
        </button>
      </div>
    </section>
    <p v-if="actionError" class="feedback error" role="alert">{{ actionError }}</p>
    <p v-if="actionMessage" class="feedback" role="status">{{ actionMessage }}</p>
    <div v-if="refreshWarning" class="refresh-warning" role="alert">
      <p>{{ refreshWarning }}</p>
      <button class="ui-button" type="button" @click="load">重新读取</button>
    </div>
    <section class="member-list" aria-label="有效成员列表">
      <article
        v-for="member in store.members"
        :key="member.userId"
        class="member-row"
        :data-member-id="member.userId"
      >
        <div class="member-identity">
          <span class="avatar" aria-hidden="true">{{ [...member.nickname][0] }}</span>
          <div>
            <h2>
              {{ member.nickname
              }}<small v-if="isCurrentUser(member)">你 · {{ member.role }}</small>
            </h2>
            <p>@{{ member.username }} · {{ member.role }}</p>
          </div>
        </div>
        <time :datetime="member.lastJoinedAt">最近加入 {{ member.lastJoinedAt.slice(0, 10) }}</time>
        <div v-if="isOwner && !isArchived && member.role === 'MEMBER'" class="member-actions">
          <button
            class="ui-button"
            type="button"
            :disabled="actionLoading || !!refreshWarning"
            @click="requestAction('transfer', $event, member)"
          >
            转让所有权</button
          ><button
            class="ui-button danger-action"
            type="button"
            :disabled="actionLoading || !!refreshWarning"
            @click="requestAction('remove', $event, member)"
          >
            移除成员
          </button>
        </div>
        <span v-else class="member-status">{{
          member.role === 'OWNER' ? '空间所有者' : '有效成员'
        }}</span>
      </article>
    </section>
    <section class="leave-section setting-block">
      <div>
        <h2>想离开这个空间？</h2>
        <p>退出后立即失去访问权，重新加入需要有效邀请码。OWNER 需先转让所有权，再退出空间。</p>
      </div>
      <button
        class="ui-button danger-action"
        type="button"
        :disabled="isOwner || actionLoading || !!refreshWarning"
        :title="isOwner ? 'OWNER 需先转让所有权' : undefined"
        @click="requestAction('leave', $event)"
      >
        退出这个空间
      </button>
    </section>
  </template>
</template>
<style scoped src="@/styles/workspace-management.css"></style>
<style scoped>
.member-list {
  margin-top: 44px;
  border-top: 1px solid var(--color-ink);
}
.member-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(130px, 0.6fr) auto;
  align-items: center;
  gap: 22px;
  padding: 22px 0;
  border-bottom: 1px solid var(--color-border);
}
.member-identity {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 14px;
}
.member-identity > div {
  min-width: 0;
}
.avatar {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  place-items: center;
  border-radius: 50%;
  background: var(--color-surface-secondary);
  font-size: 15px;
  font-weight: 700;
}
.member-identity h2 {
  font-family: inherit;
  font-size: 15px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.member-identity h2 small {
  display: inline-block;
  margin-left: 8px;
  color: var(--color-focus);
  font: 10px var(--font-mono);
}
.member-identity p,
.member-row time,
.member-status {
  font: 11px/1.8 var(--font-mono);
  color: var(--color-muted);
  overflow-wrap: anywhere;
}
.member-identity p {
  margin-top: 4px;
}
.member-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.member-actions button {
  padding-inline: 14px;
  font-size: 11px;
}
.leave-section {
  margin-top: 52px;
  border-top: 0;
}
@media (max-width: 1100px) {
  .member-row {
    grid-template-columns: minmax(0, 1fr) auto;
  }
  .member-row time {
    grid-column: 1;
  }
  .member-actions,
  .member-status {
    grid-column: 2;
    grid-row: 1 / span 2;
  }
}
@media (max-width: 700px) {
  .member-row {
    grid-template-columns: minmax(0, 1fr);
    gap: 10px;
  }
  .member-actions,
  .member-status,
  .member-row time {
    grid-column: 1;
    grid-row: auto;
  }
}
</style>
