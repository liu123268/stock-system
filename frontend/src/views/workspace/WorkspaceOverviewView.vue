<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useWorkspaceStore } from '@/stores/workspace'
import { handleWorkspaceApiError } from '@/utils/workspace-access'

const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const workspaceId = computed(() => Number(route.params.workspaceId))
const isArchived = computed(() => store.currentWorkspace?.status !== 'ACTIVE')
const invitationActionLoading = ref(false)
const invitationMessage = ref('')

async function load() {
  if (!Number.isSafeInteger(workspaceId.value) || workspaceId.value <= 0) {
    await router.replace('/workspaces')
    return
  }
  try {
    const workspace = await store.ensureCurrent(workspaceId.value)
    if (workspace.role === 'OWNER' && workspace.status === 'ACTIVE') {
      await store.loadInvitations(workspaceId.value)
    }
  } catch (error) {
    await handleWorkspaceApiError(error, router)
  }
}

async function generateInvitation() {
  invitationMessage.value = ''
  invitationActionLoading.value = true
  try {
    await store.generateInvitation(workspaceId.value)
    invitationMessage.value = '新的邀请码已经生成'
  } catch (error) {
    if (await handleWorkspaceApiError(error, router)) return
    invitationMessage.value = error instanceof Error ? error.message : '邀请码生成失败'
  } finally {
    invitationActionLoading.value = false
  }
}

async function stopInvitation() {
  invitationMessage.value = ''
  invitationActionLoading.value = true
  try {
    await store.stopInvitation(workspaceId.value)
    invitationMessage.value = '邀请码已经停用'
  } catch (error) {
    if (await handleWorkspaceApiError(error, router)) return
    invitationMessage.value = error instanceof Error ? error.message : '邀请码停用失败'
  } finally {
    invitationActionLoading.value = false
  }
}

async function copyInvitation() {
  const code = store.currentInvitation?.inviteCode
  if (!code) return
  try {
    await navigator.clipboard.writeText(code)
    invitationMessage.value = '邀请码已复制'
  } catch {
    invitationMessage.value = `请手动复制：${code}`
  }
}

