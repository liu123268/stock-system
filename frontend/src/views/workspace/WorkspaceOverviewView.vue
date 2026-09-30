<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  getWorkspaceOverview,
  type WorkspaceActivityResponseVO,
  type WorkspaceOverviewResponseVO,
} from '@/api/workspace'
import { useWorkspaceStore } from '@/stores/workspace'
import { handleWorkspaceApiError } from '@/utils/workspace-access'

const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const workspaceId = computed(() => Number(route.params.workspaceId))
const workspace = computed(() =>
  store.currentWorkspace?.workspaceId === workspaceId.value ? store.currentWorkspace : null,
)
const isArchived = computed(() => workspace.value?.status !== 'ACTIVE')
const canManageInvitation = computed(() => workspace.value?.role === 'OWNER' && !isArchived.value)
const invitationActionLoading = ref(false)
const copyLoading = ref(false)
const invitationBusy = computed(
  () => invitationActionLoading.value || copyLoading.value || store.invitationLoading,
)
const invitationMessage = ref('')
const invitationActionError = ref('')
const overviewData = ref<WorkspaceOverviewResponseVO | null>(null)
const overviewLoading = ref(false)
const overviewError = ref('')
let pageVersion = 0
let overviewRequestVersion = 0
let disposed = false

function captureTarget() {
  return { workspace: workspaceId.value, version: pageVersion }
}
function isCurrent(target: ReturnType<typeof captureTarget>) {
  return !disposed && target.workspace === workspaceId.value && target.version === pageVersion
}

async function loadOverview() {
  const target = captureTarget()
  const version = ++overviewRequestVersion
  overviewLoading.value = true
  overviewError.value = ''
  overviewData.value = null
  try {
    const result = (await getWorkspaceOverview(target.workspace)).data.data
    if (isCurrent(target) && version === overviewRequestVersion) overviewData.value = result
  } catch (error) {
    if (!isCurrent(target) || version !== overviewRequestVersion) return
    if (await handleWorkspaceApiError(error, router)) return
    overviewError.value = error instanceof Error ? error.message : '近期动态加载失败'
  } finally {
    if (isCurrent(target) && version === overviewRequestVersion) overviewLoading.value = false
  }
}

async function reloadInvitations() {
  if (!canManageInvitation.value || invitationActionLoading.value || copyLoading.value) return
  const target = captureTarget()
  try {
    await store.loadInvitations(target.workspace)
    if (isCurrent(target)) invitationActionError.value = ''
  } catch (error) {
    if (isCurrent(target)) await handleWorkspaceApiError(error, router)
  }
}

async function load() {
  const target = captureTarget()
  overviewData.value = null
  overviewError.value = ''
  invitationMessage.value = ''
  invitationActionError.value = ''
  store.clearInvitations()
  if (!Number.isSafeInteger(target.workspace) || target.workspace <= 0) {
    await router.replace('/workspaces')
    return
  }
  try {
    // 显式重新读取，所有权可能被另一会话转让，不能只复用旧身份。
    await store.loadCurrent(target.workspace)
    if (!isCurrent(target)) return
    await Promise.all([loadOverview(), reloadInvitations()])
  } catch (error) {
    if (isCurrent(target)) await handleWorkspaceApiError(error, router)
  }
}

async function changeInvitation(stop: boolean) {
  if (!canManageInvitation.value || invitationBusy.value) return
  const target = captureTarget()
  invitationMessage.value = ''
  invitationActionError.value = ''
  invitationActionLoading.value = true
  try {
    if (stop) await store.stopInvitation(target.workspace)
    else await store.generateInvitation(target.workspace)
    if (!isCurrent(target)) return
    invitationMessage.value = stop ? '邀请码已经停用' : '新的邀请码已经生成'
  } catch (error) {
    if (!isCurrent(target)) return
    if (await handleWorkspaceApiError(error, router)) return
    invitationActionError.value =
      error instanceof Error ? error.message : stop ? '邀请码停用失败' : '邀请码生成失败'
  } finally {
    if (isCurrent(target)) invitationActionLoading.value = false
  }
}

async function copyInvitation() {
  if (!canManageInvitation.value || invitationBusy.value) return
  const invitation = store.currentInvitation
  if (!invitation) return
  const target = captureTarget()
  copyLoading.value = true
  invitationActionError.value = ''
  try {
    await navigator.clipboard.writeText(invitation.inviteCode)
    if (isCurrent(target) && store.currentInvitation?.invitationId === invitation.invitationId)
      invitationMessage.value = '邀请码已复制'
  } catch {
    if (isCurrent(target) && store.currentInvitation?.invitationId === invitation.invitationId)
      invitationMessage.value = '浏览器未允许复制，请选中上方邀请码手动复制。'
  } finally {
    if (isCurrent(target)) copyLoading.value = false
  }
}

