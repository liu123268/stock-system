<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'

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
const activeWorkspaces = computed(() => store.workspaces.filter((item) => item.status === 'ACTIVE'))
const archivedWorkspaces = computed(() =>
  store.workspaces.filter((item) => item.status !== 'ACTIVE'),
)

async function load() {
  try {
    const items = await store.loadWorkspaces()
    const active = items.filter((item) => item.status === 'ACTIVE')
    const onlyWorkspace = active[0]
    if (!route.query.reason && active.length === 1 && onlyWorkspace) {
      await openWorkspace(onlyWorkspace.workspaceId)
    }
  } catch {
    // Store 已保存可展示错误，页面保留重试入口。
  }
}

async function create() {
  formError.value = ''
  if (!name.value.trim()) {
    formError.value = '请输入空间名称'
    return
  }
  creating.value = true
  try {
    const workspace = await store.create(name.value)
    await openWorkspace(workspace.workspaceId)
  } catch (error) {
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
  joinError.value = ''
  if (!/^[A-Z0-9]{6}$/.test(inviteCode.value)) {
    joinError.value = '请输入 6 位大写字母或数字'
    return
  }
  joining.value = true
  try {
    const result = await store.join(inviteCode.value)
    await openWorkspace(result.workspace.workspaceId)
  } catch (error) {
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
  router.push('/')
}

onMounted(load)
</script>

<template>
  <main class="space-picker">
    <header class="picker-header">
      <button class="brand logout-btn" type="button" @click="logout">退出登录</button>
      <span>选择研究空间</span>
      <RouterLink to="/profile">个人资料</RouterLink>
    </header>

    <p v-if="route.query.reason === 'left'" class="page-feedback" aria-live="polite">
      你已经退出空间，需要邀请码时可以重新加入。
    </p>
    <p v-else-if="route.query.reason === 'access-lost'" class="page-feedback" role="alert">
      当前空间访问权已经失效，请选择仍可访问的空间。
    </p>
    <p v-else-if="route.query.reason === 'archived'" class="page-feedback" aria-live="polite">
      空间状态已经变化，请从下方已归档区域重新进入。
    </p>

    <section v-if="store.loading" class="picker-state" aria-live="polite">
      <span class="skeleton-line"></span><span class="skeleton-line short"></span>
      <p>正在读取你的空间</p>
    </section>

    <section v-else-if="store.errorMessage" class="picker-state" role="alert">
      <h1>空间暂时没有加载出来</h1>
      <p>{{ store.errorMessage }}</p>
      <button class="primary-action" type="button" @click="load">重新加载</button>
    </section>

    <template v-else>
      <section v-if="activeWorkspaces.length === 0" class="empty-layout">
        <div class="empty-copy">
          <p class="context-label">你的第一个空间</p>
          <h1>先建立共同研究的边界。</h1>
          <p>空间会隔离成员、关注和观点。创建完成后，你会作为 OWNER 直接进入空间概览。</p>
        </div>

        <form class="create-form" @submit.prevent="create">
          <label for="workspace-name">空间名称</label>
          <input
            id="workspace-name"
            v-model="name"
            maxlength="30"
            autocomplete="off"
            placeholder="例如：家庭研究组"
          />
          <p class="field-note">保存时会删除空白字符，最终长度为 1 到 30 个字符。</p>
          <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
          <button class="primary-action" type="submit" :disabled="creating">
            {{ creating ? '正在创建…' : '创建空间' }}
          </button>
        </form>
      </section>

      <section v-else class="workspace-list-section">
        <div>
          <p class="context-label">研究空间</p>
          <h1>选择今天要进入的空间。</h1>
        </div>
        <div class="workspace-list">
          <button
            v-for="workspace in activeWorkspaces"
            :key="workspace.workspaceId"
            class="workspace-row"
            type="button"
            @click="openWorkspace(workspace.workspaceId)"
          >
            <span
              ><strong>{{ workspace.name }}</strong
              ><small>{{ workspace.role }} · {{ workspace.memberCount }} 名成员</small></span
            >
            <span aria-hidden="true">进入 →</span>
          </button>
        </div>
      </section>

      <section v-if="archivedWorkspaces.length" class="archived-section">
        <h2>已归档空间</h2>
        <button
          v-for="workspace in archivedWorkspaces"
          :key="workspace.workspaceId"
          type="button"
          @click="openWorkspace(workspace.workspaceId)"
        >
          {{ workspace.name }} · 只读
        </button>
      </section>

      <section class="join-section">
        <div>
          <p class="context-label">加入已有空间</p>
          <h2>输入 OWNER 发给你的邀请码。</h2>
          <p>邀请码为 6 位大写字母或数字，有效期和剩余次数由后端检查。</p>
        </div>
        <form @submit.prevent="join">
          <label for="invite-code">邀请码</label>
          <input
            id="invite-code"
            v-model="inviteCode"
            maxlength="6"
            autocomplete="off"
            inputmode="text"
            placeholder="例如 A1B2C3"
            @input="normalizeInviteCode"
          />
          <p v-if="joinError" class="form-error" role="alert">{{ joinError }}</p>
          <button class="primary-action" type="submit" :disabled="joining">
            {{ joining ? '正在加入…' : '使用邀请码加入' }}
          </button>
        </form>
      </section>
    </template>
  </main>
</template>

<style scoped>
.space-picker {
  min-height: 100svh;
  padding: 0 var(--gutter) 64px;
  color: var(--color-ink);
}
.picker-header {
  display: flex;
  height: var(--header-height);
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--color-border);
  font-size: 13px;
}
.brand {
  font-weight: 700;
}
.logout-btn {
  border: 0;
  background: transparent;
  color: var(--color-danger);
  cursor: pointer;
  text-align: left;
}
.logout-btn:hover {
  text-decoration: underline;
}
.page-feedback {
  max-width: 1120px;
  margin: 20px auto 0;
  color: var(--color-focus);
  font-size: 13px;
}
.empty-layout,
.workspace-list-section {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(320px, 430px);
  gap: clamp(48px, 10vw, 150px);
  max-width: 1120px;
  margin: 0 auto;
  padding-top: clamp(70px, 12vh, 130px);
}
.empty-copy h1,
.workspace-list-section h1,
.picker-state h1 {
  max-width: 12ch;
  margin-top: 18px;
  font-size: clamp(38px, 5vw, 68px);
  font-weight: 560;
  letter-spacing: -0.035em;
  line-height: 1.06;
  text-wrap: balance;
}
.empty-copy > p:last-child {
  max-width: 42ch;
  margin-top: 26px;
  line-height: 1.8;
}
.context-label {
  color: var(--color-focus);
  font-size: 13px;
  font-weight: 650;
}
.create-form {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding-top: 28px;
  border-top: 1px solid var(--color-ink);
}
.create-form label {
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 650;
}
.create-form input {
  height: 50px;
  padding: 0 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  color: var(--color-ink);
}
.create-form input:focus {
  border-color: var(--color-ink);
  outline: 3px solid color-mix(in oklab, var(--color-focus) 25%, transparent);
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
.primary-action {
  min-height: 48px;
  margin-top: 24px;
  padding: 0 20px;
  border: 0;
  border-radius: var(--radius-action);
  background: var(--color-ink);
  color: white;
  cursor: pointer;
  font-weight: 700;
}
.primary-action:disabled {
  cursor: wait;
  opacity: 0.58;
}
.workspace-list-section {
  grid-template-columns: minmax(260px, 0.72fr) minmax(0, 1fr);
}
.workspace-list {
  border-top: 1px solid var(--color-ink);
}
.workspace-row {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  padding: 22px 4px;
  border: 0;
  border-bottom: 1px solid var(--color-border);
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
}
.workspace-row:hover {
  background: color-mix(in oklab, var(--color-focus) 5%, transparent);
}
.workspace-row strong,
.workspace-row small {
  display: block;
}
.workspace-row small {
  margin-top: 7px;
  color: var(--color-muted);
}
.archived-section,
.picker-state {
  max-width: 1120px;
  margin: 64px auto 0;
  padding-top: 24px;
  border-top: 1px solid var(--color-border);
}
.archived-section h2 {
  font-size: 16px;
}
.archived-section button {
  margin: 16px 20px 0 0;
  border: 0;
  background: transparent;
  color: var(--color-muted);
  cursor: pointer;
}
.join-section {
  display: grid;
  max-width: 1120px;
  grid-template-columns: minmax(0, 1fr) minmax(280px, 430px);
  gap: clamp(40px, 10vw, 150px);
  margin: 72px auto 0;
  padding-top: 28px;
  border-top: 1px solid var(--color-border);
}
.join-section h2 {
  margin-top: 12px;
  font-size: 24px;
  text-wrap: balance;
}
.join-section > div > p:last-child {
  max-width: 54ch;
  margin-top: 12px;
  color: var(--color-muted);
  line-height: 1.7;
}
.join-section form {
  display: flex;
  flex-direction: column;
}
.join-section label {
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 650;
}
.join-section input {
  height: 50px;
  padding: 0 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  color: var(--color-ink);
  font-size: 18px;
  letter-spacing: 0.14em;
}
.join-section input:focus {
  border-color: var(--color-ink);
  outline: 3px solid color-mix(in oklab, var(--color-focus) 25%, transparent);
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
@keyframes pulse {
  to {
    opacity: 0.4;
  }
}
@media (max-width: 760px) {
  .empty-layout,
  .workspace-list-section {
    grid-template-columns: 1fr;
    gap: 44px;
    padding-top: 56px;
  }
  .empty-copy h1,
  .workspace-list-section h1 {
    font-size: 42px;
  }
  .create-form {
    padding-top: 22px;
  }
  .join-section {
    grid-template-columns: 1fr;
    gap: 24px;
    margin-top: 52px;
  }
}
</style>
