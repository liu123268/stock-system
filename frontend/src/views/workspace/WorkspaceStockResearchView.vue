<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type {
  OpinionHorizon,
  OpinionType,
  ResearchCycleDetailResponseVO,
  StockOpinionResponseVO,
  StockStance,
  WorkspaceStockMarketDetailResponseVO,
} from '@/api/stock'
import { getWorkspaceStockMarketDetail, getWorkspaceStockResearchTimeline } from '@/api/stock'
import StockMarketPanel from '@/components/stock/StockMarketPanel.vue'
import { useStockStore } from '@/stores/stock'
import { useWorkspaceStore } from '@/stores/workspace'
import { handleWorkspaceApiError } from '@/utils/workspace-access'

const route = useRoute()
const router = useRouter()
const stockStore = useStockStore()
const workspaceStore = useWorkspaceStore()

const workspaceId = computed(() => Number(route.params.workspaceId))
const workspaceStockId = computed(() => Number(route.params.workspaceStockId))
const isArchived = computed(() => workspaceStore.currentWorkspace?.status !== 'ACTIVE')
const currentCycle = computed(() => stockStore.researchDetail?.currentCycle)
const market = ref<WorkspaceStockMarketDetailResponseVO | null>(null)
const marketLoading = ref(false)
const marketError = ref('')
const chartExpanded = ref(route.hash !== '#research-timeline')
let marketRequestVersion = 0
const priceChange = computed(() => {
  const cycle = currentCycle.value
  const quote = market.value
  const detail = stockStore.researchDetail
  const latestTime = quote?.quoteTime ? new Date(quote.quoteTime).getTime() : Number.NaN
  const startTime = cycle ? new Date(cycle.quoteTime).getTime() : Number.NaN
  if (
    !cycle ||
    !quote ||
    !detail ||
    quote.price === null ||
    !quote.quoteTime ||
    quote.code !== detail.code ||
    quote.currency !== detail.currency ||
    quote.simulated !== cycle.simulated ||
    quote.price <= 0 ||
    cycle.startPrice <= 0 ||
    !Number.isFinite(latestTime) ||
    !Number.isFinite(startTime) ||
    latestTime < startTime
  )
    return null
  const difference = quote.price - cycle.startPrice
  return { difference, percent: (difference / cycle.startPrice) * 100 }
})
const endConfirming = ref(false)
const composeExpanded = ref(false)
const formError = ref('')
const successMessage = ref('')
const pageRefreshWarning = ref('')
const pageLoading = ref(true)
const actionBusy = ref(false)
const operationBusy = computed(
  () => actionBusy.value || stockStore.researchSubmitting || stockStore.submitLoading,
)
const composeCycleId = ref<number | null>(null)
const endCycleId = ref<number | null>(null)
const operationError = ref('')
let pageVersion = 0
let disposed = false

function captureTarget() {
  return { version: pageVersion, workspace: workspaceId.value, stock: workspaceStockId.value }
}

function isCurrent(request: ReturnType<typeof captureTarget>) {
  return (
    !disposed &&
    request.version === pageVersion &&
    request.workspace === workspaceId.value &&
    request.stock === workspaceStockId.value
  )
}
const authorFilter = ref('ALL')
const stanceFilter = ref('ALL')
const typeFilter = ref('ALL')
const timelineCycles = ref<ResearchCycleDetailResponseVO[]>([])
const timelineNextCursor = ref<string | null>(null)
const timelineLoading = ref(false)
const timelineError = ref('')
let timelineRequestVersion = 0
let loadedTimelineTarget = ''
const currentTime = ref(Date.now())
let withdrawClock: number | undefined

const opinion = reactive({
  type: 'UPDATE' as Exclude<OpinionType, 'INITIAL'>,
  stance: 'NEUTRAL' as StockStance,
  reason: '',
  observationNote: '',
  horizon: 'MEDIUM' as OpinionHorizon,
  targetPrice: '',
  stopLossPrice: '',
})

function restorePendingDraft() {
  const draft = stockStore.takeResearchDraft(workspaceId.value, workspaceStockId.value)
  if (!draft) return
  composeExpanded.value = true
  composeCycleId.value = currentCycle.value?.id ?? null
  opinion.stance = draft.stance
  opinion.reason = draft.reason
  opinion.observationNote = draft.observationNote ?? ''
  opinion.horizon = draft.horizon
  opinion.targetPrice = draft.targetPrice?.toString() ?? ''
  opinion.stopLossPrice = draft.stopLossPrice?.toString() ?? ''
  successMessage.value = '这只股票已经有活动研究周期。你的草稿已保留，可确认后作为观点发表。'
}

async function scrollToTimeline() {
  const requested = captureTarget()
  await nextTick()
  if (!isCurrent(requested)) return
  const target = document.getElementById('research-timeline')
  if (!target) return
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  target.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' })
}

async function toggleCompose() {
  const requested = captureTarget()
  if (operationBusy.value) return
  composeExpanded.value = !composeExpanded.value
  if (!composeExpanded.value) return
  if (!opinion.reason.trim()) composeCycleId.value = currentCycle.value?.id ?? null
  await nextTick()
  if (!isCurrent(requested)) return
  document.querySelector<HTMLElement>('[data-opinion-first-control]')?.focus()
}

function confirmDraftCycle() {
  if (operationBusy.value || !currentCycle.value) return
  composeCycleId.value = currentCycle.value.id
  formError.value = ''
}

