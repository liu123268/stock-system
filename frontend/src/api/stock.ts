import { http } from './http'
import type { ApiResult } from './auth'

export type StockStance = 'BULLISH' | 'NEUTRAL' | 'CAUTIOUS'
export type OpinionHorizon = 'SHORT' | 'MEDIUM' | 'LONG'
export type CandlestickPeriod =
  | '1m'
  | '5m'
  | '15m'
  | '30m'
  | '1h'
  | 'day'
  | 'week'
  | 'month'
  | 'year'

export interface StockSearchResponseVO {
  code: string
  name: string
  exchange: 'SSE' | 'SZSE' | 'BSE' | 'HKEX'
  currency: 'CNY' | 'HKD'
  listingStatus: 'LISTED' | 'SUSPENDED' | 'DELISTED' | 'UNKNOWN'
  price: number | null
  quoteTime: string | null
  source: string
  simulated: boolean
}

export interface AddWorkspaceStockRequestDTO {
  code: string
  stance: StockStance
  reason: string
  riskNote: string
  horizon: OpinionHorizon
  targetPrice: number | null
  stopLossPrice: number | null
}

export interface AddWorkspaceStockResponseVO {
  workspaceStockId: number
  researchCycleId: number
  alreadyActive: boolean
}

export interface WorkspaceStockListResponseVO {
  workspaceStockId: number
  researchCycleId: number
  code: string
  name: string
  exchange: 'SSE' | 'SZSE' | 'BSE' | 'HKEX'
  currency: 'CNY' | 'HKD'
  listingStatus: 'LISTED' | 'SUSPENDED' | 'DELISTED' | 'UNKNOWN'
  attentionLevel: 'NORMAL' | 'FOCUS'
  startedByUserId: number
  startedByNickname: string
  startedAt: string
  startPrice: number
  quoteTime: string
  quoteSource: string
  simulated: boolean
  initialOpinionId: number
  authorUserId: number
  authorNickname: string
  stance: StockStance
  reason: string
  riskNote: string
  horizon: OpinionHorizon
  targetPrice: number | null
  stopLossPrice: number | null
  opinionCreatedAt: string
}

export interface StockCandlestickVO {
  time: string
  open: number
  high: number
  low: number
  close: number
  volume: number
  turnover: number
}

export interface StockCandlestickSeriesVO {
  code: string
  name: string
  currency: string
  period: CandlestickPeriod
  adjustment: 'none'
  source: string
  simulated: boolean
  retrievedAt: string
  nextBefore: string | null
  hasMore: boolean
  candlesticks: StockCandlestickVO[]
}

export interface StockIntradayPointVO {
  time: string
  price: number
  averagePrice: number
  volume: number
  turnover: number
}

export interface StockTradingSessionVO {
  start: string
  end: string
}

export interface StockIntradaySeriesVO {
  code: string
  name: string
  currency: string
  tradeDate: string
  marketTimeZone: string
  tradingSessions: StockTradingSessionVO[]
  referencePrice: number
  updating: boolean
  source: string
  simulated: boolean
  retrievedAt: string
  points: StockIntradayPointVO[]
}

export function searchStocks(keyword: string) {
  return http.get<ApiResult<StockSearchResponseVO[]>>('/stocks/search', {
    params: { keyword },
  })
}

export function listWorkspaceStocks(workspaceId: number) {
  return http.get<ApiResult<WorkspaceStockListResponseVO[]>>(`/workspaces/${workspaceId}/stocks`)
}

export function addWorkspaceStock(workspaceId: number, request: AddWorkspaceStockRequestDTO) {
  return http.post<ApiResult<AddWorkspaceStockResponseVO>>(
    `/workspaces/${workspaceId}/stocks`,
    request,
  )
}

export function getWorkspaceStockCandlesticks(
  workspaceId: number,
  workspaceStockId: number,
  period: CandlestickPeriod,
  count = 1000,
  before?: string,
) {
  return http.get<ApiResult<StockCandlestickSeriesVO>>(
    `/workspaces/${workspaceId}/stocks/${workspaceStockId}/candlesticks`,
    { params: { period, count, ...(before ? { before } : {}) } },
  )
}

export function getWorkspaceStockIntraday(
  workspaceId: number,
  workspaceStockId: number,
  date?: string,
) {
  return http.get<ApiResult<StockIntradaySeriesVO>>(
    `/workspaces/${workspaceId}/stocks/${workspaceStockId}/intraday`,
    { params: date ? { date } : {} },
  )
}
