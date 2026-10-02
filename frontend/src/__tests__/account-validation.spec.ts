import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { AxiosHeaders, type AxiosResponse } from 'axios'
import type { ApiResult } from '@/api/auth'
import RegisterView from '@/views/RegisterView.vue'
import ProfileView from '@/views/ProfileView.vue'
import LoginView from '@/views/LoginView.vue'
import { useAuthStore } from '@/stores/auth'

const api = vi.hoisted(() => ({
  register: vi.fn<typeof import('@/api/auth').register>(),
  changePassword: vi.fn<typeof import('@/api/auth').changePassword>(),
  login: vi.fn<typeof import('@/api/auth').login>(),
}))
vi.mock('@/api/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/auth')>()),
  ...api,
}))

let wrapper: VueWrapper | undefined

function response<T>(data: T): AxiosResponse<ApiResult<T>> {
  return {
    data: { code: 'SUCCESS', message: '成功', data },
    status: 200,
    statusText: 'OK',
    headers: {},
    config: { headers: new AxiosHeaders() },
  }
}

beforeEach(() => {
  localStorage.clear()
  vi.resetAllMocks()
})
afterEach(() => wrapper?.unmount())

async function mountPage(component: typeof RegisterView, path: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/register', name: 'register', component: RegisterView },
      { path: '/login', name: 'login', component: LoginView },
      { path: '/profile', name: 'profile', component: ProfileView },
      { path: '/workspaces', name: 'workspaces', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  wrapper = mount(component, { global: { plugins: [pinia, router] } })
  return { page: wrapper, router, auth: useAuthStore() }
}

describe('account credential validation', () => {
  it.each(['a1B2', 'a1B2c'])(
    'blocks a new short password %s before sending registration',
    async (password) => {
      const { page } = await mountPage(RegisterView, '/register')
      await page.get('#register-username').setValue('ab')
      await page.get('#register-nickname').setValue('林')
      await page.get('#register-password').setValue(password)
      await page.get('input[name="confirmPassword"]').setValue(password)
      await page.get('form').trigger('submit')
      await flushPromises()
      expect(api.register).not.toHaveBeenCalled()
      // Element Plus 的校验消息使用防抖更新，等待实际提示出现。
      await vi.waitFor(() => expect(page.text()).toContain('密码长度为6到64个字符'))
    },
  )

  it('blocks a one character username before registration', async () => {
    const { page } = await mountPage(RegisterView, '/register')
    await page.get('#register-username').setValue('a')
    await page.get('#register-nickname').setValue('林')
    await page.get('#register-password').setValue('a1B2c3')
    await page.get('input[name="confirmPassword"]').setValue('a1B2c3')
    await page.get('form').trigger('submit')
    await flushPromises()
    expect(api.register).not.toHaveBeenCalled()
    await vi.waitFor(() => expect(page.text()).toContain('登录账号长度为2到20个字符'))
  })

  it('accepts a two character username and six character new password', async () => {
    api.register.mockResolvedValue(response({ userId: 1, username: 'ab', nickname: '林' }))
    const { page, router } = await mountPage(RegisterView, '/register')
    await page.get('#register-username').setValue('ab')
    await page.get('#register-nickname').setValue('林')
    await page.get('#register-password').setValue('a1B2c3')
    await page.get('input[name="confirmPassword"]').setValue('a1B2c3')
    await page.get('form').trigger('submit')
    await flushPromises()
    expect(api.register).toHaveBeenCalledWith({
      username: 'ab',
      nickname: '林',
      password: 'a1B2c3',
    })
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('still submits a legacy four character password from the login page', async () => {
    api.login.mockResolvedValue(
      response({
        accessToken: 'test-token',
        userId: 1,
        username: 'legacy',
        nickname: '历史用户',
        accountType: 'REGULAR',
        tokenType: 'Bearer',
        expiresInSeconds: 86400,
      }),
    )
    const { page, router } = await mountPage(LoginView, '/login')
    await page.get('#login-username').setValue('legacy')
    await page.get('#login-password').setValue('a1B2')
    await page.get('form').trigger('submit')
    await flushPromises()
    expect(api.login).toHaveBeenCalledWith({ username: 'legacy', password: 'a1B2' })
    expect(router.currentRoute.value.name).toBe('workspaces')
  })

  it.each(['a1B2', 'a1B2c'])(
    'blocks a short replacement password %s without changing the session',
    async (password) => {
      const { page, auth } = await mountPage(ProfileView, '/profile')
      auth.accessToken = 'old-token'
      await page.get('#current-password').setValue('a1B2')
      await page.get('#new-password').setValue(password)
      await page.get('#confirm-password').setValue(password)
      await page.findAll('form')[1]!.trigger('submit')
      await flushPromises()
      expect(api.changePassword).not.toHaveBeenCalled()
      expect(auth.accessToken).toBe('old-token')
      expect(page.text()).toContain('新密码长度需要 6 到 64 个字符')
    },
  )

  it('allows the legacy original password while upgrading and clears the session only on success', async () => {
    api.changePassword.mockResolvedValue(response(null))
    const { page, auth, router } = await mountPage(ProfileView, '/profile')
    auth.accessToken = 'old-token'
    auth.currentUser = {
      userId: 1,
      username: 'legacy',
      nickname: '历史用户',
      accountType: 'REGULAR',
    }
    await page.get('#current-password').setValue('a1B2')
    await page.get('#new-password').setValue('a1B2c3')
    await page.get('#confirm-password').setValue('a1B2c3')
    await page.findAll('form')[1]!.trigger('submit')
    await flushPromises()
    expect(api.changePassword).toHaveBeenCalledWith('a1B2', 'a1B2c3')
    expect(auth.accessToken).toBeNull()
    expect(router.currentRoute.value.query.reason).toBe('password-changed')
  })
})
