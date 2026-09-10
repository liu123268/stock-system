import { ref } from 'vue'
import { defineStore } from 'pinia'
import {
  addWorkspaceStock,
  getWorkspaceStockCandlesticks,
  getWorkspaceStockIntraday,
  listWorkspaceStocks,
  searchStocks,
  type AddWorkspaceStockRequestDTO,
  type CandlestickPeriod,
  type StockCandlestickSeriesVO,
  type StockIntradaySeriesVO,
  type StockSearchResponseVO,
  type WorkspaceStockListResponseVO,
} from '@/api/stock'

export const useStockStore = defineStore('stock', () => {
  const items = ref<WorkspaceStockListResponseVO[]>([])
  const listLoading = ref(false)
  const listError = ref('')
  const searchResults = ref<StockSearchResponseVO[]>([])
  const searchLoading = ref(false)
  const searchError = ref('')
  const submitLoading = ref(false)
  const submitError = ref('')
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
    searchLoading.value = true
    searchError.value = ''
    try {
      const result = (await searchStocks(keyword.trim())).data.data
      searchResults.value = result
      return result
    } catch (error) {
      searchError.value = error instanceof Error ? error.message : '股票搜索失败'
      throw error
    } finally {
      searchLoading.value = false
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
    searchResults.value = []
    searchError.value = ''
  }

  function clearAll() {
    items.value = []
    searchResults.value = []
    listError.value = ''
    searchError.value = ''
    submitError.value = ''
    listLoading.value = false
    searchLoading.value = false
    submitLoading.value = false
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
    loadCandlesticks,
    loadOlderCandlesticks,
    loadIntraday,
    clearCandlesticks,
    clearIntraday,
    clearSearch,
    clearAll,
  }
})
