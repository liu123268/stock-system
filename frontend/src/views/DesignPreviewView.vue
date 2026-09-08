<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ArrowRight, CircleCheck, Plus } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import DesignOrbit from '@/components/DesignOrbit.vue'

type PreviewKey = 'landing' | 'workspace' | 'empty'

const activePreview = ref<PreviewKey>('landing')
const authStore = useAuthStore()
let revealObserver: IntersectionObserver | null = null

const opinions = [
  {
    name: '林叔',
    tone: '偏积极',
    color: 'mint',
    text: '行业需求仍在恢复，先保留观察。',
    time: '今天 09:42',
  },
  {
    name: '小雨',
    tone: '继续观察',
    color: 'blue',
    text: '估值回到合理区间，但还想看一季数据。',
    time: '昨天 20:18',
  },
  {
    name: '你',
    tone: '有风险',
    color: 'lilac',
    text: '关注现金流变化，暂时不提高仓位。',
    time: '5月 24日',
  },
]

function selectPreview(preview: PreviewKey) {
  activePreview.value = preview
  void nextTick(() => {
    window.scrollTo({ top: 0, behavior: 'smooth' })
    observeReveals()
  })
}

function observeReveals() {
  revealObserver?.disconnect()

  if (typeof window === 'undefined' || !('IntersectionObserver' in window)) {
    return
  }

  revealObserver = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-revealed')
        } else {
          entry.target.classList.remove('is-revealed')
        }
      })
    },
    { rootMargin: '0px 0px -8% 0px', threshold: 0.16 },
  )

  document.querySelectorAll('.reveal-on-scroll').forEach((element) => {
    element.classList.add('reveal-ready')
    revealObserver?.observe(element)
  })
}

onMounted(() => {
  void nextTick(observeReveals)
})

onBeforeUnmount(() => {
  revealObserver?.disconnect()
})
</script>

