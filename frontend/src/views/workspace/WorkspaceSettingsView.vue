<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useWorkspaceStore } from '@/stores/workspace'
import { handleWorkspaceApiError } from '@/utils/workspace-access'

const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const workspaceId = computed(() => Number(route.params.workspaceId))
const workspace = computed(() =>
  store.currentWorkspace?.workspaceId === workspaceId.value ? store.currentWorkspace : null,
)
const isOwner = computed(() => workspace.value?.role === 'OWNER')
const isActive = computed(() => workspace.value?.status === 'ACTIVE')
const canOwnerRestore = computed(
  () => isOwner.value && workspace.value?.status === 'ARCHIVED_BY_OWNER',
)
const pendingAction = ref<{
  type: 'archive' | 'restore'
  workspaceId: number
  name: string
} | null>(null)
const actionLoading = ref(false)
const actionMessage = ref('')
const actionError = ref('')
const renameName = ref('')
const renameLoading = ref(false)
const renameMessage = ref('')
const renameError = ref('')
const pageLoading = ref(false)
const pageError = ref('')
const refreshWarning = ref('')
const confirmation = ref<HTMLElement | null>(null)
const busy = computed(() => actionLoading.value || renameLoading.value)
let returnFocus: HTMLElement | null = null
let pageVersion = 0
let disposed = false
const isCurrent = (id: number, version: number) =>
  !disposed && id === workspaceId.value && version === pageVersion