async function loadTimeline(reset: boolean) {
  if (!reset && (!timelineNextCursor.value || timelineLoading.value)) return false
  const requested = captureTarget()
  const requestVersion = reset ? ++timelineRequestVersion : timelineRequestVersion
  if (reset) {
    timelineCycles.value = []
    timelineNextCursor.value = null
  }
  timelineLoading.value = true
  timelineError.value = ''
  try {
    const page = (
      await getWorkspaceStockResearchTimeline(
        requested.workspace,
        requested.stock,
        {
          ...(authorFilter.value !== 'ALL' ? { author: Number(authorFilter.value) } : {}),
          ...(stanceFilter.value !== 'ALL' ? { stance: stanceFilter.value as StockStance } : {}),
          ...(typeFilter.value !== 'ALL' ? { type: typeFilter.value as OpinionType } : {}),
        },
        reset ? undefined : (timelineNextCursor.value ?? undefined),
      )
    ).data.data
    if (!isCurrent(requested) || requestVersion !== timelineRequestVersion) return false
    const cycles = reset ? [] : [...timelineCycles.value]
    for (const cycle of page.cycles) {
      const existing = cycles.find((item) => item.id === cycle.id)
      if (existing) {
        const ids = new Set(existing.opinions.map((item) => item.id))
        existing.opinions.push(...cycle.opinions.filter((item) => !ids.has(item.id)))
      } else cycles.push(cycle)
    }
    timelineCycles.value = cycles
    timelineNextCursor.value = page.nextCursor
    return true
  } catch (error) {
    if (!isCurrent(requested) || requestVersion !== timelineRequestVersion) return false
    if (await handleWorkspaceApiError(error, router)) return false
    timelineError.value = error instanceof Error ? error.message : '研究时间线加载失败'
    return false
  } finally {
    if (requestVersion === timelineRequestVersion) timelineLoading.value = false
  }
}

watch([authorFilter, stanceFilter, typeFilter], () => {
  if (loadedTimelineTarget === `${workspaceId.value}:${workspaceStockId.value}`) {
    void loadTimeline(true)
  }
})

async function loadMarket(workspace: number, stock: number, code: string) {
  const requestVersion = ++marketRequestVersion
  market.value = null
  marketLoading.value = true
  marketError.value = ''
  try {
    const quote = (await getWorkspaceStockMarketDetail(workspace, code)).data.data
    if (
      requestVersion !== marketRequestVersion ||
      workspaceId.value !== workspace ||
      workspaceStockId.value !== stock
    )
      return
    if (quote.code !== code || quote.workspaceStockId !== stock) {
      throw new Error('行情与当前研究股票不一致，请重新加载')
    }
    market.value = quote
  } catch (error) {
    if (requestVersion !== marketRequestVersion) return
    if (await handleWorkspaceApiError(error, router)) return
    marketError.value = error instanceof Error ? error.message : '行情读取失败'
  } finally {
    if (requestVersion === marketRequestVersion) marketLoading.value = false
  }
}

function retryMarket() {
  const detail = stockStore.researchDetail
  if (detail) void loadMarket(workspaceId.value, workspaceStockId.value, detail.code)
}

let enteringResearchView = true

async function load() {
  const requested = captureTarget()
  pageLoading.value = true
  // 只承接从行情页带来的保存结果，参数切换不沿用上一只股票的提示。
  const savedRefreshWarning = enteringResearchView ? stockStore.refreshWarning : ''
  enteringResearchView = false
  const requestedWorkspaceId = workspaceId.value
  const requestedStockId = workspaceStockId.value
  if (
    !Number.isSafeInteger(requestedWorkspaceId) ||
    requestedWorkspaceId <= 0 ||
    !Number.isSafeInteger(requestedStockId) ||
    requestedStockId <= 0
  ) {
    await router.replace('/workspaces')
    return
  }
  stockStore.clearRefreshWarning()
  pageRefreshWarning.value = ''
  try {
    await workspaceStore.ensureCurrent(requestedWorkspaceId)
    if (!isCurrent(requested)) return
    const detail = await stockStore.loadResearch(requestedWorkspaceId, requestedStockId)
    if (!isCurrent(requested)) return
    const quoteRead = loadMarket(requestedWorkspaceId, requestedStockId, detail.code)
    loadedTimelineTarget = `${requestedWorkspaceId}:${requestedStockId}`
    await loadTimeline(true)
    if (!isCurrent(requested)) return
    pageLoading.value = false
    restorePendingDraft()
    if (route.hash === '#research-timeline') {
      // 页面已可读；等上方报价完成布局，再定位，避免迟到报价把时间线推到顶栏下面。
      await quoteRead
      if (isCurrent(requested)) await scrollToTimeline()
    }
  } catch (error) {
    if (!isCurrent(requested)) return
    if (
      savedRefreshWarning &&
      workspaceId.value === requestedWorkspaceId &&
      workspaceStockId.value === requestedStockId
    ) {
      stockStore.refreshWarning = savedRefreshWarning
    }
    await handleWorkspaceApiError(error, router)
  } finally {
    if (isCurrent(requested)) pageLoading.value = false
  }
}

function optionalPrice(value: string) {
  if (!value) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : Number.NaN
}

function resetForm() {
  opinion.type = 'UPDATE'
  opinion.stance = 'NEUTRAL'
  opinion.reason = ''
  opinion.observationNote = ''
  opinion.horizon = 'MEDIUM'
  opinion.targetPrice = ''
  opinion.stopLossPrice = ''
}

