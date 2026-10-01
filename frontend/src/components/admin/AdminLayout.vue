<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import '@/styles/admin.css'
const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const workspaceStore = useWorkspaceStore()
const navigation = [
  { path: '/admin/users', label: '用户治理' },
  { path: '/admin/workspaces', label: '空间治理' },
  { path: '/admin/operation-logs', label: '操作记录' },
]
const currentPage = computed(
  () => navigation.find((item) => item.path === route.path)?.label ?? '治理后台',
)
function logout() {
  authStore.logout()
  workspaceStore.clearAll()
  return router.replace('/login')
}
</script>
<template>
  <div class="admin-shell research-theme">
    <a class="ui-skip-link" href="#admin-content">跳到治理内容</a>
    <aside class="admin-sidebar">
      <div>
        <RouterLink class="ui-brand" to="/" aria-label="基点首页"
          >基点<span aria-hidden="true">。</span></RouterLink
        >
        <p class="admin-kicker">( SYSTEM GOVERNANCE )</p>
        <h1 class="admin-title">治理后台</h1>
        <p class="admin-subtitle">仅处理账号和空间基础状态</p>
      </div>
      <nav aria-label="治理后台导航">
        <span class="admin-nav-label">( 导航 )</span>
        <RouterLink
          v-for="(item, index) in navigation"
          :key="item.path"
          :to="item.path"
          active-class="active"
          :aria-label="item.label"
          :aria-current="route.path === item.path ? 'page' : undefined"
          ><span aria-hidden="true">0{{ index + 1 }}</span
          >{{ item.label }}</RouterLink
        >
      </nav>
      <div class="admin-account">
        <RouterLink to="/profile" aria-label="个人资料"
          ><strong>{{ authStore.currentUser?.nickname }}</strong
          ><small>@{{ authStore.currentUser?.username }}</small
          ><span>个人资料 <span aria-hidden="true">↗</span></span></RouterLink
        >
        <button class="ui-text-action" type="button" @click="logout">退出治理后台</button>
      </div>
    </aside>
    <div class="admin-main">
      <header class="admin-topbar">
        <span
          >治理后台 <span aria-hidden="true">/</span> <strong>{{ currentPage }}</strong></span
        ><span class="ui-chip">超级管理员</span>
      </header>
      <main id="admin-content" class="admin-content" tabindex="-1"><RouterView /></main>
    </div>
  </div>
</template>
