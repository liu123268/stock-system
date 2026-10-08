import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosHeaders, type AxiosResponse } from 'axios'
import type { Router } from 'vue-router'
import { ApiError, TOKEN_STORAGE_KEY } from '@/api/http'
import type { ApiResult } from '@/api/auth'
import {
  createWorkspace,
  getWorkspace,
  joinWorkspace,
  listWorkspaces,
  type WorkspaceDetailResponseVO,
} from '@/api/workspace'
import {
  addWorkspaceStock,
  createWorkspaceStockOpinion,
  endWorkspaceStockResearch,
  getWorkspaceStockMarketDetail,
  getWorkspaceStockResearch,
  getWorkspaceStockResearchTimeline,
  type WorkspaceStockResearchResponseVO,
} from '@/api/stock'
import { useAuthStore } from '@/stores/auth'
import { useStockStore } from '@/stores/stock'
import { useWorkspaceStore } from '@/stores/workspace'
import WorkspaceListView from '@/views/workspace/WorkspaceListView.vue'
import WorkspaceStockResearchView from '@/views/workspace/WorkspaceStockResearchView.vue'
import WorkspaceStockMarketView from '@/views/workspace/WorkspaceStockMarketView.vue'

const { route, router } = vi.hoisted(() => ({
  route: {
    params: { workspaceId: '12', workspaceStockId: '1', code: '600000.SH' },
    query: { reason: 'switch' },
    hash: '',
  },
  router: { push: vi.fn<Router['push']>(), replace: vi.fn<Router['replace']>() },
}))
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => router }))
vi.mock('@/api/workspace')
vi.mock('@/api/stock')
enableAutoUnmount(afterEach)

function response<T>(data: T): AxiosResponse<ApiResult<T>> {
  return {
    data: { code: 'SUCCESS', message: '成功', data },
    status: 200,
    statusText: 'OK',
    headers: {},
    config: { headers: new AxiosHeaders() },
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

const workspace: WorkspaceDetailResponseVO = {
  workspaceId: 12,
  name: '家庭研究组',
  status: 'ACTIVE',
  role: 'OWNER',
  memberCount: 1,
  createdAt: '2026-10-02T08:00:00',
  createdByUserId: 9,
  ownerUserId: 9,
  archivedAt: null,
}
const research: WorkspaceStockResearchResponseVO = {
  workspaceStockId: 1,
  code: '600000.SH',
  name: '测试研究股票',
  exchange: 'SSE',
  currency: 'CNY',
  listingStatus: 'LISTED',
  currentCycle: null,
  authors: [],
}
const options = { global: { stubs: { RouterLink: true, StockMarketPanel: true } } }

beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear()
  localStorage.setItem(TOKEN_STORAGE_KEY, 'test-session')
  setActivePinia(createPinia())
  vi.mocked(listWorkspaces).mockResolvedValue(response([workspace]))
  vi.mocked(getWorkspace).mockResolvedValue(response(workspace))
  vi.mocked(getWorkspaceStockResearch).mockResolvedValue(response(research))
  vi.mocked(getWorkspaceStockResearchTimeline).mockResolvedValue(
    response({ cycles: [], nextCursor: null }),
  )
  vi.mocked(getWorkspaceStockMarketDetail).mockResolvedValue(
    response({
      ...research,
      price: 12,
      quoteTime: '2026-10-02T08:00:00',
      source: 'mock',
      simulated: true,
      trackingState: 'ENDED',
      activeResearchCycleId: null,
    }),
  )
})

