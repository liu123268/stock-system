import { http } from './http'
import type { ApiResult } from './auth'

export type StockStance = 'BULLISH' | 'NEUTRAL' | 'CAUTIOUS'
export type OpinionHorizon = 'SHORT' | 'MEDIUM' | 'LONG'

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
