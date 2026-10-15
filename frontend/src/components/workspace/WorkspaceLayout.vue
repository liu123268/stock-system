<script setup lang="ts">
import { computed, watch } from 'vue'
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
const workspace = computed(() =>
  workspaceStore.currentWorkspace?.workspaceId === workspaceId.value
    ? workspaceStore.currentWorkspace
    : null,
)
const navigation = [
  { path: 'overview', label: '概览' },
  { path: 'stocks', label: '共享自选' },
  { path: 'members', label: '成员' },
  { path: 'settings', label: '设置' },
]
const currentSection = computed(() => route.path.split('/')[3] ?? 'overview')
const isResearchAgentPage = computed(() => route.name === 'workspace-research-agent')
const currentPage = computed(() => {
  if (isResearchAgentPage.value) return '研究助手'
  if (route.name === 'workspace-stock-market') return '股票行情'
  if (route.name === 'workspace-stock-research') return '研究详情'
  return navigation.find((item) => item.path === currentSection.value)?.label ?? '空间'
})

function switchWorkspace() {
  workspaceStore.clearCurrent()
  stockStore.clearAll()
  // 主动选择与登录后的自动分流不同，即使只有一个活动空间也保留选择页。
  return router.push({ name: 'workspaces', query: { reason: 'switch' } })
}

function logout() {
  authStore.logout()
  workspaceStore.clearAll()
  return router.push('/')
}

watch(
  workspaceId,
  () => {
    workspaceStore.clearCurrent()
    stockStore.clearAll()
  },
  { flush: 'sync' },
)
</script>

