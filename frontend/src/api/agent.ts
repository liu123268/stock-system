import { http } from './http'
import type { ApiResult } from './auth'
import type { StockStance } from './stock'

export interface AgentTarget { workspaceId: number; stockId: number; cycleId: number }
export interface AgentFilter { authorUserId?: number; dateFrom?: string; dateTo?: string }
export interface AgentItem { text: string; sourceIds: string[] }
export interface AgentAnswer {
  summary: { overview: AgentItem; changes: AgentItem[]; members: (AgentItem & { authorId: string })[]; pendingItems: AgentItem[] }
  scope: { readAt: string; readOpinionCount: number; filter: AgentFilter }
  references: { sourceId: string; opinionId: number; authorId: string; authorName: string; createdAt: string }[]
  newOpinionsAvailable: boolean
  generatedAt: string
  quote: { status: 'AVAILABLE' | 'UNAVAILABLE'; price: number | null; currency: string; quoteTime: string | null; source: string | null; simulated: boolean }
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
export function readAgentRun(target: AgentTarget, runId: number) {
  return http.get<ApiResult<AgentRun>>(`${base(target)}/agent/runs/${runId}`)
}
export function clearAgent(target: AgentTarget) {
  return http.delete<ApiResult<null>>(`${base(target)}/agent`)
}
export function readAgentOriginal(target: AgentTarget, opinionId: number) {
  return http.get<ApiResult<OpinionOriginal>>(`${base(target)}/opinions/${opinionId}`)
}
