<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type {
  CandlestickPeriod,
  OpinionHorizon,
  StockSearchResponseVO,
  StockStance,
  WorkspaceStockListResponseVO,
} from '@/api/stock'
import { useAuthStore } from '@/stores/auth'
import { useStockStore } from '@/stores/stock'
import { useWorkspaceStore } from '@/stores/workspace'
import { handleWorkspaceApiError } from '@/utils/workspace-access'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const workspaceStore = useWorkspaceStore()
const stockStore = useStockStore()
const StockCandlestickChart = defineAsyncComponent(
  () => import('@/components/StockCandlestickChart.vue'),
)
const StockIntradayChart = defineAsyncComponent(
  () => import('@/components/StockIntradayChart.vue'),
)

const workspaceId = computed(() => Number(route.params.workspaceId))
const isArchived = computed(() => workspaceStore.currentWorkspace?.status !== 'ACTIVE')
const pageLoading = ref(true)
const keyword = ref('')
const searchHasRun = ref(false)
const selectedStock = ref<StockSearchResponseVO | null>(null)
const formError = ref('')
const successMessage = ref('')
const expandedStockId = ref<number | null>(null)
const selectedPeriod = ref<CandlestickPeriod>('day')
const chartMode = ref<'kline' | 'intraday'>('kline')
const intradayDate = ref('')
let intradayRefreshTimer: ReturnType<typeof setTimeout> | null = null
const candlestickPeriods: { value: CandlestickPeriod; label: string }[] = [
  { value: '1m', label: '1 分' },
  { value: '5m', label: '5 分' },
  { value: '15m', label: '15 分' },
  { value: '30m', label: '30 分' },
  { value: '1h', label: '1 小时' },
  { value: 'day', label: '日 K' },
  { value: 'week', label: '周 K' },
  { value: 'month', label: '月 K' },
  { value: 'year', label: '年 K' },
]

const opinion = reactive({
  stance: 'NEUTRAL' as StockStance,
  reason: '',
  riskNote: '',
  horizon: 'MEDIUM' as OpinionHorizon,
  targetPrice: '',
  stopLossPrice: '',
})

async function load() {
  if (!Number.isSafeInteger(workspaceId.value) || workspaceId.value <= 0) {
    await router.replace('/workspaces')
    return
  }

  pageLoading.value = true
  try {
    await workspaceStore.loadCurrent(workspaceId.value)
    await stockStore.loadList(workspaceId.value)
  } catch (error) {
    await handleWorkspaceApiError(error, router)
  } finally {
    pageLoading.value = false
  }
}

async function search() {
  successMessage.value = ''
  selectedStock.value = null
  stockStore.clearSearch()
  if (!keyword.value.trim()) {
    formError.value = '请输入股票代码或名称'
    return
  }

  formError.value = ''
  searchHasRun.value = true
  try {
    await stockStore.search(keyword.value)
  } catch (error) {
    await handleWorkspaceApiError(error, router)
  }
}

function selectStock(stock: StockSearchResponseVO) {
  if (stock.price === null) {
    selectedStock.value = null
    formError.value = '这只股票已收录，暂无可用报价，暂时不能记录关注价格。'
    return
  }
  selectedStock.value = stock
  formError.value = ''
  successMessage.value = ''
}

function cancelSelection() {
  selectedStock.value = null
  formError.value = ''
}

function optionalPrice(value: string | number) {
  if (value === '') return null
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : Number.NaN
}

async function submitOpinion() {
  if (!selectedStock.value) return
  const targetPrice = optionalPrice(opinion.targetPrice)
  const stopLossPrice = optionalPrice(opinion.stopLossPrice)
  if (!opinion.reason.trim() || !opinion.riskNote.trim()) {
    formError.value = '请填写观点理由和风险点'
    return
  }
  if (Number.isNaN(targetPrice) || Number.isNaN(stopLossPrice)) {
    formError.value = '目标价和止损参考价必须是大于 0 的数字'
    return
  }

  formError.value = ''
  successMessage.value = ''
  try {
    const result = await stockStore.add(workspaceId.value, {
      code: selectedStock.value.code,
      stance: opinion.stance,
      reason: opinion.reason.trim(),
      riskNote: opinion.riskNote.trim(),
      horizon: opinion.horizon,
      targetPrice,
      stopLossPrice,
    })
    successMessage.value = result.alreadyActive
      ? `${selectedStock.value.name} 已经在共享自选中，没有重复创建研究周期。`
      : `${selectedStock.value.name} 已加入共享自选，初始观点已经保存。`
    resetAddFlow()
  } catch (error) {
    if (await handleWorkspaceApiError(error, router)) return
    formError.value = error instanceof Error ? error.message : '添加股票失败'
  }
}

