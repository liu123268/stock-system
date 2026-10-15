import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { AxiosHeaders, type AxiosResponse } from 'axios'
import { ApiError, TOKEN_STORAGE_KEY } from '@/api/http'
import type { ApiResult } from '@/api/auth'
import { getWorkspace, type WorkspaceDetailResponseVO } from '@/api/workspace'
import { getWorkspaceStockResearch, getWorkspaceStockResearchTimeline, getWorkspaceStockMarketDetail,
  type WorkspaceStockResearchResponseVO, type ResearchCycleDetailResponseVO } from '@/api/stock'
import { askAgent, generateAgent } from '@/api/agent'
import { useAuthStore } from '@/stores/auth'
import { useAgentDraftStore } from '@/stores/agent-draft'
import WorkspaceResearchAgentView from '@/views/workspace/WorkspaceResearchAgentView.vue'
import WorkspaceStockResearchView from '@/views/workspace/WorkspaceStockResearchView.vue'
import productionRouter from '@/router'

vi.mock('@/api/workspace')
vi.mock('@/api/stock')
vi.mock('@/api/agent')
enableAutoUnmount(afterEach)

function response<T>(data: T): AxiosResponse<ApiResult<T>> {
  return { data: { code: 'SUCCESS', message: '成功', data }, status: 200, statusText: 'OK', headers: {}, config: { headers: new AxiosHeaders() } }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
const workspace: WorkspaceDetailResponseVO = {
  workspaceId: 12, name: '测试研究组', status: 'ACTIVE', role: 'OWNER', memberCount: 1,
  createdAt: '2026-10-14T08:00:00', createdByUserId: 9, ownerUserId: 9, archivedAt: null,
}
const research: WorkspaceStockResearchResponseVO = {
  workspaceStockId: 1, code: '600000.SH', name: '测试股票', exchange: 'SSE', currency: 'CNY',
  listingStatus: 'LISTED', currentCycle: null, authors: [],
}
let router: Router
const panel = { props: ['target', 'standalone'], template: '<div class="panel-probe" />' }

beforeEach(async () => {
  vi.resetAllMocks()
  localStorage.clear()
  localStorage.setItem(TOKEN_STORAGE_KEY, 'test-agent-view-session')
  setActivePinia(createPinia())
  vi.mocked(getWorkspace).mockResolvedValue(response(workspace))
  vi.mocked(getWorkspaceStockResearch).mockResolvedValue(response(research))
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/workspaces/:workspaceId/stocks/:workspaceStockId/research/cycles/:cycleId/assistant', name: 'workspace-research-agent', component: WorkspaceResearchAgentView },
    { path: '/workspaces/:workspaceId/stocks/:workspaceStockId', name: 'workspace-stock-research', component: { template: '<div />' } },
    { path: '/workspaces/:workspaceId/stocks', name: 'workspace-stocks', component: { template: '<div />' } },
    { path: '/workspaces', name: 'workspaces', component: { template: '<div />' } },
    { path: '/login', name: 'login', component: { template: '<div />' } },
  ] })
  await router.push({ name: 'workspace-research-agent', params: { workspaceId: 12, workspaceStockId: 1, cycleId: 77 } })
})
function render() {
  return mount(WorkspaceResearchAgentView, { global: { plugins: [router], stubs: { ResearchAgentPanel: panel } } })
}

