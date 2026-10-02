<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import { handleWorkspaceApiError } from '@/utils/workspace-access'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const store = useWorkspaceStore()
const name = ref('')
const creating = ref(false)
const formError = ref('')
const inviteCode = ref('')
const joining = ref(false)
const joinError = ref('')
const busy = computed(() => creating.value || joining.value)
const activeWorkspaces = computed(() => store.workspaces.filter((item) => item.status === 'ACTIVE'))
const archivedWorkspaces = computed(() =>
  store.workspaces.filter((item) => item.status !== 'ACTIVE'),
)
let disposed = false

async function load() {
  const session = authStore.sessionVersion
  try {
    const items = await store.loadWorkspaces()
    if (disposed || session !== authStore.sessionVersion) return
    const active = items.filter((item) => item.status === 'ACTIVE')
    const onlyWorkspace = active[0]
    if (!disposed && !route.query.reason && active.length === 1 && onlyWorkspace) {
      await openWorkspace(onlyWorkspace.workspaceId)
    }
  } catch (error) {
    if (disposed || session !== authStore.sessionVersion) return
    if (await handleWorkspaceApiError(error, router)) return
    // 普通网络失败仍由 Store 保存错误，只读重试；真实 401 则清旧会话。
  }
}

async function create() {
  if (busy.value) return
  formError.value = ''
  if (!name.value.trim()) {
    formError.value = '请输入空间名称'
    return
  }
  const session = authStore.sessionVersion
  creating.value = true
  try {
    const workspace = await store.create(name.value)
    if (!disposed && session === authStore.sessionVersion)
      await openWorkspace(workspace.workspaceId)
  } catch (error) {
    if (disposed || session !== authStore.sessionVersion) return
    if (await handleWorkspaceApiError(error, router)) return
    formError.value = error instanceof Error ? error.message : '空间创建失败'
  } finally {
    creating.value = false
  }
}

function normalizeInviteCode() {
  inviteCode.value = inviteCode.value
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, '')
    .slice(0, 6)
}

async function join() {
  if (busy.value) return
  joinError.value = ''
  if (!/^[A-Z0-9]{6}$/.test(inviteCode.value)) {
    joinError.value = '请输入 6 位大写字母或数字'
    return
  }
  const session = authStore.sessionVersion
  joining.value = true
  try {
    const result = await store.join(inviteCode.value)
    if (!disposed && session === authStore.sessionVersion)
      await openWorkspace(result.workspace.workspaceId)
  } catch (error) {
    if (disposed || session !== authStore.sessionVersion) return
    if (await handleWorkspaceApiError(error, router)) return
    joinError.value = error instanceof Error ? error.message : '加入空间失败'
  } finally {
    joining.value = false
  }
}

function openWorkspace(workspaceId: number) {
  return router.push(`/workspaces/${workspaceId}/overview`)
}

function logout() {
  authStore.logout()
  store.clearAll()
  return router.push('/')
}

onMounted(load)
onBeforeUnmount(() => {
  disposed = true
  store.invalidateEntryRequests()
})
</script>

