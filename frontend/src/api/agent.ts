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
export interface AgentFinancialValue {
  period: string; amount: string; currency: string; unit: 'YUAN' | 'MILLION_YUAN'; profitBasis: string
}
export interface AgentFinancialFacts {
  status: string; symbol: string; selectedPeriod: string; availablePeriods: string[]; retrievedAt: string | null; disclosedOn: string | null; note: string
  facts: { metric: string; sourceName: string; current: AgentFinancialValue; comparable: AgentFinancialValue | null
    change: { absoluteDifference: string | null; percent: string | null; unavailableReason: string | null } }[]
  missingMetrics: string[]
  reports: { metric: string; title: string; url: string; sha256: string; reportPeriod: string; pdfPages: number[] }[]
}
export interface AgentEvidence {
  sourceId: string; type: 'QUOTE' | 'DAILY_PRICES' | 'FINANCIAL' | 'REPORT'; label: string; quote: AgentQuote | null
  dailyPrices: AgentDailyPrices | null; financialFacts?: AgentFinancialFacts | null
  reportPassage?: { id: string; symbol: string; reportVersion: string; reportTitle: string; reportYear: number
    sourceUrl: string; sectionTitle: string; pdfPage: number; text: string; textSha256: string } | null
}
export interface AgentReportLibrary {
  retrievalEnabled: boolean; note: string
  reports: { title: string; year: number; url: string; version: string; sections: string[]; pdfPages: number[] }[]
  states?: { key: string; title: string; year: number; status: 'AVAILABLE' | 'PROCESSING' | 'FAILED' | 'REMOVED'; usableVersion: boolean }[]
}
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
export function readAgentReports(target: AgentTarget) {
  return http.get<ApiResult<AgentReportLibrary>>(`${base(target)}/agent/reports`)
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
export function readAgentRequest(target: AgentTarget, requestId: string) {
  return http.get<ApiResult<AgentRun>>(`${base(target)}/agent/requests/${requestId}`)
}
export function clearAgent(target: AgentTarget) {
  return http.delete<ApiResult<null>>(`${base(target)}/agent`)
}
export function readAgentOriginal(target: AgentTarget, opinionId: number) {
  return http.get<ApiResult<OpinionOriginal>>(`${base(target)}/opinions/${opinionId}`)
}
