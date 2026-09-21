<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type {
  OpinionHorizon,
  StockSearchResponseVO,
  StockStance,
  WorkspaceStockListResponseVO,
} from '@/api/stock'
import StockMarketPanel from '@/components/stock/StockMarketPanel.vue'
import { useStockStore } from '@/stores/stock'
import { useWorkspaceStore } from '@/stores/workspace'
import { handleWorkspaceApiError } from '@/utils/workspace-access'

const route = useRoute()
const router = useRouter()
const workspaceStore = useWorkspaceStore()
const stockStore = useStockStore()

const workspaceId = computed(() => Number(route.params.workspaceId))
const isArchived = computed(() => workspaceStore.currentWorkspace?.status !== 'ACTIVE')
const pageLoading = ref(true)
const keyword = ref('')
const searchHasRun = ref(false)
const formError = ref('')
const expandedStockId = ref<number | null>(null)

function routeKeyword() {
  return typeof route.query.q === 'string' ? route.query.q.trim() : ''
}

async function load() {
  if (!Number.isSafeInteger(workspaceId.value) || workspaceId.value <= 0) {
    await router.replace('/workspaces')
    return
  }

  pageLoading.value = true
  stockStore.clearRefreshWarning()
  let loaded = false
  try {
    await workspaceStore.ensureCurrent(workspaceId.value)
    await Promise.all([
      stockStore.loadList(workspaceId.value),
      stockStore.loadEnded(workspaceId.value),
    ])
    loaded = true
  } catch (error) {
    await handleWorkspaceApiError(error, router)
  } finally {
    pageLoading.value = false
  }

  const restoredKeyword = routeKeyword()
  if (loaded && restoredKeyword && !isArchived.value) {
    keyword.value = restoredKeyword
    await runSearch(false)
  }
}

async function runSearch(updateQuery: boolean) {
  stockStore.clearSearch()
  const normalizedKeyword = keyword.value.trim()
  if (!normalizedKeyword) {
    formError.value = '请输入股票代码、名称或拼音首字母'
    return
  }

  formError.value = ''
  searchHasRun.value = true
  if (updateQuery && routeKeyword() !== normalizedKeyword) {
    await router.replace({
      name: 'workspace-stocks',
      params: { workspaceId: workspaceId.value },
      query: { ...route.query, q: normalizedKeyword },
    })
  }
  try {
    await stockStore.search(normalizedKeyword)
  } catch (error) {
    await handleWorkspaceApiError(error, router)
  }
}

async function search() {
  await runSearch(true)
}

function listingStatusLabel(stock: StockSearchResponseVO) {
  if (stock.simulated) return '模拟行情'
  if (stock.listingStatus === 'LISTED') return '正常上市'
  if (stock.listingStatus === 'SUSPENDED') return '停牌（可研究）'
  if (stock.listingStatus === 'DELISTED') return '已退市'
  return '交易状态待核实'
}

async function retryPageData() {
  try {
    await Promise.all([stockStore.loadList(workspaceId.value), stockStore.loadEnded(workspaceId.value)])
    stockStore.clearRefreshWarning()
  } catch (error) {
    await handleWorkspaceApiError(error, router)
  }
}

async function toggleAttention(item: WorkspaceStockListResponseVO) {
  try {
    await stockStore.updateAttention(
      workspaceId.value,
      item.workspaceStockId,
      item.researchCycleId,
      item.attentionLevel === 'FOCUS' ? 'NORMAL' : 'FOCUS',
    )
  } catch (error) {
    await handleWorkspaceApiError(error, router)
  }
}

function currencySymbol(currency: string) {
  return currency === 'HKD' ? 'HK$' : '¥'
}

