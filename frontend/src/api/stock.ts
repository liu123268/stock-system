import { http } from './http'
import type { ApiResult } from './auth'

export type StockStance = 'BULLISH' | 'NEUTRAL' | 'BEARISH'
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

export type StockTrackingState = 'NOT_TRACKED' | 'ACTIVE' | 'ENDED'

export interface WorkspaceStockMarketDetailResponseVO extends StockSearchResponseVO {
  trackingState: StockTrackingState
  workspaceStockId: number | null
  activeResearchCycleId: number | null
}

export interface AddWorkspaceStockRequestDTO {
  code: string
  stance: StockStance
  reason: string
  observationNote: string | null
  horizon: OpinionHorizon
  targetPrice: number | null
  stopLossPrice: number | null
}

export interface AddWorkspaceStockResponseVO {
  workspaceStockId: number
  researchCycleId: number
  alreadyActive: boolean
}

export type AttentionLevel = 'NORMAL' | 'FOCUS'

export interface UpdateAttentionLevelResponseVO {
  workspaceStockId: number
  researchCycleId: number
  attentionLevel: AttentionLevel
}

export type OpinionType = 'INITIAL' | 'UPDATE' | 'REVIEW'

export interface CreateStockOpinionRequestDTO {
  researchCycleId: number
  type: Exclude<OpinionType, 'INITIAL'>
  stance: StockStance
  reason: string
  observationNote: string | null
  horizon: OpinionHorizon
  targetPrice: number | null
  stopLossPrice: number | null
}

export interface StockOpinionResponseVO {
  id: number
  authorUserId: number
  authorNickname: string
  type: OpinionType
  stance: StockStance
  reason: string | null
  observationNote: string | null
  horizon: OpinionHorizon
  targetPrice: number | null
  stopLossPrice: number | null
  createdAt: string
  withdrawn: boolean
  withdrawnAt: string | null
  canWithdraw: boolean
}

export interface ResearchCycleDetailResponseVO {
  id: number
  status: 'ACTIVE' | 'ENDED' | 'CANCELLED'
  attentionLevel: AttentionLevel
  startedByUserId: number
  startedByNickname: string
  startedAt: string
  startPrice: number
  quoteTime: string
  quoteSource: string
  simulated: boolean
  endedAt: string | null
  opinions: StockOpinionResponseVO[]
}

export interface WorkspaceStockResearchResponseVO {
  workspaceStockId: number
  code: string
  name: string
  exchange: 'SSE' | 'SZSE' | 'BSE' | 'HKEX'
  currency: 'CNY' | 'HKD'
  listingStatus: 'LISTED' | 'SUSPENDED' | 'DELISTED' | 'UNKNOWN'
  cycles: ResearchCycleDetailResponseVO[]
}

export interface EndedWorkspaceStockResponseVO {
  workspaceStockId: number
  code: string
  name: string
  exchange: string
  currency: 'CNY' | 'HKD'
  researchCycleId: number
  status: 'ENDED' | 'CANCELLED'
  startedAt: string
  endedAt: string
}

export interface WorkspaceStockListResponseVO {
  workspaceStockId: number
  researchCycleId: number
  code: string
  name: string
  exchange: 'SSE' | 'SZSE' | 'BSE' | 'HKEX'
  currency: 'CNY' | 'HKD'
  listingStatus: 'LISTED' | 'SUSPENDED' | 'DELISTED' | 'UNKNOWN'
  attentionLevel: AttentionLevel
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
  reason: string | null
  observationNote: string | null
  horizon: OpinionHorizon
  targetPrice: number | null
  stopLossPrice: number | null
  opinionCreatedAt: string
  initialOpinionWithdrawn: boolean
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

export function getWorkspaceStockMarketDetail(workspaceId: number, code: string) {
  return http.get<ApiResult<WorkspaceStockMarketDetailResponseVO>>(
    `/workspaces/${workspaceId}/market/stocks/${encodeURIComponent(code)}`,
  )
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

export function updateWorkspaceStockAttention(
  workspaceId: number,
  workspaceStockId: number,
  researchCycleId: number,
  attentionLevel: AttentionLevel,
) {
  return http.patch<ApiResult<UpdateAttentionLevelResponseVO>>(
    `/workspaces/${workspaceId}/stocks/${workspaceStockId}/attention`,
    { researchCycleId, attentionLevel },
  )
}

export function listEndedWorkspaceStocks(workspaceId: number) {
  return http.get<ApiResult<EndedWorkspaceStockResponseVO[]>>(
    `/workspaces/${workspaceId}/stocks/ended`,
  )
}

export function getWorkspaceStockResearch(workspaceId: number, workspaceStockId: number) {
  return http.get<ApiResult<WorkspaceStockResearchResponseVO>>(
    `/workspaces/${workspaceId}/stocks/${workspaceStockId}/research`,
  )
}

export function endWorkspaceStockResearch(
  workspaceId: number,
  workspaceStockId: number,
  researchCycleId: number,
) {
  return http.post<ApiResult<unknown>>(
    `/workspaces/${workspaceId}/stocks/${workspaceStockId}/end`,
    { researchCycleId },
  )
}

export function createWorkspaceStockOpinion(
  workspaceId: number,
  workspaceStockId: number,
  request: CreateStockOpinionRequestDTO,
) {
  return http.post<ApiResult<StockOpinionResponseVO>>(
    `/workspaces/${workspaceId}/stocks/${workspaceStockId}/opinions`,
    request,
  )
}

export function withdrawWorkspaceStockOpinion(
  workspaceId: number,
  workspaceStockId: number,
  opinionId: number,
) {
  return http.delete<ApiResult<null>>(
    `/workspaces/${workspaceId}/stocks/${workspaceStockId}/opinions/${opinionId}`,
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

export function getWorkspaceMarketStockCandlesticks(
  workspaceId: number,
  code: string,
  period: CandlestickPeriod,
  count = 1000,
  before?: string,
) {
  return http.get<ApiResult<StockCandlestickSeriesVO>>(
    `/workspaces/${workspaceId}/market/stocks/${encodeURIComponent(code)}/candlesticks`,
    { params: { period, count, ...(before ? { before } : {}) } },
  )
}

export function getWorkspaceMarketStockIntraday(
  workspaceId: number,
  code: string,
  date?: string,
) {
  return http.get<ApiResult<StockIntradaySeriesVO>>(
    `/workspaces/${workspaceId}/market/stocks/${encodeURIComponent(code)}/intraday`,
    { params: date ? { date } : {} },
  )
}