<template>
  <div class="design-preview">
    <header class="preview-header">
      <RouterLink class="preview-brand" to="/" aria-label="返回同研自选首页">
        <span class="preview-brand-mark" aria-hidden="true"><i></i><i></i></span>
        <span>同研自选</span>
      </RouterLink>

      <nav class="preview-tabs" aria-label="视觉预览页面">
        <button
          type="button"
          :class="{ active: activePreview === 'landing' }"
          @click="selectPreview('landing')"
        >
          公开首页
        </button>
        <button
          type="button"
          :class="{ active: activePreview === 'workspace' }"
          @click="selectPreview('workspace')"
        >
          研究空间
        </button>
        <button
          type="button"
          :class="{ active: activePreview === 'empty' }"
          @click="selectPreview('empty')"
        >
          空状态
        </button>
      </nav>

      <span class="preview-label">内容为演示示例 · 视觉预览</span>
    </header>

    <main>
      <section v-if="activePreview === 'landing'" class="landing-preview">
        <div class="landing-hero-block preview-section">
          <div class="hero-copy">
            <p class="hero-overline">同研自选 <span>/ 共同研究</span></p>
            <h1>把共同关注，<br /><span>留在时间里。</span></h1>
            <p class="hero-description">
              一起看同一只股票，也保留每个人不同的判断、风险和更新时刻。
            </p>
            <div class="hero-actions">
              <button class="button button-dark" type="button" @click="selectPreview('workspace')">
                看看研究空间 <ArrowRight />
              </button>
              <button class="text-action" type="button" @click="selectPreview('empty')">
                从一只股票开始
              </button>
            </div>
          </div>

          <div class="hero-art" aria-label="共同关注与时间轨迹的抽象图形">
            <div class="art-note art-note-top">3 人 · 1 个空间</div>
            <DesignOrbit />
            <div class="art-note art-note-bottom">观点会变化，记录不会消失</div>
          </div>
        </div>

        <section class="story-section story-blue preview-section">
          <div class="story-heading reveal-on-scroll reveal-from-left">
            <p class="story-label">共同关注</p>
            <h2>先看大家<br />共同关注什么。</h2>
          </div>
          <div class="story-content reveal-on-scroll reveal-from-right">
            <p class="story-lead">把散落在聊天记录里的股票，整理成一个可以一起回来的空间。</p>
            <div class="focus-board">
              <div class="focus-board-top"><span>共同关注</span><span>更新于 10 分钟前</span></div>
              <div class="focus-stock">
                <strong>贵州茅台</strong><span>600519.SH</span><b>+1.24%</b>
              </div>
              <div class="focus-meta">
                <span>3 位成员正在关注</span><span>最近 7 天有 4 次观点更新</span>
              </div>
            </div>
          </div>
        </section>

        <section class="story-section story-lilac preview-section">
          <div class="story-heading reveal-on-scroll reveal-from-left">
            <p class="story-label">不同判断</p>
            <h2>同一只股票，<br />可以有不同答案。</h2>
          </div>
          <div class="story-content reveal-on-scroll reveal-from-right">
            <p class="story-lead">系统不替你做判断，只把每个人在不同时间留下的判断放在一起。</p>
            <div class="opinion-stack">
              <article
                v-for="(opinion, index) in opinions"
                :key="opinion.name + opinion.time"
                class="opinion-row reveal-on-scroll"
                :style="{ '--reveal-delay': `${index * 90}ms` }"
              >
                <span class="opinion-avatar" :class="`avatar-${opinion.color}`">{{
                  opinion.name.slice(0, 1)
                }}</span>
                <div class="opinion-body">
                  <strong>{{ opinion.name }} · {{ opinion.tone }}</strong>
                  <p>{{ opinion.text }}</p>
                </div>
                <time>{{ opinion.time }}</time>
              </article>
            </div>
          </div>
        </section>

        <section class="story-section story-mint preview-section">
          <div class="story-heading reveal-on-scroll reveal-from-left">
            <p class="story-label">回到使用</p>
            <h2>漂亮的首页，<br />最终要回到使用。</h2>
          </div>
          <div class="story-content reveal-on-scroll reveal-from-right">
            <p class="story-lead">首页先让人理解产品，研究空间再让成员回到同一条记录。</p>
            <div class="mini-window">
              <div class="mini-window-nav">
                <span class="mini-logo"></span><span>我的空间</span
                ><span class="mini-nav-muted">成员 3</span>
              </div>
              <div class="mini-window-body">
                <div><small>共同关注</small><strong>12</strong><span>本周新增 2 只</span></div>
                <div><small>观点更新</small><strong>08</strong><span>最近来自小雨</span></div>
                <div><small>待补充观点</small><strong>03</strong><span>等待你的判断</span></div>
              </div>
            </div>
          </div>
        </section>

        <section class="landing-end preview-section reveal-on-scroll reveal-rise">
          <p class="ending-note">为下一次更新，留一个位置</p>
          <h2>让一次判断，<br />成为下一次讨论的起点。</h2>
          <button class="button button-dark" type="button" @click="selectPreview('empty')">
            预览空状态 <ArrowRight />
          </button>
        </section>
      </section>

      <section v-else-if="activePreview === 'workspace'" class="workspace-preview preview-section">
        <aside class="workspace-sidebar">
          <div class="workspace-sidebar-brand">
            <span class="sidebar-orbit"></span><strong>同研自选</strong>
          </div>
          <p class="workspace-side-label">研究空间</p>
          <div class="workspace-space-switcher">
            <span class="space-dot"></span><span>我们家的研究室</span
            ><span class="space-chevron">⌄</span>
          </div>
          <nav class="workspace-nav" aria-label="工作台导航">
            <a class="selected" href="#watchlist"
              ><span class="nav-symbol">◌</span>共同关注 <span>12</span></a
            >
            <a href="#opinions"
              ><span class="nav-symbol">◍</span>观点更新 <span class="nav-dot"></span
            ></a>
            <a href="#members"><span class="nav-symbol">○</span>成员</a>
          </nav>
          <div class="sidebar-bottom">
            <a href="#settings">空间设置</a><a href="#help">使用说明</a>
          </div>
        </aside>

        <div class="workspace-main">
          <header class="workspace-topbar">
            <div>
              <span class="workspace-breadcrumb">我们家的研究室</span><strong>共同关注</strong>
            </div>
            <div class="workspace-actions">
              <span class="workspace-user">{{ authStore.currentUser?.nickname || '你的空间' }}</span
              ><button class="icon-button" type="button" aria-label="添加股票"><Plus /></button>
            </div>
          </header>
          <div class="workspace-intro reveal-on-scroll reveal-rise">
            <div>
              <p class="workspace-date">演示界面 · 数据仅用于说明</p>
              <h1>今天，先看看<br />大家的判断。</h1>
              <p class="workspace-thesis">
                这里不追逐每一次波动，只记录你们为什么继续关注，以及什么时候改变判断。
              </p>
            </div>
            <button class="button button-dark" type="button" @click="selectPreview('empty')">
              添加股票 <Plus />
            </button>
          </div>

          <section id="watchlist" class="workspace-section reveal-on-scroll reveal-rise">
            <div class="section-title">
              <div>
                <h2>共同关注</h2>
              </div>
              <button class="quiet-link" type="button">12 只股票 <ArrowRight /></button>
            </div>
            <div class="table-heading">
              <span>股票</span><span>今日价格</span><span>最近一次判断</span>
            </div>
            <div class="stock-list">
              <article class="stock-item">
                <span class="stock-index">01</span>
                <div class="stock-symbol">
                  <span class="stock-mark stock-mark-blue">茅</span>
                  <div><strong>贵州茅台</strong><small>600519.SH · 白酒</small></div>
                </div>
                <div class="stock-price">
                  <strong>1,482.00</strong><span class="positive">+1.24%</span>
                </div>
                <div class="stock-update">
                  <span>最近观点</span><strong>小雨 · 昨天 20:18</strong>
                </div>
                <ArrowRight class="row-arrow" />
              </article>
              <article class="stock-item">
                <span class="stock-index">02</span>
                <div class="stock-symbol">
                  <span class="stock-mark stock-mark-lilac">宁</span>
                  <div><strong>宁德时代</strong><small>300750.SZ · 电池</small></div>
                </div>
                <div class="stock-price">
                  <strong>214.60</strong><span class="negative">-0.38%</span>
                </div>
                <div class="stock-update">
                  <span>最近观点</span><strong>林叔 · 5月 25日</strong>
                </div>
                <ArrowRight class="row-arrow" />
              </article>
              <article class="stock-item">
                <span class="stock-index">03</span>
                <div class="stock-symbol">
                  <span class="stock-mark stock-mark-mint">美</span>
                  <div><strong>美的集团</strong><small>000333.SZ · 家电</small></div>
                </div>
                <div class="stock-price">
                  <strong>71.18</strong><span class="positive">+0.67%</span>
                </div>
                <div class="stock-update"><span>最近观点</span><strong>你 · 5月 24日</strong></div>
                <ArrowRight class="row-arrow" />
              </article>
            </div>
          </section>

          <section id="opinions" class="workspace-lower-grid">
            <div class="workspace-section reveal-on-scroll reveal-from-left">
              <div class="section-title">
                <div>
                  <h2>最近更新</h2>
                </div>
                <span class="section-aside">按时间排列</span>
              </div>
              <div class="note-list">
                <article
                  v-for="opinion in opinions"
                  :key="`note-${opinion.name}`"
                  class="note-item"
                >
                  <span class="opinion-avatar" :class="`avatar-${opinion.color}`">{{
                    opinion.name.slice(0, 1)
                  }}</span>
                  <div>
                    <strong>{{ opinion.name }} 更新了贵州茅台</strong>
                    <p>{{ opinion.text }}</p>
                    <time>{{ opinion.time }}</time>
                  </div>
                </article>
              </div>
            </div>
            <div
              id="members"
              class="workspace-section members-section reveal-on-scroll reveal-from-right"
            >
              <div class="section-title">
                <div>
                  <h2>成员</h2>
                </div>
                <button class="quiet-link" type="button">邀请 <Plus /></button>
              </div>
              <div class="member-list">
                <span class="member-avatar avatar-mint">林</span
                ><span class="member-avatar avatar-blue">雨</span
                ><span class="member-avatar avatar-lilac">你</span>
                <div>
                  <strong>3 位成员</strong>
                  <p>一起看，也各自判断</p>
                </div>
              </div>
              <div class="member-rule"></div>
              <p class="members-footnote">每个人都可以记录多次观点，系统会保留作者与时间。</p>
            </div>
          </section>
        </div>
      </section>

      <section v-else class="empty-preview preview-section">
        <div class="empty-intro">
          <p class="empty-overline">你的研究空间已经准备好</p>
          <h1>从一只股票，<br /><span>开始共同关注。</span></h1>
          <p>添加之后，家人和伙伴就可以一起补充理由、风险和各自的判断。</p>
          <div class="empty-actions">
            <button class="button button-dark" type="button" @click="selectPreview('workspace')">
              添加第一只股票 <Plus /></button
            ><button class="text-action" type="button" @click="selectPreview('landing')">
              返回看产品介绍
            </button>
          </div>
        </div>
        <div class="empty-art">
          <div class="empty-art-caption"><span>共同关注</span><strong>还没有开始</strong></div>
          <DesignOrbit />
          <div class="empty-art-label"><CircleCheck /> 记录会在这里慢慢长出来</div>
        </div>
        <div class="empty-steps reveal-on-scroll reveal-rise">
          <div>
            <span>01</span><strong>添加股票</strong>
            <p>输入名称或代码，系统自动补充基础信息。</p>
          </div>
          <div>
            <span>02</span><strong>邀请成员</strong>
            <p>让家人或伙伴进入同一个研究空间。</p>
          </div>
          <div>
            <span>03</span><strong>留下观点</strong>
            <p>从理由、风险或目标价开始，不需要一次写完。</p>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.design-preview {
  --preview-canvas: #f7fafc;
  --preview-surface: #ffffff;
  --preview-ink: #20323b;
  --preview-muted: #62747c;
  --preview-line: #dce7eb;
  --preview-accent: #84b8d4;
  --preview-accent-soft: #d7edf5;
  --preview-lilac: #e4e1f7;
  --preview-mint: #dcefe6;
  --preview-warm: #d8a57c;
  --preview-warm-soft: #f3e2d3;
  min-height: 100vh;
  color: var(--preview-ink);
  background: var(--preview-canvas);
  font-family: 'Microsoft YaHei UI', 'PingFang SC', 'Segoe UI Variable', system-ui, sans-serif;
}

