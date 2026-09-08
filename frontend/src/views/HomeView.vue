<script setup lang="ts">
import { ref } from 'vue'
import { CircleCheck, Plus } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import DesignOrbit from '@/components/DesignOrbit.vue'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'

const router = useRouter()
const authStore = useAuthStore()
const workspaceStore = useWorkspaceStore()
const notice = ref('')

function showNextStep(action: 'create' | 'join') {
  notice.value =
    action === 'create'
      ? '空间创建入口已经准备好，下一步会接入创建空间接口。'
      : '邀请码加入入口已经准备好，下一步会接入成员加入接口。'
}

async function handleLogout() {
  authStore.logout()
  workspaceStore.clearAll()
  await router.replace('/')
}
</script>

<template>
  <div class="workspace-page">
    <aside class="workspace-sidebar" aria-label="研究空间导航">
      <RouterLink class="workspace-brand" to="/" aria-label="返回同研自选首页">
        <span class="workspace-brand-mark" aria-hidden="true"><i></i><i></i></span>
        <span>同研自选</span>
      </RouterLink>

      <p class="workspace-side-label">研究空间</p>

      <div class="workspace-space-switcher" aria-label="当前研究空间">
        <span class="space-dot" aria-hidden="true"></span>
        <span>还没有研究空间</span>
        <span class="space-chevron" aria-hidden="true">⌄</span>
      </div>

      <nav class="workspace-nav" aria-label="工作台导航">
        <button class="workspace-nav-item selected" type="button">
          <span class="nav-symbol" aria-hidden="true">◌</span>
          <span>共同关注</span>
        </button>
        <button class="workspace-nav-item" type="button" disabled title="创建空间后可用">
          <span class="nav-symbol" aria-hidden="true">◍</span>
          <span>观点更新</span>
        </button>
        <button class="workspace-nav-item" type="button" disabled title="创建空间后可用">
          <span class="nav-symbol" aria-hidden="true">○</span>
          <span>成员</span>
        </button>
      </nav>

      <div class="sidebar-bottom">
        <span>空间设置</span>
        <span>使用说明</span>
      </div>
    </aside>

    <div class="workspace-main">
      <header class="workspace-topbar">
        <div>
          <span class="workspace-breadcrumb">研究空间</span>
          <strong>开始建立</strong>
        </div>
        <div class="workspace-actions">
          <span class="workspace-user">{{ authStore.currentUser?.nickname || '研究成员' }}</span>
          <button class="workspace-logout" type="button" @click="handleLogout">退出登录</button>
        </div>
      </header>

      <main class="workspace-content">
        <section class="workspace-empty-intro" aria-labelledby="workspace-title">
          <div class="workspace-empty-copy">
            <p class="workspace-date">
              欢迎回来，{{ authStore.currentUser?.nickname || '研究成员' }}
            </p>
            <h1 id="workspace-title">先建立一个<br /><span>共享研究空间。</span></h1>
            <p class="workspace-thesis">
              创建空间后，家人或伙伴可以在同一处维护共同关注，并分别留下理由、风险和随时间变化的判断。
            </p>

            <div class="workspace-entry-actions">
              <button
                class="workspace-primary-button"
                type="button"
                @click="showNextStep('create')"
              >
                创建研究空间
                <Plus aria-hidden="true" />
              </button>
              <button
                class="workspace-secondary-button"
                type="button"
                @click="showNextStep('join')"
              >
                使用邀请码加入
              </button>
            </div>

            <p v-if="notice" class="workspace-next-step" role="status">
              <CircleCheck aria-hidden="true" />{{ notice }}
            </p>
          </div>

          <div class="workspace-empty-art" aria-label="共享研究空间的抽象图形">
            <div class="empty-art-caption"><span>第一步</span><strong>从一个空间开始</strong></div>
            <DesignOrbit />
            <p class="empty-art-label">
              <CircleCheck aria-hidden="true" />创建后，成员可以一起维护关注
            </p>
          </div>
        </section>

        <section class="workspace-guidance" aria-labelledby="guidance-title">
          <div class="guidance-heading">
            <p>空间建立后</p>
            <h2 id="guidance-title">这里会记录三件事。</h2>
          </div>
          <div class="guidance-list">
            <div>
              <span>01</span><strong>共同关注</strong>
              <p>一只股票只保留一份共享记录，避免重复添加。</p>
            </div>
            <div>
              <span>02</span><strong>成员观点</strong>
              <p>每个人都可以围绕同一只股票发表自己的判断。</p>
            </div>
            <div>
              <span>03</span><strong>时间变化</strong>
              <p>作者和更新时间由系统记录，历史判断不会被覆盖。</p>
            </div>
          </div>
        </section>
      </main>

      <footer class="workspace-footer">
        <span>同研自选 · 暂定名</span>
        <span>仅用于记录与交流，不构成投资建议</span>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.workspace-page {
  --preview-ink: var(--color-ink);
  --preview-surface: var(--color-surface);
  --preview-accent: oklch(0.62 0.075 215);
  --preview-accent-soft: oklch(0.91 0.035 215);
  --preview-lilac: oklch(0.92 0.025 300);
  --preview-mint: oklch(0.91 0.045 155);
  --preview-warm: oklch(0.68 0.08 55);
  --preview-warm-soft: oklch(0.9 0.04 55);
  display: grid;
  min-height: 100svh;
  grid-template-columns: 214px minmax(0, 1fr);
  color: var(--color-ink);
  background: var(--color-canvas);
  font-family: 'Microsoft YaHei UI', 'PingFang SC', 'Segoe UI Variable', system-ui, sans-serif;
}

