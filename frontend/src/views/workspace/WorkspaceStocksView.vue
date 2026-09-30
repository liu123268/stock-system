<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type {
  OpinionType,
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
const endedError = ref('')
const endedLoading = ref(false)
let disposed = false
let loadVersion = 0
let searchVersion = 0
let chartVersion = 0
let readVersion = 0
let endedVersion = 0

function routeKeyword() {
  return typeof route.query.q === 'string' ? route.query.q.trim() : ''
}

async function load() {
  const version = ++loadVersion
  const id = workspaceId.value
  if (!Number.isSafeInteger(workspaceId.value) || workspaceId.value <= 0) {
    await router.replace('/workspaces')
    return
  }

  pageLoading.value = true
  stockStore.clearRefreshWarning()
  let loaded = false
  try {
    await workspaceStore.ensureCurrent(id)
    if (disposed || version !== loadVersion) return
    await retryPageData()
    loaded = true
  } catch (error) {
    if (!disposed && version === loadVersion) await handleWorkspaceApiError(error, router)
  } finally {
    if (!disposed && version === loadVersion) pageLoading.value = false
  }
  if (!disposed && version === loadVersion && loaded && !isArchived.value) {
    await restoreSearch(routeKeyword())
  }
}

async function restoreSearch(q: string) {
  const version = ++searchVersion
  stockStore.clearSearch()
  keyword.value = q
  searchHasRun.value = !!q
  formError.value = ''
  if (!q) return
  try {
    await stockStore.search(q)
  } catch (error) {
    if (!disposed && version === searchVersion) await handleWorkspaceApiError(error, router)
  }
}

async function search() {
  if (isArchived.value || stockStore.searchLoading) return
  const normalizedKeyword = keyword.value.trim()
  if (!normalizedKeyword) {
    searchVersion += 1
    stockStore.clearSearch()
    searchHasRun.value = false
    formError.value = '请输入股票代码、名称或拼音首字母'
    return
  }
  formError.value = ''
  if (routeKeyword() !== normalizedKeyword) {
    await router.replace({
      name: 'workspace-stocks',
      params: { workspaceId: workspaceId.value },
      query: { ...route.query, q: normalizedKeyword },
    })
  } else {
    await restoreSearch(normalizedKeyword)
  }
}

watch(routeKeyword, (q) => {
  if (!pageLoading.value && !isArchived.value) void restoreSearch(q)
})

function listingStatusLabel(stock: StockSearchResponseVO) {
  if (stock.simulated) return '模拟行情'
  if (stock.listingStatus === 'LISTED') return '正常上市'
  if (stock.listingStatus === 'SUSPENDED') return '停牌（可研究）'
  if (stock.listingStatus === 'DELISTED') return '已退市'
  return '交易状态待核实'
}

async function retryPageData() {
  const version = ++readVersion
  const results = await Promise.allSettled([stockStore.loadList(workspaceId.value), retryEnded()])
  if (disposed || version !== readVersion) return
  if (results.every((result) => result.status === 'fulfilled') && !endedError.value) {
    stockStore.clearRefreshWarning()
  }
  for (const result of results) {
    if (result.status === 'rejected') await handleWorkspaceApiError(result.reason, router)
  }
}

async function retryEnded() {
  const version = ++endedVersion
  endedLoading.value = true
  endedError.value = ''
  try {
    await stockStore.loadEnded(workspaceId.value)
  } catch (error) {
    if (!disposed && version === endedVersion) {
      endedError.value = error instanceof Error ? error.message : '历史研究加载失败'
      await handleWorkspaceApiError(error, router)
    }
  } finally {
    if (!disposed && version === endedVersion) endedLoading.value = false
  }
}

async function toggleAttention(item: WorkspaceStockListResponseVO) {
  if (isArchived.value || stockStore.attentionUpdatingStockId !== null) return
  try {
    await stockStore.updateAttention(
      workspaceId.value,
      item.workspaceStockId,
      item.researchCycleId,
      item.attentionLevel === 'FOCUS' ? 'NORMAL' : 'FOCUS',
    )
  } catch (error) {
    if (!disposed) await handleWorkspaceApiError(error, router)
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

function opinionTypeLabel(type: OpinionType) {
  return { INITIAL: '初始观点', UPDATE: '观点更新', REVIEW: '复盘总结' }[type]
}

async function toggleCandlesticks(item: WorkspaceStockListResponseVO) {
  const version = ++chartVersion
  const nextId = expandedStockId.value === item.workspaceStockId ? null : item.workspaceStockId
  // 两张卡片的更新顺序不保证旧面板先卸载；留一个更新周期完成共享数据清理。
  expandedStockId.value = null
  await nextTick()
  if (!disposed && version === chartVersion) expandedStockId.value = nextId
}

onMounted(load)
onBeforeUnmount(() => {
  disposed = true
  stockStore.clearSearch()
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
      <span>你仍然可以查看关注时的行情快照和完整研究时间线。</span>
    </section>

    <section class="page-heading">
      <div>
        <p class="section-label"><span>02</span> — 共享研究对象</p>
        <h1>共享自选</h1>
        <p>空间成员看到同一份股票列表，每只股票从一条可追溯的初始观点开始。</p>
      </div>
      <dl>
        <div>
          <dt>正在关注</dt>
          <dd>{{ stockStore.listError ? '暂不可用' : stockStore.items.length + ' 只' }}</dd>
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
          @click="retryPageData"
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
              <span class="attention ui-chip" :class="{ focus: item.attentionLevel === 'FOCUS' }">{{
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
              <p>
                {{ item.code }} · {{ item.exchange }} ·
                {{ item.currency === 'HKD' ? '港币' : '人民币' }}
              </p>
            </div>
            <div class="metric">
              <span>关注价格</span>
              <strong>{{ formatPrice(item.startPrice, item.currency) }}</strong>
              <small>{{ item.simulated ? '本地模拟行情' : item.quoteSource }}</small>
              <small>{{ formatTime(item.quoteTime) }}</small>
            </div>
            <div class="metric">
              <span>最近一条观点</span>
              <strong>{{ stanceLabel(item.latestOpinionStance) }}</strong>
              <small>
                {{ opinionTypeLabel(item.latestOpinionType) }} ·
                {{ item.latestOpinionAuthorNickname }}
              </small>
              <small>{{ formatTime(item.latestOpinionCreatedAt) }}</small>
            </div>
          </div>
          <div class="stock-actions">
            <div class="stock-action-controls">
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
            </div>
            <div class="research-summary-meta">
              <small>
                本期 {{ item.opinionCount }} 条观点 · {{ item.participantCount }} 位成员参与
              </small>
              <RouterLink
                class="research-link"
                :to="{
                  name: 'workspace-stock-research',
                  params: { workspaceId, workspaceStockId: item.workspaceStockId },
                  hash: '#research-timeline',
                }"
              >
                查看全部观点 <span aria-hidden="true">→</span>
              </RouterLink>
            </div>
          </div>
          <div
            v-if="expandedStockId === item.workspaceStockId"
            :id="`stock-chart-${item.workspaceStockId}`"
            class="market-panel-slot"
          >
            <StockMarketPanel
              :key="`${workspaceId}:${item.workspaceStockId}`"
              :workspace-id="workspaceId"
              :workspace-stock-id="item.workspaceStockId"
              :code="item.code"
              :name="item.name"
            />
          </div>
        </article>
      </div>
      <div v-else class="empty-list">
        <h3>共享自选还是空的</h3>
        <p v-if="isArchived">这个归档空间没有保存过正在关注的股票。</p>
        <p v-else>从上方搜索一只标准股票，并写下第一条可回看的判断。</p>
      </div>
    </section>

    <section
      v-if="stockStore.endedItems.length || endedError || endedLoading"
      class="ended-section"
      aria-labelledby="ended-title"
    >
      <div class="list-heading">
        <div>
          <p class="section-label">历史研究</p>
          <h2 id="ended-title">已结束关注</h2>
        </div>
        <button v-if="endedError" type="button" :disabled="endedLoading" @click="retryEnded">
          重新加载历史研究
        </button>
      </div>
      <p v-if="endedError" class="inline-error" role="alert">{{ endedError }}</p>
      <p v-else-if="endedLoading" class="search-empty" aria-live="polite">正在读取历史研究</p>
      <div v-else class="ended-list">
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
          <span
            >{{ item.status === 'CANCELLED' ? '取消于' : '结束于' }}
            {{ formatTime(item.endedAt) }}</span
          >
        </RouterLink>
      </div>
    </section>
  </template>
</template>

<style scoped>
button {
  min-height: 44px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  padding: 8px 16px;
  background: var(--color-surface);
  color: var(--color-text);
  cursor: pointer;
  font-size: 12px;
}
button:hover {
  border-color: var(--color-focus);
  color: var(--color-focus);
}
button:disabled {
  cursor: wait;
  opacity: 0.6;
}
.page-heading {
  padding: 52px 0 0;
}
.section-label {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.12em;
}
.section-label > span {
  color: var(--color-focus);
}
.page-heading h1,
.content-state h1 {
  margin-top: 14px;
  font-family: var(--font-display);
  font-size: clamp(30px, 3vw, 40px);
  font-weight: 900;
  line-height: 1.3;
}
.page-heading > div > p:last-child {
  max-width: 66ch;
  margin-top: 12px;
  color: var(--color-muted);
  font-size: 14px;
}
.page-heading dl {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin: 30px 0 0;
  border-block: 1px solid var(--color-border);
  background: var(--color-surface);
}
.page-heading dl > div {
  padding: 16px 22px;
}
.page-heading dl > div + div {
  border-left: 1px solid var(--color-border);
}
.page-heading dt {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}
.page-heading dd {
  margin: 6px 0 0;
  font-size: 17px;
  font-weight: 700;
}
.add-section {
  margin-top: 48px;
  padding: 26px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
}
.add-copy h2 {
  margin-top: 10px;
  font-family: var(--font-display);
  font-size: 20px;
  font-weight: 900;
  line-height: 1.5;
}
.add-copy > p:last-child {
  margin-top: 8px;
  color: var(--color-muted);
  font-size: 12px;
}
.search-form {
  margin-top: 20px;
}
.search-form label {
  display: block;
  margin-bottom: 8px;
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}
.search-form > div {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
}
input {
  min-width: 0;
  width: 100%;
  min-height: 46px;
  padding: 10px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  color: var(--color-ink);
  font-size: 14px;
}
input::placeholder {
  color: var(--color-muted);
}
input:focus-visible {
  border-color: var(--color-focus);
}
.primary {
  border-color: var(--color-ink);
  color: var(--color-ink);
}
.primary:hover {
  background: var(--color-ink);
  color: var(--color-surface);
}
.search-results {
  margin-top: 18px;
  border-top: 1px solid var(--color-border);
}
.search-results a {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 18px;
  min-height: 70px;
  padding: 13px 4px;
  border-bottom: 1px solid var(--color-border);
  color: inherit;
  text-decoration: none;
  font-size: 14px;
}
.search-results a:hover {
  background: var(--color-surface-secondary);
}
.search-results strong,
.search-results small {
  display: block;
}
.search-results small {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}
.quote {
  text-align: right;
}
.quote strong {
  font-family: var(--font-mono);
}
.result-action {
  color: var(--color-focus);
  font-size: 12px;
  white-space: nowrap;
}
.list-section {
  margin-top: 56px;
}
.list-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 20px;
}
.list-heading h2 {
  margin-top: 8px;
  font-family: var(--font-display);
  font-weight: 900;
  font-size: 26px;
}
.inline-error,
.search-empty {
  margin-top: 16px;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.inline-error {
  color: var(--color-danger);
}
.inline-error button {
  margin: 8px 0 0 8px;
}
.search-empty {
  color: var(--color-muted);
}
.stock-list article {
  margin-bottom: 18px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
}
.stock-summary {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr) minmax(0, 1.25fr);
  align-items: start;
  gap: 20px;
  padding: 20px 24px;
}
.identity,
.metric {
  min-width: 0;
  overflow-wrap: anywhere;
}
.attention {
  margin-bottom: 9px;
}
.attention.focus {
  border-color: var(--color-accent);
  color: var(--color-focus);
}
.identity h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
}
.identity a {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  color: inherit;
  text-decoration: none;
}
.identity a:hover {
  color: var(--color-focus);
  text-decoration: underline;
  text-underline-offset: 4px;
}
.identity p {
  margin-top: 4px;
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}
.metric {
  padding-top: 6px;
}
.metric > span {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.1em;
}
.metric strong {
  display: block;
  margin-top: 5px;
  font-family: var(--font-mono);
  font-size: 16px;
}
.metric small {
  display: block;
  margin-top: 3px;
  color: var(--color-muted);
  font-size: 11.5px;
}
.stock-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
  padding: 10px 24px;
  border-top: 1px solid var(--color-border);
}
.stock-action-controls {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.research-summary-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  color: var(--color-muted);
  font-family: var(--font-mono);
}
.research-summary-meta small {
  font-size: 11px;
}
.research-link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  color: var(--color-focus);
  font-size: 12px;
  text-decoration: underline;
  text-decoration-color: var(--color-accent);
  text-underline-offset: 6px;
}
.market-panel-slot {
  padding: 16px 24px 24px;
  border-top: 1px solid var(--color-border);
}
.market-panel-slot :deep(.market-panel) {
  margin: 0;
}
.ended-section {
  padding: 48px 0;
}
.ended-list a {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 18px;
  min-height: 70px;
  padding: 16px 4px;
  border-bottom: 1px solid var(--color-border);
  color: inherit;
  text-decoration: none;
}
.ended-list a:hover {
  background: var(--color-surface-secondary);
}
.ended-list a > span {
  min-width: 0;
  overflow-wrap: anywhere;
}
.ended-list strong {
  font-size: 14px;
}
.ended-list small {
  margin-left: 10px;
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}
.ended-list a > span:last-child {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}
.archive-notice {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  margin-top: 24px;
  padding: 16px 18px;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  background: var(--color-surface-secondary);
  font-size: 13px;
}
.archive-notice span {
  color: var(--color-muted);
}
.empty-list,
.content-state {
  padding: 48px 0;
  color: var(--color-muted);
}
.empty-list h3 {
  margin: 0 0 12px;
  color: var(--color-ink);
  font-family: var(--font-display);
}
.content-state p {
  margin-top: 16px;
}
.state-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 24px;
}
.loading-line,
.list-skeleton span {
  display: block;
  height: 16px;
  margin-bottom: 14px;
  border-radius: 4px;
  background: var(--color-surface-secondary);
}
.loading-line {
  max-width: 480px;
}
.loading-line.short {
  max-width: 260px;
}
.list-skeleton span {
  height: 110px;
}
@media (max-width: 720px) {
  .stock-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 18px;
  }
  .identity {
    grid-column: 1 / -1;
  }
  .stock-summary,
  .stock-actions {
    padding-inline: 18px;
  }
  .market-panel-slot {
    padding: 12px;
  }
  .research-summary-meta {
    gap: 4px 16px;
  }
  .ended-list a {
    grid-template-columns: minmax(0, 1fr);
    gap: 4px;
  }
}
@media (max-width: 480px) {
  .page-heading {
    padding-top: 32px;
  }
  .page-heading dl > div {
    padding: 14px;
  }
  .add-section {
    margin-top: 32px;
    padding: 18px;
  }
  .search-form > div {
    grid-template-columns: minmax(0, 1fr);
  }
  .search-results a {
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 4px 12px;
  }
  .result-action {
    grid-column: 1 / -1;
    text-align: right;
  }
  .stock-summary {
    grid-template-columns: minmax(0, 1fr);
  }
  .identity {
    grid-column: auto;
  }
  .stock-actions {
    gap: 8px;
  }
  .list-section {
    margin-top: 40px;
  }
  .ended-list small {
    display: block;
    margin: 4px 0 0;
  }
}
</style>