function resetAddFlow() {
  selectedStock.value = null
  keyword.value = ''
  searchHasRun.value = false
  stockStore.clearSearch()
  opinion.stance = 'NEUTRAL'
  opinion.reason = ''
  opinion.riskNote = ''
  opinion.horizon = 'MEDIUM'
  opinion.targetPrice = ''
  opinion.stopLossPrice = ''
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
  return { BULLISH: '看多', NEUTRAL: '中性', CAUTIOUS: '谨慎' }[stance]
}

function horizonLabel(horizon: OpinionHorizon) {
  return { SHORT: '短期', MEDIUM: '中期', LONG: '长期' }[horizon]
}

function candlestickPeriodLabel(period: CandlestickPeriod) {
  return candlestickPeriods.find((item) => item.value === period)?.label ?? period
}

async function requestCandlesticks(workspaceStockId: number, period: CandlestickPeriod) {
  try {
    await stockStore.loadCandlesticks(workspaceId.value, workspaceStockId, period)
  } catch (error) {
    await handleWorkspaceApiError(error, router)
  }
}

async function toggleCandlesticks(item: WorkspaceStockListResponseVO) {
  if (expandedStockId.value === item.workspaceStockId) {
    stopIntradayRefresh()
    expandedStockId.value = null
    stockStore.clearCandlesticks()
    stockStore.clearIntraday()
    return
  }
  stopIntradayRefresh()
  expandedStockId.value = item.workspaceStockId
  chartMode.value = 'kline'
  selectedPeriod.value = 'day'
  stockStore.clearCandlesticks()
  await requestCandlesticks(item.workspaceStockId, selectedPeriod.value)
}

function stopIntradayRefresh() {
  if (intradayRefreshTimer) clearTimeout(intradayRefreshTimer)
  intradayRefreshTimer = null
}

function scheduleIntradayRefresh(workspaceStockId: number) {
  stopIntradayRefresh()
  if (!stockStore.intradaySeries?.updating) return
  intradayRefreshTimer = setTimeout(async () => {
    if (
      chartMode.value !== 'intraday'
      || expandedStockId.value !== workspaceStockId
      || document.hidden
    ) {
      scheduleIntradayRefresh(workspaceStockId)
      return
    }
    try {
      await stockStore.loadIntraday(workspaceId.value, workspaceStockId, intradayDate.value, true)
    } catch {
      // The current chart remains visible; the next controlled refresh can recover.
    }
    scheduleIntradayRefresh(workspaceStockId)
  }, 30_000)
}

async function requestIntraday(workspaceStockId: number) {
  stopIntradayRefresh()
  try {
    const series = await stockStore.loadIntraday(
      workspaceId.value,
      workspaceStockId,
      intradayDate.value || undefined,
    )
    intradayDate.value = series.tradeDate
    scheduleIntradayRefresh(workspaceStockId)
  } catch (error) {
    await handleWorkspaceApiError(error, router)
  }
}

async function selectChartMode(mode: 'kline' | 'intraday', workspaceStockId: number) {
  if (chartMode.value === mode) return
  chartMode.value = mode
  if (mode === 'intraday') {
    stockStore.clearIntraday()
    await requestIntraday(workspaceStockId)
  } else {
    stopIntradayRefresh()
  }
}

async function selectCandlestickPeriod(period: CandlestickPeriod) {
  if (expandedStockId.value === null || selectedPeriod.value === period) return
  selectedPeriod.value = period
  await requestCandlesticks(expandedStockId.value, period)
}

