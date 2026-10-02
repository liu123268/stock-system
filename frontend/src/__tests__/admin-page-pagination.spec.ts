import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosHeaders, type AxiosResponse } from 'axios'
import type { Router } from 'vue-router'
import type { ApiResult } from '@/api/auth'
import {
  archiveAdminWorkspace,
  disableAdminUser,
  getAdminUsers,
  getAdminWorkspaces,
  type AdminListQuery,
  type AdminUserDetailResponseVO,
  type AdminWorkspaceDetailResponseVO,
} from '@/api/admin'
import { useAdminStore } from '@/stores/admin'
import AdminUsersView from '@/views/admin/AdminUsersView.vue'
import AdminWorkspacesView from '@/views/admin/AdminWorkspacesView.vue'

const { route, router } = vi.hoisted(() => ({
  route: { fullPath: '/admin/users' },
  router: { push: vi.fn<Router['push']>(), replace: vi.fn<Router['replace']>() },
}))
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => router }))
vi.mock('@/api/admin')
enableAutoUnmount(afterEach)

function response<T>(data: T): AxiosResponse<ApiResult<T>> {
  return { data: { code: 'SUCCESS', message: '成功', data }, status: 200, statusText: 'OK',
    headers: {}, config: { headers: new AxiosHeaders() } }
}
const scrollDescriptor = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView')
beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear()
  setActivePinia(createPinia())
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn<() => void>() })
})
afterEach(() => {
  if (scrollDescriptor) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', scrollDescriptor)
  else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView')
})

function scenario(kind: 'users' | 'workspaces', count: number, mode: 'success' | 'failure' | 'delay' = 'success') {
  route.fullPath = `/admin/${kind}`
  let saved = false
  let writes = 0
  let release = () => {}
  const queries: AdminListQuery[] = []
  async function beforeRead(query: AdminListQuery) {
    queries.push({ ...query })
    if (!saved || query.page !== 1 || mode === 'success') return
    const behavior = mode
    mode = 'success'
    if (behavior === 'failure') throw new Error('有效页读取暂时失败')
    await new Promise<void>((done) => { release = done })
  }
  if (kind === 'users') {
    const users: AdminUserDetailResponseVO[] = Array.from({ length: count }, (_, index) => ({
      userId: index + 1, username: `test_user_${index + 1}`, nickname: `用户${index + 1}`,
      status: 'ACTIVE', accountType: 'REGULAR', workspaceCount: 0, ownedWorkspaceCount: 0,
      createdAt: '2026-10-02T09:00:00Z', memberships: [],
    }))
    vi.mocked(getAdminUsers).mockImplementation(async (query) => {
      await beforeRead(query)
      const { page = 1, size = 20, status } = query
      const matches = users.filter((user) => !status || user.status === status)
      return response({ items: matches.slice((page - 1) * size, page * size).map((user) => ({ ...user })),
        page, size, total: matches.length, totalPages: Math.ceil(matches.length / size) })
    })
    vi.mocked(disableAdminUser).mockImplementation(async (id) => {
      const target = users.find((user) => user.userId === id)!
      target.status = 'DISABLED'
      writes += 1
      saved = true
      return response({ ...target })
    })
  } else {
    const workspaces: AdminWorkspaceDetailResponseVO[] = Array.from({ length: count }, (_, index) => ({
      workspaceId: index + 1, name: `空间${index + 1}`, status: 'ACTIVE', createdByUserId: 1,
      createdByUsername: 'test_owner', ownerUserId: 1, ownerUsername: 'test_owner', memberCount: 1,
      archivedAt: null, createdAt: '2026-10-02T09:00:00Z', members: [],
    }))
    vi.mocked(getAdminWorkspaces).mockImplementation(async (query) => {
      await beforeRead(query)
      const { page = 1, size = 20, status } = query
      const matches = workspaces.filter((workspace) => !status || workspace.status === status)
      return response({ items: matches.slice((page - 1) * size, page * size).map((workspace) => ({ ...workspace })),
        page, size, total: matches.length, totalPages: Math.ceil(matches.length / size) })
    })
    vi.mocked(archiveAdminWorkspace).mockImplementation(async (id) => {
      const target = workspaces.find((workspace) => workspace.workspaceId === id)!
      target.status = 'ARCHIVED_BY_SUPER_ADMIN'
      writes += 1
      saved = true
      return response({ ...target })
    })
  }
  return {
    component: kind === 'users' ? AdminUsersView : AdminWorkspacesView,
    action: kind === 'users' ? '禁用' : '治理归档',
    confirm: kind === 'users' ? '确认禁用' : '确认归档',
    queries,
    writes: () => writes,
    release: () => release(),
    state: () => kind === 'users' ? useAdminStore().users : useAdminStore().workspaces,
  }
}