<template>
  <div class="workspace-shell research-theme" :class="{ 'workspace-shell--agent': isResearchAgentPage }">
    <a class="ui-skip-link" href="#workspace-content">跳到页面内容</a>
    <aside class="workspace-nav">
      <div class="ui-brand" aria-label="基点">基点<span aria-hidden="true">。</span></div>
      <button class="switcher" type="button" @click="switchWorkspace">
        <span :title="workspace?.name">{{ workspace?.name ?? '研究空间' }}</span>
        <small>切换空间 <span aria-hidden="true">↗</span></small>
      </button>
      <nav aria-label="空间主导航">
        <span class="nav-label">( 导航 )</span>
        <RouterLink
          v-for="(item, index) in navigation"
          :key="item.path"
          :to="`/workspaces/${workspaceId}/${item.path}`"
          :class="{ active: currentSection === item.path }"
          :aria-current="currentSection === item.path ? 'page' : undefined"
          :aria-label="item.label"
        >
          <span class="nav-index" aria-hidden="true">0{{ index + 1 }}</span
          >{{ item.label }}
        </RouterLink>
      </nav>
      <div class="account-area">
        <RouterLink class="account-link" to="/profile" aria-label="个人资料">
          <strong>{{ authStore.currentUser?.nickname }}</strong>
          <small>@{{ authStore.currentUser?.username }}</small>
          <span class="profile-label">个人资料 <span aria-hidden="true">↗</span></span>
        </RouterLink>
        <button class="ui-text-action logout-btn" type="button" @click="logout">退出登录</button>
      </div>
    </aside>

    <div class="workspace-main">
      <header class="workspace-topbar">
        <div class="breadcrumb" aria-label="当前位置">
          <span class="workspace-name">{{ workspace?.name ?? '研究空间' }}</span>
          <span aria-hidden="true">/</span><strong>{{ currentPage }}</strong>
        </div>
        <div class="topbar-actions">
          <span v-if="workspace && workspace.status !== 'ACTIVE'" class="ui-chip"
            >已归档 · 只读</span
          >
          <button class="ui-text-action" type="button" @click="switchWorkspace">
            返回空间选择
          </button>
        </div>
      </header>
      <main id="workspace-content" class="workspace-content" tabindex="-1">
        <!-- 空间改变时卸载旧子页；同空间内股票参数仍沿用页面原有监听。 -->
        <RouterView :key="workspaceId" />
      </main>
      <footer v-if="!isResearchAgentPage" class="workspace-footer">
        <span>基点 · 共同研究，保留各自的判断</span>
        <span>行情与观点仅供研究参考</span>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.workspace-shell {
  display: grid;
  grid-template-columns: 236px minmax(0, 1fr);
  min-height: 100svh;
}
.workspace-nav {
  position: sticky;
  top: 0;
  display: flex;
  height: 100svh;
  flex-direction: column;
  overflow-y: auto;
  padding: 26px 20px 22px;
  border-right: 1px solid var(--color-border);
  background: var(--color-surface);
}
.switcher {
  width: 100%;
  margin-top: 26px;
  padding: 12px 13px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color 180ms ease-out;
}
.switcher:hover {
  border-color: var(--color-ink);
}
.switcher > span {
  display: block;
  overflow: hidden;
  font-size: 14px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.switcher small {
  display: block;
  margin-top: 4px;
  color: var(--color-muted);
  font-size: 11px;
}
.workspace-nav nav {
  display: grid;
  gap: 2px;
  margin-top: 26px;
}
.nav-label {
  padding: 0 10px 12px;
  color: var(--color-muted);
  font-size: 11px;
  letter-spacing: 0.1em;
}
.workspace-nav nav a {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 12px;
  padding: 10px;
  border-radius: 8px;
  color: var(--color-muted);
  font-size: 13px;
  transition:
    color 180ms ease-out,
    background-color 180ms ease-out;
}
.nav-index {
  font-family: var(--font-mono);
  font-size: 11px;
}
.workspace-nav nav a:hover {
  background: var(--color-surface-secondary);
  color: var(--color-ink);
}
.workspace-nav nav a.active {
  background: color-mix(in oklab, var(--color-accent) 12%, transparent);
  color: var(--color-ink);
  font-weight: 600;
}
.active .nav-index {
  color: var(--color-focus);
}
.account-area {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: auto;
  padding-top: 16px;
  border-top: 1px solid var(--color-border);
}
.account-link {
  min-width: 0;
  padding-block: 8px;
  font-size: 13px;
}
.account-link strong,
.account-link small {
  display: block;
  overflow-wrap: anywhere;
}
.account-link small {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}
.profile-label {
  display: block;
  margin-top: 7px;
  color: var(--color-muted);
  font-size: 11px;
}
.logout-btn {
  flex-shrink: 0;
  font-size: 11px;
}
.logout-btn:hover {
  color: var(--color-danger);
}
.workspace-main {
  display: flex;
  min-width: 0;
  flex-direction: column;
}
.workspace-topbar {
  position: sticky;
  top: 0;
  z-index: var(--layer-sticky);
  display: flex;
  min-height: 57px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 8px var(--gutter);
  border-bottom: 1px solid var(--color-border);
  background: var(--color-canvas);
  font-size: 12px;
}
.breadcrumb {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
  color: var(--color-muted);
}
.workspace-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.breadcrumb strong {
  flex-shrink: 0;
  color: var(--color-ink);
  font-weight: 500;
}
.topbar-actions {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 14px;
}
.workspace-content {
  flex: 1;
  min-width: 0;
  width: 100%;
  max-width: 1352px;
  padding: 0 var(--gutter) 40px;
  scroll-margin-top: 80px;
}
.workspace-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 10px;
  padding: 24px var(--gutter);
  border-top: 1px solid var(--color-border);
  color: var(--color-muted);
  font-size: 11px;
}
.workspace-shell--agent { height: 100dvh; min-height: 0; overflow: hidden; grid-template-rows: minmax(0, 1fr); }
.workspace-shell--agent .workspace-main { min-height: 0; }
.workspace-shell--agent .workspace-nav { height: 100dvh; }
.workspace-shell--agent .workspace-topbar { flex-shrink: 0; }
.workspace-shell--agent .workspace-content { display: flex; min-height: 0; padding-bottom: 16px; }
@media (max-width: 960px) {
  .workspace-shell.workspace-shell--agent { display: flex; flex-direction: column; }
  .workspace-shell--agent .workspace-main { flex: 1; }
  .workspace-shell--agent .workspace-nav { display: none; }
  .workspace-shell {
    display: block;
  }
  .workspace-nav {
    position: static;
    height: auto;
    flex-direction: row;
    flex-wrap: wrap;
    align-items: center;
    gap: 14px;
    overflow: visible;
    padding: 14px 24px;
    border-right: 0;
    border-bottom: 1px solid var(--color-border);
  }
  .switcher {
    width: auto;
    min-width: 0;
    flex: 1;
    margin-top: 0;
  }
  .workspace-nav nav {
    order: 3;
    width: 100%;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    margin: 0;
  }
  .nav-label {
    display: none;
  }
  .workspace-nav nav a {
    justify-content: center;
    gap: 8px;
    padding-inline: 4px;
  }
  .account-area {
    max-width: 240px;
    margin: 0;
    padding: 0;
    border: 0;
  }
  .account-link {
    font-size: 12px;
  }
  .account-link small {
    display: none;
  }
  .profile-label {
    margin-top: 3px;
  }
  .workspace-topbar {
    flex-wrap: wrap;
    gap: 4px 16px;
  }
  .breadcrumb {
    flex: 1 1 130px;
  }
}
@media (max-width: 560px) {
  .workspace-nav {
    gap: 10px;
    padding: 14px 20px;
  }
  .account-area {
    order: 4;
    width: 100%;
    max-width: none;
    padding-top: 6px;
    border-top: 1px solid var(--color-border);
  }
  .account-link {
    display: flex;
    max-width: 75%;
    align-items: center;
    gap: 12px;
  }
  .profile-label {
    flex-shrink: 0;
    margin: 0;
  }
  .nav-index {
    display: none;
  }
  .topbar-actions {
    flex-wrap: wrap;
    gap: 8px;
  }
}
</style>
