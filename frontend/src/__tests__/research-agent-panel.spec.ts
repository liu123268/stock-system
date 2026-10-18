import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import ResearchAgentPanel from '@/components/stock/ResearchAgentPanel.vue'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { AgentRun } from '@/api/agent'

const api = vi.hoisted(() => ({ restoreAgent: vi.fn<(...args: unknown[]) => Promise<unknown>>(), generateAgent: vi.fn<(...args: unknown[]) => Promise<unknown>>(), askAgent: vi.fn<(...args: unknown[]) => Promise<unknown>>(), readAgentRun: vi.fn<(...args: unknown[]) => Promise<unknown>>(), readAgentRequest: vi.fn<(...args: unknown[]) => Promise<unknown>>(), clearAgent: vi.fn<(...args: unknown[]) => Promise<unknown>>(), readAgentOriginal: vi.fn<(...args: unknown[]) => Promise<unknown>>(), readAgentReports: vi.fn<(...args: unknown[]) => Promise<unknown>>() }))
const cancellation = vi.hoisted(() => vi.fn<(...args: unknown[]) => Promise<unknown>>())
vi.mock('@/api/agent', () => ({ ...api, cancelAgent: cancellation }))
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
function session(messages: AgentRun[] = [], enabled = true) {
  return { data: { data: { enabled, contextVersion: 0, messages, activeRun: null, hasEarlierMessages: false, invalidated: false } } }
}
let wrappers: ReturnType<typeof mount>[] = []
function create(pinia = createPinia(), standalone = false) {
  const wrapper = mount(ResearchAgentPanel, { props: { target, standalone }, global: { plugins: [pinia] }, attachTo: document.body })
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
  it('逐步展示真实进度，停止后保留草稿和已有答案并恢复焦点', async () => {
    let finish!: (value: unknown) => void
    api.askAgent.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    api.restoreAgent.mockResolvedValue(session([answer()]))
    const { wrapper } = create(); await flushPromises()
    await wrapper.find('textarea').setValue('停止后继续编辑的问题')
    await wrapper.find('.agent-question').trigger('submit')
    const requestId = api.askAgent.mock.calls[0]![1]
    const options = api.askAgent.mock.calls[0]![4] as { signal: AbortSignal; onProgress: (value: { stage: string }) => void }
    options.onProgress({ stage: 'GENERATING' }); await flushPromises()
    expect(wrapper.text()).toContain('正在整理回答…')
    cancellation.mockResolvedValueOnce({ data: { data: { requestId, run: { ...answer(11), requestId, status: 'RUNNING', answer: null, cancelRequested: true } } } })
    await wrapper.find('.agent-stop').trigger('click'); await flushPromises()
    expect(cancellation).toHaveBeenCalledExactlyOnceWith(target, requestId)
    expect(wrapper.find('.agent-stop').text()).toBe('正在停止…')
    options.onProgress({ stage: 'SAVING' }); await flushPromises()
    expect(wrapper.text()).toContain('等待已发出的调用结束')
    finish({ data: { data: { ...answer(11), requestId, status: 'CANCELLED', answer: null, cancelRequested: true,
      errorCode: 'RESEARCH_AGENT_CANCELLED', message: '本次生成已停止' } } }); await flushPromises()
    expect(wrapper.find('textarea').element.value).toBe('停止后继续编辑的问题')
    expect(wrapper.findAll('.agent-answer')).toHaveLength(1)
    expect(wrapper.find('.agent-stop').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.find('textarea').element)
    expect(api.askAgent).toHaveBeenCalledOnce()
  })

  it('切换周期会中断连接并忽略旧进度，不自动停止服务端运行', async () => {
    api.askAgent.mockImplementationOnce(() => new Promise(() => {}))
    const { wrapper } = create(); await flushPromises()
    await wrapper.find('textarea').setValue('旧周期')
    await wrapper.find('.agent-question').trigger('submit')
    const options = api.askAgent.mock.calls[0]![4] as { signal: AbortSignal; onProgress: (value: { stage: string }) => void }
    await wrapper.setProps({ target: { ...target, cycleId: 4 } }); await flushPromises()
    expect(options.signal.aborted).toBe(true)
    options.onProgress({ stage: 'GENERATING' }); await flushPromises()
    expect(wrapper.text()).not.toContain('正在整理回答…')
    expect(cancellation).not.toHaveBeenCalled()
  })

  it('停止请求遇到失权后清除旧连接，重新获得访问时停止按钮不会锁死', async () => {
    api.askAgent.mockImplementationOnce(() => new Promise(() => {})).mockImplementationOnce(() => new Promise(() => {}))
    cancellation.mockRejectedValueOnce(new ApiError('WORKSPACE_MEMBERSHIP_INACTIVE', '成员关系已失效', 403))
    const { wrapper } = create(); await flushPromises()
    await wrapper.find('textarea').setValue('旧请求')
    await wrapper.find('.agent-question').trigger('submit')
    const oldStream = api.askAgent.mock.calls[0]![4] as { signal: AbortSignal }
    await wrapper.find('.agent-stop').trigger('click'); await flushPromises()
    expect(oldStream.signal.aborted).toBe(true)
    expect(wrapper.findAll('.agent-answer')).toHaveLength(0)
    await wrapper.find('.agent-error button').trigger('click'); await flushPromises()
    await wrapper.find('textarea').setValue('恢复访问后的请求')
    await wrapper.find('.agent-question').trigger('submit')
    expect(wrapper.find('.agent-stop').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('.agent-stop').text()).toBe('停止生成')
  })

  it('恢复已请求停止的运行只读轮询，不重新生成或重复取消', async () => {
    const active = { ...answer(11), requestId: crypto.randomUUID(), status: 'RUNNING', answer: null, cancelRequested: true }
    api.restoreAgent.mockResolvedValue({ data: { data: { ...session().data.data, activeRun: active } } })
    const { wrapper } = create(); await flushPromises()
    expect(wrapper.find('.agent-stop').text()).toBe('正在停止…')
    expect(wrapper.find('.agent-stop').attributes('disabled')).toBeDefined()
    expect(api.askAgent).not.toHaveBeenCalled()
    expect(cancellation).not.toHaveBeenCalled()
  })

  it('按需读取当前股票报告范围，迟到目录不覆盖新周期', async () => {
    let resolveLibrary!: (value: unknown) => void
    api.readAgentReports.mockImplementationOnce(() => new Promise(resolve => { resolveLibrary = resolve }))
    const { wrapper } = create(); await flushPromises()
    expect(api.readAgentReports).not.toHaveBeenCalled()
    const details = wrapper.find('details')
    ;(details.element as HTMLDetailsElement).open = true
    await details.trigger('toggle')
    expect(api.readAgentReports).toHaveBeenCalledWith(target)
    await wrapper.setProps({ target: { ...target, cycleId: 99 } }); await flushPromises()
    resolveLibrary({ data: { data: { retrievalEnabled: true, reports: [{ title: '旧股票报告', sections: [] }] } } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧股票报告')
  })
  it('独立问答按时间顺序展示，历史与输入区分开', async () => {
    api.restoreAgent.mockResolvedValue(session([answer(12), answer(10)]))
    const { wrapper } = create(createPinia(), true); await flushPromises()
    expect(wrapper.findAll('.agent-answer').map(item => item.attributes('data-run-id'))).toEqual(['10', '12'])
    expect(wrapper.find('.agent-history').findAll('.agent-answer')).toHaveLength(2)
    expect(wrapper.find('.agent-history .agent-question').exists()).toBe(false)
    expect(wrapper.find('.agent-question').exists()).toBe(true)
    expect(wrapper.findAll('input')).toHaveLength(0)
    await wrapper.find('.agent-controls button[type=button]').trigger('click')
    expect(wrapper.findAll('input')).toHaveLength(2)
  })

  it('返回研究再进入时保留内存草稿，换周期不混入问题', async () => {
    const pinia = createPinia()
    const first = create(pinia); await flushPromises()
    await first.wrapper.find('textarea').setValue('尚未发送的问题')
    await first.wrapper.find('input').setValue('2026-10-01')
    first.wrapper.unmount()
    const second = create(pinia); await flushPromises()
    expect((second.wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('尚未发送的问题')
    expect((second.wrapper.find('input').element as HTMLInputElement).value).toBe('2026-10-01')
    await second.wrapper.setProps({ target: { ...target, cycleId: 4 } }); await flushPromises()
    expect((second.wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('')
    await second.wrapper.find('textarea').setValue('新周期的问题')
    await second.wrapper.setProps({ target }); await flushPromises()
    expect((second.wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('尚未发送的问题')
    expect(api.askAgent).not.toHaveBeenCalled()
  })

  it('已失权的组件卸载后不会重新保存被清除的草稿', async () => {
    const pinia = createPinia()
    const { wrapper } = create(pinia); await flushPromises()
    await wrapper.find('textarea').setValue('来自原对象的未发送问题')
    api.askAgent.mockResolvedValue({ data: { data: { ...answer(11), status: 'FAILED', answer: null,
      errorCode: 'WORKSPACE_MEMBERSHIP_INACTIVE', message: '成员关系已失效' } } })
    await wrapper.find('.agent-question').trigger('submit'); await flushPromises()
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('')
    wrapper.unmount()
    const next = create(pinia); await flushPromises()
    expect((next.wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('')
  })

  it('回答功能关闭时可以编辑草稿和查看旧记录，两种提交都不调用模型', async () => {
    api.restoreAgent.mockResolvedValue(session([answer()], false))
    const { wrapper } = create(); await flushPromises()
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).disabled).toBe(false)
    await wrapper.find('textarea').setValue('请比较 2025 年营业收入和归母利润')
    expect(wrapper.text()).toContain('可以先写下问题')
    expect(wrapper.text()).toContain('茅台、腾讯 2025 年报及 2024 比较列')
    expect(wrapper.find('.agent-answer').text()).toContain('成员猜测订单增长')
    for (const button of wrapper.findAll('button[type=submit]')) expect(button.attributes('disabled')).toBeDefined()
    await wrapper.find('.agent-controls').trigger('submit')
    await wrapper.find('.agent-question').trigger('submit'); await flushPromises()
    expect(api.generateAgent).not.toHaveBeenCalled()
    expect(api.askAgent).not.toHaveBeenCalled()
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('请比较 2025 年营业收入和归母利润')
  })

  it('只读检查启用状态保留问题和日期，不自动发送草稿', async () => {
    api.restoreAgent.mockResolvedValueOnce(session([], false)).mockResolvedValue(session())
    const { wrapper } = create(); await flushPromises()
    await wrapper.find('textarea').setValue('2025 年归母利润同比如何？')
    await wrapper.find('input').setValue('2026-10-01')
    await wrapper.find('.agent-availability button').trigger('click'); await flushPromises()
    expect(api.restoreAgent).toHaveBeenCalledTimes(2)
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('2025 年归母利润同比如何？')
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('2026-10-01')
    expect(wrapper.find('.agent-question button').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('.agent-availability').exists()).toBe(false)
    expect(api.generateAgent).not.toHaveBeenCalled()
    expect(api.askAgent).not.toHaveBeenCalled()
  })

  it('财务引用每次复核，保留精确金额、原报单位和 PDF 页码', async () => {
    const reply = answer()
    reply.answer!.summary = null
    reply.answer!.followUp = {
      question: '年度营收是否增长？',
      reply: { paragraphs: [{ text: '按照这份年度报告核对。', sourceIds: ['financial-1'] }], missingInformation: [] },
      historyOmitted: false,
      evidence: [{ sourceId: 'financial-1', type: 'FINANCIAL', label: '财务数据', quote: null, dailyPrices: null,
        financialFacts: { status: 'AVAILABLE', symbol: '600519.SH', selectedPeriod: 'FY 2025', availablePeriods: ['FY 2025', 'FY 2024'],
          retrievedAt: '2026-10-14T12:00:00Z', disclosedOn: null, note: '仅为报告库覆盖，不能证明订单增长。', missingMetrics: [],
          facts: [{ metric: 'REVENUE', sourceName: '营业收入',
            current: { period: 'FY 2025', amount: '168838102514.79', currency: 'CNY', unit: 'YUAN', profitBasis: 'NOT_APPLICABLE' },
            comparable: { period: 'FY 2024', amount: '170899152276.34', currency: 'CNY', unit: 'YUAN', profitBasis: 'NOT_APPLICABLE' },
            change: { absoluteDifference: '-2061049761.55', percent: '-1.2060', unavailableReason: null } }],
          reports: [{ metric: 'REVENUE', title: '官方年度报告', url: 'https://static.cninfo.com.cn/finalpage/2026-04-17/1225114741.PDF',
            sha256: 'a'.repeat(64), reportPeriod: 'FY 2025', pdfPages: [6] }] } }],
    }
    api.restoreAgent.mockResolvedValue(session([reply]))
    api.readAgentRun.mockResolvedValue({ data: { data: reply } })
    const { wrapper } = create(); await flushPromises()
    await wrapper.find('.source-link').trigger('click'); await flushPromises()
    const dialog = wrapper.find('dialog')
    expect(dialog.text()).toContain('财务依据')
    expect(dialog.text()).toContain('168838102514.79 人民币元')
    expect(dialog.text()).toContain('-1.2060%')
    expect(dialog.text()).toContain('PDF 第 6 页')
    expect(dialog.find('a').attributes('href')).toBe('https://static.cninfo.com.cn/finalpage/2026-04-17/1225114741.PDF#page=6')
    expect(api.readAgentOriginal).not.toHaveBeenCalled()
    await dialog.find('button').trigger('click')
    await wrapper.find('.source-link').trigger('click'); await flushPromises()
    expect(api.readAgentRun).toHaveBeenCalledTimes(2)
  })

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
    expect(wrapper.text()).not.toContain('研究助手暂未开启回答功能')
    expect(wrapper.find('.agent-question button').attributes('disabled')).toBeDefined()
    expect(wrapper.find('textarea').attributes('disabled')).toBeDefined()
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
