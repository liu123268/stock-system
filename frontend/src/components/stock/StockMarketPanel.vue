<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { CandlestickPeriod } from '@/api/stock'
import { useStockStore } from '@/stores/stock'
import { handleWorkspaceApiError } from '@/utils/workspace-access'

const props = defineProps<{
  workspaceId: number
  code: string
  name: string
  workspaceStockId?: number
}>()

const router = useRouter()
const stockStore = useStockStore()
const StockCandlestickChart = defineAsyncComponent(
  () => import('@/components/StockCandlestickChart.vue'),
)
const StockIntradayChart = defineAsyncComponent(() => import('@/components/StockIntradayChart.vue'))

const selectedPeriod = ref<CandlestickPeriod>('day')
const chartMode = ref<'kline' | 'intraday'>('kline')
const intradayDate = ref('')
let intradayRefreshTimer: ReturnType<typeof setTimeout> | null = null
let disposed = false
let intradayVersion = 0
let candlestickVersion = 0

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

const usesWorkspaceStock = computed(() => props.workspaceStockId !== undefined)

function candlestickPeriodLabel(period: CandlestickPeriod) {
  return candlestickPeriods.find((item) => item.value === period)?.label ?? period
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

async function requestCandlesticks(period: CandlestickPeriod) {
  const version = ++candlestickVersion
  try {
    if (usesWorkspaceStock.value) {
      await stockStore.loadCandlesticks(props.workspaceId, props.workspaceStockId!, period)
    } else {
      await stockStore.loadMarketCandlesticks(props.workspaceId, props.code, period)
    }
  } catch (error) {
    if (!disposed && version === candlestickVersion) await handleWorkspaceApiError(error, router)
  }
}

function stopIntradayRefresh() {
  if (intradayRefreshTimer) clearTimeout(intradayRefreshTimer)
  intradayRefreshTimer = null
}

async function loadIntraday(silent = false) {
  if (usesWorkspaceStock.value) {
    return stockStore.loadIntraday(
      props.workspaceId,
      props.workspaceStockId!,
      intradayDate.value || undefined,
      silent,
    )
  }
  return stockStore.loadMarketIntraday(
    props.workspaceId,
    props.code,
    intradayDate.value || undefined,
    silent,
  )
}

function scheduleIntradayRefresh(version: number) {
  if (disposed || version !== intradayVersion || chartMode.value !== 'intraday') return
  stopIntradayRefresh()
  if (!stockStore.intradaySeries?.updating) return
  intradayRefreshTimer = setTimeout(async () => {
    if (disposed || version !== intradayVersion || chartMode.value !== 'intraday') return
    if (document.hidden) {
      scheduleIntradayRefresh(version)
      return
    }
    try {
      await loadIntraday(true)
    } catch {
      // Keep the last explicitly timestamped series visible; the next controlled refresh can recover.
    }
    scheduleIntradayRefresh(version)
  }, 30_000)
}

async function requestIntraday() {
  const version = ++intradayVersion
  stopIntradayRefresh()
  try {
    const series = await loadIntraday()
    if (disposed || version !== intradayVersion || chartMode.value !== 'intraday') return
    intradayDate.value = series.tradeDate
    scheduleIntradayRefresh(version)
  } catch (error) {
    if (!disposed && version === intradayVersion) await handleWorkspaceApiError(error, router)
  }
}

async function selectChartMode(mode: 'kline' | 'intraday') {
  if (chartMode.value === mode) return
  chartMode.value = mode
  if (mode === 'intraday') {
    stockStore.clearIntraday()
    await requestIntraday()
  } else {
    intradayVersion += 1
    stopIntradayRefresh()
    stockStore.clearIntraday()
  }
}

async function selectCandlestickPeriod(period: CandlestickPeriod) {
  if (selectedPeriod.value === period) return
  selectedPeriod.value = period
  await requestCandlesticks(period)
}

async function loadOlderCandlesticks() {
  const version = candlestickVersion
  try {
    if (usesWorkspaceStock.value) {
      await stockStore.loadOlderCandlesticks(props.workspaceId, props.workspaceStockId!)
    } else {
      await stockStore.loadOlderMarketCandlesticks(props.workspaceId, props.code)
    }
  } catch (error) {
    if (!disposed && version === candlestickVersion) await handleWorkspaceApiError(error, router)
  }
}

watch(
  () => [props.workspaceId, props.workspaceStockId, props.code],
  () => {
    intradayVersion += 1
    stopIntradayRefresh()
    chartMode.value = 'kline'
    intradayDate.value = ''
    stockStore.clearCandlesticks()
    stockStore.clearIntraday()
    void requestCandlesticks(selectedPeriod.value)
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  disposed = true
  intradayVersion += 1
  candlestickVersion += 1
  stopIntradayRefresh()
  stockStore.clearCandlesticks()
  stockStore.clearIntraday()
})
</script>

<template>
  <section class="market-panel" :aria-label="`${name} K 线与分时图`">
    <div class="market-heading">
      <div class="market-title">
        <strong>
          {{ name }} · {{ chartMode === 'kline' ? candlestickPeriodLabel(selectedPeriod) : '分时' }}
        </strong>
        <small v-if="chartMode === 'kline'">
          不复权 · 首次最多 1000 根，向左拖动继续读取更早历史
        </small>
        <small v-else>今天盘中每 30 秒刷新；历史日期只读取一次</small>
      </div>

      <div class="market-controls">
        <div class="period-switch" aria-label="选择图表类型">
          <button
            type="button"
            :class="{ active: chartMode === 'kline' }"
            :aria-pressed="chartMode === 'kline'"
            @click="selectChartMode('kline')"
          >
            K 线
          </button>
          <button
            type="button"
            :class="{ active: chartMode === 'intraday' }"
            :aria-pressed="chartMode === 'intraday'"
            @click="selectChartMode('intraday')"
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
            @change="requestIntraday"
          />
        </label>
      </div>
    </div>

    <template v-if="chartMode === 'kline'">
      <div v-if="stockStore.candlestickLoading" class="market-loading" aria-live="polite">
        <span></span><span></span><span></span>
        <p>正在读取 {{ candlestickPeriodLabel(selectedPeriod) }}</p>
      </div>
      <div v-else-if="stockStore.candlestickError" class="market-error" role="alert">
        <p>{{ stockStore.candlestickError }}</p>
        <button type="button" @click="requestCandlesticks(selectedPeriod)">重新加载 K 线</button>
      </div>
      <template v-else-if="stockStore.candlestickSeries">
        <StockCandlestickChart
          :candlesticks="stockStore.candlestickSeries.candlesticks"
          :name="stockStore.candlestickSeries.name"
          :period="selectedPeriod"
          :period-label="candlestickPeriodLabel(selectedPeriod)"
          @reach-start="loadOlderCandlesticks"
        />
        <p v-if="stockStore.candlestickLoadingOlder" class="market-meta" aria-live="polite">
          正在补充更早的 1000 根 K 线…
        </p>
        <p v-else-if="stockStore.candlestickHistoryError" class="market-error" role="alert">
          {{ stockStore.candlestickHistoryError }}，再次拖到最左侧可重试。
        </p>
        <p v-else-if="!stockStore.candlestickSeries.hasMore" class="market-meta">
          已到达当前数据源可提供的最早记录。
        </p>
        <p class="market-meta">
          已加载 {{ stockStore.candlestickSeries.candlesticks.length }} 根 · 数据源：{{
            stockStore.candlestickSeries.simulated
              ? '本地模拟行情'
              : stockStore.candlestickSeries.source
          }}
          · 获取于 {{ formatTime(stockStore.candlestickSeries.retrievedAt) }}
        </p>
      </template>
    </template>

    <template v-else>
      <div v-if="stockStore.intradayLoading" class="market-loading" aria-live="polite">
        <span></span><span></span><span></span>
        <p>正在读取分时数据</p>
      </div>
      <div v-else-if="stockStore.intradayError" class="market-error" role="alert">
        <p>{{ stockStore.intradayError }}</p>
        <button type="button" @click="requestIntraday">重新加载分时图</button>
      </div>
      <template v-else-if="stockStore.intradaySeries">
        <StockIntradayChart
          :points="stockStore.intradaySeries.points"
          :trading-sessions="stockStore.intradaySeries.tradingSessions"
          :reference-price="stockStore.intradaySeries.referencePrice"
          :name="stockStore.intradaySeries.name"
        />
        <p class="market-meta">
          {{ stockStore.intradaySeries.tradeDate }} ·
          {{ stockStore.intradaySeries.updating ? '盘中受控刷新' : '已收盘历史快照' }} · 已加载
          {{ stockStore.intradaySeries.points.length }} 个点 · 数据源：{{
            stockStore.intradaySeries.simulated ? '本地模拟行情' : stockStore.intradaySeries.source
          }}
        </p>
      </template>
    </template>
  </section>
</template>

<style scoped>
.market-panel {
  padding: 22px;
  border: 1px solid var(--color-border);
  border-radius: 14px;
  background: var(--color-surface);
}
.market-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
}
.market-title strong,
.market-title small {
  display: block;
}
.market-title small {
  margin-top: 7px;
  color: var(--color-muted);
}
.market-controls {
  display: flex;
  max-width: 620px;
  align-items: flex-end;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 10px;
}
.period-switch {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.period-switch button,
.market-error button {
  min-height: 36px;
  padding: 0 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: transparent;
  color: var(--color-muted);
  cursor: pointer;
}
.period-switch button.active {
  border-color: var(--color-focus);
  background: color-mix(in oklab, var(--color-focus) 10%, transparent);
  color: var(--color-focus);
}
.period-switch button:focus-visible,
.market-error button:focus-visible,
.intraday-date input:focus-visible {
  outline: 3px solid color-mix(in oklab, var(--color-focus) 25%, transparent);
  outline-offset: 2px;
}
.intraday-date {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--color-muted);
  font-size: 13px;
  font-weight: 650;
}
.intraday-date input {
  min-height: 36px;
  padding: 0 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  color: var(--color-ink);
}
.market-loading {
  display: grid;
  min-height: 360px;
  place-content: center;
  text-align: center;
}
.market-loading span {
  display: inline-block;
  width: 6px;
  height: 26px;
  margin: 0 3px;
  border-radius: 999px;
  background: var(--color-border);
  animation: market-pulse 900ms ease-in-out infinite alternate;
}
.market-loading span:nth-child(2) {
  animation-delay: 120ms;
}
.market-loading span:nth-child(3) {
  animation-delay: 240ms;
}
.market-loading p,
.market-meta {
  color: var(--color-muted);
  font-size: 13px;
}
.market-error {
  padding: 44px 12px;
  text-align: center;
}
.market-error p {
  margin-bottom: 14px;
  color: var(--color-danger);
}
.market-meta {
  margin: 10px 0 0;
  text-align: right;
}
@keyframes market-pulse {
  to {
    transform: scaleY(0.48);
    opacity: 0.45;
  }
}
@media (prefers-reduced-motion: reduce) {
  .market-loading span {
    animation: none;
  }
}
@media (max-width: 820px) {
  .market-heading {
    flex-direction: column;
  }
  .market-controls {
    max-width: none;
    justify-content: flex-start;
  }
}
@media (max-width: 640px) {
  .market-panel {
    padding: 16px 10px;
  }
  .market-meta {
    text-align: left;
  }
}
</style>
