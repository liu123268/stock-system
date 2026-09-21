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
const code = computed(() => String(route.params.code ?? '').trim().toUpperCase())
const market = computed(() => stockStore.marketDetail)
const isArchived = computed(() => workspaceStore.currentWorkspace?.status !== 'ACTIVE')
const pageLoading = ref(true)
const showOpinionForm = ref(false)
const formError = ref('')
const addButton = ref<HTMLButtonElement | null>(null)
const opinionForm = ref<{ focusHeading: () => void } | null>(null)

const searchQuery = computed(() => {
  const value = typeof route.query.q === 'string' ? route.query.q.trim() : ''
  return value ? { q: value } : {}
})
const hasSearchQuery = computed(() => typeof route.query.q === 'string' && Boolean(route.query.q.trim()))

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
  if (detail.listingStatus === 'DELISTED') return '这只股票已经退市，历史行情仍可查看，但不能开启新的研究周期。'
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
    detail &&
      detail.trackingState !== 'ACTIVE' &&
      !isArchived.value &&
      !addBlockedReason.value,
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
    await workspaceStore.ensureCurrent(workspaceId.value)
    await stockStore.loadMarketDetail(workspaceId.value, code.value)
  } catch (error) {
    await handleWorkspaceApiError(error, router)
  } finally {
    pageLoading.value = false
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
  if (!detail || !canStartResearch.value) return
  formError.value = ''
  try {
    const result = await stockStore.add(workspaceId.value, { code: detail.code, ...draft })
    if (result.alreadyActive) {
      stockStore.keepResearchDraft(workspaceId.value, result.workspaceStockId, draft)
    }
    await router.push({
      name: 'workspace-stock-research',
      params: { workspaceId: workspaceId.value, workspaceStockId: result.workspaceStockId },
    })
  } catch (error) {
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
  stockStore.clearMarketDetail()
  stockStore.clearCandlesticks()
  stockStore.clearIntraday()
})
</script>

<template>
  <section v-if="pageLoading" class="content-state" aria-live="polite">
    <span class="loading-line"></span>
    <span class="loading-line short"></span>
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
      <RouterLink :to="backToStocks">← 返回{{ hasSearchQuery ? '搜索结果' : '共享自选' }}</RouterLink>
    </nav>

    <header class="market-hero">
      <div class="identity">
        <div class="status-row">
          <span>{{ trackingLabel(market) }}</span>
          <span>{{ listingStatusLabel(market) }}</span>
        </div>
        <h1>{{ market.name }}</h1>
        <p>{{ market.code }} · {{ market.exchange }} · {{ market.currency }}</p>
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
  padding-top: 28px;
}
.back-nav a {
  color: var(--color-focus);
  font-size: 13px;
  font-weight: 650;
  text-decoration: underline;
  text-underline-offset: 4px;
}
.market-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(240px, auto);
  gap: 48px;
  align-items: end;
  padding: 38px 0 34px;
  border-bottom: 1px solid var(--color-ink);
}
.status-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.status-row span {
  padding: 5px 9px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-action);
  color: var(--color-muted);
  font-size: 12px;
  font-weight: 650;
}
.identity h1 {
  margin: 16px 0 0;
  color: var(--color-heading);
  font-size: 42px;
  letter-spacing: -0.03em;
  line-height: 1.12;
  text-wrap: balance;
}
.identity > p {
  margin-top: 9px;
  color: var(--color-muted);
}
.identity dl {
  display: flex;
  flex-wrap: wrap;
  gap: 28px;
  margin: 24px 0 0;
}
.identity dt,
.quote-action > span {
  color: var(--color-muted);
  font-size: 12px;
  font-weight: 650;
}
.identity dd {
  margin: 5px 0 0;
  font-size: 13px;
}
.quote-action {
  display: flex;
  align-items: flex-end;
  flex-direction: column;
  text-align: right;
}
.quote-action strong {
  margin-top: 6px;
  color: var(--color-heading);
  font-size: 34px;
  letter-spacing: -0.025em;
}
.primary {
  min-height: 48px;
  margin-top: 22px;
  padding: 0 20px;
  border: 0;
  border-radius: var(--radius-action);
  background: var(--color-ink);
  color: white;
  cursor: pointer;
  font-weight: 700;
}
.primary:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
.action-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.read-only-note {
  margin: 18px 0 0;
  color: var(--color-muted);
  font-size: 13px;
}
.initial-form {
  margin-top: 34px;
}
.chart-section {
  padding: 52px 0 64px;
}
.chart-copy {
  margin-bottom: 20px;
}
.section-label {
  color: var(--color-focus);
  font-size: 12px;
  font-weight: 700;
}
.chart-copy h2,
.content-state h1 {
  margin: 7px 0 0;
  color: var(--color-heading);
  font-size: 28px;
  letter-spacing: -0.025em;
}
.chart-copy > p:last-child,
.content-state > p:not(.section-label) {
  margin-top: 9px;
  color: var(--color-muted);
}
.content-state {
  max-width: 720px;
  padding-top: 120px;
}
.loading-line {
  display: block;
  width: min(560px, 75%);
  height: 18px;
  margin-bottom: 14px;
  background: var(--color-border);
  animation: pulse 1.4s ease-in-out infinite alternate;
}
.loading-line.short {
  width: min(360px, 52%);
}
.state-actions {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 24px;
}
.state-actions .primary {
  margin-top: 0;
}
.state-actions a {
  color: var(--color-focus);
  font-weight: 650;
}
@keyframes pulse {
  to {
    opacity: 0.4;
  }
}
@media (prefers-reduced-motion: reduce) {
  .loading-line {
    animation: none;
  }
}
@media (max-width: 720px) {
  .market-hero {
    grid-template-columns: 1fr;
    gap: 30px;
  }
  .quote-action {
    align-items: flex-start;
    text-align: left;
  }
  .identity h1 {
    font-size: 36px;
  }
}
</style>
