<script setup lang="ts">
import DesignOrbit from '@/components/DesignOrbit.vue'
</script>

<template>
  <div class="auth-page">
    <header class="auth-header">
      <RouterLink class="auth-brand" to="/" aria-label="返回同研自选首页">
        <span class="auth-brand-mark" aria-hidden="true"><i></i><i></i></span>
        <span>同研自选</span>
      </RouterLink>

      <div class="auth-header-actions">
        <nav class="auth-route-nav" aria-label="账号入口">
          <RouterLink to="/login">登录</RouterLink>
          <RouterLink to="/register">注册</RouterLink>
        </nav>
        <RouterLink class="back-home" to="/">
          <span>返回首页</span>
          <span aria-hidden="true">↗</span>
        </RouterLink>
      </div>
    </header>

    <main class="auth-stage">
      <section class="auth-observation" aria-label="同研自选品牌介绍">
        <div class="auth-observation-orbit" aria-hidden="true">
          <DesignOrbit />
        </div>

        <div class="observation-copy">
          <p>共同关注，保留不同判断</p>
          <h1>让每一次判断，<br />都回到它发生的时间里。</h1>
        </div>

        <div class="observation-track" aria-label="平台记录内容">
          <span>共享关注</span>
          <span>成员观点</span>
          <span>更新时间</span>
        </div>
      </section>

      <section class="auth-workspace">
        <div class="auth-sheet" aria-label="账号表单">
          <slot />
        </div>

        <footer class="auth-footer">
          <span>同研自选 · 暂定名</span>
          <span>仅用于记录与交流，不构成投资建议</span>
        </footer>
      </section>
    </main>
  </div>
</template>

<style scoped>
.auth-page {
  --preview-ink: var(--color-ink);
  --preview-surface: var(--color-surface);
  --preview-accent: oklch(0.62 0.075 215);
  --preview-accent-soft: oklch(0.91 0.035 215);
  --preview-lilac: oklch(0.92 0.025 300);
  --preview-mint: oklch(0.91 0.045 155);
  --preview-warm: oklch(0.68 0.08 55);
  --preview-warm-soft: oklch(0.9 0.04 55);
  position: relative;
  min-height: 100svh;
  overflow: hidden auto;
  background: var(--color-canvas);
  isolation: isolate;
}

.auth-header {
  position: absolute;
  z-index: 5;
  top: 0;
  right: 0;
  left: 0;
  display: flex;
  height: var(--header-height);
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--gutter);
  border-bottom: 1px solid color-mix(in oklab, var(--color-ink) 13%, transparent);
  background: color-mix(in oklab, var(--color-canvas) 58%, transparent);
  backdrop-filter: blur(14px) saturate(0.9);
}

.auth-brand {
  display: inline-flex;
  align-items: center;
  gap: 11px;
  color: var(--color-ink);
  font-size: 14px;
  font-weight: 650;
}

.auth-brand-mark {
  position: relative;
  display: block;
  width: 28px;
  height: 17px;
}

.auth-brand-mark i {
  position: absolute;
  top: 1px;
  width: 15px;
  height: 15px;
  border: 1.5px solid currentColor;
  border-radius: 50%;
}

.auth-brand-mark i:first-child {
  left: 0;
}

.auth-brand-mark i:last-child {
  right: 0;
}

.auth-header-actions,
.auth-route-nav,
.back-home {
  display: flex;
  align-items: center;
}

.auth-header-actions {
  gap: clamp(18px, 2.5vw, 38px);
}

.auth-route-nav {
  gap: 24px;
  font-size: 13px;
}

.auth-route-nav a {
  position: relative;
  padding: 27px 0 25px;
  color: var(--color-muted);
  transition: color 180ms var(--ease-out);
}

.auth-route-nav a::after {
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 0;
  height: 2px;
  background: var(--color-ink);
  content: '';
  opacity: 0;
  transform: scaleX(0.35);
  transition:
    opacity 180ms var(--ease-out),
    transform 180ms var(--ease-out);
}

.auth-route-nav a:hover,
.auth-route-nav a.router-link-active {
  color: var(--color-ink);
}

.auth-route-nav a.router-link-active {
  font-weight: 650;
}

.auth-route-nav a.router-link-active::after {
  opacity: 1;
  transform: scaleX(1);
}

.back-home {
  gap: 13px;
  color: var(--color-text);
  font-size: 12px;
}

.back-home span:last-child {
  transition: transform 180ms var(--ease-out);
}

.back-home:hover span:last-child {
  transform: translate(2px, -2px);
}

.auth-stage {
  display: grid;
  min-height: 100svh;
  grid-template-columns: minmax(420px, 0.92fr) minmax(520px, 1.08fr);
}

