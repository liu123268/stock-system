import { ref } from 'vue'
import { defineStore } from 'pinia'
import {
  addWorkspaceStock,
  createWorkspaceStockOpinion,
  endWorkspaceStockResearch,
  getWorkspaceStockCandlesticks,
  getWorkspaceMarketStockCandlesticks,
  getWorkspaceMarketStockIntraday,
  getWorkspaceStockMarketDetail,
  getWorkspaceStockIntraday,
  getWorkspaceStockResearch,
  listEndedWorkspaceStocks,
  listWorkspaceStocks,
  searchStocks,
  updateWorkspaceStockAttention,
  withdrawWorkspaceStockOpinion,
  type AddWorkspaceStockRequestDTO,
  type AddWorkspaceStockResponseVO,
  type AttentionLevel,
  type CandlestickPeriod,
  type CreateStockOpinionRequestDTO,
  type EndedWorkspaceStockResponseVO,
  type StockCandlestickSeriesVO,
  type StockIntradaySeriesVO,
  type StockOpinionResponseVO,
  type StockSearchResponseVO,
  type WorkspaceStockListResponseVO,
  type WorkspaceStockMarketDetailResponseVO,
  type WorkspaceStockResearchResponseVO,
  type UpdateAttentionLevelResponseVO,
} from '@/api/stock'

type ResearchDraft = Omit<AddWorkspaceStockRequestDTO, 'code'>

type MarketReadTarget =
  | { kind: 'workspace-stock'; workspaceStockId: number }
  | { kind: 'directory-stock'; code: string }