async function loadOlderCandlesticks(workspaceStockId: number) {
  try {
    await stockStore.loadOlderCandlesticks(workspaceId.value, workspaceStockId)
  } catch (error) {
    await handleWorkspaceApiError(error, router)
  }
}

function switchWorkspace() {
  workspaceStore.clearCurrent()
  stockStore.clearAll()
  return router.push('/workspaces')
}

function logout() {
  authStore.logout()
  workspaceStore.clearAll()
  router.push('/')
}

onMounted(load)
onBeforeUnmount(stopIntradayRefresh)
</script>

<template>
  <div class="workspace-shell">
    <aside class="workspace-nav">
      <button class="brand logout-btn" type="button" @click="logout">退出登录</button>
      <button class="switcher" type="button" @click="switchWorkspace">
        <span>{{ workspaceStore.currentWorkspace?.name ?? '研究空间' }}</span
        ><small>切换空间</small>
      </button>
      <nav aria-label="空间主导航">
        <RouterLink :to="`/workspaces/${workspaceId}/overview`">概览</RouterLink>
        <RouterLink class="active" :to="`/workspaces/${workspaceId}/stocks`">共享自选</RouterLink>
        <RouterLink :to="`/workspaces/${workspaceId}/members`">成员</RouterLink>
        <RouterLink :to="`/workspaces/${workspaceId}/settings`">设置</RouterLink>
      </nav>
    </aside>

    <main class="workspace-main">
      <header>
        <span>{{ authStore.currentUser?.nickname }}</span>
        <button type="button" @click="switchWorkspace">返回空间选择</button>
      </header>

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
          <button type="button" @click="switchWorkspace">选择其他空间</button>
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
            <p class="section-label">添加股票</p>
            <h2 id="add-stock-title">先搜索标准股票，再记录初始判断</h2>
            <p>按代码或名称搜索股票；暂无报价时暂不能添加关注。模拟行情会单独标注。</p>
          </div>

          <form class="search-form" role="search" @submit.prevent="search">
            <label for="stock-keyword">股票代码或名称</label>
            <div>
              <input
                id="stock-keyword"
                v-model="keyword"
                autocomplete="off"
                placeholder="例如 600519 或 腾讯"
              />
              <button class="primary" type="submit" :disabled="stockStore.searchLoading">
                {{ stockStore.searchLoading ? '正在搜索…' : '搜索股票' }}
              </button>
            </div>
          </form>

          <p v-if="stockStore.searchError" class="inline-error" role="alert">
            {{ stockStore.searchError }}
          </p>
          <p v-if="formError && !selectedStock" class="inline-error" role="alert">
            {{ formError }}
          </p>

          <div v-if="stockStore.searchResults.length" class="search-results">
            <button
              v-for="stock in stockStore.searchResults"
              :key="stock.code"
              type="button"
              :class="{ selected: selectedStock?.code === stock.code }"
              @click="selectStock(stock)"
            >
              <span>
                <strong>{{ stock.name }}</strong>
                <small>{{ stock.code }} · {{ stock.exchange }}</small>
              </span>
              <span class="quote">
                <strong>{{ stock.price === null ? '暂无报价' : formatPrice(stock.price, stock.currency) }}</strong>
                <small>{{ stock.simulated ? '模拟行情' : stock.listingStatus === 'UNKNOWN' ? '交易状态待核实' : '标准股票目录' }}</small>
              </span>
            </button>
          </div>
          <p
            v-else-if="searchHasRun && !stockStore.searchLoading && !stockStore.searchError"
            class="search-empty"
          >
            没有找到匹配的股票，请尝试标准代码或更短的名称。
          </p>

          <form
            v-if="selectedStock"
            class="opinion-form"
            aria-labelledby="opinion-title"
            @submit.prevent="submitOpinion"
          >
            <div class="form-heading">
              <div>
                <p class="section-label">已选择 {{ selectedStock.code }}</p>
                <h3 id="opinion-title">记录对 {{ selectedStock.name }} 的初始观点</h3>
              </div>
              <button type="button" @click="cancelSelection">取消选择</button>
            </div>

            <div class="field-grid compact-fields">
              <label>
                <span>观点倾向</span>
                <select v-model="opinion.stance">
                  <option value="BULLISH">看多</option>
                  <option value="NEUTRAL">中性</option>
                  <option value="CAUTIOUS">谨慎</option>
                </select>
              </label>
              <label>
                <span>关注周期</span>
                <select v-model="opinion.horizon">
                  <option value="SHORT">短期</option>
                  <option value="MEDIUM">中期</option>
                  <option value="LONG">长期</option>
                </select>
              </label>
            </div>

            <div class="field-grid text-fields">
              <label>
                <span>观点理由</span>
                <textarea
                  v-model="opinion.reason"
                  maxlength="1000"
                  rows="5"
                  placeholder="你为什么开始关注这只股票？"
                ></textarea>
              </label>
              <label>
                <span>风险点</span>
                <textarea
                  v-model="opinion.riskNote"
                  maxlength="1000"
                  rows="5"
                  placeholder="什么情况可能推翻当前判断？"
                ></textarea>
              </label>
            </div>

            <div class="field-grid price-fields">
              <label>
                <span>目标价（选填）</span>
                <div class="price-input">
                  <span>{{ currencySymbol(selectedStock.currency) }}</span>
                  <input
                    v-model="opinion.targetPrice"
                    type="number"
                    min="0.0001"
                    step="0.0001"
                    placeholder="不填写也可以"
                  />
                </div>
              </label>
              <label>
                <span>止损参考价（选填）</span>
                <div class="price-input">
                  <span>{{ currencySymbol(selectedStock.currency) }}</span>
                  <input
                    v-model="opinion.stopLossPrice"
                    type="number"
                    min="0.0001"
                    step="0.0001"
                    placeholder="不填写也可以"
                  />
                </div>
              </label>
            </div>

            <p v-if="formError || stockStore.submitError" class="inline-error" role="alert">
              {{ formError || stockStore.submitError }}
            </p>
            <div class="submit-row">
              <span>关注价格将由后端重新读取，不采用页面显示值。</span>
              <button class="primary" type="submit" :disabled="stockStore.submitLoading">
                {{ stockStore.submitLoading ? '正在保存…' : '添加并保存观点' }}
              </button>
            </div>
          </form>
        </section>

        <p v-if="successMessage" class="success-message" aria-live="polite">
          {{ successMessage }}
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
            <article v-for="item in stockStore.items" :key="item.workspaceStockId">
              <div class="stock-summary">
                <div class="identity">
                  <span class="attention">{{
                    item.attentionLevel === 'FOCUS' ? '重点关注' : '普通关注'
                  }}</span>
                  <h3>{{ item.name }}</h3>
                  <p>{{ item.code }} · {{ item.exchange }}</p>
                </div>
                <div class="metric">
                  <span>关注价格</span>
                  <strong>{{ formatPrice(item.startPrice, item.currency) }}</strong>
                  <small>{{ item.simulated ? '本地模拟行情' : item.quoteSource }}</small>
                </div>
                <div class="metric">
                  <span>初始倾向</span>
                  <strong>{{ stanceLabel(item.stance) }}</strong>
                  <small>{{ horizonLabel(item.horizon) }} · {{ item.authorNickname }}</small>
                </div>
              </div>
              <div class="stock-actions">
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
              <section
                v-if="expandedStockId === item.workspaceStockId"
                :id="`stock-chart-${item.workspaceStockId}`"
                class="kline-panel"
                :aria-label="`${item.name} K 线与分时图`"
              >
                <div class="kline-heading">
                  <div>
                    <strong>
                      {{ item.name }} ·
                      {{ chartMode === 'kline' ? candlestickPeriodLabel(selectedPeriod) : '分时' }}
                    </strong>
                    <small v-if="chartMode === 'kline'">
                      不复权 · 首次最多 1000 根，向左拖动继续读取更早历史
                    </small>
                    <small v-else>今天盘中每 30 秒刷新；历史日期只读取一次</small>
                  </div>
                  <div class="period-switch" aria-label="选择图表类型">
                    <button
                      type="button"
                      :class="{ active: chartMode === 'kline' }"
                      :aria-pressed="chartMode === 'kline'"
                      @click="selectChartMode('kline', item.workspaceStockId)"
                    >
                      K 线
                    </button>
                    <button
                      type="button"
                      :class="{ active: chartMode === 'intraday' }"
                      :aria-pressed="chartMode === 'intraday'"
                      @click="selectChartMode('intraday', item.workspaceStockId)"
                    >
                      分时
                    </button>
                  </div>
                  <div v-if="chartMode === 'kline'" class="period-switch" aria-label="选择 K 线周期">
                    <button
                      v-for="period in candlestickPeriods"
                      :key="period.value"
                      type="button"
                      :class="{ active: selectedPeriod === period.value }"
                      :aria-pressed="selectedPeriod === period.value"
                      :disabled="stockStore.candlestickLoading"
                      @click="selectCandlestickPeriod(period.value)"
                    >
                      {{ period.label }}
                    </button>
                  </div>
                  <label v-else class="intraday-date">
                    交易日
                    <input
                      v-model="intradayDate"
                      type="date"
                      :disabled="stockStore.intradayLoading"
                      @change="requestIntraday(item.workspaceStockId)"
                    />
                  </label>
                </div>
                <template v-if="chartMode === 'kline'">
                <div
                  v-if="stockStore.candlestickLoading"
                  class="kline-loading"
                  aria-live="polite"
                >
                  <span></span><span></span><span></span>
                  <p>正在读取 {{ candlestickPeriodLabel(selectedPeriod) }}</p>
                </div>
                <div v-else-if="stockStore.candlestickError" class="kline-error" role="alert">
                  <p>{{ stockStore.candlestickError }}</p>
                  <button
                    type="button"
                    @click="requestCandlesticks(item.workspaceStockId, selectedPeriod)"
                  >
                    重新加载 K 线
                  </button>
                </div>
                <template v-else-if="stockStore.candlestickSeries">
                  <StockCandlestickChart
                    :candlesticks="stockStore.candlestickSeries.candlesticks"
                    :name="stockStore.candlestickSeries.name"
                    :period="selectedPeriod"
                    :period-label="candlestickPeriodLabel(selectedPeriod)"
                    @reach-start="loadOlderCandlesticks(item.workspaceStockId)"
                  />
                  <p v-if="stockStore.candlestickLoadingOlder" class="kline-meta" aria-live="polite">
                    正在补充更早的 1000 根 K 线…
                  </p>
                  <p v-else-if="stockStore.candlestickHistoryError" class="kline-error" role="alert">
                    {{ stockStore.candlestickHistoryError }}，再次拖到最左侧可重试。
                  </p>
                  <p v-else-if="!stockStore.candlestickSeries.hasMore" class="kline-meta">
                    已到达当前数据源可提供的最早记录。
                  </p>
                  <p class="kline-meta">
                    已加载 {{ stockStore.candlestickSeries.candlesticks.length }} 根 ·
                    数据源：{{
                      stockStore.candlestickSeries.simulated
                        ? '本地模拟行情'
                        : stockStore.candlestickSeries.source
                    }}
                    · 获取于 {{ formatTime(stockStore.candlestickSeries.retrievedAt) }}
                  </p>
                </template>
                </template>
                <template v-else>
                  <div v-if="stockStore.intradayLoading" class="kline-loading" aria-live="polite">
                    <span></span><span></span><span></span><p>正在读取分时数据</p>
                  </div>
                  <div v-else-if="stockStore.intradayError" class="kline-error" role="alert">
                    <p>{{ stockStore.intradayError }}</p>
                    <button type="button" @click="requestIntraday(item.workspaceStockId)">
                      重新加载分时图
                    </button>
                  </div>
                  <template v-else-if="stockStore.intradaySeries">
                    <StockIntradayChart
                      :points="stockStore.intradaySeries.points"
                      :trading-sessions="stockStore.intradaySeries.tradingSessions"
                      :reference-price="stockStore.intradaySeries.referencePrice"
                      :name="stockStore.intradaySeries.name"
                    />
                    <p class="kline-meta">
                      {{ stockStore.intradaySeries.tradeDate }} ·
                      {{ stockStore.intradaySeries.updating ? '盘中受控刷新' : '已收盘历史快照' }} ·
                      已加载 {{ stockStore.intradaySeries.points.length }} 个点 · 数据源：{{
                        stockStore.intradaySeries.simulated
                          ? '本地模拟行情'
                          : stockStore.intradaySeries.source
                      }}
                    </p>
                  </template>
                </template>
              </section>
              <details>
                <summary>查看初始观点与行情记录</summary>
                <div class="opinion-detail">
                  <div>
                    <span>观点理由</span>
                    <p>{{ item.reason }}</p>
                  </div>
                  <div>
                    <span>风险点</span>
                    <p>{{ item.riskNote }}</p>
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
      </template>
    </main>
  </div>