<template>
  <main class="space-picker research-theme">
    <header class="picker-header">
      <div class="ui-brand" aria-label="基点">基点<span aria-hidden="true">。</span></div>
      <div class="picker-account">
        <RouterLink to="/profile">个人资料 <span aria-hidden="true">↗</span></RouterLink>
        <button class="ui-text-action logout-btn" type="button" @click="logout">退出登录</button>
      </div>
    </header>
    <div class="picker-body">
      <p v-if="route.query.reason === 'left'" class="page-feedback" aria-live="polite">
        你已经退出空间，需要邀请码时可以重新加入。
      </p>
      <p v-else-if="route.query.reason === 'access-lost'" class="page-feedback" role="alert">
        当前空间访问权已经失效，请选择仍可访问的空间。
      </p>
      <p v-else-if="route.query.reason === 'archived'" class="page-feedback" aria-live="polite">
        空间状态已经变化，请从下方已归档区域重新进入。
      </p>
      <div class="picker-intro">
        <p class="context-label">( 选择研究空间 )</p>
        <h1>选择今天要进入的空间。</h1>
        <p class="description">
          选择一个空间，继续你们的研究。每个空间的成员、自选股和观点独立保存。
        </p>
      </div>
      <section v-if="store.loading" class="picker-state" aria-live="polite" aria-busy="true">
        <span class="skeleton-line"></span><span class="skeleton-line short"></span>
        <p>正在读取你的空间</p>
      </section>
      <section v-else-if="store.errorMessage" class="picker-state" role="alert">
        <h2>空间暂时没有加载出来</h2>
        <p>{{ store.errorMessage }}</p>
        <button class="ui-button primary-action" type="button" @click="load">重新加载</button>
      </section>
      <div v-else class="picker-grid">
        <section class="workspace-list-section" aria-label="我的空间">
          <div class="list-heading">
            <h2>我的空间</h2>
            <span>{{
              activeWorkspaces.length
                ? `${activeWorkspaces.length} 个可进入的活动空间`
                : '没有活动空间，可以创建或加入'
            }}</span>
          </div>
          <div v-if="activeWorkspaces.length" class="workspace-list">
            <button
              v-for="(workspace, index) in activeWorkspaces"
              :key="workspace.workspaceId"
              class="workspace-row"
              type="button"
              @click="openWorkspace(workspace.workspaceId)"
            >
              <span class="row-role"
                ><span>{{ String(index + 1).padStart(2, '0') }}</span> / {{ workspace.role }}</span
              >
              <span class="row-identity"
                ><strong>{{ workspace.name }}</strong
                ><small>{{ workspace.memberCount }} 名成员</small></span
              >
              <span class="row-enter" aria-hidden="true">进入 →</span>
            </button>
          </div>
          <section class="create-section" aria-labelledby="create-workspace-title">
            <h2 id="create-workspace-title">
              {{ activeWorkspaces.length ? '创建另一个研究空间。' : '建立你们的第一个空间。' }}
            </h2>
            <p>创建后，你将成为空间所有者，可以邀请成员共同研究。</p>
            <form class="create-form" :aria-busy="creating" @submit.prevent="create">
              <label for="workspace-name">空间名称</label>
              <input
                id="workspace-name"
                v-model="name"
                maxlength="30"
                autocomplete="off"
                placeholder="例如：家庭研究组"
                :disabled="busy"
                :aria-invalid="Boolean(formError)"
                aria-describedby="workspace-name-note workspace-name-error"
              />
              <p id="workspace-name-note" class="field-note">
                保存时会删除空白字符，最终长度为 1 到 30 个字符。
              </p>
              <p id="workspace-name-error" class="form-error" role="alert">{{ formError }}</p>
              <button class="ui-button primary-action" type="submit" :disabled="busy">
                {{ creating ? '正在创建…' : '创建空间' }}
              </button>
            </form>
          </section>
          <section v-if="archivedWorkspaces.length" class="archived-section">
            <h2>( 已归档 · 只读 )</h2>
            <button
              v-for="workspace in archivedWorkspaces"
              :key="workspace.workspaceId"
              class="archived-row"
              type="button"
              @click="openWorkspace(workspace.workspaceId)"
            >
              <span>{{ workspace.name }} <span class="ui-chip">只读</span></span>
              <span class="row-enter" aria-hidden="true">进入 →</span>
            </button>
          </section>
        </section>
        <aside class="join-section">
          <p class="context-label">( 加入已有空间 )</p>
          <h2>和大家进入同一个空间。</h2>
          <p class="description">向空间所有者索取邀请码，即可加入已有的研究空间。</p>
          <form class="join-form" :aria-busy="joining" @submit.prevent="join">
            <label for="invite-code">邀请码</label>
            <input
              id="invite-code"
              v-model="inviteCode"
              maxlength="6"
              autocomplete="off"
              inputmode="text"
              placeholder="例如 M7K2QX"
              :disabled="busy"
              :aria-invalid="Boolean(joinError)"
              aria-describedby="invite-code-note invite-code-error"
              @input="normalizeInviteCode"
            />
            <p id="invite-code-error" class="form-error" role="alert">{{ joinError }}</p>
            <button class="ui-button primary-action" type="submit" :disabled="busy">
              {{ joining ? '正在加入…' : '使用邀请码加入' }}
              <span v-if="!joining" aria-hidden="true">↗</span>
            </button>
          </form>
          <p id="invite-code-note" class="join-footnote">
            邀请码为 6 位大写字母或数字。连续输错 5 次后，本账号最多等待 15 分钟才能再试。
          </p>
        </aside>
      </div>
    </div>
    <footer class="picker-footer">
      <span>基点 JIDIAN</span><span>共同研究，保留各自的判断</span>
    </footer>
  </main>
