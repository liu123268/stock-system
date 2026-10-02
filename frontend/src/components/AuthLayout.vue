<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
withDefaults(defineProps<{ mode?: 'login' | 'register' }>(), { mode: 'login' })
const route = useRoute()
const accountQuery = computed(() =>
  typeof route.query.redirect === 'string' ? { redirect: route.query.redirect } : {},
)
</script>
<template>
  <div class="auth-page research-theme">
    <a class="ui-skip-link" href="#account-form">跳到账号表单</a>
    <header class="auth-header">
      <RouterLink class="ui-brand" to="/" aria-label="基点首页"
        >基点<span aria-hidden="true">。</span></RouterLink
      >
      <div class="auth-header-actions">
        <nav aria-label="账号入口">
          <RouterLink :to="{ name: 'login', query: accountQuery }">登录</RouterLink
          ><RouterLink :to="{ name: 'register', query: accountQuery }">注册</RouterLink>
        </nav>
        <RouterLink to="/">返回首页 <span aria-hidden="true">→</span></RouterLink>
      </div>
    </header>
    <main class="auth-stage">
      <section class="auth-copy" aria-label="账号说明">
        <p class="section-label">( {{ mode === 'login' ? '欢迎回来' : '开始记录' }} )</p>
        <h1>{{ mode === 'login' ? '回到共同研究的空间。' : '用自己的身份，留下判断。' }}</h1>
        <p>
          {{
            mode === 'login'
              ? '继续查看共同关注，以及家人和伙伴最近更新的判断。'
              : '创建账号，再加入家人或伙伴的共享研究空间。'
          }}
        </p>
        <div class="auth-mark" aria-hidden="true">✳</div>
      </section>
      <section id="account-form" class="auth-sheet" tabindex="-1" aria-label="账号表单">
        <slot />
      </section>
    </main>
    <footer class="auth-footer">
      <span>基点 · 共同研究，保留各自的判断</span><span>行情与观点仅供研究参考</span>
    </footer>
  </div>
</template>
<style scoped>
.auth-page {
  min-height: 100svh;
  padding: 24px var(--gutter) 32px;
  display: flex;
  flex-direction: column;
}
.auth-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 22px;
  border-bottom: 1px solid var(--color-border);
}
.auth-header-actions,
.auth-header nav {
  display: flex;
  align-items: center;
  gap: 24px;
}
.auth-header-actions a {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  color: var(--color-muted);
  font-size: 13px;
}
.auth-header-actions a:hover,
.auth-header nav .router-link-active {
  color: var(--color-ink);
  text-decoration: underline;
  text-underline-offset: 5px;
}
.auth-stage {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 420px);
  gap: 72px;
  width: min(1120px, 100%);
  margin: 0 auto;
  padding: 72px 0 88px;
  flex: 1;
  align-content: start;
}
.auth-copy h1 {
  max-width: 9em;
  min-height: 2lh;
  margin: 16px 0 24px;
  font: 900 44px/1.22 var(--font-display);
  letter-spacing: -0.025em;
  overflow-wrap: anywhere;
}
.auth-copy > p:last-of-type {
  max-width: 32em;
  min-height: 2lh;
  color: var(--color-muted);
  font-size: 15px;
  line-height: 1.9;
}
.section-label {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.12em;
}
.auth-mark {
  margin-top: 48px;
  color: var(--color-focus);
  font-family: var(--font-mono);
  font-size: 70px;
  line-height: 1.4;
}
.auth-sheet {
  min-width: 0;
  padding-top: 20px;
}
.auth-footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding-top: 20px;
  border-top: 1px solid var(--color-border);
  color: var(--color-muted);
  font-size: 11px;
  flex-wrap: wrap;
}
@media (max-width: 960px) {
  .auth-stage {
    gap: 36px;
  }
  .auth-copy h1 {
    font-size: 38px;
  }
}
@media (max-width: 600px) {
  .auth-page {
    padding: 20px 20px 24px;
  }
  .auth-header {
    gap: 16px;
  }
  .auth-header-actions,
  .auth-header nav {
    gap: 12px;
  }
  .auth-header-actions a {
    font-size: 12px;
  }
  .auth-stage {
    grid-template-columns: minmax(0, 1fr);
    padding-top: 36px;
    gap: 28px;
  }
  .auth-copy h1 {
    max-width: none;
    font-size: 32px;
    margin-bottom: 16px;
  }
  .auth-mark {
    display: none;
  }
  .auth-sheet {
    padding-top: 0;
  }
}
@media (max-width: 360px) {
  .auth-header-actions {
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 2px 12px;
  }
  .auth-copy h1 {
    font-size: 30px;
  }
}
</style>
