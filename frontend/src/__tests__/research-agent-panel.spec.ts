import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import ResearchAgentPanel from '@/components/stock/ResearchAgentPanel.vue'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { AgentRun } from '@/api/agent'

const api = vi.hoisted(() => ({ restoreAgent: vi.fn<(...args: unknown[]) => Promise<unknown>>(), generateAgent: vi.fn<(...args: unknown[]) => Promise<unknown>>(), readAgentRun: vi.fn<(...args: unknown[]) => Promise<unknown>>(), clearAgent: vi.fn<(...args: unknown[]) => Promise<unknown>>(), readAgentOriginal: vi.fn<(...args: unknown[]) => Promise<unknown>>() }))
vi.mock('@/api/agent', () => api)
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: vi.fn<() => void>() }) }))
vi.mock('@/utils/workspace-access', () => ({ handleWorkspaceApiError: vi.fn<() => Promise<boolean>>(async () => false) }))
const target = { workspaceId: 1, stockId: 2, cycleId: 3 }
function answer(runId = 10): AgentRun {
  return { runId, requestId: 'request', status: 'SUCCEEDED', errorCode: null, message: null,
    startedAt: '2026-10-13T12:00:00', finishedAt: '2026-10-13T12:00:01', answer: {
      summary: { overview: { text: '成员猜测订单增长，尚无公告确认。', sourceIds: ['op-5'] }, changes: [], members: [], pendingItems: [] },
      scope: { readAt: '2026-10-13T12:00:00', readOpinionCount: 1, filter: {} },
      references: [{ sourceId: 'op-5', opinionId: 5, authorId: 'M1', authorName: '成员甲', createdAt: '2026-10-13T11:00:00' }],
      newOpinionsAvailable: false, generatedAt: '2026-10-13T12:00:01',
      quote: { status: 'UNAVAILABLE', price: null, currency: 'HKD', quoteTime: null, source: null, simulated: false },
    } }
}
function session(messages: AgentRun[] = []) {
  return { data: { data: { enabled: true, contextVersion: 0, messages, activeRun: null, hasEarlierMessages: false, invalidated: false } } }
}
let wrappers: ReturnType<typeof mount>[] = []
function create() {
  const pinia = createPinia()
  const wrapper = mount(ResearchAgentPanel, { props: { target }, global: { plugins: [pinia] }, attachTo: document.body })
  wrappers.push(wrapper)
  return { wrapper, auth: useAuthStore(pinia) }
}
beforeEach(() => {
  vi.clearAllMocks()
  api.restoreAgent.mockResolvedValue(session())
  api.clearAgent.mockResolvedValue({ data: { data: null } })
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); this.dispatchEvent(new Event('close')) }
})
afterEach(() => { wrappers.forEach(wrapper => wrapper.unmount()); wrappers = []; vi.useRealTimers() })

describe('研究助手的个人记录与迟到响应', () => {
  it('关闭原文保留回答和日期，原文每次点击重新读取', async () => {
    api.restoreAgent.mockResolvedValue(session([answer()]))
    api.readAgentOriginal.mockResolvedValue({ data: { data: { id: 5, authorNickname: '成员甲', createdAt: '2026-10-13T11:00:00', stance: 'BULLISH', reason: '原文判断', observationNote: '需要公告' } } })
    const { wrapper } = create()
    await flushPromises()
    await wrapper.findAll('input')[0]!.setValue('2026-10-01')
    await wrapper.setProps({ target: { ...target } })
    await flushPromises()
    expect(api.restoreAgent).toHaveBeenCalledOnce()
    await wrapper.find('.source-link').trigger('click')
    await flushPromises()
    expect(wrapper.find('dialog').text()).toContain('原文判断')
    await wrapper.find('dialog button').trigger('click')
    expect(wrapper.find('.agent-answer').text()).toContain('成员猜测订单增长')
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('2026-10-01')
    await wrapper.find('.source-link').trigger('click'); await flushPromises()
    expect(api.readAgentOriginal).toHaveBeenCalledTimes(2)
  })
  it('生成中清空后忽略迟到答案，连点仅一次 POST', async () => {
    let resolve!: (value: unknown) => void
    api.generateAgent.mockReturnValue(new Promise(done => { resolve = done }))
    const { wrapper } = create(); await flushPromises()
    await wrapper.find('form').trigger('submit'); await wrapper.find('form').trigger('submit')
    expect(api.generateAgent).toHaveBeenCalledTimes(1)
    await wrapper.find('.agent-heading button').trigger('click')
    await wrapper.findAll('.agent-confirm button')[1]!.trigger('click'); await flushPromises()
    resolve({ data: { data: answer() } }); await flushPromises()
    expect(wrapper.findAll('.agent-answer')).toHaveLength(0)
    expect(wrapper.text()).toContain('已清空本周期的个人记录')
  })
  it('账号切换后旧 GET 不能恢复上一账号的回答', async () => {
    let resolve!: (value: unknown) => void
    api.restoreAgent.mockReturnValueOnce(new Promise(done => { resolve = done })).mockResolvedValue(session())
    const { wrapper, auth } = create()
    auth.sessionVersion++
    await flushPromises()
    resolve(session([answer()])); await flushPromises()
    expect(wrapper.findAll('.agent-answer')).toHaveLength(0)
  })
  it('原文撤回刷新整条回答，不只隐藏引用', async () => {
    api.restoreAgent.mockResolvedValueOnce(session([answer()])).mockResolvedValue(session([{ ...answer(), status: 'INVALIDATED', answer: null, message: '资料已撤回，请重新梳理。' }]))
    api.readAgentOriginal.mockRejectedValue(new ApiError('STOCK_OPINION_NOT_FOUND', '观点不存在', 404))
    const { wrapper } = create(); await flushPromises()
    await wrapper.find('.source-link').trigger('click'); await flushPromises()
    expect(wrapper.find('.agent-answer').text()).not.toContain('成员猜测订单增长')
    expect(wrapper.findAll('.source-link')).toHaveLength(0)
    expect(wrapper.find('dialog').text()).toContain('观点不存在')
  })
  it('恢复运行仅轮询本地状态，不重新发起生成', async () => {
    vi.useFakeTimers()
    api.restoreAgent.mockResolvedValue({ data: { data: { ...session().data.data, activeRun: { ...answer(), status: 'RUNNING', answer: null } } } })
    api.readAgentRun.mockResolvedValue({ data: { data: { ...answer(), status: 'RUNNING', answer: null } } })
    const { wrapper } = create(); await flushPromises()
    await vi.advanceTimersByTimeAsync(1500); await flushPromises()
    expect(api.readAgentRun).toHaveBeenCalledOnce()
    expect(api.generateAgent).not.toHaveBeenCalled()
    expect(wrapper.find('button[type=submit]').attributes('disabled')).toBeDefined()
  })
})