</template>

<style scoped>
.space-picker {
  display: flex;
  min-height: 100svh;
  flex-direction: column;
}
.picker-header {
  display: flex;
  min-height: 75px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 12px var(--gutter);
  border-bottom: 1px solid var(--color-border);
  font-size: 12px;
}
.picker-account {
  display: flex;
  align-items: center;
  gap: 26px;
  color: var(--color-muted);
}
.picker-account a {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
}
.logout-btn:hover {
  color: var(--color-danger);
}
.picker-body {
  flex: 1;
  width: 100%;
  max-width: 1752px;
  margin: 0 auto;
  padding: clamp(44px, 7vh, 80px) var(--gutter) 100px;
}
.picker-intro {
  margin-bottom: 46px;
}
.context-label {
  color: var(--color-muted);
  font-size: 11px;
  letter-spacing: 0.1em;
}
.picker-intro h1 {
  margin-top: 16px;
  font-family: var(--font-display);
  font-size: 42px;
  font-weight: 900;
  line-height: 1.4;
  letter-spacing: -0.025em;
  text-wrap: balance;
}
.description {
  max-width: 65ch;
  margin-top: 12px;
  color: var(--color-muted);
  font-size: 14px;
  line-height: 1.85;
}
.page-feedback {
  margin-bottom: 24px;
  padding: 12px 0;
  border-bottom: 1px solid var(--color-border);
  color: var(--color-focus);
  font-size: 13px;
}
.picker-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.9fr) minmax(290px, 1fr);
  align-items: start;
  gap: clamp(36px, 5vw, 80px);
}
.list-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--color-border);
}
.list-heading h2 {
  font-size: 16px;
  font-weight: 500;
}
.list-heading > span {
  color: var(--color-muted);
  font-size: 12px;
}
.workspace-row {
  display: grid;
  width: 100%;
  grid-template-columns: 96px minmax(0, 1fr) auto;
  align-items: center;
  gap: 18px;
  padding: 26px 14px 26px 0;
  border: 0;
  border-bottom: 1px solid var(--color-border);
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
}
.workspace-row:hover {
  background: var(--color-surface-secondary);
}
.row-role {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}
.row-role > span {
  color: var(--color-focus);
}
.row-identity {
  min-width: 0;
}
.row-identity strong {
  display: block;
  font-size: 21px;
  font-weight: 700;
  overflow-wrap: anywhere;
}
.row-identity small {
  display: block;
  margin-top: 7px;
  color: var(--color-muted);
  font-size: 13px;
}
.row-enter {
  flex-shrink: 0;
  color: var(--color-muted);
  font-size: 12px;
  white-space: nowrap;
}
.archived-section {
  margin-top: 38px;
}
.archived-section h2 {
  margin-bottom: 6px;
  color: var(--color-muted);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.1em;
}
.archived-row {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 0;
  border: 0;
  border-bottom: 1px solid var(--color-border);
  background: transparent;
  color: var(--color-muted);
  font-size: 14px;
  text-align: left;
  cursor: pointer;
}
.archived-row > span:first-child {
  overflow-wrap: anywhere;
}
.archived-row:hover {
  color: var(--color-ink);
}
.join-section {
  min-width: 0;
  padding: 24px 26px 28px;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  background: var(--color-surface);
}
.join-section h2,
.create-section h2 {
  margin-top: 10px;
  font-family: var(--font-display);
  font-size: 24px;
  font-weight: 900;
  line-height: 1.5;
  text-wrap: balance;
}
.join-section .description {
  font-size: 13px;
}
.join-form {
  margin-top: 28px;
}
.join-form,
.create-form {
  display: flex;
  min-width: 0;
  flex-direction: column;
}
.join-form label,
.create-form label {
  margin-bottom: 9px;
  color: var(--color-muted);
  font-size: 12px;
}
.join-form input,
.create-form input {
  width: 100%;
  min-width: 0;
  height: 44px;
  padding: 0 14px;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  background: var(--color-canvas);
  color: var(--color-ink);
}
.join-form input {
  font-family: var(--font-mono);
  font-size: 15px;
  letter-spacing: 0.2em;
}
.join-form input::placeholder,
.create-form input::placeholder {
  color: var(--color-muted);
  opacity: 1;
}
.join-form input:focus,
.create-form input:focus {
  border-color: var(--color-focus);
  outline: 2px solid var(--color-focus);
  outline-offset: 2px;
}
.join-form input[aria-invalid='true'],
.create-form input[aria-invalid='true'] {
  border-color: var(--color-danger);
}
.primary-action {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-top: 14px;
  padding: 10px 18px;
  border: 1px solid var(--color-ink);
  border-radius: 4px;
  background: var(--color-ink);
  color: var(--color-surface);
  font-size: 12px;
  cursor: pointer;
}
.primary-action:disabled {
  border-color: var(--color-muted);
  background: var(--color-muted);
  cursor: wait;
}
.join-footnote {
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px solid var(--color-border);
  color: var(--color-muted);
  font-size: 12px;
  line-height: 1.8;
}
.create-section {
  padding-top: 24px;
}
.create-section > p {
  margin-top: 10px;
  color: var(--color-muted);
  font-size: 14px;
}
.create-form {
  max-width: 440px;
  margin-top: 26px;
}
.field-note,
.form-error {
  margin-top: 9px;
  font-size: 12px;
  line-height: 1.6;
}
.field-note {
  color: var(--color-muted);
}
.form-error {
  color: var(--color-danger);
}
.form-error:empty {
  margin: 0;
}
.picker-state {
  max-width: 720px;
  padding-top: 24px;
  border-top: 1px solid var(--color-border);
}
.picker-state h2 {
  font-family: var(--font-display);
  font-size: 28px;
  line-height: 1.5;
}
.picker-state p {
  margin-top: 18px;
  color: var(--color-muted);
}
.skeleton-line {
  display: block;
  width: min(560px, 75%);
  height: 18px;
  margin-bottom: 14px;
  background: var(--color-border);
  animation: pulse 1.4s ease-in-out infinite alternate;
}
.skeleton-line.short {
  width: min(360px, 52%);
}
.picker-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 10px;
  padding: 20px var(--gutter);
  border-top: 1px solid var(--color-border);
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}
@keyframes pulse {
  to {
    opacity: 0.4;
  }
}
@media (max-width: 960px) {
  .picker-grid {
    grid-template-columns: minmax(0, 1fr) 290px;
    gap: 32px;
  }
  .workspace-row {
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 4px 12px;
  }
  .row-role {
    grid-column: 1 / -1;
    margin-bottom: 6px;
  }
}
@media (max-width: 760px) {
  .picker-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 36px;
  }
  .picker-body {
    padding-top: 34px;
    padding-bottom: 64px;
  }
  .picker-intro h1 {
    font-size: 30px;
  }
  .row-identity strong {
    font-size: 19px;
  }
  .join-section {
    padding: 22px;
  }
  .picker-account {
    gap: 16px;
  }
}
</style>