async function load(retry = false) {
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
    // 设置权限重新读取；写后重试同时读取空间列表，不再次提交状态操作。
    const results = await Promise.allSettled([
      store.loadCurrent(id),
      ...(retry ? [store.loadWorkspaces()] : []),
    ])
    if (!isCurrent(id, version)) return
    const failed = results.find((result) => result.status === 'rejected')
    if (failed?.status === 'rejected') throw failed.reason
    renameName.value = workspace.value?.name ?? ''
    refreshWarning.value = ''
  } catch (error) {
    if (!isCurrent(id, version)) return
    if (await handleWorkspaceApiError(error, router)) return
    pageError.value = error instanceof Error ? error.message : '空间设置加载失败'
  } finally {
    if (isCurrent(id, version)) pageLoading.value = false
  }
}
async function submitRename() {
  if (
    busy.value ||
    pendingAction.value ||
    refreshWarning.value ||
    !isOwner.value ||
    !isActive.value
  )
    return
  const id = workspaceId.value
  const version = pageVersion
  renameMessage.value = ''
  renameError.value = ''
  // 对齐后端按 Unicode 码点计数、去除分隔符与 Java 空白字符的规则。
  const normalized = [...renameName.value]
    .filter((character) => {
      const code = character.codePointAt(0)!
      return !(/\p{Z}/u.test(character) || (code >= 9 && code <= 13) || (code >= 28 && code <= 31))
    })
    .join('')
  if (!normalized) {
    renameError.value = '请输入空间名称'
    return
  }
  if ([...normalized].length > 30) {
    renameError.value = '移除空白后的名称最多 30 个字符'
    return
  }
  renameLoading.value = true
  try {
    const updated = await store.rename(id, renameName.value)
    if (!isCurrent(id, version)) return
    renameName.value = updated.name
    renameMessage.value = '空间名称已更新。'
  } catch (error) {
    if (!isCurrent(id, version)) return
    if (await handleWorkspaceApiError(error, router)) return
    renameError.value = error instanceof Error ? error.message : '空间改名失败'
  } finally {
    if (isCurrent(id, version)) renameLoading.value = false
  }
}
async function requestAction(type: 'archive' | 'restore', event: Event) {
  if (busy.value || refreshWarning.value || !workspace.value || !isOwner.value) return
  if (type === 'archive' ? !isActive.value : !canOwnerRestore.value) return
  pendingAction.value = { type, workspaceId: workspaceId.value, name: workspace.value.name }
  actionMessage.value = ''
  actionError.value = ''
  returnFocus = event.currentTarget as HTMLElement
  await nextTick()
  confirmation.value?.focus()
}
function cancelAction() {
  if (busy.value) return
  pendingAction.value = null
  returnFocus?.focus()
}
async function confirmAction() {
  const action = pendingAction.value
  const version = pageVersion
  if (!action || busy.value || !isCurrent(action.workspaceId, version)) return
  actionLoading.value = true
  actionError.value = ''
  try {
    if (action.type === 'archive') await store.archive(action.workspaceId)
    else await store.restore(action.workspaceId)
    if (!isCurrent(action.workspaceId, version)) return
    actionMessage.value =
      action.type === 'archive'
        ? '空间已归档，当前邀请码已停用。'
        : '空间已恢复，可以继续邀请成员和写入研究数据。'
    pendingAction.value = null
    if (store.errorMessage)
      refreshWarning.value = '操作已完成，但空间列表刷新失败。请重新读取，勿重复提交。'
  } catch (error) {
    if (!isCurrent(action.workspaceId, version)) return
    if (await handleWorkspaceApiError(error, router)) return
    actionError.value = error instanceof Error ? error.message : '空间状态修改失败'
  } finally {
    if (isCurrent(action.workspaceId, version)) actionLoading.value = false
  }
}
watch(
  workspaceId,
  () => {
    pendingAction.value = null
    renameName.value = ''
    actionMessage.value = ''
    actionError.value = ''
    renameMessage.value = ''
    renameError.value = ''
    refreshWarning.value = ''
    actionLoading.value = false
    renameLoading.value = false
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
    <p>正在读取空间设置</p>
  </section>
  <section v-else-if="pageError" class="content-state" role="alert">
    <h1>空间设置暂时没有加载出来</h1>
    <p>{{ pageError }}</p>
    <p v-if="actionMessage" class="feedback">{{ actionMessage }}</p>
    <button class="ui-button primary-action" type="button" @click="load(true)">重新加载</button>
  </section>
  <template v-else-if="workspace">
    <section class="settings-heading management-heading">
      <p class="section-label">( <span>04</span> — 空间设置 )</p>
      <h1>{{ workspace.name }}</h1>
      <p>归档不会删除成员或历史数据，空间名称也会继续保留。</p>
    </section>
    <section class="status-section setting-block" aria-labelledby="workspace-status-title">
      <div>
        <h2 id="workspace-status-title">空间状态</h2>
        <p v-if="isActive">空间正在正常使用，可以邀请成员并写入研究数据。</p>
        <p v-else-if="workspace.status === 'ARCHIVED_BY_OWNER'">
          该空间由 OWNER 归档，现在只读。当前 OWNER 可以恢复。
        </p>
        <p v-else>该空间由超级管理员治理归档，现在只读，只能从治理后台恢复。</p>
      </div>
      <strong>{{ isActive ? '正常使用' : '已归档' }}</strong>
    </section>
    <section class="rename-section setting-block" aria-labelledby="workspace-rename-title">
      <div>
        <h2 id="workspace-rename-title">空间名称</h2>
        <p>名称在所有空间中唯一，保存时会移除空白字符，最多 30 个字符。</p>
      </div>
      <form v-if="isActive && isOwner" class="rename-form" @submit.prevent="submitRename">
        <label for="rename-workspace-name">新名称</label>
        <div class="rename-controls">
          <input
            id="rename-workspace-name"
            v-model="renameName"
            maxlength="120"
            :disabled="busy || !!pendingAction || !!refreshWarning"
            aria-describedby="rename-hint"
          /><button
            class="ui-button primary-action"
            type="submit"
            :disabled="busy || !!pendingAction || !!refreshWarning"
          >
            {{ renameLoading ? '正在保存…' : '保存名称' }}
          </button>
        </div>
        <p id="rename-hint" class="field-hint">归档后仍保留此名称；改名会同步到侧栏及空间列表。</p>
        <p v-if="renameError" class="feedback error" role="alert">{{ renameError }}</p>
        <p v-if="renameMessage" class="feedback" role="status">{{ renameMessage }}</p>
      </form>
      <span v-else>{{ isActive ? '只有当前 OWNER 可以改名。' : '已归档空间不能改名。' }}</span>
    </section>
    <section class="governance-section setting-block">
      <div>
        <h2>{{ isActive ? '归档空间' : '恢复空间' }}</h2>
        <p v-if="isActive">
          适合暂时停止协作但仍需保留完整历史的情况。归档将立即停用当前邀请码，并禁止邀请、成员治理和研究写入。
        </p>
        <p v-else>恢复权限由归档来源决定，旧邀请码不会自动恢复。</p>
      </div>
      <button
        v-if="isActive && isOwner"
        class="ui-button danger-action"
        type="button"
        :disabled="busy || !!refreshWarning"
        @click="requestAction('archive', $event)"
      >
        归档这个空间
      </button>
      <button
        v-else-if="canOwnerRestore"
        class="ui-button primary-action"
        type="button"
        :disabled="busy || !!refreshWarning"
        @click="requestAction('restore', $event)"
      >
        恢复这个空间
      </button>
      <span v-else-if="isActive">只有当前 OWNER 可以归档。</span
      ><span v-else>你当前没有恢复权限。</span>
    </section>
    <section
      v-if="pendingAction"
      ref="confirmation"
      class="confirmation"
      tabindex="-1"
      aria-labelledby="settings-confirm-title"
    >
      <div>
        <h2 id="settings-confirm-title">
          {{ pendingAction.type === 'archive' ? '确认归档空间？' : '确认恢复空间？' }}
        </h2>
        <p class="confirmation-target">{{ pendingAction.name }}</p>
        <p v-if="pendingAction.type === 'archive'">
          归档后将立即停用当前邀请码，并禁止邀请、成员治理和研究写入。历史数据仍会保留。
        </p>
        <p v-else>恢复后空间重新允许正常写入，但旧邀请码不会自动恢复，需要重新生成。</p>
      </div>
      <div class="confirmation-actions">
        <button class="ui-button" type="button" :disabled="busy" @click="cancelAction">取消</button
        ><button
          class="ui-button"
          :class="pendingAction.type === 'archive' ? 'danger-action' : 'primary-action'"
          type="button"
          :disabled="busy"
          @click="confirmAction"
        >
          {{
            actionLoading
              ? '正在处理…'
              : pendingAction.type === 'archive'
                ? '确认归档空间'
                : '确认恢复空间'
          }}
        </button>
      </div>
    </section>
    <p v-if="actionError" class="feedback error" role="alert">{{ actionError }}</p>
    <p v-if="actionMessage" class="feedback" role="status">{{ actionMessage }}</p>
    <div v-if="refreshWarning" class="refresh-warning" role="alert">
      <p>{{ refreshWarning }}</p>
      <button class="ui-button" type="button" @click="load(true)">重新读取</button>
    </div>
    <section class="recovery-section setting-block">
      <div>
        <h2>已归档的空间怎么恢复</h2>
        <p>
          由 OWNER 归档的空间，当前 OWNER
          可以在这里恢复；由超级管理员治理归档的空间，只能从治理后台恢复。
        </p>
      </div>
      <span class="recovery-status">{{
        isActive
          ? '当前未归档'
          : workspace.status === 'ARCHIVED_BY_OWNER'
            ? 'OWNER 归档'
            : '治理归档'
      }}</span>
    </section>
  </template>
</template>
<style scoped src="@/styles/workspace-management.css"></style>
<style scoped>
.rename-form {
  width: min(100%, 420px);
}
.rename-form label {
  display: block;
  margin-bottom: 8px;
  font-size: 12px;
}
.rename-controls {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.rename-form input {
  min-width: 0;
  min-height: 44px;
  flex: 1 1 180px;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-surface);
  color: var(--color-ink);
  font: inherit;
  font-size: 13px;
}
.rename-form input:disabled {
  opacity: 0.55;
}
.rename-form .field-hint {
  font-size: 11px;
}
.rename-form .feedback {
  margin-top: 8px;
}
.confirmation-target {
  font-weight: 600;
}
.recovery-status {
  padding: 6px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-action);
  font-family: var(--font-mono);
}
@media (max-width: 1050px) {
  .rename-section {
    align-items: flex-start;
    flex-direction: column;
  }
  .rename-section > div:first-child {
    flex-basis: auto;
  }
}
</style>