.preview-header {
  position: sticky;
  z-index: 10;
  top: 0;
  display: flex;
  height: 76px;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 0 clamp(22px, 5vw, 72px);
  border-bottom: 1px solid color-mix(in srgb, var(--preview-ink) 12%, transparent);
  background: color-mix(in srgb, var(--preview-canvas) 90%, transparent);
  backdrop-filter: blur(16px);
}
.preview-brand,
.workspace-sidebar-brand {
  display: inline-flex;
  align-items: center;
  gap: 11px;
  color: var(--preview-ink);
  font-size: 14px;
  font-weight: 700;
  text-decoration: none;
}
.preview-brand-mark {
  position: relative;
  display: block;
  width: 28px;
  height: 17px;
}
.preview-brand-mark i {
  position: absolute;
  top: 1px;
  width: 15px;
  height: 15px;
  border: 1.5px solid currentColor;
  border-radius: 50%;
}
.preview-brand-mark i:first-child {
  left: 0;
}
.preview-brand-mark i:last-child {
  right: 0;
}
.preview-tabs {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 4px;
  border: 1px solid var(--preview-line);
  border-radius: 999px;
  background: color-mix(in srgb, var(--preview-surface) 78%, transparent);
}
.preview-tabs button {
  padding: 8px 14px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--preview-muted);
  cursor: pointer;
  font-size: 12px;
}
.preview-tabs button.active {
  background: var(--preview-ink);
  color: white;
}
.preview-label {
  color: var(--preview-muted);
  font-size: 11px;
}
.preview-section {
  padding-inline: clamp(22px, 8vw, 132px);
}
.hero-overline,
.story-label,
.ending-note,
.empty-overline,
.workspace-date,
.workspace-side-label {
  margin: 0 0 20px;
  color: var(--preview-muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  line-height: 1.4;
}
.hero-overline {
  color: var(--preview-ink);
}
.hero-overline span {
  color: var(--preview-muted);
  font-weight: 500;
}
.story-label {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 18px;
  color: var(--preview-ink);
}
.story-label::before {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--preview-accent);
  content: '';
}
.story-lilac .story-label::before {
  background: #a8a2d3;
}
.story-mint .story-label::before {
  background: #87bba7;
}
.ending-note,
.empty-overline {
  color: var(--preview-ink);
}
.button {
  display: inline-flex;
  height: 48px;
  align-items: center;
  justify-content: center;
  gap: 11px;
  padding: 0 18px;
  border: 0;
  border-radius: 999px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 700;
  transition:
    transform 180ms ease,
    background 180ms ease;
}
.button:hover {
  transform: translateY(-2px);
}
.button-dark {
  background: var(--preview-ink);
  color: white;
}
.button-dark:hover {
  background: #314a56;
}
.text-action,
.quiet-link {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0;
  border: 0;
  border-bottom: 1px solid color-mix(in srgb, var(--preview-ink) 48%, transparent);
  background: none;
  color: var(--preview-ink);
  cursor: pointer;
  font-size: 12px;
}
.landing-hero-block {
  display: grid;
  min-height: max(730px, calc(100svh - 76px));
  grid-template-columns: minmax(0, 1.02fr) minmax(360px, 0.98fr);
  align-items: center;
  gap: clamp(24px, 6vw, 104px);
  padding-top: 64px;
  padding-bottom: 70px;
}
.hero-copy h1,
.empty-intro h1 {
  margin: 0;
  font-size: clamp(54px, 6.1vw, 90px);
  font-weight: 560;
  letter-spacing: -0.03em;
  line-height: 0.98;
  text-wrap: balance;
}
.hero-copy h1 span,
.empty-intro h1 span {
  color: #4f8ea9;
  white-space: nowrap;
}
.hero-description {
  max-width: 33ch;
  margin: 42px 0 0;
  color: var(--preview-muted);
  font-size: 16px;
  line-height: 1.8;
}
.hero-actions,
.empty-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 22px;
  margin-top: 30px;
}
.hero-art {
  position: relative;
  min-width: 0;
  padding: 50px 0 25px;
}
.hero-art .design-orbit {
  max-width: 560px;
  margin: 0 auto;
}
.art-note {
  position: absolute;
  z-index: 1;
  padding: 9px 12px;
  border: 1px solid color-mix(in srgb, var(--preview-ink) 12%, transparent);
  border-radius: 999px;
  background: color-mix(in srgb, var(--preview-surface) 70%, transparent);
  color: var(--preview-muted);
  font-size: 11px;
  backdrop-filter: blur(8px);
}
.art-note-top {
  top: 7%;
  right: 2%;
}
.art-note-bottom {
  bottom: 4%;
  left: 5%;
}
.story-section {
  display: grid;
  min-height: 610px;
  grid-template-columns: minmax(260px, 0.7fr) minmax(0, 1fr);
  align-items: center;
  gap: clamp(40px, 9vw, 160px);
}
.story-blue {
  background: #e2f0f7;
}
.story-lilac {
  background: #eeecf9;
}
.story-mint {
  background: #e6f2ec;
}
.story-heading h2,
.landing-end h2 {
  margin: 0;
  font-size: clamp(34px, 4.6vw, 67px);
  font-weight: 560;
  letter-spacing: -0.03em;
  line-height: 1.04;
  text-wrap: balance;
}
.story-content {
  max-width: 600px;
}
.story-lead {
  max-width: 38ch;
  margin: 0 0 32px;
  color: var(--preview-muted);
  font-size: 16px;
  line-height: 1.8;
}
.focus-board,
.mini-window {
  border: 1px solid color-mix(in srgb, var(--preview-ink) 15%, transparent);
  border-radius: 3px;
  background: color-mix(in srgb, white 72%, transparent);
}
.focus-board {
  padding: 26px;
}
.focus-board-top,
.focus-meta {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  color: var(--preview-muted);
  font-size: 11px;
}
.focus-stock {
  display: flex;
  align-items: baseline;
  gap: 14px;
  margin: 55px 0 30px;
}
.focus-stock strong {
  font-size: 34px;
  font-weight: 600;
  letter-spacing: -0.04em;
}
.focus-stock span {
  color: var(--preview-muted);
  font-size: 11px;
}
.focus-stock b {
  margin-left: auto;
  color: #418971;
  font-size: 14px;
}
.opinion-stack {
  display: grid;
  gap: 10px;
}
.opinion-row {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr) auto;
  align-items: start;
  gap: 13px;
  padding: 17px 0;
  border-top: 1px solid color-mix(in srgb, var(--preview-ink) 16%, transparent);
  border-radius: 0;
  background: transparent;
}
.opinion-avatar,
.member-avatar {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 50%;
  color: var(--preview-ink);
  font-size: 12px;
  font-weight: 700;
}
.avatar-mint {
  background: var(--preview-mint);
}
.avatar-blue {
  background: var(--preview-accent-soft);
}
.avatar-lilac {
  background: var(--preview-lilac);
}
.opinion-body strong {
  font-size: 12px;
}
.opinion-body p {
  margin: 5px 0 0;
  color: var(--preview-muted);
  font-size: 12px;
  line-height: 1.6;
}
.opinion-row time {
  color: var(--preview-muted);
  font-size: 10px;
}
.mini-window {
  overflow: hidden;
}
.mini-window-nav {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 18px;
  border-bottom: 1px solid var(--preview-line);
  font-size: 11px;
  font-weight: 700;
}
.mini-logo,
.sidebar-orbit {
  display: inline-block;
  width: 18px;
  height: 12px;
  border: 1.5px solid currentColor;
  border-radius: 50%;
}
.mini-nav-muted {
  margin-left: auto;
  color: var(--preview-muted);
  font-weight: 400;
}
.mini-window-body {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  background: var(--preview-line);
}
.mini-window-body > div {
  display: grid;
  gap: 13px;
  padding: 25px 20px;
  background: color-mix(in srgb, white 75%, transparent);
}
.mini-window-body small,
.mini-window-body span {
  color: var(--preview-muted);
  font-size: 10px;
}
.mini-window-body strong {
  font-size: 32px;
  font-weight: 560;
  letter-spacing: -0.04em;
}
.landing-end {
  display: grid;
  min-height: 500px;
  align-content: center;
  justify-items: start;
  padding-top: 70px;
  padding-bottom: 70px;
  background: var(--preview-canvas);
}
.landing-end h2 {
  margin-bottom: 34px;
}
.workspace-preview {
  display: grid;
  min-height: calc(100vh - 76px);
  grid-template-columns: 214px minmax(0, 1fr);
  padding-inline: 0;
  background: var(--preview-canvas);
}
.workspace-sidebar {
  position: sticky;
  top: 76px;
  display: flex;
  min-height: calc(100vh - 76px);
  flex-direction: column;
  padding: 42px 22px 24px;
  border-right: 1px solid var(--preview-line);
  background: var(--preview-canvas);
}
.workspace-sidebar-brand {
  margin: 0 10px 18px;
}
.workspace-side-label {
  margin: 0 10px 25px;
  color: #86a8b4;
  font-size: 10px;
  font-weight: 600;
}
.sidebar-orbit {
  width: 23px;
  height: 15px;
}
.workspace-space-switcher {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 14px 10px;
  border-bottom: 1px solid var(--preview-line);
  border-radius: 0;
  background: transparent;
  font-size: 11px;
}
.space-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #79aebf;
}
.space-chevron {
  margin-left: auto;
  color: var(--preview-muted);
}
.workspace-nav {
  display: grid;
  gap: 4px;
  margin-top: 25px;
}
.workspace-nav a {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 12px 10px;
  border-radius: 8px;
  color: var(--preview-muted);
  font-size: 12px;
  text-decoration: none;
}
.workspace-nav a svg {
  width: 15px;
}
.workspace-nav a.selected {
  color: var(--preview-ink);
  font-weight: 700;
}
.workspace-nav a.selected .nav-symbol {
  color: #4f8ea9;
}
.nav-symbol {
  width: 15px;
  color: #a8b8bd;
  font-size: 17px;
  line-height: 1;
  text-align: center;
}
.workspace-nav a span {
  margin-left: auto;
  font-size: 10px;
}
.nav-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #78aebf;
}
.sidebar-bottom {
  display: grid;
  gap: 13px;
  margin-top: auto;
  padding: 0 10px;
}
.sidebar-bottom a {
  color: var(--preview-muted);
  font-size: 11px;
  text-decoration: none;
}
.workspace-main {
  min-width: 0;
  padding: 0 clamp(24px, 5vw, 76px) 80px;
}
.workspace-topbar {
  display: flex;
  min-height: 76px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--preview-line);
}
.workspace-breadcrumb {
  display: block;
  margin-bottom: 6px;
  color: var(--preview-muted);
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
  color: var(--preview-muted);
  font-size: 11px;
}
.icon-button {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid var(--preview-line);
  border-radius: 50%;
  background: white;
  color: var(--preview-ink);
  cursor: pointer;
}
.icon-button svg {
  width: 16px;
}
.workspace-intro {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 20px;
  padding: 75px 0 65px;
}
.workspace-intro h1 {
  margin: 0;
  font-size: clamp(38px, 5vw, 72px);
  font-weight: 560;
  letter-spacing: -0.03em;
  line-height: 1;
}
.workspace-date {
  margin-bottom: 22px;
  color: #86a8b4;
  font-weight: 600;
}
.workspace-thesis {
  max-width: 36ch;
  margin: 28px 0 0;
  color: var(--preview-muted);
  font-size: 13px;
  line-height: 1.8;
}
.workspace-section {
  min-width: 0;
}
.section-title {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
}
.section-title h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 650;
  letter-spacing: -0.03em;
}
.quiet-link {
  border-bottom-color: var(--preview-line);
  color: var(--preview-muted);
  font-size: 11px;
}
.quiet-link svg {
  width: 14px;
}
.stock-list {
  border-top: 1px solid var(--preview-line);
}
.table-heading {
  display: grid;
  grid-template-columns: 30px minmax(190px, 1.4fr) minmax(120px, 0.7fr) minmax(160px, 0.9fr) 18px;
  gap: 20px;
  padding: 0 0 12px;
  color: var(--preview-muted);
  font-size: 10px;
}
.table-heading span:first-child {
  grid-column: 2;
}
.table-heading span:nth-child(2) {
  grid-column: 3;
}
.table-heading span:nth-child(3) {
  grid-column: 4;
}
.stock-item {
  display: grid;
  grid-template-columns: 30px minmax(190px, 1.4fr) minmax(120px, 0.7fr) minmax(160px, 0.9fr) 18px;
  align-items: center;
  gap: 20px;
  padding: 20px 0;
  border-bottom: 1px solid var(--preview-line);
  transition: background 180ms ease;
}
.stock-item:hover {
  background: color-mix(in srgb, var(--preview-accent-soft) 38%, transparent);
}
.stock-index {
  align-self: start;
  padding-top: 4px;
  color: #9bb2ba;
  font-size: 10px;
  font-variant-numeric: tabular-nums;
}
.stock-symbol,
.member-list {
  display: flex;
  align-items: center;
  gap: 12px;
}
.stock-mark {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border-radius: 11px;
  font-size: 14px;
  font-weight: 700;
}
.stock-mark-blue {
  background: var(--preview-accent-soft);
}
.stock-mark-lilac {
  background: var(--preview-lilac);
}
.stock-mark-mint {
  background: var(--preview-mint);
}
.stock-symbol strong,
.stock-update strong {
  display: block;
  font-size: 13px;
}
.stock-symbol small,
.stock-update span {
  display: block;
  margin-top: 4px;
  color: var(--preview-muted);
  font-size: 10px;
}
.stock-price strong {
  display: block;
  font-size: 15px;
}
.stock-price span {
  display: block;
  margin-top: 5px;
  font-size: 11px;
}
.positive {
  color: #3d8b71;
}
.negative {
  color: #ae746d;
}
.stock-update {
  text-align: right;
}
.row-arrow {
  color: var(--preview-muted);
}
.workspace-lower-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(260px, 0.85fr);
  gap: clamp(28px, 6vw, 90px);
  margin-top: 94px;
}
.section-aside {
  color: var(--preview-muted);
  font-size: 11px;
  font-weight: 400;
}
.note-list {
  display: grid;
  gap: 10px;
}
.note-item {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 12px;
  padding: 14px 0;
  border-top: 1px solid var(--preview-line);
}
.note-item strong {
  font-size: 12px;
}
.note-item p {
  margin: 5px 0 8px;
  color: var(--preview-muted);
  font-size: 11px;
  line-height: 1.6;
}
.note-item time {
  color: var(--preview-muted);
  font-size: 10px;
}
.title-icon {
  width: 18px;
  color: var(--preview-muted);
}
.member-list {
  padding: 18px 0;
  border-top: 1px solid var(--preview-line);
  border-bottom: 1px solid var(--preview-line);
  border-radius: 0;
  background: transparent;
}
.member-avatar {
  margin-left: -6px;
  border: 2px solid white;
}
.member-avatar:first-child {
  margin-left: 0;
}
.member-list div {
  margin-left: 4px;
}
.member-list strong {
  display: block;
  font-size: 12px;
}
.member-list p {
  margin: 5px 0 0;
  color: var(--preview-muted);
  font-size: 10px;
}
.member-rule {
  height: 1px;
  margin: 26px 0 18px;
  background: var(--preview-line);
}
.members-footnote {
  max-width: 24ch;
  color: var(--preview-muted);
  font-size: 11px;
  line-height: 1.7;
}
.empty-preview {
  display: grid;
  min-height: calc(100vh - 76px);
  grid-template-columns: minmax(0, 1fr) minmax(360px, 1fr);
  align-items: center;
  gap: clamp(30px, 8vw, 130px);
  padding-top: 80px;
  padding-bottom: 80px;
}
.empty-intro > p:not(.empty-overline) {
  max-width: 38ch;
  margin: 34px 0 0;
  color: var(--preview-muted);
  font-size: 15px;
  line-height: 1.8;
}
.empty-art {
  position: relative;
  padding: 20px 0 0;
}
.empty-art .design-orbit {
  max-width: 590px;
  margin: 0 auto;
}
.empty-art-caption {
  position: absolute;
  z-index: 1;
  top: 4%;
  left: 12%;
  display: grid;
  gap: 6px;
}
.empty-art-caption span {
  color: var(--preview-muted);
  font-size: 10px;
}
.empty-art-caption strong {
  font-size: 14px;
}
.empty-art-label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: -8px 0 0 auto;
  color: var(--preview-muted);
  font-size: 11px;
}
.empty-art-label svg {
  width: 14px;
  color: #4b967d;
}
.empty-steps {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  margin-top: 48px;
  background: var(--preview-line);
}
.empty-steps > div {
  min-height: 170px;
  padding: 22px;
  background: var(--preview-canvas);
}
.empty-steps span {
  color: #6d9bae;
  font-size: 11px;
}
.empty-steps strong {
  display: block;
  margin-top: 28px;
  font-size: 15px;
}
.empty-steps p {
  max-width: 24ch;
  margin: 8px 0 0;
  color: var(--preview-muted);
  font-size: 11px;
  line-height: 1.7;
}

