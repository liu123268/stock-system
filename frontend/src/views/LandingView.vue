<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'
import DesignOrbit from '@/components/DesignOrbit.vue'

const authStore = useAuthStore()
</script>

<template>
  <div class="landing-page">
    <header class="landing-header">
      <RouterLink class="landing-brand" to="/" aria-label="同研自选首页">
        <span class="landing-brand-mark" aria-hidden="true"><i></i><i></i></span>
        <span>同研自选</span>
      </RouterLink>

      <nav class="landing-actions" aria-label="账号入口">
        <RouterLink v-if="authStore.isAuthenticated" class="workspace-link" to="/workspace">
          进入工作台
        </RouterLink>
        <template v-else>
          <RouterLink class="login-link" to="/login">登录</RouterLink>
          <RouterLink class="register-link" to="/register">创建账号</RouterLink>
        </template>
      </nav>
    </header>

    <main class="landing-hero">
      <section class="landing-copy" aria-labelledby="landing-title">
        <p class="landing-context">同研自选 <span>/ 共同研究</span></p>
        <h1 id="landing-title">把共同关注，<br /><span>留在时间里。</span></h1>
        <p class="landing-description">
          一起看同一只股票，也保留每个人不同的判断、风险和更新时刻。
        </p>

        <div class="landing-hero-actions">
          <RouterLink
            v-if="authStore.isAuthenticated"
            class="landing-primary-action"
            to="/workspace"
          >
            进入工作台 <span aria-hidden="true">↗</span>
          </RouterLink>
          <RouterLink v-else class="landing-primary-action" to="/register">
            创建账号 <span aria-hidden="true">↗</span>
          </RouterLink>
          <RouterLink class="landing-secondary-action" to="/login">已有账号，直接登录</RouterLink>
        </div>
      </section>

      <section class="landing-visual" aria-label="共同关注与时间轨迹的抽象图形">
        <div class="visual-note visual-note-top">3 人 · 1 个空间</div>
        <DesignOrbit />
        <div class="visual-note visual-note-bottom">观点会变化，记录不会消失</div>
      </section>
    </main>

    <footer class="landing-footer">
      <div class="landing-footer-items" id="product-note">
        <span>共享关注</span>
        <span>独立观点</span>
        <span>作者与时间自动记录</span>
      </div>
      <span>仅用于记录与交流，不构成投资建议</span>
    </footer>
  </div>
</template>

<style scoped>
.landing-page {
  --preview-ink: var(--color-ink);
  --preview-surface: var(--color-surface);
  --preview-accent: oklch(0.62 0.075 215);
  --preview-accent-soft: oklch(0.91 0.035 215);
  --preview-lilac: oklch(0.92 0.025 300);
  --preview-mint: oklch(0.91 0.045 155);
  --preview-warm: oklch(0.68 0.08 55);
  --preview-warm-soft: oklch(0.9 0.04 55);
  position: relative;
  display: flex;
  min-height: 100svh;
  flex-direction: column;
  overflow: hidden;
  isolation: isolate;
  color: var(--color-ink);
  background: var(--color-canvas);
  font-family: 'Microsoft YaHei UI', 'PingFang SC', 'Segoe UI Variable', system-ui, sans-serif;
}

.landing-header {
  position: relative;
  z-index: 2;
  display: flex;
  height: var(--header-height);
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--gutter);
  border-bottom: 1px solid color-mix(in oklab, var(--color-ink) 13%, transparent);
  background: color-mix(in oklab, var(--color-canvas) 58%, transparent);
  backdrop-filter: blur(14px) saturate(0.9);
}

.landing-brand {
  display: inline-flex;
  align-items: center;
  gap: 11px;
  color: var(--color-ink);
  font-size: 14px;
  font-weight: 650;
}

.landing-brand-mark {
  position: relative;
  display: block;
  width: 28px;
  height: 17px;
}

.landing-brand-mark i {
  position: absolute;
  top: 1px;
  width: 15px;
  height: 15px;
  border: 1.5px solid currentColor;
  border-radius: 50%;
}

.landing-brand-mark i:first-child {
  left: 0;
}

.landing-brand-mark i:last-child {
  right: 0;
  mix-blend-mode: multiply;
}

.landing-actions,
.landing-hero-actions,
.landing-footer-items {
  display: flex;
  align-items: center;
}

.landing-actions {
  gap: 22px;
  font-size: 13px;
}

.login-link {
  color: var(--color-text);
  transition: color 180ms var(--ease-out);
}

.login-link:hover {
  color: var(--color-ink);
}

.register-link,
.workspace-link {
  padding: 10px 16px;
  border-radius: var(--radius-action);
  background: var(--color-ink);
  color: white;
  font-weight: 600;
  transition:
    background 180ms var(--ease-out),
    transform 180ms var(--ease-out);
}

