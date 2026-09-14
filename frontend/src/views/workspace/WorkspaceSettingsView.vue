<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useWorkspaceStore } from '@/stores/workspace'
import { handleWorkspaceApiError } from '@/utils/workspace-access'

const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const workspaceId = computed(() => Number(route.params.workspaceId))
const isOwner = computed(() => store.currentWorkspace?.role === 'OWNER')
const isActive = computed(() => store.currentWorkspace?.status === 'ACTIVE')
const canOwnerRestore = computed(
  () => isOwner.value && store.currentWorkspace?.status === 'ARCHIVED_BY_OWNER',
)
const pendingAction = ref<'archive' | 'restore' | null>(null)
const actionLoading = ref(false)
const actionMessage = ref('')
const actionError = ref('')

async function load() {
  if (!Number.isSafeInteger(workspaceId.value) || workspaceId.value <= 0) {
    await router.replace('/workspaces')
    return
  }
  try {
    await store.loadCurrent(workspaceId.value)
  } catch (error) {
    await handleWorkspaceApiError(error, router)
  }
}

function requestAction(action: 'archive' | 'restore') {
  pendingAction.value = action
  actionMessage.value = ''
  actionError.value = ''
}

async function confirmAction() {
  const action = pendingAction.value
  if (!action) return
  actionLoading.value = true
  actionError.value = ''
  try {
    if (action === 'archive') {
      await store.archive(workspaceId.value)
      actionMessage.value = '空间已归档，当前邀请码已停用。'
    } else {
      await store.restore(workspaceId.value)
      actionMessage.value = '空间已恢复，可以继续邀请成员和写入研究数据。'
    }
    pendingAction.value = null
  } catch (error) {
    if (await handleWorkspaceApiError(error, router)) return
    actionError.value = error instanceof Error ? error.message : '空间状态修改失败'
  } finally {
    actionLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section v-if="store.loading" class="content-state" aria-live="polite">
    <div class="loading-line"></div>
    <div class="loading-line short"></div>
    <p>正在读取空间设置</p>
  </section>

  <section v-else-if="store.errorMessage" class="content-state" role="alert">
    <h1>空间设置暂时没有加载出来</h1>
    <p>{{ store.errorMessage }}</p>
    <button class="primary-action" type="button" @click="load">重新加载</button>
  </section>

  <template v-else-if="store.currentWorkspace">
    <section class="settings-heading">
      <p class="section-label">空间设置</p>
      <h1>{{ store.currentWorkspace.name }}</h1>
      <p>归档不会删除成员或历史数据，空间名称也会继续保留。</p>
    </section>

    <section class="status-section" aria-labelledby="workspace-status-title">
      <div>
        <h2 id="workspace-status-title">空间状态</h2>
        <p v-if="isActive">空间正在正常使用，可以邀请成员并写入研究数据。</p>
        <p v-else-if="store.currentWorkspace.status === 'ARCHIVED_BY_OWNER'">
          该空间由 OWNER 归档，现在只读。当前 OWNER 可以恢复。
        </p>
        <p v-else>该空间由超级管理员治理归档，现在只读，只能从治理后台恢复。</p>
      </div>
      <strong>{{ isActive ? '正常使用' : '已归档' }}</strong>
    </section>

    <section v-if="pendingAction" class="confirmation" aria-live="polite">
      <div>
        <h2>{{ pendingAction === 'archive' ? '确认归档空间？' : '确认恢复空间？' }}</h2>
        <p v-if="pendingAction === 'archive'">
          归档后将立即停用当前邀请码，并禁止邀请、成员治理和研究写入。历史数据仍会保留。
        </p>
        <p v-else>恢复后空间重新允许正常写入，但旧邀请码不会自动恢复，需要重新生成。</p>
      </div>
      <div class="confirmation-actions">
        <button type="button" :disabled="actionLoading" @click="pendingAction = null">取消</button>
        <button
          :class="pendingAction === 'archive' ? 'danger-action' : 'primary-action'"
          type="button"
          :disabled="actionLoading"
          @click="confirmAction"
        >
          {{
            actionLoading
              ? '正在处理…'
              : pendingAction === 'archive'
                ? '确认归档空间'
                : '确认恢复空间'
          }}
        </button>
      </div>
    </section>

    <p v-if="actionError" class="feedback error" role="alert">{{ actionError }}</p>
    <p v-if="actionMessage" class="feedback" aria-live="polite">{{ actionMessage }}</p>

    <section class="governance-section">
      <div>
        <h2>{{ isActive ? '归档空间' : '恢复空间' }}</h2>
        <p v-if="isActive">适合暂时停止协作但仍需保留完整历史的情况。</p>
        <p v-else>恢复权限由归档来源决定，页面不会覆盖另一方的治理决定。</p>
      </div>
      <button
        v-if="isActive && isOwner"
        class="danger-action"
        type="button"
        @click="requestAction('archive')"
      >
        归档这个空间
      </button>
      <button
        v-else-if="canOwnerRestore"
        class="primary-action"
        type="button"
        @click="requestAction('restore')"
      >
        恢复这个空间
      </button>
      <span v-else-if="isActive">只有当前 OWNER 可以归档。</span>
      <span v-else>你当前没有恢复权限。</span>
    </section>
  </template>
</template>

<style scoped>
.settings-heading {
  max-width: 720px;
  padding-top: 84px;
}
.section-label {
  color: var(--color-focus);
  font-size: 12px;
  font-weight: 700;
}
.settings-heading h1,
.content-state h1 {
  margin-top: 14px;
  font-size: 38px;
  letter-spacing: -0.03em;
  text-wrap: balance;
}
.settings-heading > p:last-child,
.content-state p {
  max-width: 68ch;
  margin-top: 14px;
  color: var(--color-muted);
  line-height: 1.7;
}
.status-section,
.governance-section {
  display: flex;
  max-width: 920px;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  margin-top: 52px;
  padding: 22px 0;
  border-block: 1px solid var(--color-border);
}
.status-section h2,
.governance-section h2,
.confirmation h2 {
  font-size: 18px;
}
.status-section p,
.governance-section p,
.confirmation p {
  max-width: 65ch;
  margin-top: 8px;
  color: var(--color-muted);
  line-height: 1.65;
}
.status-section > strong {
  flex-shrink: 0;
}
.governance-section > span {
  color: var(--color-muted);
  font-size: 13px;
}
.confirmation {
  display: flex;
  max-width: 920px;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  margin-top: 36px;
  padding: 20px 0;
  border-block: 1px solid var(--color-danger);
}
.confirmation-actions {
  display: flex;
  flex-shrink: 0;
  gap: 10px;
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
button:disabled {
  cursor: wait;
  opacity: 0.55;
}
.primary-action {
  border-color: var(--color-ink);
  background: var(--color-ink);
  color: white;
}
.danger-action {
  border-color: var(--color-danger);
  color: var(--color-danger);
}
.feedback {
  max-width: 920px;
  margin-top: 22px;
  color: var(--color-focus);
}
.feedback.error {
  color: var(--color-danger);
}
.content-state {
  max-width: 720px;
  padding-top: 100px;
}
.content-state .primary-action {
  margin-top: 24px;
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
  .settings-heading {
    padding-top: 52px;
  }
  .status-section,
  .governance-section,
  .confirmation {
    align-items: flex-start;
    flex-direction: column;
    gap: 20px;
  }
  .confirmation-actions {
    flex-wrap: wrap;
  }
}
</style>
