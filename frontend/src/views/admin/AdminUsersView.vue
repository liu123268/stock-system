<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { AdminUserSummaryResponseVO } from '@/api/admin'
import { useAdminStore } from '@/stores/admin'

const store = useAdminStore()
const filters = reactive({ keyword: '', status: '' })
const page = ref(1)
const pending = ref<{ user: AdminUserSummaryResponseVO; status: 'ACTIVE' | 'DISABLED' } | null>(null)
const successMessage = ref('')

function formatTime(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  await store.loadUsers({ page: page.value, size: 20, ...filters })
}

async function search() {
  page.value = 1
  await load()
}

async function openUser(userId: number) {
  pending.value = null
  await store.loadUser(userId)
}

function requestStatus(user: AdminUserSummaryResponseVO, status: 'ACTIVE' | 'DISABLED') {
  successMessage.value = ''
  pending.value = { user, status }
}

async function confirmStatus() {
  if (!pending.value) return
  const { user, status } = pending.value
  await store.setUserStatus(user.userId, status)
  successMessage.value = status === 'DISABLED' ? `已禁用 ${user.username}` : `已恢复 ${user.username}`
  pending.value = null
  await load()
}

async function changePage(next: number) {
  page.value = next
  await load()
}

onMounted(() => void load().catch(() => undefined))
</script>

<template>
  <section class="admin-page">
    <header class="admin-page-header">
      <div>
        <p class="admin-kicker">ACCOUNTS</p>
        <h2>用户治理</h2>
        <p>查看账号及其空间身份；禁用只会停止访问，不删除历史数据和成员关系。</p>
      </div>
    </header>

    <p v-if="store.error" class="admin-error" role="alert">{{ store.error }}</p>
    <p v-if="successMessage" class="admin-error" style="border-color: #9cc9be; background: #f0faf7; color: #20594f" role="status">
      {{ successMessage }}
    </p>

    <div class="admin-panel">
      <form class="admin-toolbar" @submit.prevent="search">
        <input v-model="filters.keyword" aria-label="搜索用户" placeholder="搜索账号或昵称" />
        <select v-model="filters.status" aria-label="账号状态">
          <option value="">全部状态</option>
          <option value="ACTIVE">正常</option>
          <option value="DISABLED">已禁用</option>
        </select>
        <button class="admin-button primary" type="submit" :disabled="store.loading">查询</button>
      </form>

      <p v-if="store.loading && !store.users.items.length" class="admin-state">正在读取用户…</p>
      <p v-else-if="!store.users.items.length" class="admin-state">没有符合条件的用户</p>
      <div v-else class="admin-table-wrap">
        <table class="admin-table">
          <thead><tr><th>用户</th><th>类型</th><th>状态</th><th>有效空间</th><th>拥有空间</th><th>注册时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="user in store.users.items" :key="user.userId" data-clickable="true" @click="openUser(user.userId)">
              <td><strong>{{ user.nickname }}</strong><br /><span class="admin-muted">{{ user.username }} · #{{ user.userId }}</span></td>
              <td>{{ user.accountType === 'SUPER_ADMIN' ? '超级管理员' : '普通用户' }}</td>
              <td><span class="admin-badge" :class="{ danger: user.status === 'DISABLED' }">{{ user.status === 'ACTIVE' ? '正常' : '已禁用' }}</span></td>
              <td>{{ user.workspaceCount }}</td>
              <td>{{ user.ownedWorkspaceCount }}</td>
              <td>{{ formatTime(user.createdAt) }}</td>
              <td @click.stop>
                <button
                  v-if="user.status === 'ACTIVE'"
                  class="admin-button"
                  type="button"
                  :disabled="user.accountType === 'SUPER_ADMIN'"
                  @click="requestStatus(user, 'DISABLED')"
                >禁用</button>
                <button v-else class="admin-button" type="button" @click="requestStatus(user, 'ACTIVE')">恢复</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="admin-pagination">
        <span class="admin-muted">共 {{ store.users.total }} 条</span>
        <button class="admin-button" type="button" :disabled="page <= 1 || store.loading" @click="changePage(page - 1)">上一页</button>
        <span>{{ page }} / {{ Math.max(store.users.totalPages, 1) }}</span>
        <button class="admin-button" type="button" :disabled="page >= store.users.totalPages || store.loading" @click="changePage(page + 1)">下一页</button>
      </div>
    </div>

    <section v-if="store.selectedUser" class="admin-panel admin-detail" aria-labelledby="user-detail-title">
      <div class="admin-detail-header">
        <div>
          <h3 id="user-detail-title">{{ store.selectedUser.nickname }}</h3>
          <p class="admin-muted">{{ store.selectedUser.username }} · 用户 #{{ store.selectedUser.userId }}</p>
        </div>
        <span class="admin-badge" :class="{ danger: store.selectedUser.status === 'DISABLED' }">{{ store.selectedUser.status === 'ACTIVE' ? '账号正常' : '账号已禁用' }}</span>
      </div>
      <h4>空间身份</h4>
      <p v-if="!store.selectedUser.memberships.length" class="admin-muted">该账号没有空间成员记录。</p>
      <div v-else class="admin-table-wrap">
        <table class="admin-table">
          <thead><tr><th>空间</th><th>角色</th><th>成员状态</th><th>空间状态</th><th>最近加入</th></tr></thead>
          <tbody><tr v-for="item in store.selectedUser.memberships" :key="item.workspaceId"><td>{{ item.workspaceName }} · #{{ item.workspaceId }}</td><td>{{ item.role }}</td><td>{{ item.membershipStatus }}</td><td>{{ item.workspaceStatus }}</td><td>{{ formatTime(item.lastJoinedAt) }}</td></tr></tbody>
        </table>
      </div>
    </section>

    <section v-if="pending" class="admin-confirm" role="alertdialog" aria-labelledby="user-confirm-title">
      <h3 id="user-confirm-title">确认{{ pending.status === 'DISABLED' ? '禁用' : '恢复' }} {{ pending.user.username }}？</h3>
      <p v-if="pending.status === 'DISABLED'">禁用后，该账号已有 Token 会在下一次受保护请求时失效；{{ pending.user.workspaceCount }} 个有效空间关系和 {{ pending.user.ownedWorkspaceCount }} 个所有权记录继续保留，恢复后仍可继续使用。</p>
      <p v-else>恢复只重新允许账号登录和访问，不会创建、删除或修改它原有的空间身份。</p>
      <div class="admin-confirm-actions">
        <button class="admin-button" type="button" @click="pending = null">取消</button>
        <button class="admin-button" :class="{ danger: pending.status === 'DISABLED', primary: pending.status === 'ACTIVE' }" type="button" :disabled="store.actionLoading" @click="confirmStatus">{{ store.actionLoading ? '正在提交…' : `确认${pending.status === 'DISABLED' ? '禁用' : '恢复'}` }}</button>
      </div>
    </section>
  </section>
</template>
