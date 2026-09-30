<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { AddWorkspaceStockRequestDTO, WorkspaceStockMarketDetailResponseVO } from '@/api/stock'
import StockInitialOpinionForm from '@/components/stock/StockInitialOpinionForm.vue'
import StockMarketPanel from '@/components/stock/StockMarketPanel.vue'
import { useStockStore } from '@/stores/stock'
import { useWorkspaceStore } from '@/stores/workspace'
import { handleWorkspaceApiError } from '@/utils/workspace-access'

type InitialOpinionDraft = Omit<AddWorkspaceStockRequestDTO, 'code'>

const route = useRoute()
const router = useRouter()
const workspaceStore = useWorkspaceStore()
const stockStore = useStockStore()

const workspaceId = computed(() => Number(route.params.workspaceId))
const code = computed(() =>
  String(route.params.code ?? '')
    .trim()
    .toUpperCase(),
)
const market = computed(() => stockStore.marketDetail)
const isArchived = computed(() => workspaceStore.currentWorkspace?.status !== 'ACTIVE')
const pageLoading = ref(true)
const showOpinionForm = ref(false)
const formError = ref('')
const addButton = ref<HTMLButtonElement | null>(null)
const opinionForm = ref<{ focusHeading: () => void } | null>(null)
let disposed = false
let loadVersion = 0

const searchQuery = computed(() => {
  const value = typeof route.query.q === 'string' ? route.query.q.trim() : ''
  return value ? { q: value } : {}
})
const hasSearchQuery = computed(
  () => typeof route.query.q === 'string' && Boolean(route.query.q.trim()),
)

const backToStocks = computed(() => ({
  name: 'workspace-stocks',
  params: { workspaceId: workspaceId.value },
  query: searchQuery.value,
}))

const addBlockedReason = computed(() => {
  const detail = market.value
  if (!detail) return ''
  if (isArchived.value) return '这个空间已经归档，只能查看行情和既有研究记录。'
  if (detail.trackingState === 'ACTIVE') return ''
  if (detail.listingStatus === 'DELISTED')
    return '这只股票已经退市，历史行情仍可查看，但不能开启新的研究周期。'
  if (detail.listingStatus === 'UNKNOWN') {
    return '这只股票的交易状态暂时无法核实，行情仍可查看，但暂时不能加入关注。'
  }
  if (detail.price === null) {
    return '当前没有可用报价，暂时无法记录研究开始价格；你仍然可以查看可取得的历史行情。'
  }
  return ''
})

const canStartResearch = computed(() => {
  const detail = market.value
  return Boolean(
    detail && detail.trackingState !== 'ACTIVE' && !isArchived.value && !addBlockedReason.value,
  )
})

function listingStatusLabel(detail: WorkspaceStockMarketDetailResponseVO) {
  if (detail.simulated) return '模拟行情'
  if (detail.listingStatus === 'LISTED') return '正常上市'
  if (detail.listingStatus === 'SUSPENDED') return '停牌（可研究）'
  if (detail.listingStatus === 'DELISTED') return '已退市'
  return '交易状态待核实'
}

function trackingLabel(detail: WorkspaceStockMarketDetailResponseVO) {
  if (detail.trackingState === 'ACTIVE') return '当前空间正在关注'
  if (detail.trackingState === 'ENDED') return '当前空间曾经关注'
  return '当前空间尚未关注'
}

function currencySymbol(currency: string) {
  return currency === 'HKD' ? 'HK$' : '¥'
}

function formatPrice(value: number | null, currency: string) {
  if (value === null) return '暂无报价'
  return `${currencySymbol(currency)}${new Intl.NumberFormat('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  }).format(value)}`
}

