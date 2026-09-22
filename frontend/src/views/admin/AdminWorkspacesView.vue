<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { AdminWorkspaceSummaryResponseVO } from '@/api/admin'
import { useAdminStore } from '@/stores/admin'

const store = useAdminStore()
const filters = reactive({ keyword: '', status: '' })
const page = ref(1)
const pending = ref<{ workspace: AdminWorkspaceSummaryResponseVO; status: 'ACTIVE' | 'ARCHIVED_BY_SUPER_ADMIN' } | null>(null)
const successMessage = ref('')

function formatTime(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}
function statusLabel(status: string) {
  return { ACTIVE: '活动', ARCHIVED_BY_OWNER: '所有者归档', ARCHIVED_BY_SUPER_ADMIN: '治理归档' }[status] ?? status
}
async function load() {
  await store.loadWorkspaces({ page: page.value, size: 20, ...filters })
}
async function search() { page.value = 1; await load() }
async function openWorkspace(workspaceId: number) { pending.value = null; await store.loadWorkspace(workspaceId) }
function requestStatus(workspace: AdminWorkspaceSummaryResponseVO, status: 'ACTIVE' | 'ARCHIVED_BY_SUPER_ADMIN') {
  successMessage.value = ''
  pending.value = { workspace, status }
}
async function confirmStatus() {
  if (!pending.value) return
  const { workspace, status } = pending.value
  await store.setWorkspaceStatus(workspace.workspaceId, status)
  successMessage.value = status === 'ACTIVE' ? `已恢复 ${workspace.name}` : `已治理归档 ${workspace.name}`
  pending.value = null
  await load()
}
async function changePage(next: number) { page.value = next; await load() }
onMounted(() => void load().catch(() => undefined))
</script>

<template>
  <section class="admin-page">
    <header class="admin-page-header"><div><p class="admin-kicker">WORKSPACES</p><h2>空间治理</h2><p>只查看运营基础信息和成员身份，不进入共享股票与观点正文。</p></div></header>
    <p v-if="store.error" class="admin-error" role="alert">{{ store.error }}</p>
    <p v-if="successMessage" class="admin-error" style="border-color: #9cc9be; background: #f0faf7; color: #20594f" role="status">{{ successMessage }}</p>

    <div class="admin-panel">
      <form class="admin-toolbar" @submit.prevent="search">
        <input v-model="filters.keyword" aria-label="搜索空间" placeholder="搜索空间名称" />
        <select v-model="filters.status" aria-label="空间状态"><option value="">全部状态</option><option value="ACTIVE">活动</option><option value="ARCHIVED_BY_OWNER">所有者归档</option><option value="ARCHIVED_BY_SUPER_ADMIN">治理归档</option></select>
        <button class="admin-button primary" type="submit" :disabled="store.loading">查询</button>
      </form>
      <p v-if="store.loading && !store.workspaces.items.length" class="admin-state">正在读取空间…</p>
      <p v-else-if="!store.workspaces.items.length" class="admin-state">没有符合条件的空间</p>
      <div v-else class="admin-table-wrap">
        <table class="admin-table">
          <thead><tr><th>空间</th><th>状态</th><th>当前所有者</th><th>有效成员</th><th>创建者</th><th>创建时间</th><th>操作</th></tr></thead>
          <tbody><tr v-for="workspace in store.workspaces.items" :key="workspace.workspaceId" data-clickable="true" @click="openWorkspace(workspace.workspaceId)">
            <td><strong>{{ workspace.name }}</strong><br /><span class="admin-muted">#{{ workspace.workspaceId }}</span></td>
            <td><span class="admin-badge" :class="{ danger: workspace.status === 'ARCHIVED_BY_SUPER_ADMIN', muted: workspace.status === 'ARCHIVED_BY_OWNER' }">{{ statusLabel(workspace.status) }}</span></td>
            <td>{{ workspace.ownerUsername }} · #{{ workspace.ownerUserId }}</td><td>{{ workspace.memberCount }}</td><td>{{ workspace.createdByUsername }}</td><td>{{ formatTime(workspace.createdAt) }}</td>
            <td @click.stop><button v-if="workspace.status === 'ACTIVE'" class="admin-button" type="button" @click="requestStatus(workspace, 'ARCHIVED_BY_SUPER_ADMIN')">治理归档</button><button v-else-if="workspace.status === 'ARCHIVED_BY_SUPER_ADMIN'" class="admin-button" type="button" @click="requestStatus(workspace, 'ACTIVE')">恢复</button><span v-else class="admin-muted">由所有者恢复</span></td>
          </tr></tbody>
        </table>
      </div>
      <div class="admin-pagination"><span class="admin-muted">共 {{ store.workspaces.total }} 条</span><button class="admin-button" type="button" :disabled="page <= 1 || store.loading" @click="changePage(page - 1)">上一页</button><span>{{ page }} / {{ Math.max(store.workspaces.totalPages, 1) }}</span><button class="admin-button" type="button" :disabled="page >= store.workspaces.totalPages || store.loading" @click="changePage(page + 1)">下一页</button></div>
    </div>

    <section v-if="store.selectedWorkspace" class="admin-panel admin-detail" aria-labelledby="workspace-detail-title">
      <div class="admin-detail-header"><div><h3 id="workspace-detail-title">{{ store.selectedWorkspace.name }}</h3><p class="admin-muted">空间 #{{ store.selectedWorkspace.workspaceId }} · 创建者 {{ store.selectedWorkspace.createdByUsername }}</p></div><span class="admin-badge" :class="{ danger: store.selectedWorkspace.status === 'ARCHIVED_BY_SUPER_ADMIN', muted: store.selectedWorkspace.status === 'ARCHIVED_BY_OWNER' }">{{ statusLabel(store.selectedWorkspace.status) }}</span></div>
      <h4>成员基础身份</h4>
      <div class="admin-table-wrap"><table class="admin-table"><thead><tr><th>成员</th><th>角色</th><th>成员状态</th><th>账号状态</th><th>最近加入</th></tr></thead><tbody><tr v-for="member in store.selectedWorkspace.members" :key="member.userId"><td>{{ member.nickname }}<br /><span class="admin-muted">{{ member.username }} · #{{ member.userId }}</span></td><td>{{ member.role }}</td><td>{{ member.membershipStatus }}</td><td>{{ member.accountStatus }}</td><td>{{ formatTime(member.lastJoinedAt) }}</td></tr></tbody></table></div>
    </section>

    <section v-if="pending" class="admin-confirm" role="alertdialog" aria-labelledby="workspace-confirm-title">
      <h3 id="workspace-confirm-title">确认{{ pending.status === 'ACTIVE' ? '恢复' : '治理归档' }} {{ pending.workspace.name }}？</h3>
      <p v-if="pending.status === 'ARCHIVED_BY_SUPER_ADMIN'">归档后空间进入只读状态，当前邀请码会停用，但成员、共享股票、研究周期和观点历史全部保留；之后只能由超级管理员恢复。</p>
      <p v-else>恢复后原成员关系和所有者保持不变，空间重新允许正常业务写入。</p>
      <div class="admin-confirm-actions"><button class="admin-button" type="button" @click="pending = null">取消</button><button class="admin-button" :class="{ danger: pending.status !== 'ACTIVE', primary: pending.status === 'ACTIVE' }" type="button" :disabled="store.actionLoading" @click="confirmStatus">{{ store.actionLoading ? '正在提交…' : `确认${pending.status === 'ACTIVE' ? '恢复' : '归档'}` }}</button></div>
    </section>
  </section>
</template>