export const useStockStore = defineStore('stock', () => {
  const items = ref<WorkspaceStockListResponseVO[]>([])
  const listLoading = ref(false)
  const listError = ref('')
  let listRequestVersion = 0
  let listWorkspaceId: number | null = null
  const searchResults = ref<StockSearchResponseVO[]>([])
  const searchLoading = ref(false)
  const searchError = ref('')
  let searchRequestVersion = 0
  const marketDetail = ref<WorkspaceStockMarketDetailResponseVO | null>(null)
  const marketDetailLoading = ref(false)
  const marketDetailError = ref('')
  let marketDetailRequestVersion = 0
  let marketDetailTargetKey = ''
  const submitLoading = ref(false)
  const submitError = ref('')
  const refreshWarning = ref('')
  const attentionUpdatingStockId = ref<number | null>(null)
  const attentionError = ref('')
  const endedItems = ref<EndedWorkspaceStockResponseVO[]>([])
  let endedRequestVersion = 0
  let endedWorkspaceId: number | null = null
  const researchDetail = ref<WorkspaceStockResearchResponseVO | null>(null)
  const researchLoading = ref(false)
  const researchError = ref('')
  let researchRequestVersion = 0
  let researchTargetKey = ''
  const researchSubmitting = ref(false)
  const pendingResearchDraft = ref<{
    workspaceId: number
    workspaceStockId: number
    draft: ResearchDraft
  } | null>(null)
  const candlestickSeries = ref<StockCandlestickSeriesVO | null>(null)
  const candlestickLoading = ref(false)
  const candlestickError = ref('')
  const candlestickLoadingOlder = ref(false)
  const candlestickHistoryError = ref('')
  let candlestickRequestVersion = 0
  let candlestickTargetKey = ''
  const intradaySeries = ref<StockIntradaySeriesVO | null>(null)
  const intradayLoading = ref(false)
  const intradayError = ref('')
  let intradayRequestVersion = 0
  let intradayTargetKey = ''

  async function loadList(workspaceId: number) {
    const requestVersion = ++listRequestVersion
    if (listWorkspaceId !== workspaceId) {
      items.value = []
      listWorkspaceId = workspaceId
    }
    listLoading.value = true
    listError.value = ''
    try {
      const result = (await listWorkspaceStocks(workspaceId)).data.data
      if (requestVersion === listRequestVersion) {
        items.value = result
      }
      return result
    } catch (error) {
      if (requestVersion === listRequestVersion) {
        listError.value = error instanceof Error ? error.message : '共享自选加载失败'
      }
      throw error
    } finally {
      if (requestVersion === listRequestVersion) {
        listLoading.value = false
      }
    }
  }

  async function search(keyword: string) {
    const requestVersion = ++searchRequestVersion
    searchLoading.value = true
    searchError.value = ''
    try {
      const result = (await searchStocks(keyword.trim())).data.data
      if (requestVersion === searchRequestVersion) {
        searchResults.value = result
      }
      return result
    } catch (error) {
      if (requestVersion === searchRequestVersion) {
        searchError.value = error instanceof Error ? error.message : '股票搜索失败'
      }
      throw error
    } finally {
      if (requestVersion === searchRequestVersion) {
        searchLoading.value = false
      }
    }
  }

  async function loadMarketDetail(workspaceId: number, code: string) {
    const normalizedCode = code.trim().toUpperCase()
    const targetKey = `${workspaceId}:${normalizedCode}`
    const requestVersion = ++marketDetailRequestVersion
    if (marketDetailTargetKey !== targetKey) {
      marketDetail.value = null
      marketDetailTargetKey = targetKey
    }
    marketDetailLoading.value = true
    marketDetailError.value = ''
    try {
      const result = (await getWorkspaceStockMarketDetail(workspaceId, normalizedCode)).data.data
      if (requestVersion === marketDetailRequestVersion) {
        marketDetail.value = result
      }
      return result
    } catch (error) {
      if (requestVersion === marketDetailRequestVersion) {
        marketDetail.value = null
        marketDetailError.value = error instanceof Error ? error.message : '股票行情加载失败'
      }
      throw error
    } finally {
      if (requestVersion === marketDetailRequestVersion) {
        marketDetailLoading.value = false
      }
    }
  }

  async function add(workspaceId: number, request: AddWorkspaceStockRequestDTO) {
    submitLoading.value = true
    submitError.value = ''
    refreshWarning.value = ''
    let result!: AddWorkspaceStockResponseVO
    try {
      result = (await addWorkspaceStock(workspaceId, request)).data.data
    } catch (error) {
      submitError.value = error instanceof Error ? error.message : '添加股票失败'
      submitLoading.value = false
      throw error
    }
    const refreshResults = await Promise.allSettled([loadList(workspaceId), loadEnded(workspaceId)])
    if (refreshResults.some((item) => item.status === 'rejected')) {
      refreshWarning.value = '研究已经保存，但共享自选或历史列表刷新失败，请重新加载页面数据。'
    }
    submitLoading.value = false
    return result
  }

  async function updateAttention(
    workspaceId: number,
    workspaceStockId: number,
    researchCycleId: number,
    attentionLevel: AttentionLevel,
  ) {
    attentionUpdatingStockId.value = workspaceStockId
    attentionError.value = ''
    refreshWarning.value = ''
    let result!: UpdateAttentionLevelResponseVO
    try {
      result = (
        await updateWorkspaceStockAttention(
          workspaceId,
          workspaceStockId,
          researchCycleId,
          attentionLevel,
        )
      ).data.data
    } catch (error) {
      attentionError.value = error instanceof Error ? error.message : '关注级别更新失败'
      attentionUpdatingStockId.value = null
      throw error
    }
    try {
      await loadList(workspaceId)
    } catch {
      refreshWarning.value = '关注级别已经保存，但共享自选刷新失败，请重新加载列表。'
    }
    attentionUpdatingStockId.value = null
    return result
  }

  async function loadEnded(workspaceId: number) {
    const requestVersion = ++endedRequestVersion
    if (endedWorkspaceId !== workspaceId) {
      endedItems.value = []
      endedWorkspaceId = workspaceId
    }
    const result = (await listEndedWorkspaceStocks(workspaceId)).data.data
    if (requestVersion === endedRequestVersion) {
      endedItems.value = result
    }
    return result
  }

  async function loadResearch(workspaceId: number, workspaceStockId: number) {
    const requestVersion = ++researchRequestVersion
    const targetKey = `${workspaceId}:${workspaceStockId}`
    if (researchTargetKey !== targetKey) {
      researchDetail.value = null
      researchTargetKey = targetKey
    }
    researchLoading.value = true
    researchError.value = ''
    try {
      const result = (await getWorkspaceStockResearch(workspaceId, workspaceStockId)).data.data
      if (requestVersion === researchRequestVersion) {
        researchDetail.value = result
      }
      return result
    } catch (error) {
      if (requestVersion === researchRequestVersion) {
        researchDetail.value = null
        researchError.value = error instanceof Error ? error.message : '研究记录加载失败'
      }
      throw error
    } finally {
      if (requestVersion === researchRequestVersion) {
        researchLoading.value = false
      }
    }
  }

  async function endResearch(
    workspaceId: number,
    workspaceStockId: number,
    researchCycleId: number,
  ) {
    researchSubmitting.value = true
    researchError.value = ''
    refreshWarning.value = ''
    try {
      await endWorkspaceStockResearch(workspaceId, workspaceStockId, researchCycleId)
    } catch (error) {
      researchError.value = error instanceof Error ? error.message : '结束关注失败'
      researchSubmitting.value = false
      throw error
    }
    const refreshResults = await Promise.allSettled([
      loadResearch(workspaceId, workspaceStockId),
      loadList(workspaceId),
      loadEnded(workspaceId),
    ])
    if (refreshResults.some((item) => item.status === 'rejected')) {
      refreshWarning.value = '本轮关注已经结束，但页面数据刷新失败，请重新加载。'
    }
    researchSubmitting.value = false
  }

  async function createOpinion(
    workspaceId: number,
    workspaceStockId: number,
    request: CreateStockOpinionRequestDTO,
  ) {
    researchSubmitting.value = true
    researchError.value = ''
    refreshWarning.value = ''
    let result!: StockOpinionResponseVO
    try {
      result = (
        await createWorkspaceStockOpinion(workspaceId, workspaceStockId, request)
      ).data.data
    } catch (error) {
      researchError.value = error instanceof Error ? error.message : '观点发表失败'
      researchSubmitting.value = false
      throw error
    }
    try {
      await loadResearch(workspaceId, workspaceStockId)
    } catch {
      refreshWarning.value = '观点已经保存，但研究详情刷新失败，请重新加载。'
    }
    researchSubmitting.value = false
    return result
  }

  async function withdrawOpinion(
    workspaceId: number,
    workspaceStockId: number,
    opinionId: number,
  ) {
    researchSubmitting.value = true
    researchError.value = ''
    refreshWarning.value = ''
    try {
      await withdrawWorkspaceStockOpinion(workspaceId, workspaceStockId, opinionId)
    } catch (error) {
      researchError.value = error instanceof Error ? error.message : '观点撤回失败'
      researchSubmitting.value = false
      throw error
    }
    const refreshResults = await Promise.allSettled([
      loadResearch(workspaceId, workspaceStockId),
      loadList(workspaceId),
      loadEnded(workspaceId),
    ])
    if (refreshResults.some((item) => item.status === 'rejected')) {
      refreshWarning.value = '撤销操作已经保存，但页面数据刷新失败，请重新加载。'
    }
    researchSubmitting.value = false
  }

  function marketReadTargetKey(workspaceId: number, target: MarketReadTarget) {
    return target.kind === 'workspace-stock'
      ? `${workspaceId}:workspace:${target.workspaceStockId}`
      : `${workspaceId}:directory:${target.code.trim().toUpperCase()}`
  }

  async function requestCandlestickPage(
    workspaceId: number,
    target: MarketReadTarget,
    period: CandlestickPeriod,
    count = 1000,
    before?: string,
  ) {
    if (target.kind === 'workspace-stock') {
      if (!before && count === 1000) {
        return (
          await getWorkspaceStockCandlesticks(workspaceId, target.workspaceStockId, period)
        ).data.data
      }
      return (
        await getWorkspaceStockCandlesticks(
          workspaceId,
          target.workspaceStockId,
          period,
          count,
          before,
        )
      ).data.data
    }
    if (!before && count === 1000) {
      return (
        await getWorkspaceMarketStockCandlesticks(
          workspaceId,
          target.code.trim().toUpperCase(),
          period,
        )
      ).data.data
    }
    return (
      await getWorkspaceMarketStockCandlesticks(
        workspaceId,
        target.code.trim().toUpperCase(),
        period,
        count,
        before,
      )
    ).data.data
  }

  async function loadCandlesticksForTarget(
    workspaceId: number,
    target: MarketReadTarget,
    period: CandlestickPeriod,
  ) {
    const targetKey = marketReadTargetKey(workspaceId, target)
    const requestVersion = ++candlestickRequestVersion
    if (candlestickTargetKey !== targetKey) {
      candlestickTargetKey = targetKey
      candlestickSeries.value = null
    }
    candlestickLoading.value = true
    candlestickError.value = ''
    candlestickHistoryError.value = ''
    try {
      const result = await requestCandlestickPage(workspaceId, target, period)
      if (requestVersion === candlestickRequestVersion) {
        candlestickSeries.value = result
      }
      return result
    } catch (error) {
      if (requestVersion === candlestickRequestVersion) {
        candlestickSeries.value = null
        candlestickError.value = error instanceof Error ? error.message : 'K 线加载失败'
      }
      throw error
    } finally {
      if (requestVersion === candlestickRequestVersion) {
        candlestickLoading.value = false
      }
    }
  }

  function loadCandlesticks(
    workspaceId: number,
    workspaceStockId: number,
    period: CandlestickPeriod,
  ) {
    return loadCandlesticksForTarget(
      workspaceId,
      { kind: 'workspace-stock', workspaceStockId },
      period,
    )
  }

  function loadMarketCandlesticks(workspaceId: number, code: string, period: CandlestickPeriod) {
    return loadCandlesticksForTarget(
      workspaceId,
      { kind: 'directory-stock', code },
      period,
    )
  }

  async function loadOlderCandlesticksForTarget(
    workspaceId: number,
    target: MarketReadTarget,
  ) {
    const current = candlestickSeries.value
    const targetKey = marketReadTargetKey(workspaceId, target)
    if (
      candlestickTargetKey !== targetKey ||
      !current?.hasMore ||
      !current.nextBefore ||
      candlestickLoadingOlder.value
    ) {
      return current
    }

    const requestVersion = candlestickRequestVersion
    candlestickLoadingOlder.value = true
    candlestickHistoryError.value = ''
    try {
      const older = await requestCandlestickPage(
        workspaceId,
        target,
        current.period,
        1000,
        current.nextBefore,
      )
      if (requestVersion !== candlestickRequestVersion) return older

      const merged = new Map(
        [...older.candlesticks, ...current.candlesticks].map((item) => [item.time, item]),
      )
      candlestickSeries.value = {
        ...current,
        retrievedAt: older.retrievedAt,
        nextBefore: older.nextBefore,
        hasMore: older.hasMore && older.candlesticks.length > 0,
        candlesticks: [...merged.values()].sort((left, right) =>
          left.time.localeCompare(right.time),
        ),
      }
      return candlestickSeries.value
    } catch (error) {
      if (requestVersion === candlestickRequestVersion) {
        candlestickHistoryError.value =
          error instanceof Error ? error.message : '更早的 K 线加载失败'
      }
      throw error
    } finally {
      if (requestVersion === candlestickRequestVersion) {
        candlestickLoadingOlder.value = false
      }
    }
  }

  function loadOlderCandlesticks(workspaceId: number, workspaceStockId: number) {
    return loadOlderCandlesticksForTarget(workspaceId, {
      kind: 'workspace-stock',
      workspaceStockId,
    })
  }

  function loadOlderMarketCandlesticks(workspaceId: number, code: string) {
    return loadOlderCandlesticksForTarget(workspaceId, { kind: 'directory-stock', code })
  }

  function clearCandlesticks() {
    candlestickRequestVersion += 1
    candlestickTargetKey = ''
    candlestickSeries.value = null
    candlestickError.value = ''
    candlestickHistoryError.value = ''
    candlestickLoading.value = false
    candlestickLoadingOlder.value = false
  }

  async function loadIntradayForTarget(
    workspaceId: number,
    target: MarketReadTarget,
    date?: string,
    silent = false,
  ) {
    const targetKey = marketReadTargetKey(workspaceId, target)
    const requestVersion = ++intradayRequestVersion
    if (intradayTargetKey !== targetKey) {
      intradayTargetKey = targetKey
      intradaySeries.value = null
    }
    if (!silent) intradayLoading.value = true
    intradayError.value = ''
    try {
      const result = target.kind === 'workspace-stock'
        ? (await getWorkspaceStockIntraday(workspaceId, target.workspaceStockId, date)).data.data
        : (
            await getWorkspaceMarketStockIntraday(
              workspaceId,
              target.code.trim().toUpperCase(),
              date,
            )
          ).data.data
      if (requestVersion === intradayRequestVersion) intradaySeries.value = result
      return result
    } catch (error) {
      if (requestVersion === intradayRequestVersion) {
        if (!silent) {
          intradayError.value = error instanceof Error ? error.message : '分时数据加载失败'
          intradaySeries.value = null
        }
      }
      throw error
    } finally {
      if (requestVersion === intradayRequestVersion && !silent) intradayLoading.value = false
    }
  }

  function loadIntraday(
    workspaceId: number,
    workspaceStockId: number,
    date?: string,
    silent = false,
  ) {
    return loadIntradayForTarget(
      workspaceId,
      { kind: 'workspace-stock', workspaceStockId },
      date,
      silent,
    )
  }

  function loadMarketIntraday(
    workspaceId: number,
    code: string,
    date?: string,
    silent = false,
  ) {
    return loadIntradayForTarget(
      workspaceId,
      { kind: 'directory-stock', code },
      date,
      silent,
    )
  }

  function clearIntraday() {
    intradayRequestVersion += 1
    intradayTargetKey = ''
    intradaySeries.value = null
    intradayLoading.value = false
    intradayError.value = ''
  }

  function clearSearch() {
    searchRequestVersion += 1
    searchResults.value = []
    searchLoading.value = false
    searchError.value = ''
  }

  function clearMarketDetail() {
    marketDetailRequestVersion += 1
    marketDetailTargetKey = ''
    marketDetail.value = null
    marketDetailLoading.value = false
    marketDetailError.value = ''
  }

  function keepResearchDraft(
    workspaceId: number,
    workspaceStockId: number,
    draft: ResearchDraft,
  ) {
    pendingResearchDraft.value = { workspaceId, workspaceStockId, draft }
  }

  function takeResearchDraft(workspaceId: number, workspaceStockId: number) {
    const pending = pendingResearchDraft.value
    if (
      !pending ||
      pending.workspaceId !== workspaceId ||
      pending.workspaceStockId !== workspaceStockId
    ) {
      return null
    }
    pendingResearchDraft.value = null
    return pending.draft
  }

  function clearRefreshWarning() {
    refreshWarning.value = ''
  }

  function clearListViews() {
    listRequestVersion += 1
    endedRequestVersion += 1
    listWorkspaceId = null
    endedWorkspaceId = null
    items.value = []
    endedItems.value = []
    listLoading.value = false
    listError.value = ''
  }

  function clearResearchView() {
    researchRequestVersion += 1
    researchTargetKey = ''
    researchDetail.value = null
    researchLoading.value = false
    researchError.value = ''
  }

  function clearAll() {
    clearListViews()
    clearResearchView()
    researchSubmitting.value = false
    pendingResearchDraft.value = null
    submitError.value = ''
    refreshWarning.value = ''
    submitLoading.value = false
    attentionUpdatingStockId.value = null
    attentionError.value = ''
    clearSearch()
    clearMarketDetail()
    clearCandlesticks()
    clearIntraday()
  }

  return {
    items,
    listLoading,
    listError,
    searchResults,
    searchLoading,
    searchError,
    marketDetail,
    marketDetailLoading,
    marketDetailError,
    submitLoading,
    submitError,
    refreshWarning,
    attentionUpdatingStockId,
    attentionError,
    endedItems,
    researchDetail,
    researchLoading,
    researchError,
    researchSubmitting,
    pendingResearchDraft,
    candlestickSeries,
    candlestickLoading,
    candlestickError,
    candlestickLoadingOlder,
    candlestickHistoryError,
    intradaySeries,
    intradayLoading,
    intradayError,
    loadList,
    search,
    loadMarketDetail,
    add,
    updateAttention,
    loadEnded,
    loadResearch,
    endResearch,
    createOpinion,
    withdrawOpinion,
    loadCandlesticks,
    loadMarketCandlesticks,
    loadOlderCandlesticks,
    loadOlderMarketCandlesticks,
    loadIntraday,
    loadMarketIntraday,
    clearCandlesticks,
    clearIntraday,
    clearSearch,
    clearMarketDetail,
    keepResearchDraft,
    takeResearchDraft,
    clearRefreshWarning,
    clearListViews,
    clearResearchView,
    clearAll,
  }
})