function formatPrice(value: number | null, currency: string) {
  if (value === null) return '未填写'
  return `${currencySymbol(currency)}${new Intl.NumberFormat('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  }).format(value)}`
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function stanceLabel(stance: StockStance) {
  return { BULLISH: '看多', NEUTRAL: '中性', BEARISH: '看空' }[stance]
}

function horizonLabel(horizon: OpinionHorizon) {
  return { SHORT: '短期', MEDIUM: '中期', LONG: '长期' }[horizon]
}

function toggleCandlesticks(item: WorkspaceStockListResponseVO) {
  expandedStockId.value =
    expandedStockId.value === item.workspaceStockId ? null : item.workspaceStockId
}

onMounted(load)
onBeforeUnmount(() => {
  stockStore.clearListViews()
})
</script>

<template>
  <section v-if="pageLoading" class="content-state" aria-live="polite">
    <span class="loading-line"></span>
    <span class="loading-line short"></span>
    <p>正在读取共享自选</p>
  </section>

  <section
    v-else-if="workspaceStore.errorMessage && !workspaceStore.currentWorkspace"
    class="content-state"
    role="alert"
  >
    <p class="section-label">加载失败</p>
    <h1>暂时无法进入这个空间</h1>
    <p>{{ workspaceStore.errorMessage }}</p>
    <div class="state-actions">
      <button class="primary" type="button" @click="load">重新加载</button>
      <button type="button" @click="router.push('/workspaces')">选择其他空间</button>
    </div>
  </section>

  <template v-else-if="workspaceStore.currentWorkspace">
    <section v-if="isArchived" class="archive-notice" aria-live="polite">
      <strong>这个空间已归档，共享自选处于只读状态。</strong>
      <span>你仍然可以查看关注时的行情快照和初始观点。</span>
    </section>

    <section class="page-heading">
      <div>
        <p class="section-label">共享研究对象</p>
        <h1>共享自选</h1>
        <p>空间成员看到同一份股票列表，每只股票从一条可追溯的初始观点开始。</p>
      </div>
      <dl>
        <div>
          <dt>正在关注</dt>
          <dd>{{ stockStore.items.length }}</dd>
        </div>
        <div>
          <dt>数据范围</dt>
          <dd>A 股 / 港股</dd>
        </div>
      </dl>
    </section>

    <section v-if="!isArchived" class="add-section" aria-labelledby="add-stock-title">
      <div class="add-copy">
        <p class="section-label">查找股票</p>
        <h2 id="add-stock-title">先查看行情，再决定是否加入关注</h2>
        <p>按代码、名称或拼音首字母搜索。打开一只股票只读取行情，不会自动加入共享自选。</p>
      </div>

      <form class="search-form" role="search" @submit.prevent="search">
        <label for="stock-keyword">股票代码、名称或拼音首字母</label>
        <div>
          <input
            id="stock-keyword"
            v-model="keyword"
            autocomplete="off"
            placeholder="例如 600519、腾讯或 tbdg"
          />
          <button class="primary" type="submit" :disabled="stockStore.searchLoading">
            {{ stockStore.searchLoading ? '正在搜索…' : '搜索股票' }}
          </button>
        </div>
      </form>

      <p v-if="stockStore.searchError" class="inline-error" role="alert">
        {{ stockStore.searchError }}
      </p>
      <p v-if="formError" class="inline-error" role="alert">
        {{ formError }}
      </p>

      <div v-if="stockStore.searchResults.length" class="search-results">
        <RouterLink
          v-for="stock in stockStore.searchResults"
          :key="stock.code"
          :to="{
            name: 'workspace-stock-market',
            params: { workspaceId, code: stock.code },
            query: routeKeyword() ? { q: routeKeyword() } : {},
          }"
        >
          <span>
            <strong>{{ stock.name }}</strong>
            <small>{{ stock.code }} · {{ stock.exchange }}</small>
          </span>
          <span class="quote">
            <strong>{{
              stock.price === null ? '暂无报价' : formatPrice(stock.price, stock.currency)
            }}</strong>
            <small>{{ listingStatusLabel(stock) }}</small>
          </span>
          <span class="result-action">查看行情 <span aria-hidden="true">→</span></span>
        </RouterLink>
      </div>
      <p
        v-else-if="searchHasRun && !stockStore.searchLoading && !stockStore.searchError"
        class="search-empty"
      >
        没有找到匹配的股票，请尝试标准代码、更短的名称或从首字开始的拼音缩写。
      </p>

    </section>

    <p v-if="stockStore.refreshWarning" class="inline-error" role="alert">
      {{ stockStore.refreshWarning }}
      <button type="button" @click="retryPageData">只重新加载页面数据</button>
    </p>

    <section class="list-section" aria-labelledby="shared-list-title">
      <div class="list-heading">
        <div>
          <p class="section-label">当前空间</p>
          <h2 id="shared-list-title">正在关注</h2>
        </div>
        <button
          v-if="stockStore.listError"
          type="button"
          :disabled="stockStore.listLoading"
          @click="stockStore.loadList(workspaceId)"
        >
          重新加载列表
        </button>
      </div>

      <p v-if="stockStore.listError" class="inline-error" role="alert">
        {{ stockStore.listError }}
      </p>
      <div v-else-if="stockStore.listLoading" class="list-skeleton" aria-live="polite">
        <span></span><span></span><span></span>
      </div>
      <div v-else-if="stockStore.items.length" class="stock-list">
        <p v-if="stockStore.attentionError" class="inline-error" role="alert">
          {{ stockStore.attentionError }}
        </p>
        <article v-for="item in stockStore.items" :key="item.workspaceStockId">
          <div class="stock-summary">
            <div class="identity">
              <span class="attention">{{
                item.attentionLevel === 'FOCUS' ? '重点关注' : '普通关注'
              }}</span>
              <h3>
                <RouterLink
                  :to="{
                    name: 'workspace-stock-research',
                    params: { workspaceId, workspaceStockId: item.workspaceStockId },
                  }"
                >
                  {{ item.name }}
                </RouterLink>
              </h3>
              <p>{{ item.code }} · {{ item.exchange }}</p>
            </div>
            <div class="metric">
              <span>关注价格</span>
              <strong>{{ formatPrice(item.startPrice, item.currency) }}</strong>
              <small>{{ item.simulated ? '本地模拟行情' : item.quoteSource }}</small>
            </div>
            <div class="metric">
              <span>初始倾向</span>
              <strong>{{ item.initialOpinionWithdrawn ? '已撤回' : stanceLabel(item.stance) }}</strong>
              <small>
                {{
                  item.initialOpinionWithdrawn
                    ? item.authorNickname
                    : `${horizonLabel(item.horizon)} · ${item.authorNickname}`
                }}
              </small>
            </div>
          </div>
          <div class="stock-actions">
            <button
              v-if="!isArchived"
              class="attention-toggle"
              type="button"
              :disabled="stockStore.attentionUpdatingStockId !== null"
              @click="toggleAttention(item)"
            >
              {{
                stockStore.attentionUpdatingStockId === item.workspaceStockId
                  ? '正在更新…'
                  : item.attentionLevel === 'FOCUS'
                    ? '改为普通关注'
                    : '设为重点关注'
              }}
            </button>
            <button
              class="kline-toggle"
              type="button"
              :aria-expanded="expandedStockId === item.workspaceStockId"
              :aria-controls="`stock-chart-${item.workspaceStockId}`"
              @click="toggleCandlesticks(item)"
            >
              {{ expandedStockId === item.workspaceStockId ? '收起行情图' : '查看行情图' }}
            </button>
            <small>展开后才请求该股票的行情，不会为搜索候选预取。</small>
          </div>
          <div
            v-if="expandedStockId === item.workspaceStockId"
            :id="`stock-chart-${item.workspaceStockId}`"
            class="market-panel-slot"
          >
            <StockMarketPanel
              :workspace-id="workspaceId"
              :workspace-stock-id="item.workspaceStockId"
              :code="item.code"
              :name="item.name"
            />
          </div>
          <details>
            <summary>查看初始观点与行情记录</summary>
            <p v-if="item.initialOpinionWithdrawn" class="withdrawn-summary">
              这条初始观点已由作者撤回，正文和价格信息已隐藏，发表记录仍保留。
            </p>
            <div v-else class="opinion-detail">
              <div>
                <span>判断理由</span>
                <p>{{ item.reason }}</p>
              </div>
              <div v-if="item.observationNote">
                <span>关键观察条件</span>
                <p>{{ item.observationNote }}</p>
              </div>
              <dl>
                <div>
                  <dt>目标价</dt>
                  <dd>{{ formatPrice(item.targetPrice, item.currency) }}</dd>
                </div>
                <div>
                  <dt>止损参考价</dt>
                  <dd>{{ formatPrice(item.stopLossPrice, item.currency) }}</dd>
                </div>
                <div>
                  <dt>行情时间</dt>
                  <dd>{{ formatTime(item.quoteTime) }}</dd>
                </div>
                <div>
                  <dt>发表时间</dt>
                  <dd>{{ formatTime(item.opinionCreatedAt) }}</dd>
                </div>
              </dl>
            </div>
          </details>
        </article>
      </div>
      <div v-else class="empty-list">
        <h3>共享自选还是空的</h3>
        <p v-if="isArchived">这个归档空间没有保存过正在关注的股票。</p>
        <p v-else>从上方搜索一只标准股票，并写下第一条可回看的判断。</p>
      </div>
    </section>

    <section v-if="stockStore.endedItems.length" class="ended-section" aria-labelledby="ended-title">
      <div class="list-heading">
        <div>
          <p class="section-label">历史研究</p>
          <h2 id="ended-title">已结束关注</h2>
        </div>
      </div>
      <div class="ended-list">
        <RouterLink
          v-for="item in stockStore.endedItems"
          :key="item.workspaceStockId"
          :to="{
            name: 'workspace-stock-research',
            params: { workspaceId, workspaceStockId: item.workspaceStockId },
          }"
        >
          <span>
            <strong>{{ item.name }}</strong>
            <small>{{ item.code }} · {{ item.exchange }}</small>
          </span>
          <span>{{ item.status === 'CANCELLED' ? '取消于' : '结束于' }} {{ formatTime(item.endedAt) }}</span>
        </RouterLink>
      </div>
    </section>
  </template>