.reveal-on-scroll {
  opacity: 1;
  transform: none;
}
.reveal-on-scroll.reveal-ready {
  transition:
    opacity 980ms cubic-bezier(0.16, 1, 0.3, 1) var(--reveal-delay, 0ms),
    transform 980ms cubic-bezier(0.16, 1, 0.3, 1) var(--reveal-delay, 0ms);
}
.reveal-on-scroll.reveal-ready:not(.is-revealed) {
  opacity: 0.04;
}
.reveal-from-left.reveal-ready:not(.is-revealed) {
  transform: translateX(-42px);
}
.reveal-from-right.reveal-ready:not(.is-revealed) {
  transform: translateX(42px);
  transition-delay: 130ms;
}
.reveal-rise.reveal-ready:not(.is-revealed) {
  transform: translateY(42px);
}
.reveal-on-scroll.is-revealed {
  opacity: 1;
  transform: none;
}

@media (max-width: 900px) {
  .preview-header {
    padding-inline: 20px;
  }
  .preview-label {
    display: none;
  }
  .landing-hero-block,
  .empty-preview {
    grid-template-columns: 1fr;
  }
  .landing-hero-block {
    min-height: auto;
    padding-top: 90px;
  }
  .hero-art,
  .empty-art {
    max-width: 590px;
    width: 100%;
    margin-inline: auto;
  }
  .story-section {
    grid-template-columns: 1fr;
    gap: 30px;
    padding-top: 78px;
    padding-bottom: 78px;
  }
  .story-content {
    width: 100%;
  }
  .workspace-preview {
    grid-template-columns: 1fr;
  }
  .workspace-sidebar {
    position: static;
    min-height: auto;
    flex-direction: row;
    align-items: center;
    gap: 18px;
    padding: 17px 20px;
    border-right: 0;
    border-bottom: 1px solid var(--preview-line);
  }
  .workspace-side-label {
    display: none;
  }
  .workspace-sidebar-brand {
    margin: 0;
  }
  .workspace-space-switcher {
    margin-left: auto;
  }
  .workspace-nav {
    display: flex;
    margin: 0;
  }
  .workspace-nav a {
    padding: 8px;
  }
  .workspace-nav a span {
    display: none;
  }
  .sidebar-bottom {
    display: none;
  }
  .workspace-main {
    padding-inline: 24px;
  }
}

