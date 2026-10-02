<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import type { AdminUserSummaryResponseVO } from '@/api/admin'
import { useAdminPage } from '@/composables/useAdminPage'
import AdminConfirmation from '@/components/admin/AdminConfirmation.vue'
const { store, active, run } = useAdminPage()
const filters = reactive({ keyword: '', status: '' })
const page = ref(1)
const pending = ref<{ user: AdminUserSummaryResponseVO; status: 'ACTIVE' | 'DISABLED' } | null>(
  null,
)
const successMessage = ref('')
const refreshFailed = ref(false)
const busy = computed(() => store.actionLoading)
let trigger: HTMLElement | null = null
function formatTime(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

async function load() {
  let result = await run(() => store.loadUsers({ page: page.value, size: 20, ...filters }))
  // 状态操作可能使筛选结果少一页；修正页码后只 GET，不重复提交保存。
  while (result && active() && page.value > Math.max(1, result.totalPages)) {
    page.value = Math.max(1, result.totalPages)
    result = await run(() => store.loadUsers({ page: page.value, size: 20, ...filters }))
  }
  if (active()) refreshFailed.value = !result && !!successMessage.value
  return result
}
async function search() {
  if (busy.value || store.loading) return
  page.value = 1
  pending.value = null
  successMessage.value = ''
  store.clearSelection('user')
  await load()
}
async function openUser(userId: number) {
  if (busy.value) return
  pending.value = null
  await run(() => store.loadUser(userId))
}
function requestStatus(
  user: AdminUserSummaryResponseVO,
  status: 'ACTIVE' | 'DISABLED',
  event: Event,
) {
  if (busy.value) return
  successMessage.value = ''
  refreshFailed.value = false
  trigger = event.currentTarget as HTMLElement
  pending.value = { user: { ...user }, status }
}
async function cancelStatus() {
  if (busy.value) return
  pending.value = null
  await nextTick()
  trigger?.focus()
}
async function confirmStatus() {
  if (!pending.value || busy.value) return
  const { user, status } = pending.value
  const result = await run(() => store.setUserStatus(user.userId, status))
  if (!active() || !result) return
  successMessage.value = `已${status === 'DISABLED' ? '禁用' : '恢复'} ${user.username}`
  pending.value = null
  // 保存结果已经成立；读取失败只提示刷新并提供 GET 重试。
  await load()
}
async function changePage(next: number) {
  if (busy.value || store.loading) return
  const previous = page.value
  page.value = next
  pending.value = null
  store.clearSelection('user')
  if (!(await load()) && active()) page.value = previous
}
onMounted(() => void load())
</script>

<template>
  <section class="admin-page">
    <header class="admin-page-header">
      <div>
        <p class="admin-kicker">( ACCOUNTS )</p>
        <h2>用户治理<span aria-hidden="true">。</span></h2>
        <p>查看账号及其空间身份；禁用只会停止访问，不删除历史数据和成员关系。</p>
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
    <p v-if="successMessage" class="admin-success" role="status">
      {{ successMessage }}
    </p>

    <div class="admin-panel" :aria-busy="store.loading">
      <form class="admin-toolbar" @submit.prevent="search">
        <label class="search-field"
          >搜索用户<input
            v-model="filters.keyword"
            placeholder="搜索账号或昵称"
            :disabled="store.loading || busy"
        /></label>
        <label
          >账号状态<select
            aria-label="账号状态"
            v-model="filters.status"
            :disabled="store.loading || busy"
          >
            <option value="">全部状态</option>
            <option value="ACTIVE">正常</option>
            <option value="DISABLED">已禁用</option>
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

      <p v-if="store.loading && !store.users.items.length" class="admin-state">正在读取用户…</p>
      <p v-else-if="!store.users.items.length" class="admin-state">没有符合条件的用户</p>
      <div
        v-else
        class="admin-table-wrap"
        tabindex="0"
        role="region"
        aria-label="用户列表，可横向滚动"
      >
        <table class="admin-table">
          <caption>
            用户基础身份
          </caption>
          <thead>
            <tr>
              <th>用户</th>
              <th>类型</th>
              <th>状态</th>
              <th>有效空间</th>
              <th>拥有空间</th>
              <th>注册时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="user in store.users.items"
              :key="user.userId"
              data-clickable="true"
              @click="openUser(user.userId)"
            >
              <td>
                <button
                  class="admin-row-link"
                  type="button"
                  :disabled="busy"
                  :aria-label="`查看用户 ${user.username}`"
                  @click.stop="openUser(user.userId)"
                >
                  {{ user.nickname }}</button
                ><br /><span class="admin-muted">{{ user.username }} · #{{ user.userId }}</span>
              </td>
              <td>{{ user.accountType === 'SUPER_ADMIN' ? '超级管理员' : '普通用户' }}</td>
              <td>
                <span class="admin-badge" :class="{ danger: user.status === 'DISABLED' }">{{
                  user.status === 'ACTIVE' ? '正常' : '已禁用'
                }}</span>
              </td>
              <td>{{ user.workspaceCount }}</td>
              <td>{{ user.ownedWorkspaceCount }}</td>
              <td>{{ formatTime(user.createdAt) }}</td>
              <td @click.stop>
                <button
                  v-if="user.status === 'ACTIVE'"
                  class="ui-button admin-button"
                  type="button"
                  :disabled="busy || user.accountType === 'SUPER_ADMIN'"
                  :title="user.accountType === 'SUPER_ADMIN' ? '唯一治理账号不能禁用' : undefined"
                  @click="requestStatus(user, 'DISABLED', $event)"
                >
                  禁用
                </button>
                <button
                  v-else
                  class="ui-button admin-button"
                  type="button"
                  :disabled="busy"
                  @click="requestStatus(user, 'ACTIVE', $event)"
                >
                  恢复
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="admin-pagination">
        <span class="admin-muted">共 {{ store.users.total }} 条</span>
        <button
          class="ui-button admin-button"
          type="button"
          :disabled="page <= 1 || store.loading || busy"
          @click="changePage(page - 1)"
        >
          上一页
        </button>
        <span>{{ store.users.page }} / {{ Math.max(store.users.totalPages, 1) }}</span>
        <button
          class="ui-button admin-button"
          type="button"
          :disabled="page >= store.users.totalPages || store.loading || busy"
          @click="changePage(page + 1)"
        >
          下一页
        </button>
      </div>
    </div>

    <section
      v-if="store.selectedUser"
      class="admin-panel admin-detail"
      aria-labelledby="user-detail-title"
    >
      <div class="admin-detail-header">
        <div>
          <h3 id="user-detail-title">{{ store.selectedUser.nickname }}</h3>
          <p class="admin-muted">
            {{ store.selectedUser.username }} · 用户 #{{ store.selectedUser.userId }}
          </p>
        </div>
        <span class="admin-badge" :class="{ danger: store.selectedUser.status === 'DISABLED' }">{{
          store.selectedUser.status === 'ACTIVE' ? '账号正常' : '账号已禁用'
        }}</span>
      </div>
      <h4>空间身份</h4>
      <p v-if="!store.selectedUser.memberships.length" class="admin-muted">
        该账号没有空间成员记录。
      </p>
      <div
        v-else
        class="admin-table-wrap"
        tabindex="0"
        role="region"
        aria-label="用户列表，可横向滚动"
      >
        <table class="admin-table">
          <caption>
            用户基础身份
          </caption>
          <thead>
            <tr>
              <th>空间</th>
              <th>角色</th>
              <th>成员状态</th>
              <th>空间状态</th>
              <th>最近加入</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in store.selectedUser.memberships" :key="item.workspaceId">
              <td>{{ item.workspaceName }} · #{{ item.workspaceId }}</td>
              <td>{{ item.role }}</td>
              <td>{{ item.membershipStatus }}</td>
              <td>{{ item.workspaceStatus }}</td>
              <td>{{ formatTime(item.lastJoinedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <AdminConfirmation
      v-if="pending"
      :title="`确认${pending.status === 'ACTIVE' ? '恢复' : '禁用'} ${pending.user.username}？`"
      :label="`确认${pending.status === 'ACTIVE' ? '恢复' : '禁用'}`"
      :busy="busy"
      :danger="pending.status !== 'ACTIVE'"
      @cancel="cancelStatus"
      @confirm="confirmStatus"
    >
      <p v-if="pending.status === 'DISABLED'">
        禁用后，该账号已有 Token 会在下一次受保护请求时失效；{{ pending.user.workspaceCount }}
        个有效空间关系和
        {{ pending.user.ownedWorkspaceCount }}
        个所有权记录继续保留。其他有效成员仍可使用活动空间，所有者治理能力需等账号恢复。
      </p>
      <p v-else>恢复只重新允许账号登录和访问，不会创建、删除或修改它原有的空间身份。</p>
      <p v-if="store.error" class="admin-error" role="alert">{{ store.error }}</p>
    </AdminConfirmation>
  </section>
</template>
