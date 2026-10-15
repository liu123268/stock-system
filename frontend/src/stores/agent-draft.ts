import { watch } from 'vue'
import { defineStore } from 'pinia'
import type { AgentTarget } from '@/api/agent'
import { useAuthStore } from '@/stores/auth'

export interface AgentDraft {
  question: string
  dateFrom: string
  dateTo: string
}

const MAX_DRAFTS = 8

/** 只保留当前登录会话中的未发送输入；不把研究内容写入浏览器持久存储。 */
export const useAgentDraftStore = defineStore('agent-draft', () => {
  const auth = useAuthStore()
  const drafts = new Map<string, AgentDraft>()

  function key(target: AgentTarget) {
    return `${target.workspaceId}:${target.stockId}:${target.cycleId}`
  }

  function copy(draft: AgentDraft): AgentDraft {
    return { question: draft.question, dateFrom: draft.dateFrom, dateTo: draft.dateTo }
  }

  function read(target: AgentTarget): AgentDraft | undefined {
    const draft = drafts.get(key(target))
    return draft ? copy(draft) : undefined
  }

  function remove(target: AgentTarget) {
    drafts.delete(key(target))
  }

  function write(target: AgentTarget, draft: AgentDraft) {
    if (!draft.question.trim() && !draft.dateFrom.trim() && !draft.dateTo.trim()) {
      remove(target)
      return
    }
    const targetKey = key(target)
    // 更新已有对象也放到最后，按最近一次写入淘汰最早草稿。
    drafts.delete(targetKey)
    drafts.set(targetKey, copy(draft))
    if (drafts.size > MAX_DRAFTS) {
      const oldest = drafts.keys().next().value
      if (oldest !== undefined) drafts.delete(oldest)
    }
  }

  function clearAll() {
    drafts.clear()
  }

  watch([() => auth.currentUser?.userId, () => auth.sessionVersion], clearAll, { flush: 'sync' })

  return { read, write, remove, clearAll }
})
