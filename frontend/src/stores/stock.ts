import { ref } from 'vue'
import { defineStore } from 'pinia'
import {
  addWorkspaceStock,
  createWorkspaceStockOpinion,
  endWorkspaceStockResearch,
  getWorkspaceStockCandlesticks,
  getWorkspaceStockIntraday,
  getWorkspaceStockResearch,
  listEndedWorkspaceStocks,
  listWorkspaceStocks,
  searchStocks,
  updateWorkspaceStockAttention,
  withdrawWorkspaceStockOpinion,
  type AddWorkspaceStockRequestDTO,
  type AttentionLevel,
  type CandlestickPeriod,
  type CreateStockOpinionRequestDTO,
  type EndedWorkspaceStockResponseVO,
  type StockCandlestickSeriesVO,
  type StockIntradaySeriesVO,
  type StockSearchResponseVO,
  type WorkspaceStockListResponseVO,
  type WorkspaceStockResearchResponseVO,
} from '@/api/stock'

export const useStockStore = defineStore('stock', () => {
  const items = ref<WorkspaceStockListResponseVO[]>([])
  const listLoading = ref(false)
  const listError = ref('')
  const searchResults = ref<StockSearchResponseVO[]>([])
  const searchLoading = ref(false)
  const searchError = ref('')
  let searchRequestVersion = 0
  const submitLoading = ref(false)
  const submitError = ref('')
  const attentionUpdatingStockId = ref<number | null>(null)
  const attentionError = ref('')
  const endedItems = ref<EndedWorkspaceStockResponseVO[]>([])
  const researchDetail = ref<WorkspaceStockResearchResponseVO | null>(null)
  const researchLoading = ref(false)
  const researchError = ref('')
  const researchSubmitting = ref(false)
  const candlestickSeries = ref<StockCandlestickSeriesVO | null>(null)
  const candlestickLoading = ref(false)
  const candlestickError = ref('')
  const candlestickLoadingOlder = ref(false)
  const candlestickHistoryError = ref('')
  let candlestickRequestVersion = 0
  const intradaySeries = ref<StockIntradaySeriesVO | null>(null)
  const intradayLoading = ref(false)
  const intradayError = ref('')
  let intradayRequestVersion = 0

  async function loadList(workspaceId: number) {
    listLoading.value = true
    listError.value = ''
    try {
      const result = (await listWorkspaceStocks(workspaceId)).data.data
      items.value = result
      return result
    } catch (error) {
      listError.value = error instanceof Error ? error.message : '共享自选加载失败'
      throw error
    } finally {
      listLoading.value = false
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

  async function add(workspaceId: number, request: AddWorkspaceStockRequestDTO) {
    submitLoading.value = true
    submitError.value = ''
    try {
      const result = (await addWorkspaceStock(workspaceId, request)).data.data
      await loadList(workspaceId)
      return result
    } catch (error) {
      submitError.value = error instanceof Error ? error.message : '添加股票失败'
      throw error
    } finally {
      submitLoading.value = false
    }
  }

  async function updateAttention(
    workspaceId: number,
    workspaceStockId: number,
    attentionLevel: AttentionLevel,
  ) {
    attentionUpdatingStockId.value = workspaceStockId
    attentionError.value = ''
    try {
      const result = (
        await updateWorkspaceStockAttention(workspaceId, workspaceStockId, attentionLevel)
      ).data.data
      await loadList(workspaceId)
      return result
    } catch (error) {
      attentionError.value = error instanceof Error ? error.message : '关注级别更新失败'
      throw error
    } finally {
      attentionUpdatingStockId.value = null
    }
  }

  async function loadEnded(workspaceId: number) {
    const result = (await listEndedWorkspaceStocks(workspaceId)).data.data
    endedItems.value = result
    return result
  }

  async function loadResearch(workspaceId: number, workspaceStockId: number) {
    researchLoading.value = true
    researchError.value = ''
    try {
      const result = (await getWorkspaceStockResearch(workspaceId, workspaceStockId)).data.data
      researchDetail.value = result
      return result
    } catch (error) {
      researchError.value = error instanceof Error ? error.message : '研究记录加载失败'
      throw error
    } finally {
      researchLoading.value = false
    }
  }

  async function endResearch(workspaceId: number, workspaceStockId: number) {
    researchSubmitting.value = true
    researchError.value = ''
    try {
      await endWorkspaceStockResearch(workspaceId, workspaceStockId)
      await Promise.all([
        loadResearch(workspaceId, workspaceStockId),
        loadList(workspaceId),
        loadEnded(workspaceId),
      ])
    } catch (error) {
      researchError.value = error instanceof Error ? error.message : '结束关注失败'
      throw error
    } finally {
      researchSubmitting.value = false
    }
  }

  async function createOpinion(
    workspaceId: number,
    workspaceStockId: number,
    request: CreateStockOpinionRequestDTO,
  ) {
    researchSubmitting.value = true
    researchError.value = ''
    try {
      const result = (
        await createWorkspaceStockOpinion(workspaceId, workspaceStockId, request)
      ).data.data
      await loadResearch(workspaceId, workspaceStockId)
      return result
    } catch (error) {
      researchError.value = error instanceof Error ? error.message : '观点发表失败'
      throw error
    } finally {
      researchSubmitting.value = false
    }
  }

  async function withdrawOpinion(
    workspaceId: number,
    workspaceStockId: number,
    opinionId: number,
  ) {
    researchSubmitting.value = true
    researchError.value = ''
    try {
      await withdrawWorkspaceStockOpinion(workspaceId, workspaceStockId, opinionId)
      await Promise.all([
        loadResearch(workspaceId, workspaceStockId),
        loadList(workspaceId),
        loadEnded(workspaceId),
      ])
    } catch (error) {
      researchError.value = error instanceof Error ? error.message : '观点撤回失败'
      throw error
    } finally {
      researchSubmitting.value = false
    }
  }

  async function loadCandlesticks(
    workspaceId: number,
    workspaceStockId: number,
    period: CandlestickPeriod,
  ) {
    const requestVersion = ++candlestickRequestVersion
    candlestickLoading.value = true
    candlestickError.value = ''
    candlestickHistoryError.value = ''
    try {
      const result = (
        await getWorkspaceStockCandlesticks(workspaceId, workspaceStockId, period)
      ).data.data
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

  async function loadOlderCandlesticks(workspaceId: number, workspaceStockId: number) {
    const current = candlestickSeries.value
    if (!current?.hasMore || !current.nextBefore || candlestickLoadingOlder.value) return current

    const requestVersion = candlestickRequestVersion
    candlestickLoadingOlder.value = true
    candlestickHistoryError.value = ''
    try {
      const older = (
        await getWorkspaceStockCandlesticks(
          workspaceId,
          workspaceStockId,
          current.period,
          1000,
          current.nextBefore,
        )
      ).data.data
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

  function clearCandlesticks() {
    candlestickRequestVersion += 1
    candlestickSeries.value = null
    candlestickError.value = ''
    candlestickHistoryError.value = ''
    candlestickLoading.value = false
    candlestickLoadingOlder.value = false
  }

  async function loadIntraday(
    workspaceId: number,
    workspaceStockId: number,
    date?: string,
    silent = false,
  ) {
    const requestVersion = ++intradayRequestVersion
    if (!silent) intradayLoading.value = true
    intradayError.value = ''
    try {
      const result = (
        await getWorkspaceStockIntraday(workspaceId, workspaceStockId, date)
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

  function clearIntraday() {
    intradayRequestVersion += 1
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

  function clearAll() {
    items.value = []
    endedItems.value = []
    researchDetail.value = null
    researchError.value = ''
    researchLoading.value = false
    researchSubmitting.value = false
    listError.value = ''
    submitError.value = ''
    listLoading.value = false
    submitLoading.value = false
    clearSearch()
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
    submitLoading,
    submitError,
    attentionUpdatingStockId,
    attentionError,
    endedItems,
    researchDetail,
    researchLoading,
    researchError,
    researchSubmitting,
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
    add,
    updateAttention,
    loadEnded,
    loadResearch,
    endResearch,
    createOpinion,
    withdrawOpinion,
    loadCandlesticks,
    loadOlderCandlesticks,
    loadIntraday,
    clearCandlesticks,
    clearIntraday,
    clearSearch,
    clearAll,
  }
})
