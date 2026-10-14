import { http } from './http'
import type { ApiResult } from './auth'
import type { StockStance } from './stock'

export interface AgentTarget { workspaceId: number; stockId: number; cycleId: number }
export interface AgentFilter { authorUserId?: number; dateFrom?: string; dateTo?: string }
export interface AgentItem { text: string; sourceIds: string[] }
export interface AgentQuote { status: 'AVAILABLE' | 'UNAVAILABLE'; price: number | null; currency: string; quoteTime: string | null; source: string | null; simulated: boolean }
export interface AgentDailyPrices {
  status: string; currency: string; requestedFrom: string; requestedTo: string; actualFrom: string | null; actualTo: string | null
  source: string | null; simulated: boolean; adjustment: string; nominalPriceDifference: number | null
  nominalPriceChangePercent: number | null; coverageNote: string
  prices: { time: string; open: number; high: number; low: number; close: number; volume: number }[]
}
export interface AgentEvidence { sourceId: string; type: 'QUOTE' | 'DAILY_PRICES'; label: string; quote: AgentQuote | null; dailyPrices: AgentDailyPrices | null }
export interface AgentAnswer {
  summary: { overview: AgentItem; changes: AgentItem[]; members: (AgentItem & { authorId: string })[]; pendingItems: AgentItem[] } | null
  scope: { readAt: string; readOpinionCount: number; filter: AgentFilter }
  references: { sourceId: string; opinionId: number; authorId: string; authorName: string; createdAt: string }[]
  newOpinionsAvailable: boolean
  generatedAt: string
  quote: AgentQuote | null
  followUp?: { question: string; reply: { paragraphs: AgentItem[]; missingInformation: string[] }; evidence: AgentEvidence[]; historyOmitted: boolean } | null
}
export interface AgentRun {
  runId: number; requestId: string; status: string; errorCode: string | null; message: string | null
  startedAt: string; finishedAt: string | null; answer: AgentAnswer | null
}
export interface AgentSession {
  enabled: boolean; contextVersion: number; messages: AgentRun[]; activeRun: AgentRun | null
  hasEarlierMessages: boolean; invalidated: boolean
}
export interface OpinionOriginal {
  id: number; authorNickname: string; createdAt: string; stance: StockStance
  reason: string; observationNote: string | null
}
function base(target: AgentTarget) {
  return `/workspaces/${target.workspaceId}/stocks/${target.stockId}/research/cycles/${target.cycleId}`
}
export function restoreAgent(target: AgentTarget, beforeRunId?: number) {
  return http.get<ApiResult<AgentSession>>(`${base(target)}/agent`, { params: { beforeRunId } })
}
export function generateAgent(target: AgentTarget, requestId: string, filter: AgentFilter) {
  return http.post<ApiResult<AgentRun>>(`${base(target)}/agent/summaries`, { requestId, ...filter }, { timeout: 65_000 })
}
export function askAgent(target: AgentTarget, requestId: string, question: string, filter: AgentFilter) {
  return http.post<ApiResult<AgentRun>>(`${base(target)}/agent/messages`, { requestId, question, ...filter }, { timeout: 65_000 })
}
export function readAgentRun(target: AgentTarget, runId: number) {
  return http.get<ApiResult<AgentRun>>(`${base(target)}/agent/runs/${runId}`)
}
export function clearAgent(target: AgentTarget) {
  return http.delete<ApiResult<null>>(`${base(target)}/agent`)
}
export function readAgentOriginal(target: AgentTarget, opinionId: number) {
  return http.get<ApiResult<OpinionOriginal>>(`${base(target)}/opinions/${opinionId}`)
}