describe('dedicated research assistant entry', () => {
  it('inherits authenticated workspace routes and reads the stock before opening its scoped panel', async () => {
    const resolved = productionRouter.resolve('/workspaces/12/stocks/1/research/cycles/77/assistant')
    expect(resolved.name).toBe('workspace-research-agent')
    expect(resolved.meta.requiresAuth).toBe(true)
    expect(resolved.meta.requiresRegular).toBe(true)
    const wrapper = render()
    expect(wrapper.get('[role="status"]').text()).toContain('正在读取')
    expect(wrapper.findComponent(panel).exists()).toBe(false)
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe('测试股票 · 研究助手')
    expect(wrapper.text()).toContain('600000.SH · 研究周期 #77')
    expect(wrapper.findComponent(panel).props()).toMatchObject({ target: { workspaceId: 12, stockId: 1, cycleId: 77 }, standalone: '' })
    expect(getWorkspaceStockResearch).toHaveBeenCalledWith(12, 1)
    expect(askAgent).not.toHaveBeenCalled()
    expect(generateAgent).not.toHaveBeenCalled()
  })

  it('returns to the same cycle and time-line page without loading all historical cycles', async () => {
    await router.replace({ ...router.currentRoute.value, query: { timelineCursor: '78:35', author: '9', stance: 'BEARISH', type: 'UPDATE' } })
    const wrapper = render()
    await flushPromises()
    const link = wrapper.get('.return-research')
    const destination = router.resolve(link.attributes('href') ?? '')
    expect(destination.name).toBe('workspace-stock-research')
    expect(destination.params).toMatchObject({ workspaceId: '12', workspaceStockId: '1' })
    expect(destination.query).toMatchObject({ cycleId: '77', timelineCursor: '78:35', author: '9', stance: 'BEARISH', type: 'UPDATE' })
    expect(destination.hash).toBe('#research-timeline')
    expect(getWorkspaceStockResearch).toHaveBeenCalledTimes(1)
  })

  it('keeps a read failure separate from usable assistant state and retries only reads', async () => {
    vi.mocked(getWorkspaceStockResearch).mockRejectedValueOnce(new Error('股票读取失败'))
    const wrapper = render()
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('股票读取失败')
    expect(wrapper.findComponent(panel).exists()).toBe(false)
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(panel).exists()).toBe(true)
    expect(getWorkspaceStockResearch).toHaveBeenCalledTimes(2)
    expect(askAgent).not.toHaveBeenCalled()
    expect(generateAgent).not.toHaveBeenCalled()
  })

  it('does not restore a late read from another stock', async () => {
    const oldRead = deferred<Awaited<ReturnType<typeof getWorkspaceStockResearch>>>()
    vi.mocked(getWorkspaceStockResearch).mockReturnValueOnce(oldRead.promise)
    const wrapper = render()
    await flushPromises()
    vi.mocked(getWorkspaceStockResearch).mockResolvedValueOnce(response({ ...research, workspaceStockId: 2, name: '后来进入的股票' }))
    await router.push({ name: 'workspace-research-agent', params: { workspaceId: 12, workspaceStockId: 2, cycleId: 88 } })
    await flushPromises()
    oldRead.resolve(response(research))
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe('后来进入的股票 · 研究助手')
    expect(wrapper.findComponent(panel).props('target')).toEqual({ workspaceId: 12, stockId: 2, cycleId: 88 })
  })

  it('clears the object before redirecting on membership loss', async () => {
    vi.mocked(getWorkspaceStockResearch).mockRejectedValueOnce(new ApiError('WORKSPACE_MEMBERSHIP_INACTIVE', '成员关系已失效', 403))
    const wrapper = render()
    await flushPromises()
    expect(wrapper.findComponent(panel).exists()).toBe(false)
    expect(wrapper.text()).not.toContain(research.name)
    expect(router.currentRoute.value.name).toBe('workspaces')
  })

  it.each(['workspace', 'stock'] as const)('removes only the current object draft when its %s read confirms lost access before the panel mounts', async (phase) => {
    const drafts = useAgentDraftStore()
    const current = { workspaceId: 12, stockId: 1, cycleId: 77 }
    const other = { workspaceId: 12, stockId: 1, cycleId: 88 }
    const draft = { question: '待发送的问题', dateFrom: '', dateTo: '' }
    drafts.write(current, draft)
    drafts.write(other, { ...draft, question: '另一个周期的草稿' })
    const denied = new ApiError('WORKSPACE_MEMBERSHIP_INACTIVE', '成员关系已失效', 403)
    if (phase === 'workspace') vi.mocked(getWorkspace).mockRejectedValueOnce(denied)
    else vi.mocked(getWorkspaceStockResearch).mockRejectedValueOnce(denied)
    const wrapper = render()
    await flushPromises()
    expect(wrapper.findComponent(panel).exists()).toBe(false)
    expect(drafts.read(current)).toBeUndefined()
    expect(drafts.read(other)?.question).toBe('另一个周期的草稿')
    expect(router.currentRoute.value.name).toBe('workspaces')
  })

  it.each(['workspace', 'stock'] as const)('keeps the current and other drafts on a temporary %s network failure', async (phase) => {
    const drafts = useAgentDraftStore()
    const current = { workspaceId: 12, stockId: 1, cycleId: 77 }
    const other = { workspaceId: 12, stockId: 2, cycleId: 88 }
    const draft = { question: '断线后还要继续的问题', dateFrom: '', dateTo: '' }
    drafts.write(current, draft)
    drafts.write(other, { ...draft, question: '其他股票的草稿' })
    if (phase === 'workspace') vi.mocked(getWorkspace).mockRejectedValueOnce(new Error('连接暂时中断'))
    else vi.mocked(getWorkspaceStockResearch).mockRejectedValueOnce(new Error('连接暂时中断'))
    const wrapper = render()
    await flushPromises()
    expect(wrapper.findComponent(panel).exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('连接暂时中断')
    expect(drafts.read(current)).toEqual(draft)
    expect(drafts.read(other)?.question).toBe('其他股票的草稿')
  })

  it('ignores a late read after logout', async () => {
    const oldRead = deferred<Awaited<ReturnType<typeof getWorkspaceStockResearch>>>()
    vi.mocked(getWorkspaceStockResearch).mockReturnValueOnce(oldRead.promise)
    const wrapper = render()
    await flushPromises()
    useAuthStore().logout()
    oldRead.resolve(response(research))
    await flushPromises()
    expect(wrapper.findComponent(panel).exists()).toBe(false)
    expect(wrapper.text()).not.toContain(research.name)
  })

  it('refuses invalid object IDs without reading a stock or opening the panel', async () => {
    await router.push({ name: 'workspace-research-agent', params: { workspaceId: 12, workspaceStockId: 1, cycleId: 'invalid' } })
    const invalid = render()
    await flushPromises()
    expect(invalid.get('[role="alert"]').text()).toContain('研究对象地址无效')
    expect(invalid.findComponent(panel).exists()).toBe(false)
    expect(getWorkspaceStockResearch).not.toHaveBeenCalled()
  })
})