.workspace-sidebar {
  display: flex;
  min-height: 100svh;
  flex-direction: column;
  padding: 42px 22px 24px;
  border-right: 1px solid var(--color-border);
  background: var(--color-canvas);
}

.workspace-brand {
  display: inline-flex;
  align-items: center;
  gap: 11px;
  margin: 0 10px 18px;
  color: var(--color-ink);
  font-size: 14px;
  font-weight: 650;
}

.workspace-brand-mark {
  position: relative;
  display: block;
  width: 28px;
  height: 17px;
}

.workspace-brand-mark i {
  position: absolute;
  top: 1px;
  width: 15px;
  height: 15px;
  border: 1.5px solid currentColor;
  border-radius: 50%;
}

.workspace-brand-mark i:first-child {
  left: 0;
}

.workspace-brand-mark i:last-child {
  right: 0;
}

.workspace-side-label,
.workspace-date,
.guidance-heading > p {
  margin: 0;
  color: var(--color-muted);
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.04em;
  line-height: 1.4;
}

.workspace-side-label {
  margin: 0 10px 25px;
  color: oklch(0.55 0.06 215);
  font-size: 10px;
}

.workspace-space-switcher {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 49px;
  padding: 14px 10px;
  border-bottom: 1px solid var(--color-border);
  color: var(--color-muted);
  font-size: 11px;
}

.space-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-faint);
}

.space-chevron {
  margin-left: auto;
  color: var(--color-muted);
}

.workspace-nav {
  display: grid;
  gap: 4px;
  margin-top: 25px;
}

.workspace-nav-item {
  display: grid;
  grid-template-columns: 15px minmax(0, 1fr);
  align-items: center;
  gap: 11px;
  padding: 12px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--color-muted);
  cursor: pointer;
  font-size: 12px;
  text-align: left;
}

.workspace-nav-item:hover:not(:disabled) {
  background: color-mix(in oklab, var(--preview-accent-soft) 42%, transparent);
  color: var(--color-ink);
}

.workspace-nav-item.selected {
  color: var(--color-ink);
  font-weight: 700;
}

.workspace-nav-item.selected .nav-symbol {
  color: oklch(0.56 0.09 220);
}

.workspace-nav-item:disabled {
  cursor: not-allowed;
  opacity: 0.48;
}

.nav-symbol {
  color: var(--color-faint);
  font-size: 17px;
  line-height: 1;
  text-align: center;
}

.sidebar-bottom {
  display: grid;
  gap: 13px;
  margin-top: auto;
  padding: 0 10px;
  color: var(--color-muted);
  font-size: 11px;
}

.workspace-main {
  min-width: 0;
  padding: 0 clamp(24px, 5vw, 76px) 70px;
}

.workspace-topbar {
  display: flex;
  min-height: 76px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--color-border);
}

.workspace-breadcrumb {
  display: block;
  margin-bottom: 6px;
  color: var(--color-muted);
  font-size: 10px;
}

.workspace-topbar strong {
  font-size: 14px;
}

.workspace-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}

.workspace-user {
  color: var(--color-muted);
  font-size: 11px;
}

.workspace-logout {
  padding: 7px 0;
  border: 0;
  border-bottom: 1px solid color-mix(in oklab, var(--color-ink) 42%, transparent);
  background: transparent;
  color: var(--color-ink);
  cursor: pointer;
  font-size: 11px;
}

.workspace-content {
  padding-top: 72px;
}

.workspace-empty-intro {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(340px, 0.9fr);
  align-items: center;
  gap: clamp(32px, 7vw, 110px);
  min-height: 500px;
}

.workspace-empty-copy {
  position: relative;
  z-index: 1;
  min-width: 0;
}

.workspace-date {
  margin-bottom: 22px;
  color: oklch(0.55 0.06 215);
}

.workspace-empty-copy h1 {
  max-width: 10ch;
  color: var(--color-heading);
  font-size: clamp(52px, 5.7vw, 84px);
  font-weight: 560;
  letter-spacing: -0.038em;
  line-height: 1;
  text-wrap: balance;
}

.workspace-empty-copy h1 span {
  color: oklch(0.56 0.09 220);
  white-space: nowrap;
}

.workspace-thesis {
  max-width: 39ch;
  margin-top: 28px;
  color: var(--color-text);
  font-size: 14px;
  line-height: 1.8;
  text-wrap: pretty;
}

.workspace-entry-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 22px;
  margin-top: 28px;
}

.workspace-primary-button,
.workspace-secondary-button {
  height: 48px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 700;
}

.workspace-primary-button {
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 0 18px;
  border: 0;
  border-radius: var(--radius-action);
  background: var(--color-ink);
  color: white;
}

