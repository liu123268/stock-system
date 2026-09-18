<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { WorkspaceMemberResponseVO } from '@/api/workspace'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import { handleWorkspaceApiError } from '@/utils/workspace-access'

type PendingAction =
  | { type: 'leave' }
  | { type: 'remove'; member: WorkspaceMemberResponseVO }
  | { type: 'transfer'; member: WorkspaceMemberResponseVO }
  | null

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const store = useWorkspaceStore()
const workspaceId = computed(() => Number(route.params.workspaceId))
const isOwner = computed(() => store.currentWorkspace?.role === 'OWNER')
const isArchived = computed(() => store.currentWorkspace?.status !== 'ACTIVE')
const pendingAction = ref<PendingAction>(null)
const actionLoading = ref(false)
const actionMessage = ref('')
const actionError = ref('')

async function load() {
  if (!Number.isSafeInteger(workspaceId.value) || workspaceId.value <= 0) {
    await router.replace('/workspaces')
    return
  }
  try {
    // 成员角色可能被另一会话改变，治理动作不能沿用同 ID 空间的旧角色。
    await Promise.all([store.loadCurrent(workspaceId.value), store.loadMembers(workspaceId.value)])
  } catch (error) {
    await handleWorkspaceApiError(error, router)
  }
}

function isCurrentUser(member: WorkspaceMemberResponseVO) {
  return member.userId === authStore.currentUser?.userId
}

function requestAction(action: PendingAction) {
  actionMessage.value = ''
  actionError.value = ''
  pendingAction.value = action
}

function cancelAction() {
  pendingAction.value = null
}

function confirmationText() {
  const action = pendingAction.value
  if (!action) return ''
  if (action.type === 'leave') return '退出后将立即失去访问权，需要有效邀请码才能重新加入。'
  if (action.type === 'remove') {
    return `移除 ${action.member.nickname} 后，对方只能使用移除后新生成的邀请码重新加入。`
  }
  return `所有权将立即转让给 ${action.member.nickname}，你会变为普通成员。`
}

function confirmationLabel() {
  const type = pendingAction.value?.type
  if (type === 'leave') return '确认退出空间'
  if (type === 'remove') return '确认移除成员'
  return '确认转让所有权'
}