async function submitOpinion(reopen: boolean) {
  if (operationBusy.value || isArchived.value) return
  if (!reopen && composeCycleId.value !== currentCycle.value?.id) {
    formError.value = '研究周期已经变化，请确认当前周期后再提交草稿'
    return
  }
  const requested = captureTarget()
  const targetPrice = optionalPrice(opinion.targetPrice)
  const stopLossPrice = optionalPrice(opinion.stopLossPrice)
  if (!opinion.reason.trim()) {
    formError.value = '请填写判断理由'
    return
  }
  if (Number.isNaN(targetPrice) || Number.isNaN(stopLossPrice)) {
    formError.value = '目标价和止损参考价必须是大于 0 的数字'
    return
  }
  formError.value = ''
  successMessage.value = ''
  actionBusy.value = true
  try {
    if (reopen) {
      const detail = stockStore.researchDetail
      if (!detail) return
      const result = await stockStore.add(requested.workspace, {
        code: detail.code,
        stance: opinion.stance,
        reason: opinion.reason.trim(),
        observationNote: opinion.observationNote.trim() || null,
        horizon: opinion.horizon,
        targetPrice,
        stopLossPrice,
      })
      if (!isCurrent(requested)) return
      try {
        await stockStore.loadResearch(requested.workspace, requested.stock)
        if (!isCurrent(requested)) return
        retryMarket()
        const timelineLoaded = await loadTimeline(true)
        if (!isCurrent(requested)) return
        if (!timelineLoaded)
          pageRefreshWarning.value = '新的研究周期已经保存，但时间线刷新失败，请重新加载。'
      } catch {
        if (!isCurrent(requested)) return
        pageRefreshWarning.value = result.alreadyActive
          ? '其他成员已经开启研究周期，但详情刷新失败；你的草稿仍然保留，请重新加载。'
          : '新的研究周期已经开启，但详情刷新失败，请重新加载。'
        formError.value = pageRefreshWarning.value
      }
      if (result.alreadyActive) {
        composeCycleId.value = currentCycle.value?.id ?? null
        successMessage.value = '其他成员已经先开启研究周期，你的草稿仍然保留，可作为观点发表。'
        return
      }
      successMessage.value = '新的研究周期已经开启，旧周期仍保留在下方。'
    } else {
      const cycle = currentCycle.value
      if (!cycle) {
        formError.value = '研究周期已经变化，请刷新后重试'
        return
      }
      await stockStore.createOpinion(requested.workspace, requested.stock, {
        researchCycleId: cycle.id,
        type: opinion.type,
        stance: opinion.stance,
        reason: opinion.reason.trim(),
        observationNote: opinion.observationNote.trim() || null,
        horizon: opinion.horizon,
        targetPrice,
        stopLossPrice,
      })
      if (!isCurrent(requested)) return
      const timelineLoaded = await loadTimeline(true)
      if (!isCurrent(requested)) return
      if (!timelineLoaded) pageRefreshWarning.value = '观点已经保存，但时间线刷新失败，请重新加载。'
      successMessage.value = '观点已经加入当前研究时间线。'
    }
    if (!isCurrent(requested)) return
    resetForm()
    composeExpanded.value = false
    await scrollToTimeline()
  } catch (error) {
    if (!isCurrent(requested)) return
    if (await handleWorkspaceApiError(error, router)) return
    formError.value = error instanceof Error ? error.message : '提交失败'
  } finally {
    if (isCurrent(requested)) actionBusy.value = false
  }
}

async function retryResearchData() {
  if (operationBusy.value) return
  const requested = captureTarget()
  actionBusy.value = true
  try {
    await stockStore.loadResearch(requested.workspace, requested.stock)
    if (!isCurrent(requested)) return
    loadedTimelineTarget = `${requested.workspace}:${requested.stock}`
    retryMarket()
    const results = await Promise.all([
      loadTimeline(true),
      stockStore.loadList(requested.workspace),
      stockStore.loadEnded(requested.workspace),
    ])
    if (!isCurrent(requested)) return
    restorePendingDraft()
    if (!results[0]) return
    stockStore.clearRefreshWarning()
    pageRefreshWarning.value = ''
    formError.value = ''
  } catch (error) {
    if (!isCurrent(requested)) return
    await handleWorkspaceApiError(error, router)
  } finally {
    if (isCurrent(requested)) actionBusy.value = false
  }
}

function openEndConfirmation() {
  if (operationBusy.value || !currentCycle.value) return
  endCycleId.value = currentCycle.value.id
  endConfirming.value = true
}

async function endResearch() {
  const cycle = currentCycle.value
  if (!cycle || operationBusy.value || isArchived.value) return
  if (endCycleId.value !== cycle.id) {
    endConfirming.value = false
    operationError.value = '研究周期已经变化，请重新确认要结束的周期。'
    return
  }
  const requested = captureTarget()
  actionBusy.value = true
  try {
    await stockStore.endResearch(requested.workspace, requested.stock, cycle.id)
    if (!isCurrent(requested)) return
    const timelineLoaded = await loadTimeline(true)
    if (!isCurrent(requested)) return
    if (!timelineLoaded)
      pageRefreshWarning.value = '本轮关注已经结束，但时间线刷新失败，请重新加载。'
    if (!isCurrent(requested)) return
    endConfirming.value = false
    composeExpanded.value = false
    resetForm()
    successMessage.value = '本轮关注已经结束，研究记录已转为只读历史。'
  } catch (error) {
    if (!isCurrent(requested)) return
    if (await handleWorkspaceApiError(error, router)) return
  } finally {
    if (isCurrent(requested)) actionBusy.value = false
  }
}

