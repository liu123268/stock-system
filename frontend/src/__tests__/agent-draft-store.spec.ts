import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, disposePinia, setActivePinia, type Pinia } from 'pinia'
import type { AgentTarget } from '@/api/agent'
import { useAgentDraftStore, type AgentDraft } from '@/stores/agent-draft'
import { useAuthStore } from '@/stores/auth'

vi.mock('@/api/auth')

const target: AgentTarget = { workspaceId: 1, stockId: 2, cycleId: 3 }
const draft: AgentDraft = { question: '保留这个问题', dateFrom: '2026-09-10', dateTo: '2026-10-14' }

function user(userId: number) {
  return { userId, username: `user_${userId}`, nickname: `成员${userId}`, accountType: 'REGULAR' as const }
}

describe('research assistant memory drafts', () => {
  let pinia: Pinia

  beforeEach(() => {
    window.localStorage.clear()
    window.sessionStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.currentUser = user(1)
    auth.accessToken = 'offline-session'
  })

  afterEach(() => {
    disposePinia(pinia)
    vi.restoreAllMocks()
  })

  it('keeps separate drafts for each workspace, stock and cycle', () => {
    const store = useAgentDraftStore()
    const others = [
      { ...target, workspaceId: 4 },
      { ...target, stockId: 5 },
      { ...target, cycleId: 6 },
    ]
    store.write(target, draft)
    for (const other of others) expect(store.read(other)).toBeUndefined()
    for (const [index, other] of others.entries()) {
      store.write(other, { ...draft, question: `其他对象 ${index}` })
    }
    expect(store.read(target)).toEqual(draft)
    for (const [index, other] of others.entries()) {
      expect(store.read(other)?.question).toBe(`其他对象 ${index}`)
    }
  })

  it('copies both written and read values so callers cannot mutate another view', () => {
    const store = useAgentDraftStore()
    const input = { ...draft }
    store.write(target, input)
    input.question = '写入后修改原对象'
    input.dateFrom = ''
    const restored = store.read(target)!
    expect(restored).toEqual(draft)
    restored.question = '修改读取副本'
    restored.dateTo = ''
    expect(store.read(target)).toEqual(draft)
  })

  it('limits drafts to eight objects and refreshes eviction order only when writing', () => {
    const store = useAgentDraftStore()
    const targets = Array.from({ length: 9 }, (_, index) => ({ ...target, cycleId: index + 1 }))
    for (const entry of targets.slice(0, 8)) store.write(entry, draft)
    store.write(targets[0]!, { ...draft, question: '重新编辑第一个对象' })
    expect(store.read(targets[1]!)).toEqual(draft)
    store.write(targets[8]!, draft)
    expect(store.read(targets[1]!)).toBeUndefined()
    expect(store.read(targets[0]!)?.question).toBe('重新编辑第一个对象')
    expect(targets.filter((entry) => store.read(entry))).toHaveLength(8)
  })

  it('removes blank drafts and can remove one object or clear all', () => {
    const store = useAgentDraftStore()
    const other = { ...target, cycleId: 9 }
    store.write(target, draft)
    store.write(other, { question: '', dateFrom: '2026-10-01', dateTo: '' })
    expect(store.read(other)?.dateFrom).toBe('2026-10-01')
    store.write(target, { question: '  ', dateFrom: '', dateTo: '' })
    expect(store.read(target)).toBeUndefined()
    store.remove(other)
    expect(store.read(other)).toBeUndefined()
    store.write(target, draft)
    store.write(other, draft)
    store.clearAll()
    expect(store.read(target)).toBeUndefined()
    expect(store.read(other)).toBeUndefined()
  })

  it('clears synchronously on account changes and does not restore an old account draft', () => {
    const auth = useAuthStore()
    const store = useAgentDraftStore()
    store.write(target, draft)
    auth.currentUser = user(2)
    expect(store.read(target)).toBeUndefined()
    store.write(target, { ...draft, question: '第二个账号的输入' })
    auth.currentUser = user(1)
    expect(store.read(target)).toBeUndefined()
  })

  it('clears synchronously on a new session even when the user ID stays the same', () => {
    const auth = useAuthStore()
    const store = useAgentDraftStore()
    store.write(target, draft)
    auth.sessionVersion += 1
    expect(store.read(target)).toBeUndefined()
    expect(auth.currentUser?.userId).toBe(1)
  })

  it('clears immediately on logout but retains input when only the nickname changes', () => {
    const auth = useAuthStore()
    const store = useAgentDraftStore()
    store.write(target, draft)
    auth.currentUser = { ...user(1), nickname: '修改后的昵称' }
    expect(store.read(target)).toEqual(draft)
    auth.logout()
    expect(store.read(target)).toBeUndefined()
  })

  it('does not persist input in localStorage or sessionStorage or survive a new Pinia instance', () => {
    const store = useAgentDraftStore()
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const removeItem = vi.spyOn(Storage.prototype, 'removeItem')
    store.write(target, draft)
    expect(store.read(target)).toEqual(draft)
    store.remove(target)
    store.write(target, draft)
    expect(setItem).not.toHaveBeenCalled()
    expect(removeItem).not.toHaveBeenCalled()
    const otherPinia = createPinia()
    try {
      expect(useAgentDraftStore(otherPinia).read(target)).toBeUndefined()
    } finally {
      disposePinia(otherPinia)
    }
  })
})