.register-link:hover,
.workspace-link:hover {
  background: oklch(0.16 0.006 255);
}

.register-link:active,
.workspace-link:active {
  transform: scale(0.98);
}

.landing-hero {
  position: relative;
  z-index: 1;
  display: grid;
  width: 100%;
  min-height: 0;
  flex: 1 1 auto;
  grid-template-columns: minmax(0, 1.02fr) minmax(360px, 0.98fr);
  align-items: center;
  gap: clamp(30px, 6vw, 104px);
  padding: clamp(52px, 8vh, 90px) var(--gutter) clamp(56px, 8vh, 92px);
}

.landing-copy,
.landing-visual {
  min-width: 0;
}

.landing-context {
  margin: 0 0 22px;
  color: var(--color-ink);
  font-size: 12px;
  font-weight: 650;
  line-height: 1.5;
}

.landing-context span {
  color: var(--color-muted);
  font-weight: 450;
}

.landing-copy h1 {
  max-width: 11ch;
  color: var(--color-heading);
  font-size: clamp(56px, 6.7vw, 94px);
  font-weight: 560;
  letter-spacing: -0.038em;
  line-height: 0.99;
  text-wrap: balance;
}

.landing-copy h1 span {
  color: oklch(0.56 0.09 220);
  white-space: nowrap;
}

.landing-description {
  max-width: 35ch;
  margin: 38px 0 0;
  color: var(--color-text);
  font-size: 15px;
  line-height: 1.8;
  text-wrap: pretty;
}

.landing-hero-actions {
  flex-wrap: wrap;
  gap: 22px;
  margin-top: 28px;
}

.landing-primary-action {
  display: inline-flex;
  height: 48px;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 0 18px;
  border-radius: var(--radius-action);
  background: var(--color-ink);
  color: white;
  font-size: 13px;
  font-weight: 700;
  transition:
    background 180ms var(--ease-out),
    transform 180ms var(--ease-out);
}

.landing-primary-action:hover {
  background: oklch(0.16 0.006 255);
}

.landing-primary-action:active {
  transform: scale(0.98);
}

.landing-secondary-action {
  padding: 4px 0;
  border-bottom: 1px solid color-mix(in oklab, var(--color-ink) 48%, transparent);
  color: var(--color-ink);
  font-size: 12px;
}

.landing-visual {
  position: relative;
  width: 100%;
  padding: 44px 0 30px;
}

.landing-visual .design-orbit {
  width: 100%;
  max-width: 580px;
  margin: 0 auto;
}

.visual-note {
  position: absolute;
  z-index: 1;
  padding: 9px 12px;
  border: 1px solid color-mix(in oklab, var(--color-ink) 15%, transparent);
  border-radius: var(--radius-action);
  background: color-mix(in oklab, var(--color-surface) 72%, transparent);
  color: var(--color-muted);
  font-size: 11px;
  backdrop-filter: blur(8px);
}

.visual-note-top {
  top: 4%;
  right: 1%;
}

.visual-note-bottom {
  bottom: 3%;
  left: 4%;
}

.landing-footer {
  position: relative;
  z-index: 2;
  display: flex;
  min-height: 50px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 12px var(--gutter);
  border-top: 1px solid color-mix(in oklab, var(--color-ink) 12%, transparent);
  background: color-mix(in oklab, var(--color-canvas) 62%, transparent);
  color: var(--color-text);
  font-size: 11px;
  backdrop-filter: blur(10px);
}

.landing-footer-items {
  flex-wrap: wrap;
  gap: 10px 22px;
}

.landing-footer-items span + span::before {
  margin-right: 22px;
  color: var(--color-faint);
  content: '·';
}

@media (max-width: 900px) {
  .landing-hero {
    grid-template-columns: 1fr;
    gap: 18px;
    padding-top: 68px;
  }

  .landing-visual {
    width: min(100%, 600px);
    margin-inline: auto;
  }
}

@media (max-width: 700px) {
  .landing-header,
  .landing-hero,
  .landing-footer {
    padding-inline: 18px;
  }

  .landing-actions {
    gap: 14px;
  }

  .register-link,
  .workspace-link {
    padding: 9px 13px;
  }

  .landing-hero {
    padding-top: 48px;
    padding-bottom: 54px;
  }

  .landing-copy h1 {
    font-size: clamp(44px, 13.5vw, 68px);
  }

  .landing-copy h1 span {
    white-space: normal;
  }

  .landing-description {
    margin-top: 28px;
    font-size: 14px;
  }

  .landing-visual {
    padding-top: 24px;
  }

  .landing-footer {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
    padding-top: 16px;
    padding-bottom: 16px;
  }

  .landing-footer > span {
    font-size: 10px;
  }
}

@media (max-width: 430px) {
  .landing-brand > span:last-child {
    display: none;
  }
}
</style>
