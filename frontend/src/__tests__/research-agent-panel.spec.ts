import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import ResearchAgentPanel from '@/components/stock/ResearchAgentPanel.vue'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { AgentRun } from '@/api/agent'

const api = vi.hoisted(() => ({ restoreAgent: vi.fn<(...args: unknown[]) => Promise<unknown>>(), generateAgent: vi.fn<(...args: unknown[]) => Promise<unknown>>(), askAgent: vi.fn<(...args: unknown[]) => Promise<unknown>>(), readAgentRun: vi.fn<(...args: unknown[]) => Promise<unknown>>(), readAgentRequest: vi.fn<(...args: unknown[]) => Promise<unknown>>(), clearAgent: vi.fn<(...args: unknown[]) => Promise<unknown>>(), readAgentOriginal: vi.fn<(...args: unknown[]) => Promise<unknown>>() }))
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
  sessionStorage.clear()
  api.restoreAgent.mockResolvedValue(session())
  api.readAgentRequest.mockRejectedValue(new ApiError('RESEARCH_AGENT_RUN_NOT_FOUND', '记录不存在', 404))
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
  it('追问失败保留问题和有效旧答案，成功按段落展示与恢复', async () => {
    api.restoreAgent.mockResolvedValue(session([answer()]))
    api.askAgent.mockRejectedValueOnce(new ApiError('RESEARCH_AGENT_BUSY', '已有查询正在进行', 429))
    const { wrapper } = create(); await flushPromises()
    await wrapper.find('textarea').setValue('现在最新可用价是多少？')
    await wrapper.find('.agent-question').trigger('submit'); await flushPromises()
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('现在最新可用价是多少？')
    expect(wrapper.find('.agent-answer').text()).toContain('成员猜测订单增长')
    const reply = answer(11)
    reply.answer!.summary = null
    reply.answer!.followUp = { question: '现在最新可用价是多少？', reply: { paragraphs: [{ text: '这是本次取得的模拟报价。', sourceIds: [] }], missingInformation: [] }, evidence: [], historyOmitted: false }
    api.askAgent.mockResolvedValue({ data: { data: reply } })
    api.restoreAgent.mockResolvedValue(session([reply, answer()]))
    await wrapper.find('.agent-question').trigger('submit'); await flushPromises()
    expect(wrapper.findAll('.agent-answer')).toHaveLength(2)
    expect(wrapper.findAll('.agent-answer')[0]!.text()).toContain('你的问题：现在最新可用价是多少？')
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('')
  })
  it('查看行情依据先核对运行权限，关闭后保留正在输入的问题', async () => {
    const reply = answer(11)
    const quote = reply.answer!.quote!
    reply.answer!.summary = null
    reply.answer!.followUp = { question: '价格？', reply: { paragraphs: [{ text: '报价依据', sourceIds: ['quote-1'] }], missingInformation: [] }, evidence: [{ sourceId: 'quote-1', type: 'QUOTE', label: '最新可用报价', quote, dailyPrices: null }], historyOmitted: false }
    api.restoreAgent.mockResolvedValue(session([reply]))
    api.readAgentRun.mockResolvedValue({ data: { data: reply } })
    const { wrapper } = create(); await flushPromises()
    await wrapper.find('textarea').setValue('下一条问题')
    await wrapper.find('.source-link').trigger('click'); await flushPromises()
    expect(api.readAgentRun).toHaveBeenCalledWith(target, 11)
    expect(wrapper.find('dialog').text()).toContain('行情依据')
    await wrapper.find('dialog button').trigger('click')
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('下一条问题')
  })
  it('POST 回包丢失后按原编号恢复已成功结果，整个过程没有第二次 POST', async () => {
    api.generateAgent.mockRejectedValue(new Error('response lost'))
    api.readAgentRequest.mockImplementation(async (_target, requestId) => ({ data: { data: { ...answer(), requestId } } }))
    const { wrapper } = create(); await flushPromises()
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(api.generateAgent).toHaveBeenCalledOnce()
    expect(api.readAgentRequest).toHaveBeenCalledWith(target, api.generateAgent.mock.calls[0]![1])
    expect(wrapper.find('.agent-answer').text()).toContain('成员猜测订单增长')
    expect(sessionStorage.getItem('stock-system-agent-pending')).toBeNull()
  })
  it('未找到运行且再次发送时沿用原编号，避免原请求晚到造成两次生成', async () => {
    api.askAgent.mockRejectedValue(new Error('network unavailable'))
    const { wrapper } = create(); await flushPromises()
    await wrapper.find('textarea').setValue('现在价格？')
    await wrapper.find('.agent-question').trigger('submit'); await flushPromises()
    expect(wrapper.text()).toContain('暂未找到本次任务')
    expect(wrapper.text()).not.toContain('正在根据问题核对所需资料')
    await wrapper.find('.agent-question').trigger('submit'); await flushPromises()
    expect(api.askAgent).toHaveBeenCalledTimes(2)
    expect(api.askAgent.mock.calls[1]![1]).toBe(api.askAgent.mock.calls[0]![1])
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('现在价格？')
    expect(sessionStorage.getItem('stock-system-agent-pending')).not.toContain('现在价格')
  })
  it('刷新后的待确认编号可恢复失败终态并保留旧回答，不用 POST 重试', async () => {
    const requestId = crypto.randomUUID()
    sessionStorage.setItem('stock-system-agent-pending', JSON.stringify({ requestId, target, userId: null }))
    api.restoreAgent.mockResolvedValue(session([answer()]))
    api.readAgentRequest.mockResolvedValue({ data: { data: { ...answer(11), requestId, status: 'FAILED', answer: null, message: '本次资料已变化' } } })
    const { wrapper } = create(); await flushPromises()
    expect(wrapper.text()).toContain('本次资料已变化')
    expect(wrapper.find('.agent-answer').text()).toContain('成员猜测订单增长')
    expect(api.askAgent).not.toHaveBeenCalled()
    expect(api.generateAgent).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('stock-system-agent-pending')).toBeNull()
  })
  it('切换周期后迟到的状态查询失败不能解锁新周期正在进行的任务', async () => {
    vi.useFakeTimers()
    let reject!: (reason: unknown) => void
    api.restoreAgent.mockResolvedValue({ data: { data: { ...session().data.data, activeRun: { ...answer(), status: 'RUNNING', answer: null } } } })
    api.readAgentRun.mockReturnValueOnce(new Promise((_resolve, fail) => { reject = fail }))
    const { wrapper } = create(); await flushPromises()
    await vi.advanceTimersByTimeAsync(1500)
    await wrapper.setProps({ target: { ...target, cycleId: 4 } }); await flushPromises()
    reject(new Error('old request failed')); await flushPromises()
    expect(wrapper.find('button[type=submit]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('暂时无法连接')
  })
  it('运行回包已确认失权时立即清除旧正文，即使随后恢复 GET 又断线', async () => {
    api.restoreAgent.mockResolvedValueOnce(session([answer()])).mockRejectedValue(new Error('restore unavailable'))
    api.askAgent.mockResolvedValue({ data: { data: { ...answer(11), status: 'FAILED', answer: null,
      errorCode: 'WORKSPACE_MEMBERSHIP_INACTIVE', message: '成员关系已失效' } } })
    const { wrapper } = create(); await flushPromises()
    await wrapper.find('textarea').setValue('查询当前观点')
    await wrapper.find('.agent-question').trigger('submit'); await flushPromises()
    expect(wrapper.findAll('.agent-answer')).toHaveLength(0)
    expect(wrapper.findAll('.source-link')).toHaveLength(0)
    expect(wrapper.text()).toContain('成员关系已失效')
    expect(wrapper.text()).not.toContain('研究助手尚未启用')
    expect(wrapper.find('.agent-question button').attributes('disabled')).toBeDefined()
  })
  it('POST 后的恢复 GET 迟到时，旧运行错误不能覆盖新周期反馈', async () => {
    let resolve!: (value: unknown) => void
    api.restoreAgent.mockResolvedValueOnce(session())
      .mockReturnValueOnce(new Promise(done => { resolve = done })).mockResolvedValue(session())
    api.askAgent.mockResolvedValue({ data: { data: { ...answer(11), status: 'FAILED', answer: null, message: '旧周期错误' } } })
    const { wrapper } = create(); await flushPromises()
    await wrapper.find('textarea').setValue('旧周期的问题')
    await wrapper.find('.agent-question').trigger('submit'); await flushPromises()
    await wrapper.setProps({ target: { ...target, cycleId: 4 } }); await flushPromises()
    resolve(session()); await flushPromises()
    expect(wrapper.text()).not.toContain('旧周期错误')
  })
  it('已收到失权回包后，更早发出的恢复 GET 不能再次恢复旧正文', async () => {
    let finishQuestion!: (value: unknown) => void
    let finishOldGet!: (value: unknown) => void
    api.restoreAgent.mockResolvedValueOnce(session([answer()]))
      .mockReturnValueOnce(new Promise(done => { finishOldGet = done }))
    api.askAgent.mockReturnValue(new Promise(done => { finishQuestion = done }))
    api.readAgentOriginal.mockRejectedValue(new ApiError('STOCK_OPINION_NOT_FOUND', '观点已撤回', 404))
    const { wrapper } = create(); await flushPromises()
    await wrapper.find('textarea').setValue('读取当前观点')
    await wrapper.find('.agent-question').trigger('submit')
    await wrapper.find('.source-link').trigger('click'); await flushPromises()
    finishQuestion({ data: { data: { ...answer(11), status: 'FAILED', answer: null,
      errorCode: 'WORKSPACE_MEMBERSHIP_INACTIVE', message: '成员关系已失效' } } }); await flushPromises()
    finishOldGet(session([answer()])); await flushPromises()
    expect(wrapper.findAll('.agent-answer')).toHaveLength(0)
    expect(wrapper.text()).toContain('成员关系已失效')
    expect(wrapper.find('.agent-question button').attributes('disabled')).toBeDefined()
  })
})