async function withdrawOpinion(item: StockOpinionResponseVO) {
  if (operationBusy.value || isArchived.value || !canWithdrawNow(item)) return
  const requested = captureTarget()
  const initial = item.type === 'INITIAL'
  if (
    !window.confirm(
      initial
        ? '撤销初始观点会取消本期研究，并将股票移出正在关注；内容会隐藏，但取消记录永久保留。确定继续吗？'
        : '撤销后，这条误发内容会隐藏，但作者、发表时间和撤销记录永久保留。观点发生变化时应发表新观点，不应撤销历史。确定继续吗？',
    )
  )
    return
  successMessage.value = ''
  actionBusy.value = true
  try {
    await stockStore.withdrawOpinion(requested.workspace, requested.stock, item.id)
    if (!isCurrent(requested)) return
    const timelineLoaded = await loadTimeline(true)
    if (!isCurrent(requested)) return
    if (!timelineLoaded)
      pageRefreshWarning.value = '撤销操作已经保存，但时间线刷新失败，请重新加载。'
    if (!isCurrent(requested)) return
    successMessage.value = initial
      ? '初始观点已撤销，本期研究已取消并移出正在关注，历史记录仍然保留。'
      : '误发内容已撤销：正文已经隐藏，时间线仍保留发表和撤销记录。'
  } catch (error) {
    if (!isCurrent(requested)) return
    await handleWorkspaceApiError(error, router)
  } finally {
    if (isCurrent(requested)) actionBusy.value = false
  }
}

function formatTime(value: string | null) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function formatPrice(value: number | null, currency: string) {
  if (value === null) return '未填写'
  return `${currency === 'HKD' ? 'HK$' : '¥'}${value.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  })}`
}

function formatSignedPrice(value: number, currency: string) {
  return `${value > 0 ? '+' : value < 0 ? '−' : ''}${formatPrice(Math.abs(value), currency)}`
}

function canWithdrawNow(item: StockOpinionResponseVO) {
  return item.canWithdraw && new Date(item.createdAt).getTime() + 5 * 60 * 1000 >= currentTime.value
}

const stanceLabels = { BULLISH: '看多', NEUTRAL: '中性', BEARISH: '看空' }
const typeLabels = { INITIAL: '初始观点', UPDATE: '观点更新', REVIEW: '复盘总结' }
const horizonLabels = { SHORT: '短期', MEDIUM: '中期', LONG: '长期' }

watch(
  [workspaceId, workspaceStockId],
  () => {
    pageVersion += 1
    actionBusy.value = false
    endConfirming.value = false
    endCycleId.value = null
    composeExpanded.value = false
    composeCycleId.value = null
    resetForm()
    formError.value = ''
    successMessage.value = ''
    operationError.value = ''
    stockStore.clearResearchView()
    chartExpanded.value = route.hash !== '#research-timeline'
    marketRequestVersion += 1
    market.value = null
    marketLoading.value = false
    marketError.value = ''
    timelineRequestVersion += 1
    loadedTimelineTarget = ''
    timelineCycles.value = []
    timelineNextCursor.value = null
    timelineLoading.value = false
    timelineError.value = ''
    authorFilter.value = 'ALL'
    stanceFilter.value = 'ALL'
    typeFilter.value = 'ALL'
    void load()
  },
  { immediate: true },
)

watch(
  () => route.hash,
  (hash) => {
    if (
      hash === '#research-timeline' &&
      loadedTimelineTarget === `${workspaceId.value}:${workspaceStockId.value}`
    )
      void scrollToTimeline()
  },
)