const historicalCycle: ResearchCycleDetailResponseVO = {
  id: 77, status: 'ENDED', attentionLevel: 'NORMAL', startedByUserId: 9, startedByNickname: '成员甲',
  startedAt: '2026-09-01T08:00:00', startPrice: 12, quoteTime: '2026-09-01T08:00:00', quoteSource: 'mock', simulated: true,
  endedAt: '2026-10-01T08:00:00', opinions: [],
}
describe('research timeline returns from assistant', () => {
  beforeEach(() => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true })))
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { value: vi.fn<HTMLElement['scrollIntoView']>(), configurable: true })
    vi.mocked(getWorkspaceStockMarketDetail).mockResolvedValue(response({ ...research, price: 12,
      quoteTime: '2026-10-14T08:00:00', source: 'mock', simulated: true,
      trackingState: 'ENDED', activeResearchCycleId: null,
    }))
    vi.mocked(getWorkspaceStockResearch).mockResolvedValue(response({ ...research, authors: [{ userId: 9, nickname: '成员甲' }] }))
  })
  afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = '' })
  function renderResearch() {
    return mount(WorkspaceStockResearchView, { attachTo: document.body,
      global: { plugins: [router], stubs: { StockMarketPanel: true, ResearchAgentPanel: panel } } })
  }

  it('reads the original bounded page, restores filters, locates the cycle and leaves only an assistant link', async () => {
    vi.mocked(getWorkspaceStockResearchTimeline).mockResolvedValue(response({ cycles: [historicalCycle], nextCursor: '77:1' }))
    await router.push({ name: 'workspace-stock-research', params: { workspaceId: 12, workspaceStockId: 1 },
      query: { cycleId: '77', timelineCursor: '78:35', author: '9', stance: 'BEARISH', type: 'UPDATE' }, hash: '#research-timeline' })
    const wrapper = renderResearch()
    await flushPromises()
    expect(getWorkspaceStockResearchTimeline).toHaveBeenCalledExactlyOnceWith(12, 1, { author: 9, stance: 'BEARISH', type: 'UPDATE' }, '78:35')
    const cycle = wrapper.get('#research-cycle-77')
    expect(cycle.element.scrollIntoView).toHaveBeenCalledWith({ behavior: 'auto', block: 'start' })
    expect(document.activeElement).toBe(cycle.element)
    expect(wrapper.findComponent(panel).exists()).toBe(false)
    const link = router.resolve(wrapper.get('.assistant-link').attributes('href') ?? '')
    expect(link.name).toBe('workspace-research-agent')
    expect(link.params.cycleId).toBe('77')
    expect(link.query).toMatchObject({ timelineCursor: '78:35', author: '9', stance: 'BEARISH', type: 'UPDATE' })
    await wrapper.get('select[aria-label="按倾向筛选"]').setValue('ALL')
    await flushPromises()
    expect(getWorkspaceStockResearchTimeline).toHaveBeenLastCalledWith(12, 1, { author: 9, type: 'UPDATE' }, undefined)
  })

  it('explains an unloaded cycle without automatically paging through history', async () => {
    vi.mocked(getWorkspaceStockResearchTimeline).mockResolvedValue(response({ cycles: [historicalCycle], nextCursor: '77:1' }))
    await router.push({ name: 'workspace-stock-research', params: { workspaceId: 12, workspaceStockId: 1 },
      query: { cycleId: '55' }, hash: '#research-timeline' })
    const wrapper = renderResearch()
    await flushPromises()
    expect(wrapper.text()).toContain('返回的研究周期尚未出现在本页')
    expect(getWorkspaceStockResearchTimeline).toHaveBeenCalledExactlyOnceWith(12, 1, {}, undefined)
  })
})