.auth-observation {
  position: relative;
  z-index: 0;
  display: flex;
  min-width: 0;
  min-height: 100svh;
  flex-direction: column;
  justify-content: flex-end;
  overflow: hidden;
  padding: calc(var(--header-height) + clamp(54px, 8vh, 92px)) var(--gutter) clamp(42px, 6vh, 68px);
  border-right: 1px solid color-mix(in oklab, var(--color-ink) 14%, transparent);
}

.auth-observation-orbit {
  position: absolute;
  z-index: 0;
  top: 10%;
  right: -18%;
  bottom: 12%;
  left: 3%;
  display: grid;
  place-items: center;
  opacity: 0.9;
  pointer-events: none;
}

.auth-observation-orbit :deep(.design-orbit) {
  width: 100%;
  max-width: 620px;
}

.observation-copy {
  position: relative;
  z-index: 1;
  max-width: 650px;
  padding-bottom: clamp(56px, 8vh, 92px);
}

.observation-copy p {
  margin-bottom: 22px;
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.6;
}

.observation-copy h1 {
  color: var(--color-heading);
  font-size: clamp(37px, 3.55vw, 58px);
  font-weight: 560;
  letter-spacing: -0.038em;
  line-height: 1.08;
  text-wrap: balance;
}

.observation-track {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  border-top: 1px solid color-mix(in oklab, var(--color-ink) 48%, transparent);
}

.observation-track span {
  position: relative;
  padding-top: 13px;
  color: var(--color-muted);
  font-size: 11px;
}

.observation-track span::before {
  position: absolute;
  top: -4px;
  left: 0;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--color-ink);
  content: '';
}

.auth-workspace {
  position: relative;
  display: flex;
  min-width: 0;
  min-height: 100svh;
  align-items: center;
  justify-content: center;
  padding: calc(var(--header-height) + 46px) clamp(34px, 6vw, 96px) 82px;
  background: var(--color-surface);
}

.auth-sheet {
  width: min(420px, 100%);
  min-width: 0;
}

.auth-footer {
  position: absolute;
  right: clamp(34px, 6vw, 96px);
  bottom: 24px;
  left: clamp(34px, 6vw, 96px);
  display: flex;
  justify-content: space-between;
  gap: 20px;
  color: var(--color-muted);
  font-size: 10px;
}

@media (max-width: 960px) {
  .auth-stage {
    grid-template-columns: minmax(320px, 0.78fr) minmax(480px, 1.22fr);
  }

  .observation-copy h1 {
    font-size: clamp(34px, 4.7vw, 46px);
  }

  .observation-track span:nth-child(2) {
    text-align: center;
  }

  .observation-track span:last-child {
    text-align: right;
  }

  .observation-track span:nth-child(2)::before {
    left: 50%;
  }

  .observation-track span:last-child::before {
    right: 0;
    left: auto;
  }
}

@media (max-width: 760px) {
  .auth-header {
    position: relative;
    padding-inline: 18px;
    background: var(--color-canvas);
    backdrop-filter: none;
  }

  .auth-header-actions {
    gap: 18px;
  }

  .auth-route-nav {
    gap: 18px;
  }

  .auth-route-nav a {
    padding-block: 27px 25px;
  }

  .back-home span:first-child {
    display: none;
  }

  .back-home {
    font-size: 15px;
  }

  .auth-stage {
    display: block;
    min-height: calc(100svh - var(--header-height));
  }

  .auth-observation {
    min-height: 238px;
    padding: 42px 20px 24px;
    border-right: 0;
    border-bottom: 1px solid color-mix(in oklab, var(--color-ink) 14%, transparent);
  }

  .auth-observation-orbit {
    top: 0;
    right: -14%;
    bottom: -16%;
    left: 23%;
    opacity: 0.62;
  }

  .observation-copy {
    padding-bottom: 36px;
  }

  .observation-copy p {
    margin-bottom: 14px;
    font-size: 11px;
  }

  .observation-copy h1 {
    font-size: clamp(29px, 8vw, 36px);
    letter-spacing: -0.03em;
  }

  .observation-track {
    display: none;
  }

  .auth-workspace {
    min-height: auto;
    align-items: flex-start;
    padding: 46px 20px 82px;
  }

  .auth-sheet {
    width: min(430px, 100%);
  }

  .auth-footer {
    right: 20px;
    bottom: 22px;
    left: 20px;
  }

  .auth-footer span:last-child {
    display: none;
  }
}

@media (max-width: 430px) {
  .auth-brand > span:last-child {
    display: none;
  }
}
</style>