describe('standalone market page recovery', () => {
  it('explains a confirmed special trading state without presenting it as an unavailable check', async () => {
    vi.mocked(getWorkspaceStockMarketDetail).mockResolvedValueOnce(response({
      ...research, listingStatus: 'UNKNOWN', price: null, quoteTime: null,
      source: 'LONGBRIDGE_OPENAPI', simulated: false, trackingState: 'NOT_TRACKED',
      workspaceStockId: null, activeResearchCycleId: null,
      statusInfo: { tradeStatus: 'SPLIT_STOCK_HALTS', lastKnownListingStatus: 'UNKNOWN',
        checkedAt: '2026-10-08T06:35:00', attemptedAt: '2026-10-08T06:35:00', current: true },
    }))
    const wrapper = mount(WorkspaceStockMarketView, options)
    await flushPromises()
    expect(wrapper.text()).toContain('拆股暂停交易')
    expect(wrapper.text()).toContain('这只股票目前处于“拆股暂停交易”，暂时不能加入关注')
    expect(wrapper.text()).not.toContain('交易状态暂时无法核实')
    expect(wrapper.get<HTMLButtonElement>('.quote-action button').element.disabled).toBe(true)
    expect(addWorkspaceStock).not.toHaveBeenCalled()
  })

  it('shows the failed workspace precondition and retries both reads without a write', async () => {
    vi.mocked(getWorkspace).mockRejectedValueOnce(new Error('空间信息暂时不可用'))
    const wrapper = mount(WorkspaceStockMarketView, options)
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('空间信息暂时不可用')
    expect(wrapper.get('h1').text()).toBe('暂时无法进入这个空间')
    expect(wrapper.text()).not.toContain('股票行情不存在')
    expect(getWorkspaceStockMarketDetail).not.toHaveBeenCalled()
    expect(useAuthStore().isAuthenticated).toBe(true)

    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(getWorkspace).toHaveBeenCalledTimes(2)
    expect(getWorkspaceStockMarketDetail).toHaveBeenCalledWith(12, '600000.SH')
    expect(wrapper.get('h1').text()).toBe(research.name)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(addWorkspaceStock).not.toHaveBeenCalled()
    expect(createWorkspaceStockOpinion).not.toHaveBeenCalled()
    expect(endWorkspaceStockResearch).not.toHaveBeenCalled()
  })

  it('keeps a market read failure distinct from a workspace read failure', async () => {
    vi.mocked(getWorkspaceStockMarketDetail).mockRejectedValueOnce(new Error('行情读取失败'))
    const wrapper = mount(WorkspaceStockMarketView, options)
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe('暂时无法打开这只股票')
    expect(wrapper.get('[role="alert"]').text()).toContain('行情读取失败')
    expect(useAuthStore().isAuthenticated).toBe(true)
  })

  it('still expires the current session on a workspace 401', async () => {
    vi.mocked(getWorkspace).mockRejectedValueOnce(new ApiError('UNAUTHENTICATED', '登录已过期', 401))
    mount(WorkspaceStockMarketView, options)
    await flushPromises()
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(router.replace).toHaveBeenCalledWith({ name: 'login', query: { reason: 'expired' } })
    expect(getWorkspaceStockMarketDetail).not.toHaveBeenCalled()
  })
})

