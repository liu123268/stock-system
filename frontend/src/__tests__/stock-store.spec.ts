import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosHeaders, type AxiosResponse } from 'axios'
import { addWorkspaceStock, listWorkspaceStocks, searchStocks } from '@/api/stock'
import type { ApiResult } from '@/api/auth'
import type {
  AddWorkspaceStockResponseVO,
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
})