function invitationStatus(status: string) {
  return (
    { ACTIVE: '使用中', REVOKED: '已停用', EXPIRED: '已过期', EXHAUSTED: '次数已用完' }[status] ??
    status
  )
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
function activityText(item: WorkspaceActivityResponseVO) {
  if (item.eventType === 'STOCK_ADDED') return '加入共享关注'
  if (item.eventType === 'FOLLOW_RESTARTED') return '开启新一期研究'
  if (item.eventType === 'FOLLOW_ENDED') return '结束关注'
  if (item.eventType === 'FOLLOW_CANCELLED') return '撤销初始观点并取消本期关注'
  if (item.eventType === 'ATTENTION_CHANGED')
    return item.attentionLevel === 'FOCUS' ? '切换为重点关注' : '切换为普通关注'
  if (item.eventType === 'OPINION_WITHDRAWN') return '撤销观点'
  if (item.opinionWithdrawn) return '曾发表的观点已撤销'
  return item.opinionType === 'REVIEW' ? '发表复盘总结' : '发表观点更新'
}
function stanceText(stance: WorkspaceActivityResponseVO['stance']) {
  return stance ? { BULLISH: '看多', NEUTRAL: '中性', BEARISH: '谨慎' }[stance] : ''
}
function showStance(item: WorkspaceActivityResponseVO) {
  return (
    !item.opinionWithdrawn &&
    item.stance &&
    ['STOCK_ADDED', 'FOLLOW_RESTARTED', 'OPINION_POSTED'].includes(item.eventType)
  )
}

watch(
  workspaceId,
  () => {
    pageVersion += 1
    overviewRequestVersion += 1
    invitationActionLoading.value = false
    copyLoading.value = false
    void load()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  disposed = true
  pageVersion += 1
  overviewRequestVersion += 1
  store.clearInvitations()
})
</script>

<template>
  <section v-if="store.loading" class="content-state" aria-live="polite">
    <p class="section-label">空间概览</p>
    <h1>正在确认空间和成员关系</h1>
    <div class="loading-line"></div>
    <div class="loading-line short"></div>
  </section>
  <section v-else-if="store.errorMessage" class="content-state" role="alert">
    <p class="section-label">加载失败</p>
    <h1>暂时无法进入这个空间。</h1>
    <p>{{ store.errorMessage }}</p>
    <div class="state-actions">
      <button class="primary" type="button" @click="load">重新加载</button
      ><button type="button" @click="router.push('/workspaces')">选择其他空间</button>
    </div>
  </section>
  <template v-else-if="workspace">
    <section v-if="isArchived" class="archive-notice" aria-live="polite">
      <strong>这个空间已归档，当前为只读状态。</strong
      ><span>历史和成员仍可查看；恢复权限取决于归档来源。</span>
    </section>
    <header id="overview" class="overview-heading">
      <p class="section-label">( 01 — 空间概览 )</p>
      <h1>{{ workspace.name }}</h1>
      <p class="page-description">查看空间状态与近期研究记录，邀请成员共同参与。</p>
    </header>
    <dl class="overview-stats">
      <div>
        <dt>你的身份</dt>
        <dd>{{ workspace.role }}</dd>
      </div>
      <div>
        <dt>有效成员</dt>
        <dd>
          <span>{{ workspace.memberCount }}</span> <small>人</small>
        </dd>
      </div>
      <div data-overview-count>
        <dt>正在关注</dt>
        <dd v-if="overviewLoading" class="stat-unavailable">读取中</dd>
        <dd v-else-if="overviewError" class="stat-unavailable">暂不可用</dd>
        <dd v-else>
          <span>{{ overviewData?.activeStockCount ?? '—' }}</span> <small>只</small>
        </dd>
      </div>
      <div>
        <dt>空间状态</dt>
        <dd class="status-value">{{ isArchived ? '已归档' : '正常使用' }}</dd>
      </div>
    </dl>

    <section
      v-if="canManageInvitation"
      class="invitation-section"
      aria-labelledby="invitation-title"
    >
      <div class="invitation-heading">
        <div>
          <p class="section-label">( 成员邀请 · OWNER 可见 )</p>
          <h2 id="invitation-title">邀请成员进入这个空间。</h2>
        </div>
        <button type="button" :disabled="invitationBusy" @click="changeInvitation(false)">
          {{
            invitationActionLoading
              ? '正在处理…'
              : store.currentInvitation
                ? '重新生成邀请码'
                : '生成邀请码'
          }}
        </button>
      </div>
      <p class="section-description">
        重新生成后，旧邀请码会失效。每个邀请码有效期为 7 天，最多供 5 名成员加入。
      </p>
      <p v-if="store.invitationLoading" class="invitation-state" aria-live="polite">
        正在读取邀请码
      </p>
      <template v-else-if="store.currentInvitation">
        <div class="current-invitation">
          <div>
            <span class="code-label">当前邀请码</span
            ><strong>{{ store.currentInvitation.inviteCode }}</strong>
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
        </div>
        <div class="invitation-actions">
          <button type="button" :disabled="invitationBusy" @click="copyInvitation">
            {{ copyLoading ? '正在复制…' : '复制邀请码' }}</button
          ><button
            type="button"
            class="danger"
            :disabled="invitationBusy"
            @click="changeInvitation(true)"
          >
            停用当前邀请码
          </button>
        </div>
      </template>
      <p v-else-if="!store.invitationError" class="invitation-state">
        当前没有邀请码。生成后可供最多 5 名成员加入。
      </p>
      <p v-if="invitationMessage" class="invitation-message" aria-live="polite">
        {{ invitationMessage }}
      </p>
      <div v-if="store.invitationError" class="invitation-error" role="alert">
        <p>
          {{ invitationMessage ? '操作已经完成，但邀请信息刷新失败。' : '邀请信息暂未完整读取。' }}
          {{ store.invitationError }}
        </p>
        <button type="button" :disabled="invitationBusy" @click="reloadInvitations">
          只重新读取邀请信息
        </button>
      </div>
      <div v-if="invitationActionError" class="invitation-error" role="alert">
        <p>{{ invitationActionError }}</p>
        <button type="button" :disabled="invitationBusy" @click="load">
          重新核对空间与邀请信息
        </button>
      </div>
      <section class="invitation-history" aria-labelledby="history-title">
        <h3 id="history-title">生成历史</h3>
        <template v-if="store.invitationHistory.length"
          ><div class="history-labels" aria-hidden="true">
            <span>生成时间</span><span>状态</span><span>使用次数</span>
          </div>
          <div
            v-for="item in store.invitationHistory"
            :key="item.invitationId"
            class="history-row"
            :data-invitation-id="item.invitationId"
          >
            <time :datetime="item.createdAt">{{ formatTime(item.createdAt) }}</time
            ><span>{{ invitationStatus(item.status) }}</span
            ><span>已使用 {{ item.usedCount }} 次</span>
          </div></template
        >
        <p v-else-if="!store.invitationLoading && !store.invitationError" class="invitation-state">
          还没有生成记录。
        </p>
      </section>
    </section>
    <section v-else class="next-capability">
      <p class="section-label">{{ isArchived ? '( 归档边界 )' : '( 成员权限 )' }}</p>
      <template v-if="isArchived"
        ><h2>邀请和研究写入已经暂停。</h2>
        <p>前往设置查看归档来源。普通成员仍可在成员页主动退出这个空间。</p>
        <RouterLink :to="{ name: 'workspace-settings', params: { workspaceId } }"
          >查看空间设置 →</RouterLink
        ></template
      ><template v-else
        ><h2>邀请码由空间 OWNER 管理。</h2>
        <p>你可以参与空间研究，但不能生成、查看或停用完整邀请码。</p></template
      >
    </section>

    <section class="recent-section" aria-labelledby="recent-title">
      <div class="recent-heading">
        <div>
          <p class="section-label">( 研究记录 )</p>
          <h2 id="recent-title">近期动态</h2>
        </div>
        <RouterLink :to="{ name: 'workspace-stocks', params: { workspaceId } }"
          >查看共享自选 →</RouterLink
        >
      </div>
      <p class="section-description">最近 20 条共享研究动态；进入股票详情可查看完整周期和观点。</p>
      <p v-if="overviewLoading" class="recent-state" aria-live="polite">正在读取近期动态</p>
      <div v-else-if="overviewError" class="recent-state error-state" role="alert">
        <p>{{ overviewError }}</p>
        <button type="button" @click="loadOverview">重试近期动态</button>
      </div>
      <p v-else-if="!overviewData?.recentActivities.length" class="recent-state">
        还没有研究动态。前往共享自选添加第一只股票。
      </p>
      <ol v-else class="recent-list">
        <li
          v-for="(item, index) in overviewData.recentActivities"
          :key="`${item.eventType}-${item.workspaceStockId}-${item.occurredAt}-${index}`"
          :data-activity-type="item.eventType"
        >
          <time :datetime="item.occurredAt">{{ formatTime(item.occurredAt) }}</time>
          <div class="activity-content">
            <p>
              <strong>{{ item.actorNickname ?? '空间成员' }}</strong> · {{ activityText(item)
              }}<span v-if="showStance(item)"> · {{ stanceText(item.stance) }}</span
              ><span
                v-else-if="
                  item.opinionWithdrawn &&
                  ['STOCK_ADDED', 'FOLLOW_RESTARTED', 'OPINION_POSTED'].includes(item.eventType)
                "
              >
                · 内容已隐藏</span
              >
            </p>
            <RouterLink
              :to="{
                name: 'workspace-stock-research',
                params: { workspaceId, workspaceStockId: item.workspaceStockId },
              }"
              >{{ item.stockName }} <span>{{ item.stockCode }}</span
              ><span aria-hidden="true"> ↗</span></RouterLink
            >
          </div>
        </li>
      </ol>
    </section>
  </template>
