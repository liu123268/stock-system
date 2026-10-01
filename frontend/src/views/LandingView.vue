<script setup lang="ts">
import { computed, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const entry = computed(() =>
  authStore.isAuthenticated
    ? { name: authStore.isSuperAdmin ? 'admin-users' : 'workspaces' }
    : { name: 'login' },
)
const entryLabel = computed(() =>
  authStore.isAuthenticated && authStore.isSuperAdmin ? '进入治理后台' : '进入研究空间',
)
const selectedNote = ref(1)
// 首页图示是局部演示，不使用行情 Store，也不调用研究接口。
const curve =
  'M0,196 L18,192 L34,205 L50,198 L68,174 L85,182 L102,159 L119,166 L137,140 L153,148 L170,121 L186,133 L205,108 L224,128 L240,119 L258,144 L278,157 L295,146 L314,173 L330,166 L350,183 L368,168 L386,175 L406,160 L423,168 L442,149 L460,157 L478,130 L496,139 L514,120 L533,131 L551,111 L569,120 L587,100 L606,115 L624,95 L642,108 L660,92 L678,112 L696,121 L714,105 L732,111 L750,86 L768,96 L787,84 L806,93 L825,72 L844,82 L863,67 L882,81 L902,75 L922,91 L943,82 L963,95 L982,76 L1000,83'
const notes = [
  { id: 1, x: 205, y: 108, title: '开始关注，写下看好的理由', meta: '04.02 · 小林 · 初始观点' },
  { id: 2, x: 350, y: 183, title: '价格回落，补充风险与疑问', meta: '04.23 · 阿周 · 观点更新' },
  { id: 3, x: 825, y: 72, title: '新信息出现，重新检查判断', meta: '07.05 · 小林 · 复盘记录' },
]
const ticker = [
  { name: '示例公司 A', price: '80.20', change: '+1.24%', direction: 'up' },
  { name: '示例公司 B', price: '36.42', change: '+2.31%', direction: 'up' },
  { name: '示例公司 C', price: '42.60', change: '-0.86%', direction: 'down' },
  { name: '示例公司 D', price: '21.35', change: '+0.94%', direction: 'up' },
]
const features = [
  {
    code: 'SPACE',
    title: '隔离空间',
    description: '每个研究组是一间独立房间。只有有效成员可以访问空间内的研究记录。',
  },
  {
    code: 'VOICE',
    title: '各自判断',
    description: '同一只股票，成员分别写下自己的倾向、理由与风险点，保留各自的判断。',
  },
  {
    code: 'TIME',
    title: '研究时间线',
    description: '初始观点、更新与复盘按作者和时间排列；结束关注后，仍可回看旧周期。',
  },
  {
    code: 'CHART',
    title: 'K 线与分时',
    description: '查看价格与成交量，再回到研究记录。开始关注价与最新行情分别保留。',
  },
]
const opinions = [
  { author: '小林', stance: '看多', reason: '继续关注经营质量，也留意现金流变化。', time: '04.02' },
  { author: '阿周', stance: '看空', reason: '补充风险：需求变化仍需进一步确认。', time: '04.23' },
  { author: '小陈', stance: '中性', reason: '等新信息出现，再检查原来的判断。', time: '07.05' },
]
const workflow = [
  { title: '注册或登录', detail: '使用自己的账号' },
  { title: '创建或加入', detail: '选一间研究空间' },
  { title: '加入共享关注', detail: '写下初始观点' },
  { title: '更新与复盘', detail: '沿时间线回看' },
]
</script>

<template>
  <div class="landing-page research-theme">
    <a class="ui-skip-link" href="#landing-content">跳到页面内容</a>
    <header class="landing-header">
      <RouterLink class="ui-brand" :to="{ name: 'home' }" aria-label="基点首页"
        >基点<span aria-hidden="true">。</span></RouterLink
      >
      <nav class="nav-links" aria-label="产品介绍">
        <RouterLink :to="{ name: 'home', hash: '#desk' }">产品</RouterLink>
        <RouterLink :to="{ name: 'home', hash: '#features' }">功能</RouterLink>
        <RouterLink :to="{ name: 'home', hash: '#room' }">空间</RouterLink>
        <RouterLink :to="{ name: 'home', hash: '#workflow' }">开始</RouterLink>
      </nav>
      <nav class="landing-actions" aria-label="账号入口">
        <RouterLink v-if="authStore.isAuthenticated" class="ui-button nav-cta" :to="entry"
          >{{ entryLabel }} <span aria-hidden="true">↗</span></RouterLink
        >
        <template v-else
          ><RouterLink class="login-link" :to="{ name: 'login' }">登录</RouterLink
          ><RouterLink class="ui-button nav-cta" :to="{ name: 'register' }"
            >注册 <span aria-hidden="true">↗</span></RouterLink
          ></template
        >
      </nav>
    </header>
    <main id="landing-content" tabindex="-1">
      <div class="firstscreen">
        <section class="hero" aria-labelledby="landing-title">
          <p class="section-label">( <span>01</span> — 为家庭与小型研究组而做 )</p>
          <h1 id="landing-title">把共同关注的公司，放进<em>同一个房间</em>。</h1>
          <div class="hero-bottom">
            <p class="hero-description">
              基点把分散在聊天记录和不同软件里的<strong>自选股、判断理由、风险点与观点变化</strong>，整理进同一间安静的研究室，让事实、作者和时间清楚可读。
            </p>
            <RouterLink class="line-link" :to="{ name: 'home', hash: '#features' }"
              >查看它的能力 <span aria-hidden="true">↓</span></RouterLink
            >
          </div>
          <div class="hero-meta">
            <span>A 股 · 港股</span><span>共享自选 · 独立判断</span><span>不构成投资建议</span
            ><span aria-hidden="true">↓ 向下滚动</span>
          </div>
        </section>
        <section id="desk" class="desk-section" aria-label="研究过程示意">
          <div class="desk-row">
            <figure class="desk">
              <figcaption class="desk-head">
                <h2>价格之外，还有判断 <small>研究过程示意</small></h2>
                <span class="ui-chip">虚构曲线</span>
              </figcaption>
              <div class="chart-context">
                <span>示例公司 · 价格与观点记录</span><span>非真实行情 / 非收益率</span>
              </div>
              <svg
                class="demo-chart"
                viewBox="-24 0 1048 270"
                preserveAspectRatio="xMidYMid meet"
                role="img"
                aria-label="虚构价格曲线，展示上涨、回撤和震荡，以及三个研究记录位置，不代表真实证券走势"
              >
                <defs>
                  <linearGradient id="landing-research-fill" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0" stop-color="var(--color-focus)" stop-opacity=".12" />
                    <stop offset="1" stop-color="var(--color-focus)" stop-opacity="0" />
                  </linearGradient>
                </defs>
                <path
                  d="M0 55 H1000 M0 120 H1000 M0 185 H1000 M0 250 H1000"
                  fill="none"
                  stroke="var(--color-border)"
                />
                <path :d="`${curve} L1000,250 L0,250 Z`" fill="url(#landing-research-fill)" />
                <path
                  :d="curve"
                  fill="none"
                  stroke="var(--color-focus)"
                  stroke-width="2.5"
                  vector-effect="non-scaling-stroke"
                  stroke-linejoin="round"
                />
                <g
                  v-for="note in notes"
                  :key="note.id"
                  class="research-marker"
                  :class="{ 'is-active': selectedNote === note.id }"
                  :data-marker="note.id"
                >
                  <line
                    :x1="note.x"
                    :y1="note.y + 16"
                    :x2="note.x"
                    y2="250"
                    class="research-guide"
                  />
                  <circle :cx="note.x" :cy="note.y" r="16" />
                  <text :x="note.x" :y="note.y">{{ note.id }}</text>
                </g>
              </svg>
              <div class="axis" aria-hidden="true">
                <span>03 月</span><span>04 月</span><span>05 月</span><span>06 月</span
                ><span>07 月</span><span>08 月</span>
              </div>
            </figure>
            <aside class="research-rail" aria-label="图中研究记录示例">
              <p class="rail-label">同一家公司，不同时间的判断</p>
              <h2>留下理由，<br />再回来看。</h2>
              <button
                v-for="note in notes"
                :key="note.id"
                type="button"
                class="research-note"
                :data-note="note.id"
                :aria-pressed="selectedNote === note.id"
                @click="selectedNote = note.id"
              >
                <span class="note-index" aria-hidden="true">{{ note.id }}</span
                ><span
                  ><strong>{{ note.title }}</strong
                  ><small>{{ note.meta }}</small></span
                >
              </button>
              <div class="rail-bottom">
                <small>人物与内容均为演示</small
                ><RouterLink class="line-link" :to="entry"
                  >{{ entryLabel }} <span aria-hidden="true">↗</span></RouterLink
                >
              </div>
            </aside>
          </div>
        </section>
      </div>
      <section class="marquee" aria-label="虚构行情示意，不是真实行情" tabindex="0">
        <div class="ticker-label">行情一瞥<small>虚构数据</small></div>
        <div class="ticker-window">
          <div class="marquee-track">
            <div
              v-for="group in 2"
              :key="group"
              class="ticker-group"
              :aria-hidden="group === 2 ? true : undefined"
            >
              <span><i aria-hidden="true">✳</i>虚构行情 DEMO</span
              ><span v-for="item in ticker" :key="item.name"
                ><i aria-hidden="true">✳</i>{{ item.name }} {{ item.price }}
                <b :class="item.direction">{{ item.change }}</b></span
              >
            </div>
          </div>
        </div>
      </section>
      <section class="manifesto" aria-label="产品理念">
        <p>
          <span>留一张安静的书桌，</span>把不同的判断放在一起，<em>让研究有来处，也有回看的路。</em>
        </p>
      </section>
      <section id="features" class="feature-section" aria-labelledby="features-title">
        <div class="section-head">
          <h2 id="features-title">它替小组管好四件事</h2>
          <span>( FUNCTION INDEX — 04 )</span>
        </div>
        <div class="feature-list">
          <article v-for="(feature, index) in features" :key="feature.code" class="feature-row">
            <span class="feature-number"
              ><i>0{{ index + 1 }}</i> / {{ feature.code }}</span
            >
            <h3>{{ feature.title }}</h3>
            <p>{{ feature.description }}</p>
          </article>
        </div>
      </section>
      <section id="room" class="room-section" aria-labelledby="room-title">
        <div class="section-head">
          <h2 id="room-title">空间的样子</h2>
          <span>( THE ROOM — DEMO )</span>
        </div>
        <div class="room">
          <div class="room-demo" aria-label="虚构空间与观点示意">
            <div class="mock-head">
              <strong>示例公司 <small>共享研究</small></strong
              ><span class="ui-chip">界面示意</span>
            </div>
            <p class="mock-label">示意周期观点 · 3 条</p>
            <article v-for="opinion in opinions" :key="opinion.author" class="demo-opinion">
              <strong>{{ opinion.author }}</strong
              ><span class="ui-chip">{{ opinion.stance }}</span>
              <p>{{ opinion.reason }}</p>
              <span class="demo-time">{{ opinion.time }}</span>
            </article>
            <p class="mock-foot">演示人物与内容；实际记录由成员在空间内发表。</p>
          </div>
          <div class="room-note">
            <span class="section-label">( 登录之后 )</span>
            <p>
              一间房，一份共享自选。每只股票下面，成员各自的<em>观点、理由和时间</em>排在一起，回看时知道是谁写下了什么。
            </p>
            <p class="mini">
              可以创建自己的空间，也可以用有效邀请码加入。下面是实际动线：注册／登录 → 选空间 →
              进入研究。
            </p>
          </div>
        </div>
      </section>
      <section id="workflow" class="workflow-section" aria-labelledby="workflow-title">
        <div class="section-head">
          <h2 id="workflow-title">从一间空间开始</h2>
          <span>( HOW TO START — 04 )</span>
        </div>
        <ol class="workflow">
          <li v-for="(step, index) in workflow" :key="step.title">
            <span class="step-number" aria-hidden="true">0{{ index + 1 }}</span>
            <h3>{{ step.title }}</h3>
            <p>{{ step.detail }}</p>
          </li>
        </ol>
      </section>
    </main>
    <footer class="landing-footer">
      <div class="foot-mark" aria-hidden="true">基点</div>
      <nav class="foot-links" aria-label="页脚入口">
        <RouterLink :to="entry">{{ entryLabel }} <span aria-hidden="true">↗</span></RouterLink
        ><RouterLink v-if="!authStore.isAuthenticated" :to="{ name: 'register' }"
          >创建账号 <span aria-hidden="true">↗</span></RouterLink
        ><RouterLink :to="{ name: 'home', hash: '#features' }"
          >查看功能 <span aria-hidden="true">↑</span></RouterLink
        >
      </nav>
      <div class="foot-bottom">
        <span>基点 · 共同研究，保留各自的判断</span
        ><span>行情与观点仅供研究参考，不构成投资建议</span>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.landing-page {
  --landing-pad: max(28px, calc((100vw - 1480px) / 2));
  min-height: 100svh;
}
.landing-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  min-height: 84px;
  padding: 20px var(--landing-pad);
  border-bottom: 1px solid var(--color-border);
}
.landing-header nav {
  display: flex;
  align-items: center;
  gap: 24px;
}
.landing-header a {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
}
.nav-links a,
.login-link {
  color: var(--color-muted);
  font: 12px var(--font-mono);
}
.nav-links a:hover,
.login-link:hover {
  color: var(--color-ink);
  text-decoration: underline;
  text-underline-offset: 4px;
}
.landing-header .nav-cta {
  border: 1px solid var(--color-ink);
  padding: 8px 18px;
  gap: 12px;
  font: 12px var(--font-mono);
}
.firstscreen {
  display: flex;
  height: calc(100svh - 138px);
  min-height: 650px;
  flex-direction: column;
}
.hero {
  padding: clamp(32px, 5vh, 66px) var(--landing-pad) 6px;
}
.section-label {
  color: var(--color-muted);
  font: 11px var(--font-mono);
  letter-spacing: 0.1em;
}
.section-label > span {
  color: var(--color-focus);
}
h1,
h2 {
  font-family: var(--font-display);
  font-weight: 700;
  color: var(--color-ink);
}
h1 {
  margin: 12px 0 0;
  font-size: clamp(30px, 3.1vw, 54px);
  letter-spacing: -0.025em;
  line-height: 1.35;
  overflow-wrap: anywhere;
}
em {
  font-style: normal;
  color: var(--color-focus);
}
.hero-bottom {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 24px;
  margin-top: 18px;
}
.hero-description {
  max-width: 46em;
  color: var(--color-muted);
  font-size: 15px;
  line-height: 1.9;
}
.hero-description strong {
  color: var(--color-ink);
  font-weight: 500;
}
.line-link {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid var(--color-focus);
  font: 13px var(--font-mono);
}
.line-link span {
  color: var(--color-focus);
}
.line-link:hover {
  text-decoration: underline;
  text-underline-offset: 4px;
}
.hero-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 24px;
  margin-top: 16px;
  color: var(--color-muted);
  font: 11px/1.8 var(--font-mono);
}
.desk-section {
  display: flex;
  min-height: 0;
  flex: 1;
  padding: clamp(24px, 4vh, 48px) var(--landing-pad) 42px;
}
.desk-row {
  display: grid;
  min-width: 0;
  min-height: 0;
  width: 100%;
  grid-template-columns: minmax(0, 2.15fr) minmax(270px, 1fr);
  gap: clamp(30px, 4vw, 64px);
}
.desk {
  display: flex;
  min-width: 0;
  min-height: 280px;
  margin: 0;
  flex-direction: column;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  background: var(--color-surface);
}
.desk-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding: 15px 22px;
  border-bottom: 1px solid var(--color-border);
}
.desk-head h2 {
  font-family: inherit;
  font-weight: 600;
  line-height: 1.6;
  font-size: 16px;
}
.desk-head small {
  margin-left: 8px;
  font: 11px var(--font-mono);
  color: var(--color-muted);
}
.chart-context {
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  padding: 14px 22px 2px;
  gap: 8px;
  color: var(--color-muted);
  font-size: 11px;
}
.demo-chart {
  display: block;
  width: 100%;
  min-height: 170px;
  min-width: 0;
  flex: 1;
}
.research-marker circle {
  fill: var(--color-surface);
  stroke: var(--color-muted);
  stroke-width: 1.5;
}
.research-marker text {
  fill: var(--color-ink);
  font: 12px var(--font-mono);
  text-anchor: middle;
  dominant-baseline: central;
}
.research-marker.is-active circle {
  fill: var(--color-ink);
  stroke: var(--color-ink);
}
.research-marker.is-active text {
  fill: var(--color-surface);
}
.research-guide {
  stroke: var(--color-border);
  stroke-dasharray: 4 5;
}
.axis {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 22px 15px;
  color: var(--color-muted);
  font: 11px var(--font-mono);
}
.research-rail {
  display: flex;
  min-width: 0;
  flex-direction: column;
  padding-top: 6px;
}
.rail-label {
  color: var(--color-muted);
  font-size: 12px;
}
.research-rail h2 {
  font-size: clamp(23px, 1.8vw, 30px);
  line-height: 1.5;
  margin: 9px 0 20px;
}
.research-note {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 12px;
  min-height: 44px;
  padding: 15px 0;
  border: 0;
  border-top: 1px solid var(--color-border);
  background: transparent;
  color: var(--color-ink);
  text-align: left;
  font: inherit;
  cursor: pointer;
}
.note-index {
  display: grid;
  width: 26px;
  height: 26px;
  margin-top: 2px;
  place-items: center;
  border: 1px solid var(--color-border);
  border-radius: 50%;
  font: 11px var(--font-mono);
}
.research-note strong {
  display: block;
  font-size: 14px;
  font-weight: 500;
  line-height: 1.8;
}
.research-note small {
  display: block;
  margin-top: 4px;
  color: var(--color-muted);
  font-size: 11px;
}
.research-note[aria-pressed='true'] .note-index {
  background: var(--color-ink);
  border-color: var(--color-ink);
  color: var(--color-surface);
}
.research-note:hover strong {
  text-decoration: underline;
  text-underline-offset: 4px;
}
.rail-bottom {
  display: flex;
  margin-top: auto;
  padding-top: 17px;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.rail-bottom small {
  color: var(--color-muted);
  font-size: 11px;
}
.marquee {
  display: flex;
  min-height: 54px;
  border-block: 1px solid #404647;
  background: #292e30;
}
.ticker-label {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
  padding: 0 22px 0 var(--landing-pad);
  border-right: 1px solid #596061;
  color: #f4f1e8;
  font-size: 12px;
}
.ticker-label small {
  color: #c5cdce;
  font-size: 10px;
}
.ticker-window {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: center;
  overflow: hidden;
}
.marquee-track {
  display: flex;
  width: max-content;
  flex-shrink: 0;
  animation: marquee 30s linear infinite;
}
.ticker-group {
  display: flex;
  flex-shrink: 0;
  align-items: center;
}
.ticker-group > span {
  padding: 0 26px;
  white-space: nowrap;
  color: #ece9e2;
  font: 12px/1.8 var(--font-mono);
}
.ticker-group i {
  margin-right: 22px;
  color: #b7c8d2;
  font-style: normal;
  font-size: 10px;
}
.ticker-group .up {
  color: #f0ac9e;
}
.ticker-group .down {
  color: #aad1be;
}
@keyframes marquee {
  to {
    transform: translateX(-50%);
  }
}
.manifesto,
.feature-section,
.room-section,
.workflow-section {
  padding: clamp(72px, 10vh, 128px) var(--landing-pad) 0;
}
.manifesto p {
  max-width: 24em;
  font: 700 clamp(21px, 2.7vw, 34px)/1.75 var(--font-display);
}
.manifesto p > span {
  color: var(--color-muted);
}
.section-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 30px;
}
.section-head h2 {
  font-size: clamp(26px, 3.2vw, 44px);
}
.section-head > span {
  color: var(--color-muted);
  font: 11px var(--font-mono);
  letter-spacing: 0.08em;
}
.feature-list {
  border-top: 1px solid var(--color-border);
}
.feature-row {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr) minmax(0, 1.25fr);
  align-items: baseline;
  gap: 24px;
  padding: 26px 0;
  border-bottom: 1px solid var(--color-border);
}
.feature-number {
  color: var(--color-muted);
  font: 12px var(--font-mono);
}
.feature-number i {
  color: var(--color-focus);
  font-style: normal;
}
.feature-row h3 {
  margin: 0;
  font-size: 19px;
  font-weight: 600;
}
.feature-row p {
  color: var(--color-muted);
  font-size: 14px;
  line-height: 1.9;
}
.room {
  display: grid;
  grid-template-columns: minmax(0, 600px) minmax(0, 1fr);
  gap: 64px;
  align-items: center;
}
.room-demo {
  border: 1px solid var(--color-border);
  border-radius: 4px;
  background: var(--color-surface);
}
.mock-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding: 14px 22px;
  border-bottom: 1px solid var(--color-border);
  font-size: 13px;
}
.mock-head small {
  margin-left: 10px;
  color: var(--color-muted);
  font-weight: 400;
}
.mock-label {
  padding: 16px 22px 6px;
  color: var(--color-muted);
  font: 11px var(--font-mono);
}
.demo-opinion {
  display: grid;
  grid-template-columns: 44px 58px minmax(0, 1fr) auto;
  align-items: baseline;
  gap: 12px;
  padding: 16px 22px;
  border-bottom: 1px solid var(--color-border);
  font-size: 13px;
}
.demo-opinion strong {
  white-space: nowrap;
}
.demo-opinion .ui-chip {
  justify-self: start;
  padding-inline: 9px;
}
.demo-opinion p {
  line-height: 1.9;
}
.demo-time {
  color: var(--color-muted);
  font: 11px var(--font-mono);
}
.mock-foot {
  padding: 14px 22px;
  color: var(--color-muted);
  font-size: 12px;
  line-height: 1.8;
}
.room-note > p {
  margin-top: 14px;
  font-size: 16px;
  line-height: 2;
}
.room-note .mini {
  color: var(--color-muted);
  font-size: 12px;
}
.workflow {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 0;
  padding: 0;
  list-style: none;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  background: var(--color-surface);
}
.workflow li {
  min-width: 0;
  padding: 26px 32px;
  border-right: 1px solid var(--color-border);
}
.workflow li:last-child {
  border: 0;
}
.step-number {
  font: 700 clamp(28px, 3.2vw, 44px)/1.1 var(--font-display);
}
.workflow h3 {
  margin: 12px 0 4px;
  font-size: 15px;
}
.workflow p {
  color: var(--color-muted);
  font-size: 12px;
}
.landing-footer {
  margin-top: clamp(36px, 6vh, 64px);
  padding: 52px var(--landing-pad) 24px;
  border-top: 1px solid var(--color-border);
}
.foot-mark {
  color: transparent;
  -webkit-text-stroke: 1px var(--color-border);
  font: 900 clamp(60px, 10vw, 150px)/1 var(--font-display);
  user-select: none;
  transition:
    color 600ms var(--ease-out),
    -webkit-text-stroke-color 600ms var(--ease-out);
}
@media (hover: hover) and (pointer: fine) {
  .foot-mark:hover {
    color: var(--color-ink);
    -webkit-text-stroke-color: transparent;
  }
}
.foot-links {
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 20px;
  margin-top: 40px;
}
.foot-links a {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
  color: var(--color-muted);
  font: 12px var(--font-mono);
}
.foot-links a:hover {
  color: var(--color-ink);
  text-decoration: underline;
  text-underline-offset: 4px;
}
.foot-bottom {
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 28px;
  padding-top: 20px;
  border-top: 1px solid var(--color-border);
  color: var(--color-muted);
  font: 11px/1.8 var(--font-mono);
}
@media (min-width: 1800px) {
  .landing-page {
    --landing-pad: max(80px, calc((100vw - 1640px) / 2));
  }
}
@media (max-width: 1100px) {
  .firstscreen {
    height: auto;
    min-height: 0;
  }
  .desk-row {
    grid-template-columns: minmax(0, 1.8fr) minmax(240px, 1fr);
    gap: 28px;
  }
  .desk {
    min-height: 340px;
  }
  .room {
    gap: 40px;
  }
}
@media (max-width: 860px) {
  .nav-links {
    display: none !important;
  }
  .feature-row {
    grid-template-columns: 90px minmax(0, 1fr);
    row-gap: 8px;
  }
  .feature-row p {
    grid-column: 2;
  }
  .room {
    grid-template-columns: minmax(0, 1fr);
  }
  .workflow {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .workflow li:nth-child(2) {
    border-right: 0;
  }
  .workflow li:nth-child(-n + 2) {
    border-bottom: 1px solid var(--color-border);
  }
}
@media (max-width: 760px) {
  .landing-page {
    --landing-pad: 24px;
  }
  .landing-header {
    padding-block: 12px;
    gap: 12px;
    min-height: 68px;
  }
  .landing-header nav {
    gap: 16px;
  }
  .hero {
    padding-top: 32px;
  }
  .hero-description {
    font-size: 14px;
  }
  .hero-meta {
    gap: 6px 18px;
    font-size: 10px;
  }
  .desk-row {
    grid-template-columns: minmax(0, 1fr);
    gap: 28px;
  }
  .desk {
    min-height: 280px;
  }
  .desk-head {
    padding: 14px;
    gap: 8px;
  }
  .desk-head h2 {
    font-size: 14px;
  }
  .desk-head small {
    display: none;
  }
  .chart-context {
    padding-inline: 14px;
    font-size: 10px;
  }
  .rail-bottom {
    margin-top: 2px;
  }
  .ticker-label {
    padding-inline: var(--landing-pad) 12px;
    flex-direction: column;
    justify-content: center;
    gap: 0;
    font-size: 11px;
  }
  .demo-opinion {
    grid-template-columns: 44px 58px minmax(0, 1fr);
    padding-inline: 16px;
  }
  .demo-time {
    grid-row: 2;
    grid-column: 1 / -1;
  }
  .workflow li {
    padding: 24px 18px;
  }
  .foot-links {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }
}
@media (max-width: 360px) {
  .landing-header .nav-cta {
    padding-inline: 12px;
  }
  .feature-row {
    grid-template-columns: 70px minmax(0, 1fr);
    gap: 14px;
  }
  .demo-opinion {
    grid-template-columns: 44px minmax(0, 1fr);
  }
  .demo-opinion p {
    grid-column: 1 / -1;
  }
  .demo-time {
    grid-row: auto;
  }
  .axis {
    padding-inline: 14px;
    font-size: 10px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .marquee {
    align-items: stretch;
  }
  .ticker-window {
    overflow: visible;
  }
  .marquee-track {
    width: 100%;
    animation: none;
    transform: none;
  }
  .ticker-group {
    width: 100%;
    flex: 1 1 0;
    min-width: 0;
    flex-wrap: wrap;
    padding: 8px 0;
  }
  .ticker-group[aria-hidden='true'] {
    display: none;
  }
  .ticker-group > span {
    white-space: normal;
    padding: 5px 16px;
    font-size: 11px;
  }
  .ticker-group i {
    margin-right: 10px;
  }
}
</style>