function formatTime(value: string | null) {
  if (!value) return '时间暂不可用'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

async function load() {
  const version = ++loadVersion
  const requestedWorkspace = workspaceId.value
  const requestedCode = code.value
  if (!Number.isSafeInteger(workspaceId.value) || workspaceId.value <= 0 || !code.value) {
    await router.replace('/workspaces')
    return
  }

  pageLoading.value = true
  showOpinionForm.value = false
  formError.value = ''
  stockStore.clearMarketDetail()
  stockStore.clearCandlesticks()
  stockStore.clearIntraday()
  try {
    await workspaceStore.ensureCurrent(requestedWorkspace)
    if (disposed || version !== loadVersion) return
    await stockStore.loadMarketDetail(requestedWorkspace, requestedCode)
  } catch (error) {
    if (!disposed && version === loadVersion) await handleWorkspaceApiError(error, router)
  } finally {
    if (!disposed && version === loadVersion) pageLoading.value = false
  }
}

async function openOpinionForm() {
  if (!canStartResearch.value) return
  formError.value = ''
  showOpinionForm.value = true
  await nextTick()
  opinionForm.value?.focusHeading()
}

async function closeOpinionForm() {
  showOpinionForm.value = false
  formError.value = ''
  await nextTick()
  addButton.value?.focus()
}

async function submitOpinion(draft: InitialOpinionDraft) {
  const detail = market.value
  if (!detail || !canStartResearch.value || stockStore.submitLoading) return
  const version = loadVersion
  const requestedWorkspace = workspaceId.value
  formError.value = ''
  try {
    const result = await stockStore.add(requestedWorkspace, { code: detail.code, ...draft })
    if (disposed || version !== loadVersion) return
    if (result.alreadyActive) {
      stockStore.keepResearchDraft(workspaceId.value, result.workspaceStockId, draft)
    }
    await router.push({
      name: 'workspace-stock-research',
      params: { workspaceId: workspaceId.value, workspaceStockId: result.workspaceStockId },
    })
  } catch (error) {
    if (disposed || version !== loadVersion) return
    if (await handleWorkspaceApiError(error, router)) return
    formError.value = error instanceof Error ? error.message : '添加股票失败'
  }
}

onMounted(load)
watch(
  () => [workspaceId.value, code.value] as const,
  (current, previous) => {
    if (current[0] !== previous[0] || current[1] !== previous[1]) void load()
  },
)
onBeforeUnmount(() => {
  disposed = true
  stockStore.clearMarketDetail()
  stockStore.clearCandlesticks()
  stockStore.clearIntraday()
})
</script>

<template>
  <section v-if="pageLoading" class="content-state" aria-live="polite">
    <p>正在读取股票行情</p>
  </section>

  <section v-else-if="stockStore.marketDetailError || !market" class="content-state" role="alert">
    <p class="section-label">加载失败</p>
    <h1>暂时无法打开这只股票</h1>
    <p>{{ stockStore.marketDetailError || '股票行情不存在' }}</p>
    <div class="state-actions">
      <button class="primary" type="button" @click="load">重新加载</button>
      <RouterLink :to="backToStocks">返回股票搜索</RouterLink>
    </div>
  </section>

  <template v-else>
    <nav class="back-nav" aria-label="返回路径">
      <RouterLink :to="backToStocks"
        >← 返回{{ hasSearchQuery ? '搜索结果' : '共享自选' }}</RouterLink
      >
    </nav>

    <header class="market-hero">
      <div class="identity">
        <div class="status-row">
          <span>{{ trackingLabel(market) }}</span>
          <span>{{ listingStatusLabel(market) }}</span>
        </div>
        <h1>{{ market.name }}</h1>
        <p class="stock-code">
          {{ market.code }} · {{ market.exchange }} ·
          {{ market.currency === 'HKD' ? '港币' : '人民币' }}
        </p>
        <dl>
          <div>
            <dt>行情来源</dt>
            <dd>{{ market.simulated ? '本地模拟行情' : market.source }}</dd>
          </div>
          <div>
            <dt>报价时间</dt>
            <dd>{{ formatTime(market.quoteTime) }}</dd>
          </div>
        </dl>
      </div>

      <div class="quote-action">
        <span>最新可用价格</span>
        <strong>{{ formatPrice(market.price, market.currency) }}</strong>
        <RouterLink
          v-if="market.trackingState === 'ACTIVE' && market.workspaceStockId"
          class="primary action-link"
          :to="{
            name: 'workspace-stock-research',
            params: { workspaceId, workspaceStockId: market.workspaceStockId },
          }"
        >
          查看研究时间线
        </RouterLink>
        <button
          v-else
          ref="addButton"
          class="primary"
          type="button"
          :disabled="!canStartResearch"
          @click="openOpinionForm"
        >
          {{ market.trackingState === 'ENDED' ? '重新加入关注' : '加入共享自选' }}
        </button>
      </div>
    </header>

    <p v-if="addBlockedReason" class="read-only-note" role="status">
      {{ addBlockedReason }}
    </p>

    <StockInitialOpinionForm
      v-if="showOpinionForm"
      ref="opinionForm"
      class="initial-form"
      :stock-name="market.name"
      :currency="market.currency"
      :restarting="market.trackingState === 'ENDED'"
      :loading="stockStore.submitLoading"
      :error="formError || stockStore.submitError"
      @submit="submitOpinion"
      @cancel="closeOpinionForm"
    />

    <section class="chart-section" aria-labelledby="market-chart-title">
      <div class="chart-copy">
        <p class="section-label">按需行情</p>
        <h2 id="market-chart-title">K 线与分时</h2>
        <p>这里只读取当前打开的股票；返回搜索结果后不会在后台批量加载其他候选。</p>
      </div>
      <StockMarketPanel
        :key="`${workspaceId}:${market.code}`"
        :workspace-id="workspaceId"
        :code="market.code"
        :name="market.name"
      />
    </section>
  </template>
