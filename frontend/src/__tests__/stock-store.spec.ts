import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosHeaders, type AxiosResponse } from 'axios'
import {
  addWorkspaceStock,
  getWorkspaceStockCandlesticks,
  listWorkspaceStocks,
  searchStocks,
} from '@/api/stock'
import type { ApiResult } from '@/api/auth'
import type {
  AddWorkspaceStockResponseVO,
  StockCandlestickSeriesVO,
  StockSearchResponseVO,
  WorkspaceStockListResponseVO,
} from '@/api/stock'
import { useStockStore } from '@/stores/stock'

vi.mock('@/api/stock')

function response<T>(data: T): AxiosResponse<T> {
  return {
    data,
    status: 200,
    statusText: 'OK',
    headers: {},
    config: { headers: new AxiosHeaders() },
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

const searchResult: StockSearchResponseVO = {
  code: '600519.SH',
  name: '贵州茅台',
  exchange: 'SSE',
  currency: 'CNY',
  listingStatus: 'LISTED',
  price: 1488.88,
  quoteTime: '2026-09-04T15:00:00',
  source: 'LOCAL_MOCK',
  simulated: true,
}

const listItem: WorkspaceStockListResponseVO = {
  workspaceStockId: 21,
  researchCycleId: 31,
  code: '600519.SH',
  name: '贵州茅台',
  exchange: 'SSE',
  currency: 'CNY',
  listingStatus: 'LISTED',
  attentionLevel: 'NORMAL',
  startedByUserId: 9,
  startedByNickname: '研究成员',
  startedAt: '2026-09-07T14:00:00',
  startPrice: 1488.88,
  quoteTime: '2026-09-04T15:00:00',
  quoteSource: 'LOCAL_MOCK',
  simulated: true,
  initialOpinionId: 41,
  authorUserId: 9,
  authorNickname: '研究成员',
  stance: 'BULLISH',
  reason: '品牌优势仍然稳定',
  riskNote: '估值可能继续收缩',
  horizon: 'MEDIUM',
  targetPrice: 1680,
  stopLossPrice: 1350,
  opinionCreatedAt: '2026-09-07T14:00:00',
}

const candlestickSeries: StockCandlestickSeriesVO = {
  code: '600519.SH',
  name: '贵州茅台',
  currency: 'CNY',
  period: 'day',
  adjustment: 'none',
  source: 'LOCAL_MOCK',
  simulated: true,
  retrievedAt: '2026-09-09T15:00:00',
  nextBefore: '2026-09-04T07:00:00Z',
  hasMore: true,
  candlesticks: [
    {
      time: '2026-09-04T15:00:00',
      open: 1480,
      high: 1500,
      low: 1470,
      close: 1488.88,
      volume: 123456,
      turnover: 184000000,
    },
  ],
}

describe('stock store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads the current workspace shared list', async () => {
    vi.mocked(listWorkspaceStocks).mockResolvedValue(
      response<ApiResult<WorkspaceStockListResponseVO[]>>({
        code: 'SUCCESS',
        message: '成功',
        data: [listItem],
      }),
    )

    const store = useStockStore()
    await store.loadList(12)

    expect(listWorkspaceStocks).toHaveBeenCalledWith(12)
    expect(store.items).toEqual([listItem])
    expect(store.listError).toBe('')
  })

  it('trims the keyword and keeps trusted search response fields', async () => {
    vi.mocked(searchStocks).mockResolvedValue(
      response<ApiResult<StockSearchResponseVO[]>>({
        code: 'SUCCESS',
        message: '成功',
        data: [searchResult],
      }),
    )

    const store = useStockStore()
    await store.search('  茅台  ')

    expect(searchStocks).toHaveBeenCalledWith('茅台')
    expect(store.searchResults[0]?.source).toBe('LOCAL_MOCK')
    expect(store.searchResults[0]?.simulated).toBe(true)
  })

  it('keeps the newest search when an older request finishes later', async () => {
    const olderRequest = deferred<AxiosResponse<ApiResult<StockSearchResponseVO[]>>>()
    const newerRequest = deferred<AxiosResponse<ApiResult<StockSearchResponseVO[]>>>()
    const newerResult = { ...searchResult, code: '00700.HK', name: '腾讯控股' }
    vi.mocked(searchStocks)
      .mockReturnValueOnce(olderRequest.promise)
      .mockReturnValueOnce(newerRequest.promise)

    const store = useStockStore()
    const olderSearch = store.search('茅台')
    const newerSearch = store.search('腾讯')
    newerRequest.resolve(response({ code: 'SUCCESS', message: '成功', data: [newerResult] }))
    await newerSearch
    olderRequest.resolve(response({ code: 'SUCCESS', message: '成功', data: [searchResult] }))
    await olderSearch

    expect(store.searchResults).toEqual([newerResult])
    expect(store.searchLoading).toBe(false)
  })

  it('submits only the allowed opinion input and refreshes the shared list', async () => {
    const request = {
      code: '600519.SH',
      stance: 'BULLISH' as const,
      reason: '品牌优势仍然稳定',
      riskNote: '估值可能继续收缩',
      horizon: 'MEDIUM' as const,
      targetPrice: 1680,
      stopLossPrice: 1350,
    }
    vi.mocked(addWorkspaceStock).mockResolvedValue(
      response<ApiResult<AddWorkspaceStockResponseVO>>({
        code: 'SUCCESS',
        message: '成功',
        data: { workspaceStockId: 21, researchCycleId: 31, alreadyActive: false },
      }),
    )
    vi.mocked(listWorkspaceStocks).mockResolvedValue(
      response<ApiResult<WorkspaceStockListResponseVO[]>>({
        code: 'SUCCESS',
        message: '成功',
        data: [listItem],
      }),
    )

    const store = useStockStore()
    const result = await store.add(12, request)

    expect(addWorkspaceStock).toHaveBeenCalledWith(12, request)
    expect(listWorkspaceStocks).toHaveBeenCalledWith(12)
    expect(result.alreadyActive).toBe(false)
    expect(store.items).toEqual([listItem])
  })

  it('loads candlesticks only after an explicit stock and period request', async () => {
    vi.mocked(getWorkspaceStockCandlesticks).mockResolvedValue(
      response<ApiResult<StockCandlestickSeriesVO>>({
        code: 'SUCCESS',
        message: '成功',
        data: candlestickSeries,
      }),
    )

    const store = useStockStore()
    await store.loadCandlesticks(12, 21, 'day')

    expect(getWorkspaceStockCandlesticks).toHaveBeenCalledWith(12, 21, 'day')
    expect(store.candlestickSeries).toEqual(candlestickSeries)
    expect(store.candlestickError).toBe('')
  })

  it('prepends older candlesticks without replacing the visible series', async () => {
    const older = {
      ...candlestickSeries,
      nextBefore: '2026-09-03T07:00:00Z',
      hasMore: false,
      candlesticks: [
        { ...candlestickSeries.candlesticks[0]!, time: '2026-09-03T15:00:00' },
      ],
    }
    vi.mocked(getWorkspaceStockCandlesticks)
      .mockResolvedValueOnce(response({ code: 'SUCCESS', message: '成功', data: candlestickSeries }))
      .mockResolvedValueOnce(response({ code: 'SUCCESS', message: '成功', data: older }))

    const store = useStockStore()
    await store.loadCandlesticks(12, 21, 'day')
    await store.loadOlderCandlesticks(12, 21)

    expect(getWorkspaceStockCandlesticks).toHaveBeenLastCalledWith(
      12,
      21,
      'day',
      1000,
      candlestickSeries.nextBefore,
    )
    expect(store.candlestickSeries?.candlesticks.map((item) => item.time)).toEqual([
      '2026-09-03T15:00:00',
      '2026-09-04T15:00:00',
    ])
    expect(store.candlestickSeries?.hasMore).toBe(false)
  })
})
