<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useStockStore } from '@/stores/stock'
import { useWorkspaceStore } from '@/stores/workspace'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const stockStore = useStockStore()
const workspaceStore = useWorkspaceStore()
const workspaceId = computed(() => Number(route.params.workspaceId))

function switchWorkspace() {
  workspaceStore.clearCurrent()
  stockStore.clearAll()
  return router.push('/workspaces')
}

function logout() {
  authStore.logout()
  workspaceStore.clearAll()
  return router.push('/')
}
</script>

<template>
  <div class="workspace-shell">
    <aside class="workspace-nav">
      <button class="brand logout-btn" type="button" @click="logout">退出登录</button>
      <button class="switcher" type="button" @click="switchWorkspace">
        <span>{{ workspaceStore.currentWorkspace?.name ?? '研究空间' }}</span>
        <small>切换空间</small>
      </button>
      <nav aria-label="空间主导航">
        <RouterLink :to="`/workspaces/${workspaceId}/overview`" exact-active-class="active">
          概览
        </RouterLink>
        <RouterLink :to="`/workspaces/${workspaceId}/stocks`" exact-active-class="active">
          共享自选
        </RouterLink>
        <RouterLink :to="`/workspaces/${workspaceId}/members`" exact-active-class="active">
          成员
        </RouterLink>
        <RouterLink :to="`/workspaces/${workspaceId}/settings`" exact-active-class="active">
          设置
        </RouterLink>
      </nav>
    </aside>

    <main class="workspace-main">
      <header>
        <span>{{ authStore.currentUser?.nickname }}</span>
        <button type="button" @click="switchWorkspace">返回空间选择</button>
      </header>
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.workspace-shell {
  display: grid;
  min-height: 100svh;
  grid-template-columns: 224px minmax(0, 1fr);
  color: var(--color-ink);
}
.workspace-nav {
  display: flex;
  min-height: 100svh;
  flex-direction: column;
  padding: 32px 22px;
  border-right: 1px solid var(--color-border);
  background: var(--color-surface);
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
.logout-btn:focus-visible,
.switcher:focus-visible,
.workspace-nav nav a:focus-visible,
.workspace-main > header button:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 3px;
}
.switcher {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 32px;
  padding: 14px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
}
.switcher span {
  overflow: hidden;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.switcher small {
  flex: 0 0 auto;
  color: var(--color-muted);
}
.workspace-nav nav {
  display: grid;
  gap: 5px;
  margin-top: 26px;
}
.workspace-nav nav a {
  padding: 11px 10px;
  border-radius: 8px;
  font-size: 13px;
}
.workspace-nav nav a:hover {
  background: color-mix(in oklab, var(--color-focus) 5%, transparent);
}
.workspace-nav nav .active {
  background: color-mix(in oklab, var(--color-focus) 9%, transparent);
  font-weight: 700;
}
.workspace-main {
  min-width: 0;
  padding: 0 clamp(24px, 6vw, 86px) 72px;
}
.workspace-main > header {
  display: flex;
  height: var(--header-height);
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--color-border);
  color: var(--color-muted);
  font-size: 13px;
}
.workspace-main > header button {
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
@media (max-width: 820px) {
  .workspace-shell {
    display: block;
  }
  .workspace-nav {
    min-height: auto;
    flex-wrap: wrap;
    flex-direction: row;
    align-items: center;
    padding: 14px 18px;
    border-right: 0;
    border-bottom: 1px solid var(--color-border);
  }
  .switcher {
    flex: 1 1 0;
    margin-top: 0;
    margin-left: auto;
    border: 0;
  }
  .switcher span {
    min-width: 0;
  }
  .workspace-nav nav {
    display: grid;
    width: 100%;
    grid-template-columns: repeat(4, 1fr);
    margin-top: 10px;
    text-align: center;
  }
  .workspace-main {
    padding-inline: 20px;
  }
  .workspace-main > header {
    display: none;
  }
}
@media (max-width: 640px) {
  .workspace-main {
    padding-bottom: 56px;
  }
}
</style>