</template>

<style scoped>
.workspace-shell {
  display: grid;
  min-height: 100svh;
  grid-template-columns: 224px minmax(0, 1fr);
  color: var(--color-ink);
}
.workspace-nav {
  display: flex;
  min-height: 100svh;
  flex-direction: column;
  padding: 32px 22px;
  border-right: 1px solid var(--color-border);
  background: var(--color-surface);
}
.brand {
  font-weight: 700;
}
.logout-btn {
  border: 0;
  background: transparent;
  color: var(--color-danger);
  cursor: pointer;
  text-align: left;
}
.logout-btn:hover {
  text-decoration: underline;
}
.switcher {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 32px;
  padding: 14px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
}
.switcher span {
  overflow: hidden;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.switcher small {
  flex: 0 0 auto;
  color: var(--color-muted);
}
.workspace-nav nav {
  display: grid;
  gap: 5px;
  margin-top: 26px;
}
.workspace-nav nav a {
  padding: 11px 10px;
  border-radius: 8px;
  font-size: 13px;
}
.workspace-nav nav a:hover {
  background: color-mix(in oklab, var(--color-focus) 5%, transparent);
}
.workspace-nav nav .active {
  background: color-mix(in oklab, var(--color-focus) 9%, transparent);
  font-weight: 700;
}
.workspace-main {
  min-width: 0;
  padding: 0 clamp(24px, 6vw, 86px) 72px;
}
.workspace-main > header {
  display: flex;
  height: var(--header-height);
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--color-border);
  color: var(--color-muted);
  font-size: 13px;
}
.workspace-main > header button,
.form-heading button,
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
.search-form label,
.opinion-form label > span {
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
input,
select,
textarea {
  width: 100%;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  color: var(--color-ink);
}
input,
select {
  min-height: 48px;
  padding: 0 14px;
}
textarea {
  min-height: 132px;
  padding: 13px 14px;
  line-height: 1.7;
  resize: vertical;
}
input::placeholder,
textarea::placeholder {
  color: oklch(0.46 0.008 255);
}
input:focus,
select:focus,
textarea:focus {
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
.search-results button {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 18px 12px;
  border: 0;
  border-bottom: 1px solid var(--color-border);
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
}
.search-results button:hover,
.search-results button.selected {
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
.opinion-form {
  margin-top: 34px;
  padding-top: 30px;
  border-top: 1px solid var(--color-ink);
}
.form-heading,
.list-heading,
.submit-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}
.form-heading h3 {
  margin: 7px 0 0;
  font-size: 20px;
}
.form-heading button {
  color: var(--color-muted);
  text-decoration: underline;
  text-underline-offset: 4px;
}
.field-grid {
  display: grid;
  gap: 22px;
  margin-top: 24px;
}
.compact-fields,
.price-fields {
  grid-template-columns: repeat(2, minmax(0, 260px));
}
.text-fields {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.price-input {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
}
.price-input:focus-within {
  border-color: var(--color-ink);
  outline: 3px solid color-mix(in oklab, var(--color-focus) 25%, transparent);
}
.price-input > span {
  padding-left: 14px;
  color: var(--color-muted);
}
.price-input input {
  border: 0;
  outline: 0;
}
.submit-row {
  margin-top: 26px;
}
.submit-row > span {
  color: var(--color-muted);
  font-size: 12px;
}
.inline-error,
.success-message,
.search-empty {
  margin-top: 16px;
  font-size: 13px;
}
.inline-error {
  color: var(--color-danger);
}
.success-message {
  padding: 14px 16px;
  background: color-mix(in oklab, var(--color-focus) 9%, var(--color-surface));
  color: var(--color-focus);
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
.stock-actions small,
.kline-heading small,
.kline-meta {
  color: var(--color-muted);
  font-size: 12px;
}
.kline-toggle,
.period-switch button,
.kline-error button {
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
.kline-toggle:focus-visible,
.period-switch button:focus-visible,
.kline-error button:focus-visible {
  border-radius: 4px;
  outline: 3px solid color-mix(in oklab, var(--color-focus) 25%, transparent);
  outline-offset: 3px;
}
.kline-panel {
  margin: 0 4px 24px;
  padding: 22px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: var(--color-surface);
}
.kline-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 12px;
}
.kline-heading strong,
.kline-heading small {
  display: block;
}
.kline-heading small {
  margin-top: 4px;
}
.period-switch {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.period-switch button {
  min-height: 36px;
  padding: 0 12px;
  border-radius: var(--radius-action);
  color: var(--color-muted);
}
.period-switch button:hover,
.period-switch button.active {
  background: color-mix(in oklab, var(--color-focus) 10%, transparent);
  color: var(--color-focus);
}
.period-switch button:disabled {
  cursor: wait;
  opacity: 0.6;
}
.intraday-date {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--color-muted);
  font-size: 12px;
}
.intraday-date input {
  min-height: 36px;
  padding: 0 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-action);
  background: var(--color-surface);
  color: var(--color-text);
}
.kline-loading {
  padding: 72px 6%;
}
.kline-loading span {
  display: block;
  height: 16px;
  margin-bottom: 12px;
  background: var(--color-border);
  animation: pulse 1.4s ease-in-out infinite alternate;
}
.kline-loading span:nth-child(2) {
  width: 78%;
}
.kline-loading span:nth-child(3) {
  width: 58%;
}
.kline-loading p,
.kline-error p {
  margin-top: 16px;
  color: var(--color-muted);
  font-size: 13px;
}
.kline-error {
  padding: 48px 0;
  text-align: center;
}
.kline-error button {
  margin-top: 14px;
}
.kline-meta {
  margin-top: 8px;
  text-align: right;
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
  .list-skeleton span,
  .kline-loading span {
    animation: none;
  }
}
@media (max-width: 820px) {
  .workspace-shell {
    display: block;
  }
  .workspace-nav {
    min-height: auto;
    flex-wrap: wrap;
    flex-direction: row;
    align-items: center;
    padding: 14px 18px;
    border-right: 0;
    border-bottom: 1px solid var(--color-border);
  }
  .switcher {
    flex: 1 1 0;
    margin-top: 0;
    margin-left: auto;
    border: 0;
  }
  .switcher span {
    min-width: 0;
  }
  .workspace-nav nav {
    display: grid;
    width: 100%;
    grid-template-columns: repeat(4, 1fr);
    margin-top: 10px;
    text-align: center;
  }
  .workspace-main > header {
    display: none;
  }
  .page-heading {
    padding-top: 42px;
  }
  .stock-summary {
    grid-template-columns: minmax(0, 1fr) repeat(2, minmax(130px, 0.5fr));
  }
  .opinion-detail dl {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .kline-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
@media (max-width: 640px) {
  .workspace-main {
    padding: 0 20px 56px;
  }
  .archive-notice,
  .page-heading,
  .add-copy,
  .form-heading,
  .submit-row {
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
  .compact-fields,
  .text-fields,
  .price-fields,
  .stock-summary,
  .opinion-detail,
  .opinion-detail dl {
    grid-template-columns: 1fr;
  }
  .search-form .primary {
    width: 100%;
  }
  .form-heading button {
    margin-top: 12px;
  }
  .submit-row .primary {
    width: 100%;
    margin-top: 18px;
  }
  .stock-summary {
    gap: 18px;
  }
  .stock-actions {
    align-items: flex-start;
    flex-direction: column;
  }
  .kline-panel {
    margin-inline: 0;
    padding: 16px 10px;
  }
  .opinion-detail dl {
    grid-column: auto;
  }
}
</style>
