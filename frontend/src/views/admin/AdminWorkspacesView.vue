<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import type { AdminWorkspaceSummaryResponseVO } from '@/api/admin'
import { useAdminPage } from '@/composables/useAdminPage'
import AdminConfirmation from '@/components/admin/AdminConfirmation.vue'
const { store, active, run } = useAdminPage()
const filters = reactive({ keyword: '', status: '' })
const page = ref(1)
const pending = ref<{
  workspace: AdminWorkspaceSummaryResponseVO
  status: 'ACTIVE' | 'ARCHIVED_BY_SUPER_ADMIN'
} | null>(null)
const successMessage = ref('')
const refreshFailed = ref(false)
const busy = computed(() => store.actionLoading)
let trigger: HTMLElement | null = null
function formatTime(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}
function statusLabel(status: string) {
  return (
    { ACTIVE: '活动', ARCHIVED_BY_OWNER: '所有者归档', ARCHIVED_BY_SUPER_ADMIN: '治理归档' }[
      status
    ] ?? status
  )
}
async function load() {
  const result = await run(() => store.loadWorkspaces({ page: page.value, size: 20, ...filters }))
  if (active()) refreshFailed.value = !result && !!successMessage.value
  return result
}
async function search() {
  if (busy.value || store.loading) return
  page.value = 1
  pending.value = null
  successMessage.value = ''
  store.clearSelection('workspace')
  await load()
}
async function openWorkspace(workspaceId: number) {
  if (busy.value) return
  pending.value = null
  await run(() => store.loadWorkspace(workspaceId))
}
function requestStatus(
  workspace: AdminWorkspaceSummaryResponseVO,
  status: 'ACTIVE' | 'ARCHIVED_BY_SUPER_ADMIN',
  event: Event,
) {
  if (busy.value) return
  successMessage.value = ''
  refreshFailed.value = false
  trigger = event.currentTarget as HTMLElement
  pending.value = { workspace: { ...workspace }, status }
}
async function cancelStatus() {
  if (busy.value) return
  pending.value = null
  await nextTick()
  trigger?.focus()
}
async function confirmStatus() {
  if (!pending.value || busy.value) return
  const { workspace, status } = pending.value
  const result = await run(() => store.setWorkspaceStatus(workspace.workspaceId, status))
  if (!active() || !result) return
  successMessage.value = `已${status === 'ACTIVE' ? '恢复' : '治理归档'} ${workspace.name}`
  pending.value = null
  // 保存结果已经成立；读取失败只提示刷新并提供 GET 重试。
  await load()
}
async function changePage(next: number) {
  if (busy.value || store.loading) return
  const previous = page.value
  page.value = next
  pending.value = null
  store.clearSelection('workspace')
  if (!(await load()) && active()) page.value = previous
}
onMounted(() => void load())
</script>

