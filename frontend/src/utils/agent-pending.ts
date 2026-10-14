import type { AgentTarget } from '@/api/agent'

// 仅在本标签页保留一次待确认动作的编号与对象，不保存问题、回答或凭据。
const KEY = 'stock-system-agent-pending'
export interface PendingAgentRequest { requestId: string; target: AgentTarget; userId: number | null }
export function readPendingAgent(target: AgentTarget, userId: number | null): PendingAgentRequest | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(KEY) ?? 'null') as PendingAgentRequest | null
    return value && /^[0-9a-f-]{36}$/i.test(value.requestId) && value.userId === userId
      && value.target?.workspaceId === target.workspaceId && value.target.stockId === target.stockId
      && value.target.cycleId === target.cycleId ? value : null
  } catch { return null }
}
export function writePendingAgent(value: PendingAgentRequest) {
  try { sessionStorage.setItem(KEY, JSON.stringify(value)) } catch { /* 存储不可用时仍可在本页恢复。 */ }
}
export function removePendingAgent(requestId: string) {
  try {
    if ((JSON.parse(sessionStorage.getItem(KEY) ?? 'null') as PendingAgentRequest | null)?.requestId === requestId)
      sessionStorage.removeItem(KEY)
  } catch { /* 不影响服务端状态查询。 */ }
}