.workspace-primary-button:hover {
  background: oklch(0.16 0.006 255);
}

.workspace-primary-button svg {
  width: 16px;
}

.workspace-secondary-button {
  padding: 4px 0;
  border: 0;
  border-bottom: 1px solid color-mix(in oklab, var(--color-ink) 48%, transparent);
  background: transparent;
  color: var(--color-ink);
}

.workspace-next-step {
  display: flex;
  max-width: 46ch;
  align-items: flex-start;
  gap: 8px;
  margin-top: 20px;
  color: var(--color-muted);
  font-size: 11px;
  line-height: 1.7;
}

.workspace-next-step svg {
  flex: 0 0 auto;
  width: 15px;
  margin-top: 2px;
  color: oklch(0.48 0.08 160);
}

.workspace-empty-art {
  position: relative;
  z-index: 1;
  min-width: 0;
}

.empty-art-caption {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 4px;
  color: var(--color-muted);
}

.empty-art-caption span {
  font-size: 10px;
}

.empty-art-caption strong {
  color: var(--color-ink);
  font-size: 14px;
}

.workspace-empty-art .design-orbit {
  display: block;
  width: 100%;
  max-width: 560px;
  margin: 0 auto;
}

.empty-art-label {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin: -13px 0 0;
  color: var(--color-muted);
  font-size: 11px;
  text-align: right;
}

.empty-art-label svg {
  width: 14px;
  color: oklch(0.48 0.08 160);
}

.workspace-guidance {
  display: grid;
  grid-template-columns: minmax(210px, 0.7fr) minmax(0, 1fr);
  gap: clamp(34px, 7vw, 100px);
  margin-top: 76px;
  padding-top: 24px;
  border-top: 1px solid var(--color-border);
}

.guidance-heading h2 {
  margin-top: 14px;
  color: var(--color-heading);
  font-size: 26px;
  font-weight: 600;
  letter-spacing: -0.03em;
}

.guidance-list {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  background: var(--color-border);
}

.guidance-list > div {
  min-height: 154px;
  padding: 18px;
  background: var(--color-canvas);
}

.guidance-list span {
  color: oklch(0.55 0.07 165);
  font-size: 11px;
}

.guidance-list strong {
  display: block;
  margin-top: 27px;
  color: var(--color-ink);
  font-size: 13px;
}

.guidance-list p {
  margin-top: 8px;
  color: var(--color-muted);
  font-size: 11px;
  line-height: 1.7;
}

.workspace-footer {
  display: flex;
  min-height: 50px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-top: 70px;
  padding-top: 16px;
  border-top: 1px solid var(--color-border);
  color: var(--color-text);
  font-size: 11px;
}

@media (max-width: 980px) {
  .workspace-empty-intro {
    grid-template-columns: minmax(0, 1fr) minmax(300px, 0.85fr);
    gap: 36px;
  }

  .workspace-empty-copy h1 {
    font-size: clamp(46px, 6vw, 68px);
  }
}

@media (max-width: 820px) {
  .workspace-page {
    display: block;
  }

  .workspace-sidebar {
    min-height: auto;
    flex-direction: row;
    align-items: center;
    gap: 18px;
    padding: 17px 20px;
    border-right: 0;
    border-bottom: 1px solid var(--color-border);
  }

  .workspace-brand {
    margin: 0;
  }

  .workspace-side-label,
  .sidebar-bottom {
    display: none;
  }

  .workspace-space-switcher {
    min-width: 140px;
    margin-left: auto;
    border-bottom: 0;
  }

  .workspace-nav {
    display: flex;
    margin: 0;
  }

  .workspace-nav-item {
    width: 40px;
    grid-template-columns: 1fr;
    padding: 8px;
  }

  .workspace-nav-item > span:last-child {
    display: none;
  }

  .workspace-main {
    padding-inline: 24px;
  }

  .workspace-guidance {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 620px) {
  .workspace-sidebar {
    overflow-x: auto;
  }

  .workspace-brand > span:last-child {
    display: none;
  }

  .workspace-main {
    padding-inline: 20px;
  }

  .workspace-topbar {
    min-height: 68px;
  }

  .workspace-user {
    display: none;
  }

  .workspace-content {
    padding-top: 48px;
  }

  .workspace-empty-intro {
    display: flex;
    min-height: auto;
    flex-direction: column;
    align-items: stretch;
    gap: 32px;
  }

  .workspace-empty-copy h1 {
    font-size: clamp(36px, 10.4vw, 52px);
  }

  .workspace-empty-copy h1 span {
    white-space: nowrap;
  }

  .workspace-empty-art {
    width: min(100%, 480px);
    margin-inline: auto;
  }

  .workspace-guidance {
    margin-top: 58px;
  }

  .guidance-list {
    grid-template-columns: 1fr;
  }

  .guidance-list > div {
    min-height: auto;
  }

  .guidance-list strong {
    margin-top: 15px;
  }

  .workspace-footer {
    margin-top: 58px;
  }

  .workspace-footer span:last-child {
    display: none;
  }
}
</style>