@media (max-width: 620px) {
  .preview-header {
    height: 68px;
  }
  .preview-brand > span:last-child {
    display: none;
  }
  .preview-tabs button {
    padding-inline: 10px;
    font-size: 11px;
  }
  .preview-section {
    padding-inline: 20px;
  }
  .hero-copy h1,
  .empty-intro h1 {
    font-size: clamp(45px, 13vw, 68px);
  }
  .hero-copy h1 span,
  .empty-intro h1 span {
    white-space: normal;
  }
  .hero-description {
    margin-top: 28px;
    font-size: 14px;
  }
  .story-heading h2,
  .landing-end h2 {
    font-size: 42px;
  }
  .focus-stock {
    margin-top: 38px;
  }
  .focus-stock strong {
    font-size: 26px;
  }
  .focus-meta,
  .focus-board-top {
    display: grid;
    gap: 8px;
  }
  .opinion-row {
    grid-template-columns: 32px minmax(0, 1fr);
  }
  .opinion-row time {
    grid-column: 2;
  }
  .mini-window-body {
    grid-template-columns: 1fr;
  }
  .mini-window-body > div {
    grid-template-columns: 1fr auto;
    align-items: center;
    gap: 8px 16px;
    padding: 18px;
  }
  .mini-window-body strong {
    grid-row: span 2;
    grid-column: 2;
  }
  .workspace-sidebar {
    overflow-x: auto;
  }
  .workspace-space-switcher {
    min-width: 140px;
  }
  .workspace-nav a {
    white-space: nowrap;
  }
  .workspace-intro {
    display: grid;
    padding-top: 54px;
  }
  .workspace-intro h1 {
    font-size: 46px;
  }
  .stock-item {
    grid-template-columns: 30px minmax(0, 1fr) auto 16px;
    gap: 12px;
  }
  .table-heading {
    display: none;
  }
  .stock-update {
    display: none;
  }
  .workspace-lower-grid {
    grid-template-columns: 1fr;
    margin-top: 54px;
  }
  .empty-preview {
    min-height: auto;
    padding-top: 70px;
  }
  .empty-steps {
    grid-template-columns: 1fr;
  }
  .empty-steps > div {
    min-height: auto;
  }
  .empty-steps strong {
    margin-top: 16px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .button,
  .stock-item,
  .reveal-on-scroll.reveal-ready {
    transition: none;
  }
  .reveal-on-scroll.reveal-ready:not(.is-revealed),
  .reveal-on-scroll.is-revealed {
    opacity: 1;
    transform: none;
  }
}
</style>
