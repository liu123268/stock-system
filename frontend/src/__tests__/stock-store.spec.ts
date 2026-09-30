import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosHeaders, type AxiosResponse } from 'axios'
import {
  addWorkspaceStock,
  createWorkspaceStockOpinion,
  endWorkspaceStockResearch,
  getWorkspaceMarketStockCandlesticks,
  getWorkspaceMarketStockIntraday,
  getWorkspaceStockCandlesticks,
  getWorkspaceStockMarketDetail,
  getWorkspaceStockResearch,
  listEndedWorkspaceStocks,
  listWorkspaceStocks,
  searchStocks,
  updateWorkspaceStockAttention,
} from '@/api/stock'
import type { ApiResult } from '@/api/auth'
import type {
  AddWorkspaceStockResponseVO,
  EndedWorkspaceStockResponseVO,
  StockCandlestickSeriesVO,
  StockIntradaySeriesVO,
  StockOpinionResponseVO,
  StockSearchResponseVO,
  WorkspaceStockListResponseVO,
  WorkspaceStockMarketDetailResponseVO,
  WorkspaceStockResearchResponseVO,
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
  latestOpinionId: 42,
  latestOpinionType: 'UPDATE',
  latestOpinionAuthorUserId: 10,
  latestOpinionAuthorNickname: '另一位成员',
  latestOpinionStance: 'NEUTRAL',
  latestOpinionCreatedAt: '2026-09-08T10:30:00',
  opinionCount: 2,
  participantCount: 2,
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

const marketDetail: WorkspaceStockMarketDetailResponseVO = {
  ...searchResult,
  trackingState: 'NOT_TRACKED',
  workspaceStockId: null,
  activeResearchCycleId: null,
}

const intradaySeries: StockIntradaySeriesVO = {
  code: '600519.SH',
  name: '贵州茅台',
  currency: 'CNY',
  tradeDate: '2026-09-21',
  marketTimeZone: 'Asia/Shanghai',
  referencePrice: 1480,
  source: 'LOCAL_MOCK',
  simulated: true,
  retrievedAt: '2026-09-21T10:30:00',
  updating: true,
  tradingSessions: [
    { start: '2026-09-21T09:30:00', end: '2026-09-21T11:30:00' },
    { start: '2026-09-21T13:00:00', end: '2026-09-21T15:00:00' },
  ],
  points: [
    {
      time: '2026-09-21T09:30:00',
      price: 1488.88,
      averagePrice: 1485,
      volume: 1200,
      turnover: 1_786_656,
    },
  ],
}

function researchDetail(
  workspaceStockId: number,
  name: string,
): WorkspaceStockResearchResponseVO {
  return {
    workspaceStockId,
    code: workspaceStockId === 21 ? '600519.SH' : '00700.HK',
    name,
    exchange: workspaceStockId === 21 ? 'SSE' : 'HKEX',
    currency: workspaceStockId === 21 ? 'CNY' : 'HKD',
    listingStatus: 'LISTED',
    currentCycle: null,
    authors: [],
  }
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
    vi.mocked(listEndedWorkspaceStocks).mockResolvedValue(
      response({ code: 'SUCCESS', message: '成功', data: [] }),
    )

    const store = useStockStore()
    await store.loadList(12)

    expect(listWorkspaceStocks).toHaveBeenCalledWith(12)
    expect(store.items).toEqual([listItem])
    expect(store.listError).toBe('')
  })

  it('keeps the newest workspace list when an older request finishes later', async () => {
    const olderRequest = deferred<AxiosResponse<ApiResult<WorkspaceStockListResponseVO[]>>>()
    const newerRequest = deferred<AxiosResponse<ApiResult<WorkspaceStockListResponseVO[]>>>()
    const newerItem = { ...listItem, workspaceStockId: 22, code: '00700.HK', name: '腾讯控股' }
    vi.mocked(listWorkspaceStocks)
      .mockReturnValueOnce(olderRequest.promise)
      .mockReturnValueOnce(newerRequest.promise)

    const store = useStockStore()
    const olderLoad = store.loadList(12)
    const newerLoad = store.loadList(13)
    newerRequest.resolve(response({ code: 'SUCCESS', message: '成功', data: [newerItem] }))
    await newerLoad
    olderRequest.resolve(response({ code: 'SUCCESS', message: '成功', data: [listItem] }))
    await olderLoad

    expect(store.items).toEqual([newerItem])
    expect(store.listLoading).toBe(false)
  })

  it('keeps the newest ended list when an older workspace request finishes later', async () => {
    const olderRequest = deferred<AxiosResponse<ApiResult<EndedWorkspaceStockResponseVO[]>>>()
    const newerRequest = deferred<AxiosResponse<ApiResult<EndedWorkspaceStockResponseVO[]>>>()
    const newerItem: EndedWorkspaceStockResponseVO = {
      workspaceStockId: 22,
      code: '00700.HK',
      name: '腾讯控股',
      exchange: 'HKEX',
      currency: 'HKD' as const,
      researchCycleId: 32,
      status: 'ENDED' as const,
      startedAt: '2026-09-19T10:00:00',
      endedAt: '2026-09-20T10:00:00',
    }
    vi.mocked(listEndedWorkspaceStocks)
      .mockReturnValueOnce(olderRequest.promise)
      .mockReturnValueOnce(newerRequest.promise)

    const store = useStockStore()
    const olderLoad = store.loadEnded(12)
    const newerLoad = store.loadEnded(13)
    newerRequest.resolve(response({ code: 'SUCCESS', message: '成功', data: [newerItem] }))
    await newerLoad
    olderRequest.resolve(response({ code: 'SUCCESS', message: '成功', data: [] }))
    await olderLoad

    expect(store.endedItems).toEqual([newerItem])
  })

  it('keeps the newest research detail and invalidates responses after clearing state', async () => {
    const olderRequest = deferred<AxiosResponse<ApiResult<WorkspaceStockResearchResponseVO>>>()
    const newerRequest = deferred<AxiosResponse<ApiResult<WorkspaceStockResearchResponseVO>>>()
    vi.mocked(getWorkspaceStockResearch)
      .mockReturnValueOnce(olderRequest.promise)
      .mockReturnValueOnce(newerRequest.promise)

    const store = useStockStore()
    const olderLoad = store.loadResearch(12, 21)
    const newerLoad = store.loadResearch(12, 22)
    newerRequest.resolve(
      response({ code: 'SUCCESS', message: '成功', data: researchDetail(22, '腾讯控股') }),
    )
    await newerLoad
    olderRequest.resolve(
      response({ code: 'SUCCESS', message: '成功', data: researchDetail(21, '贵州茅台') }),
    )
    await olderLoad
    expect(store.researchDetail?.workspaceStockId).toBe(22)

    const pendingRequest = deferred<AxiosResponse<ApiResult<WorkspaceStockResearchResponseVO>>>()
    vi.mocked(getWorkspaceStockResearch).mockReturnValueOnce(pendingRequest.promise)
    const pendingLoad = store.loadResearch(12, 21)
    store.clearAll()
    pendingRequest.resolve(
      response({ code: 'SUCCESS', message: '成功', data: researchDetail(21, '贵州茅台') }),
    )
    await pendingLoad

    expect(store.researchDetail).toBeNull()
    expect(store.researchLoading).toBe(false)
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

  it('loads an unfollowed stock market preview with a normalized standard code', async () => {
    vi.mocked(getWorkspaceStockMarketDetail).mockResolvedValue(
      response({ code: 'SUCCESS', message: '成功', data: marketDetail }),
    )

    const store = useStockStore()
    await store.loadMarketDetail(12, ' 600519.sh ')

    expect(getWorkspaceStockMarketDetail).toHaveBeenCalledWith(12, '600519.SH')
    expect(store.marketDetail).toEqual(marketDetail)
    expect(store.marketDetail?.trackingState).toBe('NOT_TRACKED')
    expect(store.marketDetailError).toBe('')
  })

  it('submits only the allowed opinion input and refreshes the shared list', async () => {
    const request = {
      code: '600519.SH',
      stance: 'BULLISH' as const,
      reason: '品牌优势仍然稳定',
      observationNote: '估值可能继续收缩',
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
    vi.mocked(listEndedWorkspaceStocks).mockResolvedValue(
      response({ code: 'SUCCESS', message: '成功', data: [] }),
    )

    const store = useStockStore()
    const result = await store.add(12, request)

    expect(addWorkspaceStock).toHaveBeenCalledWith(12, request)
    expect(listWorkspaceStocks).toHaveBeenCalledWith(12)
    expect(result.alreadyActive).toBe(false)
    expect(store.items).toEqual([listItem])
  })

  it('reports a refresh warning without turning a saved add into a submission failure', async () => {
    vi.mocked(addWorkspaceStock).mockResolvedValue(
      response({
        code: 'SUCCESS',
        message: '成功',
        data: { workspaceStockId: 21, researchCycleId: 31, alreadyActive: false },
      }),
    )
    vi.mocked(listWorkspaceStocks).mockRejectedValue(new Error('列表暂时不可用'))
    vi.mocked(listEndedWorkspaceStocks).mockResolvedValue(
      response({ code: 'SUCCESS', message: '成功', data: [] }),
    )

    const store = useStockStore()
    const result = await store.add(12, {
      code: '600519.SH',
      stance: 'BULLISH',
      reason: '品牌优势仍然稳定',
      observationNote: null,
      horizon: 'MEDIUM',
      targetPrice: null,
      stopLossPrice: null,
    })

    expect(result.workspaceStockId).toBe(21)
    expect(store.submitError).toBe('')
    expect(store.refreshWarning).toContain('研究已经保存')
  })

  it('reports a refresh warning without turning a saved opinion into a publish failure', async () => {
    const savedOpinion: StockOpinionResponseVO = {
      id: 51,
      authorUserId: 9,
      authorNickname: '研究成员',
      type: 'UPDATE',
      stance: 'BULLISH',
      reason: '盈利能力继续改善',
      observationNote: null,
      horizon: 'MEDIUM',
      targetPrice: null,
      stopLossPrice: null,
      createdAt: '2026-09-20T00:00:00',
      withdrawn: false,
      withdrawnAt: null,
      canWithdraw: true,
    }
    vi.mocked(createWorkspaceStockOpinion).mockResolvedValue(
      response({ code: 'SUCCESS', message: '成功', data: savedOpinion }),
    )
    vi.mocked(getWorkspaceStockResearch).mockRejectedValue(new Error('详情暂时不可用'))

    const store = useStockStore()
    const result = await store.createOpinion(12, 21, {
      researchCycleId: 31,
      type: 'UPDATE',
      stance: 'BULLISH',
      reason: '盈利能力继续改善',
      observationNote: null,
      horizon: 'MEDIUM',
      targetPrice: null,
      stopLossPrice: null,
    })

    expect(result).toEqual(savedOpinion)
    expect(store.researchError).toBe('详情暂时不可用')
    expect(store.refreshWarning).toContain('观点已经保存')
  })

  it('carries a duplicate-add draft only into its matching research page', () => {
    const store = useStockStore()
    const draft = {
      stance: 'BULLISH' as const,
      reason: '保留这段尚未发表的判断',
      observationNote: null,
      horizon: 'MEDIUM' as const,
      targetPrice: null,
      stopLossPrice: null,
    }

    store.keepResearchDraft(12, 21, draft)

    expect(store.takeResearchDraft(12, 22)).toBeNull()
    expect(store.takeResearchDraft(12, 21)).toEqual(draft)
    expect(store.takeResearchDraft(12, 21)).toBeNull()
  })

  it('switches the attention level and reloads the server-sorted list', async () => {
    const focusedItem = { ...listItem, attentionLevel: 'FOCUS' as const }
    vi.mocked(updateWorkspaceStockAttention).mockResolvedValue(
      response({
        code: 'SUCCESS',
        message: '成功',
        data: { workspaceStockId: 21, researchCycleId: 31, attentionLevel: 'FOCUS' as const },
      }),
    )
    vi.mocked(listWorkspaceStocks).mockResolvedValue(
      response<ApiResult<WorkspaceStockListResponseVO[]>>({
        code: 'SUCCESS',
        message: '成功',
        data: [focusedItem],
      }),
    )

    const store = useStockStore()
    const result = await store.updateAttention(12, 21, 31, 'FOCUS')

    expect(updateWorkspaceStockAttention).toHaveBeenCalledWith(12, 21, 31, 'FOCUS')
    expect(listWorkspaceStocks).toHaveBeenCalledWith(12)
    expect(result.attentionLevel).toBe('FOCUS')
    expect(store.items).toEqual([focusedItem])
    expect(store.attentionUpdatingStockId).toBeNull()
  })

  it('ends a cycle and refreshes active, ended, and current research state', async () => {
    const detail: WorkspaceStockResearchResponseVO = {
      workspaceStockId: 21,
      code: '600519.SH',
      name: '贵州茅台',
      exchange: 'SSE',
      currency: 'CNY',
      listingStatus: 'LISTED',
      currentCycle: null,
      authors: [],
    }
    vi.mocked(endWorkspaceStockResearch).mockResolvedValue(
      response({ code: 'SUCCESS', message: '成功', data: null }),
    )
    vi.mocked(getWorkspaceStockResearch).mockResolvedValue(
      response({ code: 'SUCCESS', message: '成功', data: detail }),
    )
    vi.mocked(listWorkspaceStocks).mockResolvedValue(
      response({ code: 'SUCCESS', message: '成功', data: [] }),
    )
    vi.mocked(listEndedWorkspaceStocks).mockResolvedValue(
      response({
        code: 'SUCCESS',
        message: '成功',
        data: [
          {
            workspaceStockId: 21,
            code: '600519.SH',
            name: '贵州茅台',
            exchange: 'SSE',
            currency: 'CNY',
            researchCycleId: 31,
            status: 'ENDED',
            startedAt: '2026-09-07T14:00:00',
            endedAt: '2026-09-18T14:00:00',
          },
        ],
      }),
    )

    const store = useStockStore()
    await store.endResearch(12, 21, 31)

    expect(endWorkspaceStockResearch).toHaveBeenCalledWith(12, 21, 31)
    expect(store.items).toEqual([])
    expect(store.endedItems).toHaveLength(1)
    expect(store.researchDetail?.currentCycle).toBeNull()
    expect(store.researchSubmitting).toBe(false)
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

  it('uses code-based market endpoints for an unfollowed stock and paginates its K line', async () => {
    const older = {
      ...candlestickSeries,
      nextBefore: null,
      hasMore: false,
      candlesticks: [
        { ...candlestickSeries.candlesticks[0]!, time: '2026-09-03T15:00:00' },
      ],
    }
    vi.mocked(getWorkspaceMarketStockCandlesticks)
      .mockResolvedValueOnce(response({ code: 'SUCCESS', message: '成功', data: candlestickSeries }))
      .mockResolvedValueOnce(response({ code: 'SUCCESS', message: '成功', data: older }))

    const store = useStockStore()
    await store.loadMarketCandlesticks(12, ' 600519.sh ', 'day')
    await store.loadOlderMarketCandlesticks(12, '600519.SH')

    expect(getWorkspaceMarketStockCandlesticks).toHaveBeenNthCalledWith(
      1,
      12,
      '600519.SH',
      'day',
    )
    expect(getWorkspaceMarketStockCandlesticks).toHaveBeenNthCalledWith(
      2,
      12,
      '600519.SH',
      'day',
      1000,
      candlestickSeries.nextBefore,
    )
    expect(getWorkspaceStockCandlesticks).not.toHaveBeenCalled()
    expect(store.candlestickSeries?.candlesticks.map((item) => item.time)).toEqual([
      '2026-09-03T15:00:00',
      '2026-09-04T15:00:00',
    ])
  })

  it('does not reload an old workspace after its delayed attention write finishes off-page', async () => {
    const pending =
      deferred<
        AxiosResponse<
          ApiResult<{ workspaceStockId: number; researchCycleId: number; attentionLevel: 'FOCUS' }>
        >
      >()
    vi.mocked(updateWorkspaceStockAttention).mockReturnValue(pending.promise)
    const store = useStockStore()
    const write = store.updateAttention(12, 21, 31, 'FOCUS')
    store.clearListViews()
    pending.resolve(
      response({
        code: 'SUCCESS',
        message: '成功',
        data: { workspaceStockId: 21, researchCycleId: 31, attentionLevel: 'FOCUS' },
      }),
    )
    await write
    expect(listWorkspaceStocks).not.toHaveBeenCalled()
    expect(store.attentionUpdatingStockId).toBeNull()
    expect(store.refreshWarning).toBe('')
  })

  it('does not show an old attention failure in a new list view', async () => {
    const pending =
      deferred<
        AxiosResponse<
          ApiResult<{ workspaceStockId: number; researchCycleId: number; attentionLevel: 'FOCUS' }>
        >
      >()
    vi.mocked(updateWorkspaceStockAttention).mockReturnValue(pending.promise)
    const store = useStockStore()
    const write = store.updateAttention(12, 21, 31, 'FOCUS')
    store.clearListViews()
    pending.reject(new Error('旧空间写入失败'))
    await expect(write).rejects.toThrow('旧空间写入失败')
    expect(store.attentionError).toBe('')
    expect(store.attentionUpdatingStockId).toBeNull()
  })

  it('uses the code-based intraday endpoint without requiring a workspace stock id', async () => {
    vi.mocked(getWorkspaceMarketStockIntraday).mockResolvedValue(
      response({ code: 'SUCCESS', message: '成功', data: intradaySeries }),
    )

    const store = useStockStore()
    await store.loadMarketIntraday(12, ' 600519.sh ')

    expect(getWorkspaceMarketStockIntraday).toHaveBeenCalledWith(12, '600519.SH', undefined)
    expect(store.intradaySeries).toEqual(intradaySeries)
    expect(store.intradayError).toBe('')
  })
})