</template>

<style scoped>
.overview-heading {
  padding-top: clamp(40px, 5vw, 60px);
}
.section-label {
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.1em;
  color: var(--color-muted);
}
h1,
h2 {
  font-family: var(--font-display);
  font-weight: 700;
  color: var(--color-ink);
}
.overview-heading h1,
.content-state h1 {
  margin: 20px 0 0;
  font-size: clamp(34px, 4.3vw, 58px);
  line-height: 1.18;
  letter-spacing: -0.035em;
  overflow-wrap: anywhere;
}
.page-description,
.section-description {
  margin-top: 16px;
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.9;
  max-width: 740px;
}
.overview-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 34px 0 0;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  background: var(--color-surface);
}
.overview-stats > div {
  padding: 22px 24px;
  min-width: 0;
  border-right: 1px solid var(--color-border);
}
.overview-stats > div:last-child {
  border-right: 0;
}
dt {
  font-size: 11px;
  color: var(--color-muted);
  font-family: var(--font-mono);
}
.overview-stats dd {
  margin: 12px 0 0;
  font-family: var(--font-mono);
  font-size: 28px;
  font-weight: 650;
  overflow-wrap: anywhere;
}
.overview-stats small {
  font: 13px sans-serif;
  color: var(--color-muted);
}
.overview-stats .status-value,
.overview-stats .stat-unavailable {
  font: 600 18px sans-serif;
  line-height: 34px;
}
.invitation-section,
.next-capability,
.recent-section {
  margin-top: 52px;
  padding-top: 28px;
  border-top: 1px solid var(--color-ink);
}
.invitation-heading,
.recent-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}
h2 {
  margin: 12px 0 0;
  font-size: clamp(22px, 2.2vw, 28px);
  line-height: 1.4;
}
button {
  min-height: 44px;
  padding: 10px 19px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: var(--color-surface);
  color: var(--color-ink);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  transition:
    border-color 0.15s,
    background-color 0.15s;
}
button:hover:not(:disabled) {
  border-color: var(--color-ink);
  background: var(--color-surface-secondary);
}
button:focus-visible,
a:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 4px;
}
button:disabled {
  opacity: 0.55;
  cursor: wait;
}
button.danger {
  color: var(--color-danger);
}
button.primary {
  background: var(--color-ink);
  color: var(--color-canvas);
  border-color: var(--color-ink);
}
.current-invitation {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(0, 1.3fr);
  gap: 30px;
  align-items: center;
  margin-top: 26px;
  padding: 24px 0;
  border-block: 1px solid var(--color-border);
}
.code-label {
  display: block;
  font: 11px var(--font-mono);
  letter-spacing: 0.1em;
  color: var(--color-muted);
}
.current-invitation strong {
  display: block;
  margin-top: 12px;
  font: 600 clamp(28px, 3vw, 40px)/1.2 var(--font-mono);
  letter-spacing: 0.18em;
  user-select: all;
}
.current-invitation dl {
  display: flex;
  flex-wrap: wrap;
  gap: 24px 30px;
  margin: 0;
}
.current-invitation dd {
  margin: 8px 0 0;
  font-size: 14px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.invitation-actions,
.state-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 22px;
}
.invitation-message {
  margin-top: 20px;
  color: var(--color-focus);
  font-size: 13px;
  overflow-wrap: anywhere;
}
.invitation-error,
.error-state {
  color: var(--color-danger);
  font-size: 13px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.invitation-error {
  margin-top: 20px;
  padding: 16px 20px;
  border: 1px solid var(--color-border);
  border-left: 3px solid var(--color-danger);
  background: var(--color-surface);
}
.invitation-error p,
.error-state p {
  margin: 0 0 10px;
}
.invitation-history {
  margin-top: 32px;
}
.invitation-history h3 {
  margin: 0 0 14px;
  font: 600 12px sans-serif;
}
.history-row,
.history-labels {
  display: grid;
  grid-template-columns: minmax(160px, 1fr) minmax(90px, 0.5fr) minmax(110px, 0.6fr);
  gap: 16px;
  padding: 13px 0;
  border-bottom: 1px solid var(--color-border);
  font: 12px/1.7 var(--font-mono);
  color: var(--color-muted);
}
.history-labels {
  font-size: 11px;
}
.invitation-state,
.recent-state {
  margin-top: 24px;
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.8;
}
.recent-heading a,
.next-capability a {
  color: var(--color-focus);
  font-size: 12px;
  text-underline-offset: 4px;
}
.recent-list {
  list-style: none;
  margin: 26px 0 0;
  padding: 0;
}
.recent-list li {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  gap: 20px;
  padding: 19px 0;
  border-bottom: 1px solid var(--color-border);
}
.recent-list time {
  font: 11px/1.8 var(--font-mono);
  color: var(--color-muted);
}
.activity-content {
  min-width: 0;
  font-size: 13px;
  line-height: 1.9;
  overflow-wrap: anywhere;
}
.activity-content p {
  margin: 0;
}
.activity-content strong {
  font-weight: 600;
}
.activity-content a {
  display: inline-block;
  min-height: 30px;
  margin-top: 4px;
  color: var(--color-focus);
  text-underline-offset: 4px;
}
.activity-content a span {
  font: 11px var(--font-mono);
}
.next-capability > p:not(.section-label) {
  margin-top: 14px;
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.9;
}
.next-capability a {
  display: inline-block;
  margin-top: 12px;
  min-height: 32px;
}
.archive-notice {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  margin-top: 28px;
  padding: 14px 18px;
  border: 1px solid var(--color-border);
  background: var(--color-surface-secondary);
  color: var(--color-muted);
  font-size: 12px;
  line-height: 1.8;
}
.archive-notice strong {
  color: var(--color-ink);
}
.content-state {
  padding-top: 60px;
  max-width: 760px;
}
.content-state > p:not(.section-label) {
  margin-top: 20px;
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.8;
}
.loading-line {
  width: min(540px, 80%);
  height: 18px;
  margin-top: 24px;
  background: var(--color-border);
  animation: pulse 1.4s ease-in-out infinite alternate;
}
.loading-line.short {
  width: min(340px, 52%);
  margin-top: 12px;
}
@keyframes pulse {
  to {
    opacity: 0.4;
  }
}
@media (max-width: 960px) {
  .overview-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .overview-stats > div:nth-child(2) {
    border-right: 0;
  }
  .overview-stats > div:nth-child(-n + 2) {
    border-bottom: 1px solid var(--color-border);
  }
  .current-invitation {
    grid-template-columns: 1fr;
    gap: 24px;
  }
}
@media (max-width: 600px) {
  .overview-heading {
    padding-top: 30px;
  }
  .overview-heading h1 {
    font-size: 34px;
  }
  .overview-stats > div {
    padding: 16px;
  }
  .overview-stats dd {
    font-size: 25px;
  }
  .overview-stats .status-value,
  .overview-stats .stat-unavailable {
    font-size: 15px;
    line-height: 30px;
  }
  .invitation-section,
  .recent-section,
  .next-capability {
    margin-top: 36px;
    padding-top: 24px;
  }
  .current-invitation dl {
    gap: 20px 24px;
  }
  .history-labels {
    display: none;
  }
  .history-row {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 24px;
  }
  .history-row time {
    flex-basis: 100%;
  }
  .recent-list li {
    grid-template-columns: 1fr;
    gap: 5px;
  }
  .invitation-error {
    padding: 14px;
  }
  .invitation-error button {
    max-width: 100%;
    white-space: normal;
  }
  .state-actions {
    gap: 10px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .loading-line {
    animation: none;
  }
  button {
    transition: none;
  }
}
</style>