async function performAction(setup: ReturnType<typeof scenario>, lastPage: boolean) {
  const view = mount(setup.component)
  await flushPromises()
  await view.get('select').setValue('ACTIVE')
  await view.get('form').trigger('submit')
  await flushPromises()
  if (lastPage) {
    await view.findAll('button').find((button) => button.text() === '下一页')!.trigger('click')
    await flushPromises()
  }
  expect(setup.state().items).toHaveLength(1)
  await view.findAll('button').find((button) => button.text() === setup.action)!.trigger('click')
  await flushPromises()
  await view.get('[role="alertdialog"]').findAll('button')
    .find((button) => button.text() === setup.confirm)!.trigger('click')
  await flushPromises()
  return view
}

describe('governance filtered pagination after a successful write', () => {
  it.each([
    ['users', 21, [2, 1]], ['workspaces', 21, [2, 1]], ['users', 1, [1]], ['workspaces', 1, [1]],
  ] as const)('keeps %s on a valid page when %i matching rows lose one', async (kind, count, expectedPages) => {
    const setup = scenario(kind, count)
    const view = await performAction(setup, count > 20)
    expect(setup.writes()).toBe(1)
    expect(setup.state().page).toBe(1)
    expect(setup.state().total).toBe(count - 1)
    expect(setup.state().items).toHaveLength(count - 1)
    expect(view.get('.admin-pagination').text()).toContain('1 / 1')
    expect(setup.queries.slice(-expectedPages.length).map((query) => query.page)).toEqual(expectedPages)
    expect(view.text().includes('没有符合条件')).toBe(count === 1)
    expect(setup.queries.filter((query) => query.status === 'ACTIVE').every((query) => query.size === 20)).toBe(true)
  })

  it.each(['users', 'workspaces'] as const)('retries a failed %s corrected-page GET without another POST', async (kind) => {
    const setup = scenario(kind, 21, 'failure')
    const view = await performAction(setup, true)
    expect(view.get('[role="alert"]').text()).toContain('操作已完成，但列表刷新失败')
    expect(view.get('[role="alert"]').text()).toContain('有效页读取暂时失败')
    expect(setup.writes()).toBe(1)
    await view.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(setup.state().items).toHaveLength(20)
    expect(setup.state().page).toBe(1)
    expect(view.find('[role="alert"]').exists()).toBe(false)
    expect(setup.writes()).toBe(1)
    expect(setup.queries.slice(-3).map((query) => query.page)).toEqual([2, 1, 1])
    expect(setup.queries.slice(-3).every((query) => query.status === 'ACTIVE')).toBe(true)
  })

  it.each(['users', 'workspaces'] as const)('does not replace a new %s list with an old correction after leaving', async (kind) => {
    const setup = scenario(kind, 21, 'delay')
    const oldView = await performAction(setup, true)
    expect(setup.queries[setup.queries.length - 1]?.page).toBe(1)
    oldView.unmount()
    const newView = mount(setup.component)
    await flushPromises()
    expect(setup.state().total).toBe(21)
    expect(setup.queries[setup.queries.length - 1]?.status).toBe('')
    setup.release()
    await flushPromises()
    expect(setup.state().total).toBe(21)
    expect(newView.find('.admin-success').exists()).toBe(false)
    expect(setup.writes()).toBe(1)
    expect(router.replace).not.toHaveBeenCalled()
  })
})