<template>
  <section class="admin-page">
    <header class="admin-page-header">
      <div>
        <p class="admin-kicker">( WORKSPACES )</p>
        <h2>空间治理<span aria-hidden="true">。</span></h2>
        <p>只查看运营基础信息和成员身份，不进入共享股票与观点正文。</p>
      </div>
    </header>
    <div v-if="store.error && !pending" class="admin-error" role="alert">
      <p>{{ refreshFailed ? '操作已完成，但列表刷新失败。' : '' }}{{ store.error }}</p>
      <button
        class="ui-button admin-button"
        type="button"
        :disabled="store.loading || busy"
        @click="load"
      >
        重试读取
      </button>
    </div>
    <p v-if="successMessage" class="admin-success" role="status">{{ successMessage }}</p>

    <div class="admin-panel" :aria-busy="store.loading">
      <form class="admin-toolbar" @submit.prevent="search">
        <label class="search-field"
          >搜索空间<input
            v-model="filters.keyword"
            placeholder="搜索空间名称"
            :disabled="store.loading || busy"
        /></label>
        <label
          >空间状态<select
            aria-label="空间状态"
            v-model="filters.status"
            :disabled="store.loading || busy"
          >
            <option value="">全部状态</option>
            <option value="ACTIVE">活动</option>
            <option value="ARCHIVED_BY_OWNER">所有者归档</option>
            <option value="ARCHIVED_BY_SUPER_ADMIN">治理归档</option>
          </select></label
        >
        <button
          class="ui-button admin-button primary"
          type="submit"
          :disabled="store.loading || busy"
        >
          查询
        </button>
      </form>
      <p v-if="store.loading && !store.workspaces.items.length" class="admin-state">
        正在读取空间…
      </p>
      <p v-else-if="!store.workspaces.items.length" class="admin-state">没有符合条件的空间</p>
      <div
        v-else
        class="admin-table-wrap"
        tabindex="0"
        role="region"
        aria-label="空间列表，可横向滚动"
      >
        <table class="admin-table">
          <caption>
            空间基础身份
          </caption>
          <thead>
            <tr>
              <th>空间</th>
              <th>状态</th>
              <th>当前所有者</th>
              <th>有效成员</th>
              <th>创建者</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="workspace in store.workspaces.items"
              :key="workspace.workspaceId"
              data-clickable="true"
              @click="openWorkspace(workspace.workspaceId)"
            >
              <td>
                <button
                  class="admin-row-link"
                  type="button"
                  :disabled="busy"
                  :aria-label="`查看空间 ${workspace.name}`"
                  @click.stop="openWorkspace(workspace.workspaceId)"
                >
                  {{ workspace.name }}</button
                ><br /><span class="admin-muted">#{{ workspace.workspaceId }}</span>
              </td>
              <td>
                <span
                  class="admin-badge"
                  :class="{
                    danger: workspace.status === 'ARCHIVED_BY_SUPER_ADMIN',
                    muted: workspace.status === 'ARCHIVED_BY_OWNER',
                  }"
                  >{{ statusLabel(workspace.status) }}</span
                >
              </td>
              <td>{{ workspace.ownerUsername }} · #{{ workspace.ownerUserId }}</td>
              <td>{{ workspace.memberCount }}</td>
              <td>{{ workspace.createdByUsername }}</td>
              <td>{{ formatTime(workspace.createdAt) }}</td>
              <td @click.stop>
                <button
                  v-if="workspace.status === 'ACTIVE'"
                  class="ui-button admin-button"
                  type="button"
                  :disabled="busy"
                  @click="requestStatus(workspace, 'ARCHIVED_BY_SUPER_ADMIN', $event)"
                >
                  治理归档</button
                ><button
                  v-else-if="workspace.status === 'ARCHIVED_BY_SUPER_ADMIN'"
                  class="ui-button admin-button"
                  type="button"
                  :disabled="busy"
                  @click="requestStatus(workspace, 'ACTIVE', $event)"
                >
                  恢复</button
                ><span v-else class="admin-muted">由所有者恢复</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="admin-pagination">
        <span class="admin-muted">共 {{ store.workspaces.total }} 条</span
        ><button
          class="ui-button admin-button"
          type="button"
          :disabled="page <= 1 || store.loading || busy"
          @click="changePage(page - 1)"
        >
          上一页</button
        ><span>{{ store.workspaces.page }} / {{ Math.max(store.workspaces.totalPages, 1) }}</span
        ><button
          class="ui-button admin-button"
          type="button"
          :disabled="page >= store.workspaces.totalPages || store.loading || busy"
          @click="changePage(page + 1)"
        >
          下一页
        </button>
      </div>
    </div>

    <section
      v-if="store.selectedWorkspace"
      class="admin-panel admin-detail"
      aria-labelledby="workspace-detail-title"
    >
      <div class="admin-detail-header">
        <div>
          <h3 id="workspace-detail-title">{{ store.selectedWorkspace.name }}</h3>
          <p class="admin-muted">
            空间 #{{ store.selectedWorkspace.workspaceId }} · 创建者
            {{ store.selectedWorkspace.createdByUsername }}
          </p>
        </div>
        <span
          class="admin-badge"
          :class="{
            danger: store.selectedWorkspace.status === 'ARCHIVED_BY_SUPER_ADMIN',
            muted: store.selectedWorkspace.status === 'ARCHIVED_BY_OWNER',
          }"
          >{{ statusLabel(store.selectedWorkspace.status) }}</span
        >
      </div>
      <h4>成员基础身份</h4>
      <div class="admin-table-wrap" tabindex="0" role="region" aria-label="空间详情，可横向滚动">
        <table class="admin-table">
          <caption>
            空间基础身份
          </caption>
          <thead>
            <tr>
              <th>成员</th>
              <th>角色</th>
              <th>成员状态</th>
              <th>账号状态</th>
              <th>最近加入</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="member in store.selectedWorkspace.members" :key="member.userId">
              <td>
                {{ member.nickname }}<br /><span class="admin-muted"
                  >{{ member.username }} · #{{ member.userId }}</span
                >
              </td>
              <td>{{ member.role }}</td>
              <td>{{ member.membershipStatus }}</td>
              <td>{{ member.accountStatus }}</td>
              <td>{{ formatTime(member.lastJoinedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <AdminConfirmation
      v-if="pending"
      :title="`确认${pending.status === 'ACTIVE' ? '恢复' : '治理归档'} ${pending.workspace.name}？`"
      :label="`确认${pending.status === 'ACTIVE' ? '恢复' : '归档'}`"
      :busy="busy"
      :danger="pending.status !== 'ACTIVE'"
      @cancel="cancelStatus"
      @confirm="confirmStatus"
    >
      <p v-if="pending.status === 'ARCHIVED_BY_SUPER_ADMIN'">
        归档后空间进入只读状态，当前邀请码会停用，但成员、共享股票、研究周期和观点历史全部保留；之后只能由超级管理员恢复。
      </p>
      <p v-else>
        恢复后原成员关系和所有者保持不变，空间重新允许正常业务写入；原停用的邀请码不会自动恢复。
      </p>
      <p v-if="store.error" class="admin-error" role="alert">{{ store.error }}</p>
    </AdminConfirmation>
  </section>
</template>