function invitationStatus(status: string) {
  return {
    ACTIVE: '使用中',
    REVOKED: '已停用',
    EXPIRED: '已过期',
    EXHAUSTED: '次数已用完',
  }[status]
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

onMounted(load)
</script>

<template>
  <section v-if="store.loading" class="content-state" aria-live="polite">
    <div class="loading-line"></div>
    <div class="loading-line short"></div>
    <p>正在确认空间和成员关系</p>
  </section>

  <section v-else-if="store.errorMessage" class="content-state" role="alert">
    <p class="section-label">加载失败</p>
    <h1>暂时无法进入这个空间。</h1>
    <p>{{ store.errorMessage }}</p>
    <div class="state-actions">
      <button class="primary" type="button" @click="load">重新加载</button>
      <button type="button" @click="router.push('/workspaces')">选择其他空间</button>
    </div>
  </section>

  <template v-else-if="store.currentWorkspace">
    <section v-if="isArchived" class="archive-notice" aria-live="polite">
      <strong>这个空间已归档，当前为只读状态。</strong>
      <span>历史和成员仍可查看；恢复权限取决于归档来源。</span>
    </section>

    <section id="overview" class="overview-heading">
      <div>
        <p class="section-label">空间概览</p>
        <h1>{{ store.currentWorkspace.name }}</h1>
      </div>
      <dl>
        <div>
          <dt>你的身份</dt>
          <dd>{{ store.currentWorkspace.role }}</dd>
        </div>
        <div>
          <dt>有效成员</dt>
          <dd>{{ store.currentWorkspace.memberCount }} 人</dd>
        </div>
        <div>
          <dt>空间状态</dt>
          <dd>{{ store.currentWorkspace.status === 'ACTIVE' ? '正常使用' : '已归档' }}</dd>
        </div>
      </dl>
    </section>

    <section
      v-if="store.currentWorkspace.role === 'OWNER' && !isArchived"
      class="invitation-section"
    >
      <div class="invitation-heading">
        <div>
          <p class="section-label">成员邀请</p>
          <h2>邀请成员进入这个空间。</h2>
        </div>
        <button
          class="primary"
          type="button"
          :disabled="invitationActionLoading || store.invitationLoading"
          @click="generateInvitation"
        >
          {{ store.currentInvitation ? '重新生成邀请码' : '生成邀请码' }}
        </button>
      </div>

      <p v-if="store.invitationLoading" class="invitation-state" aria-live="polite">
        正在读取邀请码
      </p>
      <div v-else-if="store.currentInvitation" class="current-invitation">
        <div>
          <span>当前邀请码</span>
          <strong>{{ store.currentInvitation.inviteCode }}</strong>
        </div>
        <dl>
          <div>
            <dt>状态</dt>
            <dd>{{ invitationStatus(store.currentInvitation.status) }}</dd>
          </div>
          <div>
            <dt>剩余次数</dt>
            <dd>{{ store.currentInvitation.remainingUses }} / 5</dd>
          </div>
          <div>
            <dt>有效期至</dt>
            <dd>{{ formatTime(store.currentInvitation.expiresAt) }}</dd>
          </div>
        </dl>
        <div class="invitation-actions">
          <button type="button" @click="copyInvitation">复制邀请码</button>
          <button type="button" :disabled="invitationActionLoading" @click="stopInvitation">
            停用当前邀请码
          </button>
        </div>
      </div>
      <p v-else class="invitation-state">当前没有邀请码。生成后可供最多 5 名成员加入。</p>

      <p v-if="store.invitationError" class="invitation-error" role="alert">
        {{ store.invitationError }}
      </p>
      <p v-if="invitationMessage" class="invitation-message" aria-live="polite">
        {{ invitationMessage }}
      </p>

      <div v-if="store.invitationHistory.length" class="invitation-history">
        <h3>生成历史</h3>
        <div v-for="item in store.invitationHistory" :key="item.invitationId">
          <span>{{ formatTime(item.createdAt) }}</span>
          <span>{{ invitationStatus(item.status) }}</span>
          <span>已使用 {{ item.usedCount }} 次</span>
        </div>
      </div>
    </section>

    <section v-else-if="isArchived" class="next-capability">
      <p class="section-label">归档边界</p>
      <h2>邀请和研究写入已经暂停。</h2>
      <p>前往设置查看归档来源。普通成员仍可在成员页主动退出这个空间。</p>
    </section>

    <section v-else class="next-capability">
      <p class="section-label">成员权限</p>
      <h2>邀请码由空间 OWNER 管理。</h2>
      <p>你可以参与空间研究，但不能生成、查看或停用完整邀请码。</p>
    </section>
  </template>
</template>

<style scoped>
.overview-heading {
  padding-top: clamp(64px, 10vh, 110px);
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
.overview-heading h1,
.content-state h1 {
  margin-top: 16px;
  font-size: clamp(42px, 6vw, 76px);
  font-weight: 560;
  letter-spacing: -0.038em;
  line-height: 1.05;
  text-wrap: balance;
}
.overview-heading dl {
  display: flex;
  flex-wrap: wrap;
  gap: 1px;
  margin: 54px 0 0;
  background: var(--color-border);
}
.overview-heading dl div {
  min-width: 180px;
  flex: 1;
  padding: 20px;
  background: var(--color-canvas);
}
.overview-heading dt {
  color: var(--color-muted);
  font-size: 12px;
}
.overview-heading dd {
  margin: 12px 0 0;
  font-size: 18px;
  font-weight: 650;
}
.next-capability {
  max-width: 660px;
  margin-top: 72px;
  padding-top: 26px;
  border-top: 1px solid var(--color-ink);
}
.invitation-section {
  max-width: 820px;
  margin-top: 72px;
  padding-top: 26px;
  border-top: 1px solid var(--color-ink);
}
.invitation-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
}
.invitation-heading h2 {
  margin-top: 14px;
  font-size: 28px;
  text-wrap: balance;
}
.invitation-heading button,
.invitation-actions button {
  min-height: 42px;
  padding: 0 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-action);
  background: transparent;
  color: var(--color-ink);
  cursor: pointer;
}
.invitation-heading .primary {
  border-color: var(--color-ink);
  background: var(--color-ink);
  color: white;
}
.invitation-heading button:disabled,
.invitation-actions button:disabled {
  cursor: wait;
  opacity: 0.55;
}
.current-invitation {
  margin-top: 30px;
  padding-block: 24px;
  border-block: 1px solid var(--color-border);
}
.current-invitation > div:first-child span,
.current-invitation > div:first-child strong {
  display: block;
}
.current-invitation > div:first-child span,
.current-invitation dt {
  color: var(--color-muted);
  font-size: 12px;
}
.current-invitation > div:first-child strong {
  margin-top: 8px;
  font-size: 30px;
  letter-spacing: 0.16em;
}
.current-invitation dl {
  display: flex;
  flex-wrap: wrap;
  gap: 28px;
  margin: 24px 0 0;
}
.current-invitation dd {
  margin: 7px 0 0;
  font-weight: 650;
}
.invitation-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 24px;
}
.invitation-state,
.invitation-error,
.invitation-message {
  margin-top: 24px;
  color: var(--color-muted);
}
.invitation-error {
  color: var(--color-danger);
}
.invitation-history {
  margin-top: 38px;
}
.invitation-history h3 {
  font-size: 14px;
}
.invitation-history > div {
  display: grid;
  grid-template-columns: minmax(160px, 1fr) minmax(80px, 0.6fr) minmax(100px, 0.6fr);
  gap: 16px;
  padding: 14px 0;
  border-bottom: 1px solid var(--color-border);
  color: var(--color-muted);
  font-size: 13px;
}
.next-capability h2 {
  margin: 16px 0 0;
  font-size: 28px;
}
.next-capability > p:not(.section-label) {
  margin-top: 16px;
  line-height: 1.8;
}
.next-capability button {
  margin-top: 24px;
  padding: 13px 18px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-action);
  background: transparent;
  color: var(--color-muted);
}
.content-state {
  max-width: 720px;
  padding-top: 100px;
}
.content-state > p:not(.section-label) {
  margin-top: 20px;
  color: var(--color-muted);
}
.state-actions {
  display: flex;
  gap: 18px;
  margin-top: 28px;
}
.state-actions button {
  padding: 12px 17px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-action);
  background: transparent;
  cursor: pointer;
}
.state-actions .primary {
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
.content-state > p {
  color: var(--color-muted);
}
@keyframes pulse {
  to {
    opacity: 0.4;
  }
}
@media (max-width: 760px) {
  .overview-heading {
    padding-top: 54px;
  }
  .overview-heading h1 {
    font-size: 46px;
  }
  .overview-heading dl {
    display: grid;
    grid-template-columns: 1fr;
  }
  .next-capability {
    margin-top: 52px;
  }
  .invitation-section {
    margin-top: 52px;
  }
  .invitation-heading {
    align-items: stretch;
    flex-direction: column;
  }
  .invitation-heading button {
    align-self: flex-start;
  }
  .invitation-history > div {
    grid-template-columns: 1fr;
    gap: 5px;
  }
}
</style>
