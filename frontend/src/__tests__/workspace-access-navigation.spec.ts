import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { AxiosHeaders, type AxiosResponse } from 'axios'
import { login, type ApiResult } from '@/api/auth'
import { ApiError, TOKEN_STORAGE_KEY } from '@/api/http'
import { listWorkspaces, type WorkspaceSummaryResponseVO } from '@/api/workspace'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import { handleWorkspaceApiError } from '@/utils/workspace-access'
import WorkspaceListView from '@/views/workspace/WorkspaceListView.vue'

vi.mock('@/api/workspace')
vi.mock('@/api/auth')
enableAutoUnmount(afterEach)

function response<T>(data: T): AxiosResponse<ApiResult<T>> {
  return { data: { code: 'SUCCESS', message: '成功', data }, status: 200, statusText: 'OK',
    headers: {}, config: { headers: new AxiosHeaders() } }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}
const workspace: WorkspaceSummaryResponseVO = { workspaceId: 12, name: '旧空间',
  role: 'MEMBER', status: 'ACTIVE', memberCount: 2, createdAt: '2026-10-02T08:00:00' }

beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear()
  localStorage.setItem(TOKEN_STORAGE_KEY, 'old-session')
  setActivePinia(createPinia())
})

async function application() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/workspaces/12/stocks', component: { template: '<div>原空间页面</div>' } },
    { path: '/workspaces', name: 'workspaces', component: WorkspaceListView },
    { path: '/profile', component: { template: '<div>个人资料</div>' } },
    { path: '/workspaces/99/overview', component: { template: '<div>新账号的空间</div>' } },
  ] })
  await router.push('/workspaces/12/stocks')
  const wrapper = mount(RouterView, { global: { plugins: [pinia, router] } })
  return { router, wrapper }
}

describe('workspace access navigation and selector-owned reads', () => {
  it.each([
    ['WORKSPACE_MEMBERSHIP_INACTIVE', 'access-lost', 'success'],
    ['WORKSPACE_MEMBERSHIP_INACTIVE', 'access-lost', 'failure'],
    ['WORKSPACE_ARCHIVED', 'archived', 'success'],
    ['WORKSPACE_ARCHIVED', 'archived', 'failure'],
  ] as const)('does not wait or navigate again for %s (%s) and a late %s list', async (code, reason, outcome) => {
    const list = deferred<Awaited<ReturnType<typeof listWorkspaces>>>()
    vi.mocked(listWorkspaces).mockReturnValueOnce(list.promise)
    const { router } = await application()
    const handling = handleWorkspaceApiError(new ApiError(code, '权限失效', 403), router)
    await flushPromises()
    // 选择页已出现，列表仍未完成；仅由该页发起一次读取。
    expect(router.currentRoute.value.fullPath).toBe(`/workspaces?reason=${reason}`)
    expect(listWorkspaces).toHaveBeenCalledTimes(1)
    await handling
    await router.push('/profile')
    await flushPromises()
    if (outcome === 'success') list.resolve(response([workspace]))
    else list.reject(new Error('旧选择页列表读取失败'))
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/profile')
    expect(useWorkspaceStore().workspaces).toEqual([])
    expect(useWorkspaceStore().errorMessage).toBe('')
    expect(useAuthStore().accessToken).toBe('old-session')
  })

  it('keeps the new account and its route after an old selector 401 finishes', async () => {
    const list = deferred<Awaited<ReturnType<typeof listWorkspaces>>>()
    vi.mocked(listWorkspaces).mockReturnValueOnce(list.promise)
    const { router } = await application()
    const handling = handleWorkspaceApiError(
      new ApiError('WORKSPACE_MEMBERSHIP_INACTIVE', '权限失效', 403), router,
    )
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/workspaces')
    await handling
    const auth = useAuthStore()
    auth.logout()
    useWorkspaceStore().clearAll()
    vi.mocked(login).mockResolvedValueOnce(response({ accessToken: 'new-session', tokenType: 'Bearer',
      expiresInSeconds: 3600, userId: 99, username: 'new_user', nickname: '新用户', accountType: 'REGULAR' }))
    await auth.login({ username: 'new_user', password: 'test-password' })
    await router.push('/workspaces/99/overview')
    await flushPromises()
    list.reject(new ApiError('UNAUTHENTICATED', '旧会话过期', 401, true))
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/workspaces/99/overview')
    expect(auth.accessToken).toBe('new-session')
  })

  it('lets the selector display and retry a list network failure without expiring the account', async () => {
    vi.mocked(listWorkspaces).mockRejectedValueOnce(new Error('列表暂时不可用'))
    const { router, wrapper } = await application()
    await handleWorkspaceApiError(new ApiError('WORKSPACE_NOT_FOUND', '空间不可见', 404), router)
    await flushPromises()
    expect(wrapper.text()).toContain('列表暂时不可用')
    expect(useAuthStore().accessToken).toBe('old-session')
    vi.mocked(listWorkspaces).mockResolvedValueOnce(response([workspace]))
    await wrapper.findAll('button').find((button) => button.text() === '重新加载')!.trigger('click')
    await flushPromises()
    expect(listWorkspaces).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('旧空间')
    expect(wrapper.text()).not.toContain('列表暂时不可用')
    // reason=access-lost 保留选择页，不因只有一间空间又自动进入。
    expect(router.currentRoute.value.fullPath).toBe('/workspaces?reason=access-lost')
  })
})