async function confirmAction() {
  const action = pendingAction.value
  if (!action) return
  actionLoading.value = true
  actionError.value = ''
  try {
    if (action.type === 'leave') {
      await store.leave(workspaceId.value)
      await router.replace({ path: '/workspaces', query: { reason: 'left' } })
      return
    }
    if (action.type === 'remove') {
      await store.removeMember(workspaceId.value, action.member.userId)
      actionMessage.value = `${action.member.nickname} 已被移出空间`
    } else {
      await store.transferOwnership(workspaceId.value, action.member.userId)
      actionMessage.value = `所有权已转让给 ${action.member.nickname}`
    }
    pendingAction.value = null
  } catch (error) {
    if (await handleWorkspaceApiError(error, router)) return
    actionError.value = error instanceof Error ? error.message : '成员操作失败'
  } finally {
    actionLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section v-if="store.loading || store.membersLoading" class="content-state" aria-live="polite">
    <div class="loading-line"></div>
    <div class="loading-line short"></div>
    <p>正在读取成员关系</p>
  </section>

  <section v-else-if="store.errorMessage || store.membersError" class="content-state" role="alert">
    <h1>成员列表暂时没有加载出来</h1>
    <p>{{ store.errorMessage || store.membersError }}</p>
    <button class="primary" type="button" @click="load">重新加载</button>
  </section>

  <template v-else-if="store.currentWorkspace">
    <section v-if="isArchived" class="archive-notice" aria-live="polite">
      <strong>已归档空间只保留读取和普通成员主动退出。</strong>
      <span>移除成员与转让所有权需先由有权限的人恢复空间。</span>
    </section>

    <section class="members-heading">
      <div>
        <p class="section-label">空间成员</p>
        <h1>{{ store.currentWorkspace.name }}</h1>
        <p>当前共有 {{ store.members.length }} 名有效成员。</p>
      </div>
      <button
        v-if="!isOwner"
        class="danger-action"
        type="button"
        @click="requestAction({ type: 'leave' })"
      >
        退出这个空间
      </button>
    </section>

    <section v-if="pendingAction" class="confirmation" aria-live="polite">
      <p>{{ confirmationText() }}</p>
      <div>
        <button type="button" :disabled="actionLoading" @click="cancelAction">取消</button>
        <button
          class="danger-action"
          type="button"
          :disabled="actionLoading"
          @click="confirmAction"
        >
          {{ actionLoading ? '正在处理…' : confirmationLabel() }}
        </button>
      </div>
    </section>

    <p v-if="actionError" class="feedback error" role="alert">{{ actionError }}</p>
    <p v-if="actionMessage" class="feedback" aria-live="polite">{{ actionMessage }}</p>

    <section class="member-list" aria-label="有效成员列表">
      <article v-for="member in store.members" :key="member.userId" class="member-row">
        <div class="member-identity">
          <span class="avatar" aria-hidden="true">{{ member.nickname.slice(0, 1) }}</span>
          <div>
            <h2>
              {{ member.nickname }}
              <small v-if="isCurrentUser(member)">你</small>
            </h2>
            <p>@{{ member.username }} · {{ member.role }}</p>
          </div>
        </div>
        <time :datetime="member.lastJoinedAt"
          >最近加入 {{ new Date(member.lastJoinedAt).toLocaleDateString('zh-CN') }}</time
        >
        <div v-if="isOwner && !isArchived && member.role === 'MEMBER'" class="member-actions">
          <button type="button" @click="requestAction({ type: 'transfer', member })">
            转让所有权
          </button>
          <button
            class="danger-link"
            type="button"
            @click="requestAction({ type: 'remove', member })"
          >
            移除成员
          </button>
        </div>
        <span v-else class="member-status">有效成员</span>
      </article>
    </section>
  </template>
</template>

<style scoped>
.member-row time,
.member-status {
  color: var(--color-muted);
  font-size: 12px;
}
.members-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 32px;
  padding-top: clamp(64px, 10vh, 104px);
}
.archive-notice {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 10px 24px;
  margin-top: 36px;
  padding: 14px 0;
  border-block: 1px solid var(--color-border);
  color: var(--color-muted);
  font-size: 13px;
}
.archive-notice strong {
  color: var(--color-ink);
}
.section-label {
  color: var(--color-focus);
  font-size: 12px;
  font-weight: 700;
}
.members-heading h1,
.content-state h1 {
  margin-top: 14px;
  font-size: 38px;
  letter-spacing: -0.03em;
  text-wrap: balance;
}
.members-heading p:last-child,
.content-state p {
  margin-top: 14px;
  color: var(--color-muted);
}
button {
  min-height: 40px;
  padding: 0 15px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-action);
  background: transparent;
  color: var(--color-ink);
  cursor: pointer;
}
button:focus-visible,
a:focus-visible {
  outline: 3px solid color-mix(in oklab, var(--color-focus) 35%, transparent);
  outline-offset: 2px;
}
button:disabled {
  cursor: wait;
  opacity: 0.55;
}
.danger-action {
  border-color: var(--color-danger);
  color: var(--color-danger);
}
.confirmation {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  max-width: 920px;
  margin-top: 40px;
  padding: 18px 0;
  border-block: 1px solid var(--color-danger);
}
.confirmation p {
  max-width: 65ch;
  line-height: 1.7;
}
.confirmation div {
  display: flex;
  flex-shrink: 0;
  gap: 10px;
}
.feedback {
  max-width: 920px;
  margin-top: 24px;
  color: var(--color-focus);
}
.feedback.error {
  color: var(--color-danger);
}
.member-list {
  max-width: 920px;
  margin-top: 50px;
  border-top: 1px solid var(--color-ink);
}
.member-row {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(150px, 0.65fr) auto;
  align-items: center;
  gap: 24px;
  padding: 20px 0;
  border-bottom: 1px solid var(--color-border);
}
.member-identity {
  display: flex;
  align-items: center;
  gap: 14px;
}
.avatar {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  place-items: center;
  border-radius: 50%;
  background: color-mix(in oklab, var(--color-focus) 10%, transparent);
  font-weight: 700;
}
.member-identity h2 {
  font-size: 15px;
}
.member-identity h2 small {
  margin-left: 7px;
  color: var(--color-focus);
  font-size: 11px;
}
.member-identity p {
  margin-top: 5px;
  color: var(--color-muted);
  font-size: 12px;
}
.member-actions {
  display: flex;
  gap: 8px;
}
.danger-link {
  color: var(--color-danger);
}
.content-state {
  max-width: 720px;
  padding-top: 100px;
}
.content-state .primary {
  margin-top: 24px;
  border-color: var(--color-ink);
  background: var(--color-ink);
  color: white;
}
.loading-line {
  width: min(540px, 80%);
  height: 22px;
  margin-bottom: 14px;
  background: var(--color-border);
  animation: pulse 1.4s ease-in-out infinite alternate;
}
.loading-line.short {
  width: min(340px, 52%);
}
@keyframes pulse {
  to {
    opacity: 0.4;
  }
}
@media (prefers-reduced-motion: reduce) {
  .loading-line {
    animation: none;
  }
}
@media (max-width: 820px) {
  .members-heading {
    align-items: flex-start;
    flex-direction: column;
    padding-top: 52px;
  }
  .confirmation {
    align-items: stretch;
    flex-direction: column;
  }
  .confirmation div {
    flex-wrap: wrap;
  }
  .member-row {
    grid-template-columns: 1fr;
    gap: 12px;
  }
  .member-actions {
    flex-wrap: wrap;
  }
}
</style>