</template>

<style scoped>
.list-heading button,
.state-actions button {
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.archive-notice {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 24px;
  margin-top: 24px;
  padding: 16px 18px;
  background: color-mix(in oklab, var(--color-focus) 8%, var(--color-surface));
  font-size: 13px;
}
.archive-notice span {
  color: var(--color-muted);
}
.page-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 42px;
  padding: 64px 0 42px;
  border-bottom: 1px solid var(--color-ink);
}
.section-label {
  color: var(--color-focus);
  font-size: 13px;
  font-weight: 650;
}
.page-heading h1,
.content-state h1 {
  margin-top: 12px;
  font-size: 44px;
  font-weight: 570;
  letter-spacing: -0.035em;
  line-height: 1.08;
  text-wrap: balance;
}
.page-heading > div > p:last-child {
  max-width: 58ch;
  margin-top: 15px;
  color: var(--color-muted);
}
.page-heading dl {
  display: flex;
  flex: 0 0 auto;
  gap: 36px;
  margin: 0;
}
.page-heading dt,
.metric span,
.opinion-detail span,
.opinion-detail dt {
  color: var(--color-muted);
  font-size: 12px;
}
.page-heading dd {
  margin: 5px 0 0;
  font-weight: 700;
}
.add-section {
  padding: 42px 0 48px;
  border-bottom: 1px solid var(--color-border);
}
.add-copy {
  display: grid;
  grid-template-columns: minmax(0, 0.7fr) minmax(320px, 1.3fr);
  gap: 32px;
}
.add-copy h2,
.list-heading h2 {
  font-size: 25px;
  letter-spacing: -0.025em;
  text-wrap: balance;
}
.add-copy > p:last-child {
  max-width: 58ch;
  color: var(--color-muted);
}
.search-form {
  margin-top: 30px;
}
.search-form label {
  display: block;
  margin-bottom: 9px;
  font-size: 13px;
  font-weight: 650;
}
.search-form > div {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
}
input {
  width: 100%;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  color: var(--color-ink);
}
input {
  min-height: 48px;
  padding: 0 14px;
}
input::placeholder {
  color: oklch(0.46 0.008 255);
}
input:focus {
  border-color: var(--color-ink);
  outline: 3px solid color-mix(in oklab, var(--color-focus) 25%, transparent);
}
.primary {
  min-height: 46px;
  padding: 0 20px;
  border: 0;
  border-radius: var(--radius-action);
  background: var(--color-ink);
  color: white;
  cursor: pointer;
  font-weight: 700;
}
.primary:disabled {
  cursor: wait;
  opacity: 0.58;
}
.search-results {
  margin-top: 18px;
  border-top: 1px solid var(--color-border);
}
.search-results a {
  display: grid;
  width: 100%;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 24px;
  padding: 18px 12px;
  border-bottom: 1px solid var(--color-border);
  background: transparent;
  color: inherit;
  text-align: left;
}
.search-results a:hover,
.search-results a:focus-visible {
  background: color-mix(in oklab, var(--color-focus) 7%, transparent);
}
.search-results strong,
.search-results small {
  display: block;
}
.search-results small {
  margin-top: 4px;
  color: var(--color-muted);
}
.quote {
  flex: 0 0 auto;
  text-align: right;
}
.result-action {
  color: var(--color-focus);
  font-size: 13px;
  font-weight: 650;
  white-space: nowrap;
}
.list-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}
.inline-error,
.search-empty {
  margin-top: 16px;
  font-size: 13px;
}
.inline-error {
  color: var(--color-danger);
}
.search-empty {
  color: var(--color-muted);
}
.list-section {
  padding-top: 48px;
}
.list-heading {
  padding-bottom: 20px;
  border-bottom: 1px solid var(--color-ink);
}
.list-heading button {
  color: var(--color-focus);
  font-weight: 650;
}
.stock-list article {
  border-bottom: 1px solid var(--color-border);
}
.identity a {
  color: inherit;
  text-decoration-color: color-mix(in oklab, var(--color-focus) 45%, transparent);
  text-underline-offset: 4px;
}
.ended-section {
  padding: 64px 0;
}
.ended-list a {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 20px 4px;
  border-bottom: 1px solid var(--color-border);
  color: inherit;
  text-decoration: none;
}
.ended-list strong,
.ended-list small {
  display: block;
}
.ended-list small,
.ended-list a > span:last-child {
  margin-top: 4px;
  color: var(--color-muted);
  font-size: 12px;
}
.stock-summary {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(150px, 0.55fr) minmax(150px, 0.55fr);
  gap: 28px;
  align-items: center;
  padding: 27px 4px 22px;
}
.stock-actions {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 4px 18px;
}
.stock-actions small {
  color: var(--color-muted);
  font-size: 12px;
}
.kline-toggle {
  border: 0;
  background: transparent;
  color: var(--color-focus);
  cursor: pointer;
  font-weight: 650;
}
.kline-toggle {
  padding: 0;
  text-decoration: underline;
  text-underline-offset: 4px;
}
.attention-toggle {
  min-height: 34px;
  padding: 0 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-action);
  background: var(--color-surface);
  color: var(--color-focus);
  cursor: pointer;
  font-weight: 650;
}
.attention-toggle:disabled {
  cursor: wait;
  opacity: 0.58;
}
.attention-toggle:focus-visible,
.kline-toggle:focus-visible {
  border-radius: 4px;
  outline: 3px solid color-mix(in oklab, var(--color-focus) 25%, transparent);
  outline-offset: 3px;
}
.market-panel-slot {
  margin: 0 4px 24px;
}
.identity h3 {
  margin: 6px 0 2px;
  font-size: 21px;
}
.identity p {
  color: var(--color-muted);
  font-size: 13px;
}
.attention {
  color: var(--color-focus);
  font-size: 12px;
  font-weight: 700;
}
.metric strong,
.metric small {
  display: block;
}
.metric strong {
  margin-top: 5px;
  font-size: 18px;
}
.metric small {
  margin-top: 3px;
  color: var(--color-muted);
}
details {
  padding: 0 4px 22px;
}
summary {
  width: fit-content;
  color: var(--color-focus);
  cursor: pointer;
  font-size: 13px;
  font-weight: 650;
}
.opinion-detail {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
  margin-top: 20px;
  padding: 22px;
  background: var(--color-surface);
}
.withdrawn-summary {
  margin-top: 18px;
  color: var(--color-muted);
  font-size: 13px;
}
.opinion-detail p {
  margin-top: 7px;
  line-height: 1.7;
  white-space: pre-wrap;
}
.opinion-detail dl {
  display: grid;
  grid-column: 1 / -1;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px;
  margin: 0;
  padding-top: 18px;
  border-top: 1px solid var(--color-border);
}
.opinion-detail dd {
  margin: 4px 0 0;
  font-size: 13px;
}
.empty-list {
  padding: 70px 0;
  text-align: center;
}
.empty-list h3 {
  font-size: 21px;
}
.empty-list p {
  margin-top: 8px;
  color: var(--color-muted);
}
.content-state {
  max-width: 720px;
  padding-top: 120px;
}
.content-state > p:not(.section-label) {
  margin-top: 18px;
  color: var(--color-muted);
}
.loading-line,
.list-skeleton span {
  display: block;
  height: 18px;
  margin-bottom: 14px;
  background: var(--color-border);
  animation: pulse 1.4s ease-in-out infinite alternate;
}
.loading-line {
  width: min(560px, 75%);
}
.loading-line.short {
  width: min(360px, 52%);
}
.list-skeleton {
  padding: 28px 0;
}
.list-skeleton span:nth-child(2) {
  width: 82%;
}
.list-skeleton span:nth-child(3) {
  width: 64%;
}
.state-actions {
  display: flex;
  gap: 12px;
  margin-top: 24px;
}
@keyframes pulse {
  to {
    opacity: 0.4;
  }
}
@media (prefers-reduced-motion: reduce) {
  .loading-line,
  .list-skeleton span {
    animation: none;
  }
}
@media (max-width: 820px) {
  .page-heading {
    padding-top: 42px;
  }
  .stock-summary {
    grid-template-columns: minmax(0, 1fr) repeat(2, minmax(130px, 0.5fr));
  }
  .opinion-detail dl {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 640px) {
  .archive-notice,
  .page-heading,
  .add-copy {
    display: block;
  }
  .archive-notice span {
    display: block;
    margin-top: 6px;
  }
  .page-heading h1 {
    font-size: 36px;
  }
  .page-heading dl {
    margin-top: 28px;
  }
  .add-copy {
    display: block;
  }
  .add-copy > p:last-child {
    margin-top: 12px;
  }
  .search-form > div,
  .stock-summary,
  .opinion-detail,
  .opinion-detail dl {
    grid-template-columns: 1fr;
  }
  .search-form .primary {
    width: 100%;
  }
  .search-results a {
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 6px 16px;
  }
  .search-results .quote {
    grid-column: 1;
    text-align: left;
  }
  .result-action {
    grid-row: 1 / span 2;
    grid-column: 2;
  }
  .stock-summary {
    gap: 18px;
  }
  .stock-actions {
    align-items: flex-start;
    flex-direction: column;
  }
  .market-panel-slot {
    margin-inline: 0;
  }
  .opinion-detail dl {
    grid-column: auto;
  }
}
</style>
