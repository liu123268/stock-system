<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useAdminStore } from '@/stores/admin'
import { useWorkspaceStore } from '@/stores/workspace'

const router = useRouter()
const authStore = useAuthStore()
const adminStore = useAdminStore()
const workspaceStore = useWorkspaceStore()

function logout() {
  adminStore.clearAll()
  workspaceStore.clearAll()
  authStore.logout()
  return router.replace('/login')
}
</script>

<template>
  <div class="admin-shell">
    <aside class="admin-sidebar">
      <div>
        <p class="admin-kicker">SYSTEM GOVERNANCE</p>
        <h1>治理后台</h1>
        <span>仅处理账号和空间基础状态</span>
      </div>

      <nav aria-label="治理后台导航">
        <RouterLink to="/admin/users" active-class="active">用户治理</RouterLink>
        <RouterLink to="/admin/workspaces" active-class="active">空间治理</RouterLink>
        <RouterLink to="/admin/operation-logs" active-class="active">操作记录</RouterLink>
      </nav>

      <div class="admin-account">
        <strong>{{ authStore.currentUser?.nickname }}</strong>
        <span>{{ authStore.currentUser?.username }}</span>
        <button type="button" @click="logout">退出治理后台</button>
      </div>
    </aside>

    <main class="admin-main">
      <RouterView />
    </main>
  </div>
</template>

<style>
.admin-shell {
  display: grid;
  min-height: 100svh;
  grid-template-columns: 236px minmax(0, 1fr);
  background: #f6f7f8;
  color: var(--color-ink);
}
.admin-sidebar {
  position: sticky;
  top: 0;
  display: flex;
  height: 100svh;
  flex-direction: column;
  padding: 30px 22px;
  border-right: 1px solid var(--color-border);
  background: #101718;
  color: #edf4f1;
}
.admin-kicker {
  color: #7fb7aa;
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 0.14em;
}
.admin-sidebar h1 {
  margin: 8px 0 5px;
  font-size: 24px;
}
.admin-sidebar > div > span,
.admin-account span {
  color: #94a5a0;
  font-size: 12px;
}
.admin-sidebar nav {
  display: grid;
  gap: 5px;
  margin-top: 38px;
}
.admin-sidebar nav a {
  padding: 11px 12px;
  border-radius: 8px;
  color: #b8c6c2;
  font-size: 14px;
}
.admin-sidebar nav a:hover,
.admin-sidebar nav a.active {
  background: #1d2a29;
  color: #fff;
}
.admin-account {
  display: grid;
  gap: 4px;
  margin-top: auto;
  padding-top: 18px;
  border-top: 1px solid #2b3736;
}
.admin-account button {
  margin-top: 10px;
  padding: 0;
  border: 0;
  background: transparent;
  color: #d69b91;
  cursor: pointer;
  text-align: left;
}
.admin-main {
  min-width: 0;
  padding: 42px clamp(24px, 5vw, 72px) 72px;
}
.admin-page {
  max-width: 1180px;
  margin: 0 auto;
}
.admin-page-header {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 26px;
}
.admin-page-header h2 {
  margin: 5px 0 7px;
  font-size: clamp(26px, 4vw, 38px);
}
.admin-page-header p,
.admin-muted {
  color: var(--color-muted);
}
.admin-panel {
  border: 1px solid var(--color-border);
  border-radius: 14px;
  background: #fff;
}
.admin-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 16px;
  border-bottom: 1px solid var(--color-border);
}
.admin-toolbar input,
.admin-toolbar select {
  min-height: 40px;
  padding: 0 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: #fff;
}
.admin-toolbar input {
  min-width: min(100%, 260px);
}
.admin-button {
  min-height: 40px;
  padding: 0 16px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: #fff;
  color: inherit;
  cursor: pointer;
}
.admin-button.primary {
  border-color: #183f3b;
  background: #183f3b;
  color: #fff;
}
.admin-button.danger {
  border-color: #b94a3b;
  background: #b94a3b;
  color: #fff;
}
.admin-button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.admin-table-wrap {
  overflow-x: auto;
}
.admin-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.admin-table th,
.admin-table td {
  padding: 13px 16px;
  border-bottom: 1px solid #edf0ef;
  text-align: left;
  white-space: nowrap;
}
.admin-table th {
  color: var(--color-muted);
  font-size: 11px;
  letter-spacing: 0.04em;
}
.admin-table tr:last-child td {
  border-bottom: 0;
}
.admin-table tbody tr[data-clickable='true'] {
  cursor: pointer;
}
.admin-table tbody tr[data-clickable='true']:hover {
  background: #f6f9f8;
}
.admin-badge {
  display: inline-flex;
  padding: 4px 8px;
  border-radius: 999px;
  background: #e7f2ef;
  color: #20594f;
  font-size: 11px;
  font-weight: 700;
}
.admin-badge.danger,
.admin-badge.failed {
  background: #f8e9e6;
  color: #9a352b;
}
.admin-badge.muted {
  background: #eceeed;
  color: #5c6663;
}
.admin-detail {
  margin-top: 20px;
  padding: 22px;
}
.admin-detail-header {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 20px;
}
.admin-detail-header h3 {
  margin: 0 0 5px;
  font-size: 20px;
}
.admin-confirm {
  margin-top: 18px;
  padding: 18px;
  border: 1px solid #d6a097;
  border-radius: 10px;
  background: #fff8f6;
}
.admin-confirm h3 {
  margin: 0 0 7px;
}
.admin-confirm p {
  color: #69433d;
  line-height: 1.7;
}
.admin-confirm-actions,
.admin-pagination {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 14px;
}
.admin-pagination {
  align-items: center;
  justify-content: flex-end;
  padding: 16px;
  border-top: 1px solid var(--color-border);
}
.admin-state {
  padding: 34px 20px;
  color: var(--color-muted);
  text-align: center;
}
.admin-error {
  margin-bottom: 16px;
  padding: 12px 14px;
  border: 1px solid #e4b0a9;
  border-radius: 8px;
  background: #fff5f3;
  color: #8a3329;
}
@media (max-width: 760px) {
  .admin-shell {
    display: block;
  }
  .admin-sidebar {
    position: static;
    height: auto;
    padding: 18px;
  }
  .admin-sidebar nav {
    grid-template-columns: repeat(3, 1fr);
    margin-top: 18px;
    text-align: center;
  }
  .admin-account {
    display: none;
  }
  .admin-main {
    padding: 28px 16px 54px;
  }
  .admin-page-header,
  .admin-detail-header {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