describe('workspace picker recovery', () => {
  it.each([0, 1, 2])('offers creation with %i active spaces', async (count) => {
    vi.mocked(listWorkspaces).mockResolvedValueOnce(
      response(
        Array.from({ length: count }, (_, index) => ({ ...workspace, workspaceId: 12 + index })),
      ),
    )
    const wrapper = mount(WorkspaceListView, options)
    await flushPromises()
    expect(wrapper.findAll('.workspace-row')).toHaveLength(count)
    expect(wrapper.find('.create-form').exists()).toBe(true)
    expect(router.push).not.toHaveBeenCalled()
  })

  it.each(['create', 'join'] as const)(
    'invalidates a delayed %s on unmount without changing the next page or navigating',
    async (action) => {
      const write = deferred<Awaited<ReturnType<typeof createWorkspace>>>()
      const joining = deferred<Awaited<ReturnType<typeof joinWorkspace>>>()
      vi.mocked(createWorkspace).mockReturnValueOnce(write.promise)
      vi.mocked(joinWorkspace).mockReturnValueOnce(joining.promise)
      const wrapper = mount(WorkspaceListView, options)
      await flushPromises()
      if (action === 'create') {
        await wrapper.find('#workspace-name').setValue('第二个空间')
        await wrapper.find('.create-form').trigger('submit')
      } else {
        await wrapper.find('#invite-code').setValue('A1B2C3')
        await wrapper.find('.join-form').trigger('submit')
      }
      wrapper.unmount()
      const store = useWorkspaceStore()
      store.currentWorkspace = { ...workspace, workspaceId: 13, name: '后来进入的空间' }
      write.resolve(response(workspace))
      joining.resolve(response({ workspace, newlyJoined: true }))
      await flushPromises()
      expect(store.currentWorkspace.workspaceId).toBe(13)
      expect(listWorkspaces).toHaveBeenCalledTimes(1)
      expect(router.push).not.toHaveBeenCalled()
    },
  )

  it.each(['create', 'join'] as const)(
    'handles a current-session 401 from %s without leaving a misleading form error',
    async (action) => {
      const error = new ApiError('UNAUTHENTICATED', '登录已过期', 401)
      vi.mocked(createWorkspace).mockRejectedValueOnce(error)
      vi.mocked(joinWorkspace).mockRejectedValueOnce(error)
      const wrapper = mount(WorkspaceListView, options)
      await flushPromises()
      if (action === 'create') {
        await wrapper.find('#workspace-name').setValue('第二个空间')
        await wrapper.find('.create-form').trigger('submit')
      } else {
        await wrapper.find('#invite-code').setValue('A1B2C3')
        await wrapper.find('.join-form').trigger('submit')
      }
      await flushPromises()
      expect(useAuthStore().isAuthenticated).toBe(false)
      expect(router.replace).toHaveBeenCalledWith({ name: 'login', query: { reason: 'expired' } })
      expect(router.push).not.toHaveBeenCalled()
      expect(wrapper.text()).not.toContain('登录已过期')
    },
  )
})

describe('research entry recovery', () => {
  it('shows the initial workspace error and retries the complete read without any write', async () => {
    vi.mocked(getWorkspace).mockRejectedValueOnce(new Error('空间网络暂时不可用'))
    const wrapper = mount(WorkspaceStockResearchView, options)
    await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('空间网络暂时不可用')
    expect(getWorkspaceStockResearch).not.toHaveBeenCalled()
    expect(useAuthStore().isAuthenticated).toBe(true)
    await wrapper.find('.page-state button').trigger('click')
    await flushPromises()
    expect(wrapper.find('h1').text()).toBe('测试研究股票 · 研究')
    expect(getWorkspace).toHaveBeenCalledTimes(2)
    expect(getWorkspaceStockResearch).toHaveBeenCalledWith(12, 1)
    expect(getWorkspaceStockResearchTimeline).toHaveBeenCalledTimes(1)
    expect(addWorkspaceStock).not.toHaveBeenCalled()
    expect(createWorkspaceStockOpinion).not.toHaveBeenCalled()
    expect(endWorkspaceStockResearch).not.toHaveBeenCalled()
  })

  it('retains the saved-operation warning over repeated workspace read failures', async () => {
    vi.mocked(getWorkspace).mockRejectedValue(new Error('空间读取失败'))
    const store = useStockStore()
    store.refreshWarning = '观点已经保存，但研究详情刷新失败，请重新加载。'
    const wrapper = mount(WorkspaceStockResearchView, options)
    await flushPromises()
    expect(wrapper.find('h1').text()).toBe('操作已经保存，空间信息暂未读取')
    await wrapper.find('.page-state button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('观点已经保存')
    expect(getWorkspace).toHaveBeenCalledTimes(2)
    expect(createWorkspaceStockOpinion).not.toHaveBeenCalled()
  })

  it('still clears an expired session and redirects to login on a real 401', async () => {
    vi.mocked(getWorkspace).mockRejectedValueOnce(
      new ApiError('UNAUTHENTICATED', '登录已过期', 401),
    )
    const wrapper = mount(WorkspaceStockResearchView, options)
    await flushPromises()
    expect(useAuthStore().isAuthenticated).toBe(false)
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(router.replace).toHaveBeenCalledWith({ name: 'login', query: { reason: 'expired' } })
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })
})