onMounted(() => {
  withdrawClock = window.setInterval(() => {
    currentTime.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  disposed = true
  pageVersion += 1
  marketRequestVersion += 1
  timelineRequestVersion += 1
  if (withdrawClock !== undefined) window.clearInterval(withdrawClock)
  stockStore.clearResearchView()
})
</script>

<template>
  <section v-if="pageLoading || stockStore.researchLoading" class="page-state" aria-live="polite">
    正在读取研究记录…
  </section>
  <section v-else-if="stockStore.researchError && !stockStore.researchDetail" class="page-state">
    <h1>
      {{
        stockStore.refreshWarning || pageRefreshWarning
          ? '操作已经保存，页面暂未刷新'
          : '研究记录暂时无法加载'
      }}
    </h1>
    <p>{{ pageRefreshWarning || stockStore.refreshWarning || stockStore.researchError }}</p>
    <button type="button" @click="retryResearchData">重新加载页面数据</button>
  </section>
  <template v-else-if="stockStore.researchDetail">
    <header class="research-header">
      <RouterLink :to="{ name: 'workspace-stocks', params: { workspaceId } }"
        >← 返回共享自选</RouterLink
      >
      <p class="eyebrow">
        {{ stockStore.researchDetail.code }} · {{ stockStore.researchDetail.exchange }}
      </p>
      <h1>{{ stockStore.researchDetail.name }} · 研究</h1>
      <p>每次关注形成一段独立周期；观点按发表时间追加，旧判断不会被新内容覆盖。</p>
    </header>

    <p v-if="successMessage" class="success-message" aria-live="polite">{{ successMessage }}</p>
    <p v-if="operationError || stockStore.researchError" class="error-message" role="alert">
      {{ operationError || stockStore.researchError }}
      <button type="button" :disabled="operationBusy" @click="retryResearchData">
        重新加载研究数据
      </button>
    </p>
    <p v-if="stockStore.refreshWarning" class="error-message" role="alert">
      {{ stockStore.refreshWarning }}
      <button type="button" :disabled="operationBusy" @click="retryResearchData">
        只重新加载页面数据
      </button>
    </p>
    <p v-if="pageRefreshWarning" class="error-message" role="alert">
      {{ pageRefreshWarning }}
      <button type="button" :disabled="operationBusy" @click="retryResearchData">
        只重新加载页面数据
      </button>
    </p>

    <section class="research-market" aria-labelledby="research-market-title">
      <div class="market-summary">
        <div>
          <p class="eyebrow">当前可用行情</p>
          <h2 id="research-market-title">最新报价与价格变化</h2>
          <p v-if="marketLoading">正在读取行情…</p>
          <p v-else-if="marketError" role="alert">
            {{ marketError }} <button type="button" @click="retryMarket">重试行情</button>
          </p>
          <template v-else-if="market">
            <strong class="latest-price">{{
              market.price === null ? '暂无可用报价' : formatPrice(market.price, market.currency)
            }}</strong>
            <p>
              报价时间：{{ formatTime(market.quoteTime) }} · 来源：{{ market.source
              }}{{ market.simulated ? '（本地模拟）' : '' }}
            </p>
            <p v-if="priceChange && currentCycle" class="price-change">
              较本期关注价 {{ formatPrice(currentCycle.startPrice, market.currency) }}：
              {{ formatSignedPrice(priceChange.difference, market.currency) }}
              （{{ priceChange.percent > 0 ? '+' : '' }}{{ priceChange.percent.toFixed(2) }}%）
            </p>
            <p v-else-if="currentCycle">
              当前报价缺失、早于关注时的报价，或模拟状态不同，暂不计算价格变化。
            </p>
            <p v-else>当前没有进行中的周期；历史周期不使用今天的报价计算结束时的价格变化。</p>
            <p v-if="currentCycle" class="market-note">
              比较的是两次未复权报价，不包含分红、除权等因素，不代表投资收益。关注价来源：{{
                currentCycle.quoteSource
              }}{{ currentCycle.simulated ? '（本地模拟）' : '' }}，时间：{{
                formatTime(currentCycle.quoteTime)
              }}。
            </p>
          </template>
        </div>
        <div class="market-controls">
          <button type="button" :disabled="marketLoading" @click="retryMarket">刷新行情</button>
          <button
            type="button"
            :aria-expanded="chartExpanded"
            aria-controls="research-market-chart"
            @click="chartExpanded = !chartExpanded"
          >
            {{ chartExpanded ? '收起行情图' : '查看行情图' }}
          </button>
        </div>
      </div>
      <div v-if="chartExpanded" id="research-market-chart">
        <StockMarketPanel
          :key="`${workspaceId}:${workspaceStockId}`"
          :workspace-id="workspaceId"
          :workspace-stock-id="workspaceStockId"
          :code="stockStore.researchDetail.code"
          :name="stockStore.researchDetail.name"
        />
      </div>
    </section>

    <section v-if="!isArchived" class="current-actions">
      <div v-if="currentCycle">
        <p class="eyebrow">当前周期</p>
        <h2>{{ currentCycle.attentionLevel === 'FOCUS' ? '重点关注' : '普通关注' }}</h2>
        <p>
          开始于 {{ formatTime(currentCycle.startedAt) }}，由
          {{ currentCycle.startedByNickname }} 发起。
        </p>
      </div>
      <div v-else>
        <p class="eyebrow">当前状态</p>
        <h2>没有正在进行的研究周期</h2>
        <p>历史观点保持只读，需要继续跟踪时可以开启新一期。</p>
      </div>
      <div v-if="!endConfirming" class="current-action-controls">
        <button
          class="compose-toggle"
          type="button"
          :disabled="operationBusy"
          :aria-expanded="composeExpanded"
          aria-controls="opinion-compose"
          @click="toggleCompose"
        >
          {{ composeExpanded ? '收起观点表单' : currentCycle ? '发表观点更新' : '开启新一期研究' }}
        </button>
        <button
          v-if="currentCycle"
          class="danger-link"
          type="button"
          :disabled="operationBusy"
          @click="openEndConfirmation"
        >
          结束本轮关注
        </button>
      </div>
      <div v-else class="confirm-end">
        <span>结束后本周期永久只读。</span>
        <button type="button" :disabled="operationBusy" @click="endConfirming = false">取消</button>
        <button type="button" :disabled="operationBusy" @click="endResearch">确认结束</button>
      </div>
    </section>

    <section v-if="!isArchived && composeExpanded" id="opinion-compose" class="opinion-compose">
      <p class="eyebrow">{{ currentCycle ? '追加研究记录' : '重新开启研究' }}</p>
      <h2>{{ currentCycle ? '发表观点更新或复盘' : '用新的初始观点开启下一段周期' }}</h2>
      <p class="form-guidance">
        判断理由说明“为什么形成当前倾向”；关键观察条件为选填，用于记录哪些变化可能支持、削弱或推翻当前判断。
      </p>
      <form novalidate :aria-busy="operationBusy" @submit.prevent="submitOpinion(!currentCycle)">
        <fieldset :disabled="operationBusy">
          <div class="form-grid">
            <label v-if="currentCycle">
              <span>记录类型（必填）</span>
              <select v-model="opinion.type" data-opinion-first-control required>
                <option value="UPDATE">观点更新</option>
                <option value="REVIEW">复盘总结</option>
              </select>
            </label>
            <label>
              <span>观点倾向（必填）</span>
              <select
                v-model="opinion.stance"
                :data-opinion-first-control="currentCycle ? undefined : ''"
                required
              >
                <option value="BULLISH">看多</option>
                <option value="NEUTRAL">中性</option>
                <option value="BEARISH">看空</option>
              </select>
            </label>
            <label>
              <span>关注周期（必填）</span>
              <select v-model="opinion.horizon" required>
                <option value="SHORT">短期</option>
                <option value="MEDIUM">中期</option>
                <option value="LONG">长期</option>
              </select>
            </label>
          </div>
          <div class="text-grid">
            <label>
              <span>判断理由（必填）</span>
              <textarea
                v-model="opinion.reason"
                maxlength="1000"
                required
                :aria-describedby="formError ? 'research-form-error' : undefined"
                placeholder="为什么形成当前的看多、中性或看空判断？"
              ></textarea>
            </label>
            <label>
              <span>关键观察条件（选填）</span>
              <textarea
                v-model="opinion.observationNote"
                maxlength="1000"
                placeholder="哪些变化可能支持、削弱或推翻当前判断？"
              ></textarea>
            </label>
          </div>
          <div class="form-grid price-fields">
            <label
              ><span>目标价（选填）</span>
              <input
                v-model="opinion.targetPrice"
                inputmode="decimal"
                :placeholder="
                  stockStore.researchDetail.currency === 'HKD'
                    ? '港币金额，选填'
                    : '人民币金额，选填'
                "
                :aria-describedby="formError ? 'research-form-error' : undefined"
              />
            </label>
            <label
              ><span>止损参考价（选填）</span>
              <input
                v-model="opinion.stopLossPrice"
                inputmode="decimal"
                :placeholder="
                  stockStore.researchDetail.currency === 'HKD'
                    ? '港币金额，选填'
                    : '人民币金额，选填'
                "
                :aria-describedby="formError ? 'research-form-error' : undefined"
              />
            </label>
          </div>
        </fieldset>
        <p v-if="formError" id="research-form-error" class="error-message" role="alert">
          {{ formError }}
        </p>
        <button
          v-if="currentCycle && composeCycleId !== currentCycle.id"
          type="button"
          :disabled="operationBusy"
          @click="confirmDraftCycle"
        >
          确认当前周期，保留草稿
        </button>
        <button class="primary" type="submit" :disabled="operationBusy">
          {{ operationBusy ? '正在提交…' : currentCycle ? '发表到时间线' : '重新开启关注' }}
        </button>
      </form>
    </section>

    <section id="research-timeline" class="timeline-section">
      <div class="timeline-heading">
        <div>
          <p class="eyebrow">完整历史</p>
          <h2>研究周期与观点时间线</h2>
        </div>
        <div class="filters" aria-label="观点筛选">
          <select v-model="authorFilter" aria-label="按成员筛选">
            <option value="ALL">全部成员</option>
            <option
              v-for="author in stockStore.researchDetail.authors"
              :key="author.userId"
              :value="String(author.userId)"
            >
              {{ author.nickname }}
            </option>
          </select>
          <select v-model="stanceFilter" aria-label="按倾向筛选">
            <option value="ALL">全部倾向</option>
            <option value="BULLISH">看多</option>
            <option value="NEUTRAL">中性</option>
            <option value="BEARISH">看空</option>
          </select>
          <select v-model="typeFilter" aria-label="按类型筛选">
            <option value="ALL">全部类型</option>
            <option value="INITIAL">初始观点</option>
            <option value="UPDATE">观点更新</option>
            <option value="REVIEW">复盘总结</option>
          </select>
        </div>
      </div>

      <p v-if="timelineLoading && !timelineCycles.length" class="empty-filter">
        正在读取研究时间线…
      </p>
      <p v-else-if="timelineError && !timelineCycles.length" class="empty-filter" role="alert">
        {{ timelineError }} <button type="button" @click="loadTimeline(true)">重试</button>
      </p>
      <p v-else-if="!timelineCycles.length" class="empty-filter">当前筛选条件下没有观点。</p>
      <article
        v-for="cycle in timelineCycles"
        :key="cycle.id"
        class="cycle-card"
        :data-cycle-id="cycle.id"
      >
        <header>
          <div>
            <span class="cycle-number"
              >开始于 {{ formatTime(cycle.startedAt) }} · {{ cycle.startedByNickname }}</span
            >
            <h3>
              {{
                cycle.status === 'ACTIVE'
                  ? '正在关注'
                  : cycle.status === 'CANCELLED'
                    ? '已取消'
                    : '已结束'
              }}
              · {{ cycle.attentionLevel === 'FOCUS' ? '重点关注' : '普通关注' }}
            </h3>
          </div>
          <dl>
            <div>
              <dt>开始</dt>
              <dd>{{ formatTime(cycle.startedAt) }}</dd>
            </div>
            <div>
              <dt>结束</dt>
              <dd>{{ formatTime(cycle.endedAt) }}</dd>
            </div>
            <div>
              <dt>关注价</dt>
              <dd>{{ formatPrice(cycle.startPrice, stockStore.researchDetail.currency) }}</dd>
            </div>
          </dl>
        </header>

        <div class="timeline">
          <section
            v-for="item in cycle.opinions"
            :key="item.id"
            class="opinion-item"
            :data-opinion-id="item.id"
          >
            <div class="opinion-meta">
              <span class="author-avatar" aria-hidden="true">{{
                Array.from(item.authorNickname)[0]
              }}</span>
              <div>
                <strong>{{ item.authorNickname }}</strong
                ><small>成员 #{{ item.authorUserId }}</small>
              </div>
            </div>
            <div class="opinion-body">
              <template v-if="item.withdrawn">
                <p class="opinion-tags">
                  <span>{{ typeLabels[item.type] }}</span>
                </p>
                <p class="withdrawn">
                  这条观点已由作者撤销，记录仍保留。撤销于 {{ formatTime(item.withdrawnAt) }}。
                </p>
              </template>
              <template v-else>
                <p class="opinion-tags">
                  <span>{{ typeLabels[item.type] }}</span
                  ><span>{{ stanceLabels[item.stance] }}</span
                  ><span>{{ horizonLabels[item.horizon] }}</span>
                </p>
                <div class="opinion-copy">
                  <p>{{ item.reason }}</p>
                  <p v-if="item.observationNote">
                    <strong>关键观察条件：</strong>{{ item.observationNote }}
                  </p>
                </div>
                <p class="opinion-prices">
                  目标价 {{ formatPrice(item.targetPrice, stockStore.researchDetail.currency) }} ·
                  止损参考 {{ formatPrice(item.stopLossPrice, stockStore.researchDetail.currency) }}
                </p>
                <button
                  v-if="!isArchived && canWithdrawNow(item)"
                  type="button"
                  class="withdraw-link"
                  :disabled="operationBusy"
                  @click="withdrawOpinion(item)"
                >
                  {{ item.type === 'INITIAL' ? '撤销本次误添加' : '撤销误发内容' }}
                </button>
              </template>
            </div>
            <time class="opinion-time" :datetime="item.createdAt">{{
              formatTime(item.createdAt)
            }}</time>
          </section>
        </div>
        <p class="cycle-note">
          {{
            !isArchived && cycle.status === 'ACTIVE'
              ? '观点变化时发表新观点；误发可在规定窗口内撤销，保留记录。'
              : '历史周期只读，重新关注会开启新周期。'
          }}
        </p>
      </article>
      <p v-if="timelineError && timelineCycles.length" class="empty-filter" role="alert">
        {{ timelineError }} <button type="button" @click="loadTimeline(false)">重试</button>
      </p>
      <button
        v-if="timelineNextCursor"
        type="button"
        class="load-more"
        :disabled="timelineLoading"
        @click="loadTimeline(false)"
      >
        {{ timelineLoading ? '正在读取…' : '加载更早的观点' }}
      </button>
    </section>
  </template>
</template>

<style scoped>
.page-state {
  min-height: 360px;
  display: grid;
  align-content: center;
  gap: 16px;
  color: var(--color-muted);
}
.page-state h1 {
  color: var(--color-ink);
  font-family: var(--font-display);
  font-size: 28px;
}
.page-state button {
  justify-self: start;
}
.research-header {
  padding: 36px 0 28px;
  border-bottom: 1px solid var(--color-ink);
}
.research-header > a {
  color: var(--color-focus);
  font-size: 12px;
}
.eyebrow,
label span,
dt {
  color: var(--color-muted);
  font-size: 12px;
}
.research-header .eyebrow {
  margin-top: 32px;
  font-family: var(--font-mono);
  font-size: 11px;
}
.research-header h1 {
  margin-top: 10px;
  font-family: var(--font-display);
  font-size: 40px;
  letter-spacing: -0.02em;
  overflow-wrap: anywhere;
}
.research-header > p:last-child {
  max-width: 68ch;
  margin-top: 14px;
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.8;
}
button {
  min-height: 44px;
  padding: 8px 18px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-action);
  background: var(--color-surface);
  color: var(--color-ink);
  font-size: 12px;
  cursor: pointer;
}
button:hover:not(:disabled) {
  border-color: var(--color-ink);
}
button:disabled {
  cursor: default;
  opacity: 0.55;
}
button:focus-visible,
a:focus-visible,
input:focus-visible,
select:focus-visible,
textarea:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: 3px;
}
.research-market {
  padding: 28px 0;
  border-bottom: 1px solid var(--color-border);
}
.market-summary {
  display: flex;
  justify-content: space-between;
  align-items: start;
  gap: 24px;
  margin-bottom: 24px;
}
.market-summary h2 {
  margin: 8px 0 16px;
  font-family: var(--font-display);
  font-size: 24px;
}
.market-summary p {
  max-width: 72ch;
  margin-top: 8px;
  color: var(--color-muted);
  font-size: 12px;
  line-height: 1.8;
}
.market-summary .latest-price {
  font-family: var(--font-mono);
  font-size: 34px;
  font-weight: 600;
}
.market-summary .price-change {
  color: var(--color-ink);
  font-family: var(--font-mono);
}
.market-summary .market-note {
  font-size: 11px;
}
.market-controls {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 8px;
}
.success-message,
.error-message {
  margin-top: 20px;
  padding: 14px 18px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  font-size: 13px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.success-message {
  background: var(--color-surface-secondary);
  color: var(--color-focus);
}
.error-message {
  color: var(--color-danger);
  background: var(--color-surface);
}
.error-message button {
  margin-left: 8px;
}
.current-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 22px;
  margin-top: 28px;
  padding: 22px;
  border: 1px solid var(--color-border);
  border-left: 3px solid var(--color-focus);
  border-radius: var(--radius-control);
  background: var(--color-surface-secondary);
}
.current-actions h2 {
  margin-top: 6px;
  font-family: var(--font-display);
  font-size: 24px;
}
.current-actions p:not(.eyebrow) {
  margin-top: 8px;
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.8;
}
.current-action-controls,
.confirm-end {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}
.confirm-end {
  font-size: 13px;
}
.compose-toggle,
.primary,
.confirm-end button:last-child {
  border-color: var(--color-ink);
  background: var(--color-ink);
  color: var(--color-surface);
}
.danger-link,
.withdraw-link {
  border-color: transparent;
  background: transparent;
  color: var(--color-danger);
  text-decoration: underline;
  text-underline-offset: 4px;
}
.opinion-compose {
  margin-top: 24px;
  padding: 26px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  scroll-margin-top: 90px;
}
.opinion-compose h2 {
  margin-top: 8px;
  font-family: var(--font-display);
  font-size: 24px;
}
.form-guidance {
  max-width: 72ch;
  margin-top: 12px;
  color: var(--color-muted);
  font-size: 12px;
  line-height: 1.8;
}
.opinion-compose form {
  margin-top: 22px;
}
fieldset {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
}
.form-grid,
.text-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  margin-bottom: 20px;
}
.text-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.price-fields {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
label span {
  display: block;
  margin-bottom: 8px;
}
input,
select,
textarea {
  width: 100%;
  min-width: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  color: var(--color-ink);
  font-size: 14px;
}
input,
select {
  min-height: 44px;
  padding: 8px 12px;
}
input {
  font-family: var(--font-mono);
}
textarea {
  min-height: 160px;
  padding: 12px;
  line-height: 1.8;
  resize: vertical;
}
textarea::placeholder {
  color: var(--color-muted);
}
fieldset:disabled input,
fieldset:disabled select,
fieldset:disabled textarea {
  background: var(--color-surface-secondary);
}
.primary {
  margin-top: 8px;
}
.timeline-section {
  padding: 48px 0 64px;
  scroll-margin-top: 90px;
}
.timeline-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 22px;
  padding-bottom: 20px;
}
.timeline-heading h2 {
  margin-top: 8px;
  font-family: var(--font-display);
  font-size: 26px;
}
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.filters select {
  width: auto;
  min-width: 120px;
  font-size: 12px;
}
.cycle-card {
  margin-bottom: 22px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  overflow: hidden;
}
.cycle-card > header {
  display: flex;
  justify-content: space-between;
  align-items: start;
  flex-wrap: wrap;
  gap: 22px;
  padding: 20px 22px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface-secondary);
}
.cycle-number {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
  overflow-wrap: anywhere;
}
.cycle-card h3 {
  margin-top: 8px;
  font-family: var(--font-display);
  font-size: 20px;
}
.cycle-card dl {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  margin: 0;
}
dt {
  font-size: 11px;
}
dd {
  margin: 6px 0 0;
  font-family: var(--font-mono);
  font-size: 11px;
}
.opinion-item {
  display: grid;
  grid-template-columns: minmax(120px, 160px) minmax(0, 1fr) auto;
  gap: 20px;
  padding: 22px;
  border-bottom: 1px solid var(--color-border);
}
.opinion-item:last-child {
  border-bottom: 0;
}
.opinion-meta {
  display: flex;
  align-items: start;
  gap: 10px;
  min-width: 0;
}
.author-avatar {
  display: grid;
  flex: 0 0 30px;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 50%;
  background: var(--color-surface-secondary);
  color: var(--color-focus);
  font-size: 13px;
}
.opinion-meta strong {
  display: block;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.opinion-meta small {
  display: block;
  margin-top: 5px;
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 10px;
}
.opinion-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}
.opinion-tags span {
  padding: 3px 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-action);
  color: var(--color-muted);
  font-size: 11px;
}
.opinion-copy {
  margin-top: 12px;
  max-width: 72ch;
  font-size: 14px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.opinion-copy p + p {
  margin-top: 10px;
}
.opinion-prices {
  margin-top: 12px;
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.opinion-time {
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}
.withdraw-link {
  min-height: 44px;
  margin-top: 8px;
  padding: 6px 0;
  font-size: 11px;
}
.withdrawn,
.empty-filter {
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.8;
}
.empty-filter {
  padding: 24px 0;
}
.cycle-note {
  padding: 13px 22px;
  border-top: 1px dashed var(--color-border);
  color: var(--color-muted);
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.8;
}
@media (max-width: 1050px) {
  .opinion-item {
    grid-template-columns: 135px minmax(0, 1fr);
  }
  .opinion-meta {
    grid-column: 1;
  }
  .opinion-body {
    grid-column: 2;
    grid-row: 1 / span 2;
  }
  .opinion-time {
    grid-column: 1;
  }
}
@media (max-width: 760px) {
  .research-header h1 {
    font-size: 30px;
  }
  .market-summary,
  .current-actions,
  .timeline-heading {
    align-items: start;
    flex-direction: column;
  }
  .latest-price {
    overflow-wrap: anywhere;
  }
  .current-actions {
    padding: 18px;
  }
  .current-action-controls {
    width: 100%;
  }
  .opinion-compose {
    padding: 18px;
  }
  .form-grid,
  .text-grid {
    grid-template-columns: 1fr;
  }
  .filters {
    width: 100%;
    flex-direction: column;
  }
  .filters select {
    width: 100%;
  }
  .cycle-card > header {
    padding: 18px;
  }
  .opinion-item {
    grid-template-columns: 1fr;
    gap: 12px;
    padding: 18px;
  }
  .opinion-meta,
  .opinion-time,
  .opinion-body {
    grid-column: 1;
    grid-row: auto;
  }
  .opinion-meta {
    grid-row: 1;
  }
  .opinion-time {
    grid-row: 2;
  }
  .opinion-body {
    grid-row: 3;
  }
  .cycle-note {
    padding: 14px 18px;
  }
}
</style>