</template>

<style scoped>
.back-nav {
  padding: 24px 0 4px;
}
.back-nav a {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  color: var(--color-focus);
  font-size: 12px;
  text-decoration: none;
}
.back-nav a:hover {
  text-decoration: underline;
  text-underline-offset: 5px;
}
.market-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 32px;
  padding: 20px 0 30px;
  border-bottom: 1px solid var(--color-ink);
}
.status-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.status-row span {
  padding: 3px 10px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  color: var(--color-muted);
  font-size: 11px;
}
.market-hero h1,
.content-state h1 {
  margin: 12px 0 0;
  font-family: var(--font-display);
  font-size: clamp(28px, 3vw, 40px);
  font-weight: 900;
  line-height: 1.3;
  overflow-wrap: anywhere;
}
.stock-code {
  margin-top: 6px;
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 12px;
}
.market-hero dl {
  display: flex;
  flex-wrap: wrap;
  gap: 18px 30px;
  margin: 20px 0 0;
}
.market-hero dt {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.1em;
}
.market-hero dd {
  margin: 5px 0 0;
  font-size: 13px;
  font-weight: 650;
  overflow-wrap: anywhere;
}
.quote-action {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
}
.quote-action > span {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}
.quote-action > strong {
  font-family: var(--font-mono);
  font-size: clamp(28px, 3vw, 40px);
  font-weight: 500;
  line-height: 1.25;
}
.primary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: 8px 20px;
  border: 1px solid var(--color-ink);
  border-radius: 999px;
  background: var(--color-ink);
  color: var(--color-surface);
  text-decoration: none;
  cursor: pointer;
  font-size: 12px;
}
.primary:hover {
  background: var(--color-text);
}
.primary:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}
.read-only-note {
  margin: 22px 0 0;
  padding: 14px 18px;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  background: var(--color-surface-secondary);
  color: var(--color-muted);
  font-size: 13px;
}
.chart-section {
  margin: 44px 0 52px;
}
.chart-copy {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: baseline;
  gap: 8px 20px;
  margin-bottom: 18px;
}
.chart-copy h2 {
  grid-row: 1;
  margin: 0;
  font-family: var(--font-display);
  font-size: 26px;
  font-weight: 900;
}
.section-label {
  grid-column: 2;
  grid-row: 1;
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}
.chart-copy > p:last-child {
  grid-column: 1 / -1;
  max-width: 70ch;
  color: var(--color-muted);
  font-size: 12px;
}
.content-state {
  padding: 60px 0;
  color: var(--color-muted);
}
.content-state p {
  margin: 14px 0;
}
.content-state a {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  color: var(--color-focus);
}
@media (max-width: 640px) {
  .market-hero {
    grid-template-columns: minmax(0, 1fr);
    gap: 24px;
  }
  .quote-action {
    align-items: flex-start;
  }
  .chart-copy {
    grid-template-columns: minmax(0, 1fr);
  }
  .section-label {
    grid-column: 1;
    grid-row: 2;
  }
  .chart-copy > p:last-child {
    grid-row: 3;
  }
  .market-hero dl {
    gap: 18px;
  }
}
</style>
