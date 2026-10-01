import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosHeaders, type AxiosResponse } from 'axios'
import {
  getCurrentUser,
  login,
  updateNickname,
  type ApiResult,
  type CurrentUserResponseVO,
  type LoginResponseVO,
} from '@/api/auth'
import { TOKEN_STORAGE_KEY } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

vi.mock('@/api/auth')

function axiosResponse<T>(data: T): AxiosResponse<T> {
  return {
    data,
    status: 200,
    statusText: 'OK',
    headers: {},
    config: { headers: new AxiosHeaders() },
  }
}

describe('auth store', () => {
  beforeEach(() => {
    window.localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('saves JWT after login and refreshes user through the protected me API', async () => {
    vi.mocked(login).mockResolvedValue(
      axiosResponse<ApiResult<LoginResponseVO>>({
        code: 'SUCCESS',
        message: '成功',
        data: {
          accessToken: 'jwt-token',
          tokenType: 'Bearer',
          expiresInSeconds: 86400,
          userId: 9,
          username: 'frontend_user',
          nickname: '前端用户',
          accountType: 'REGULAR',
        },
      }),
    )
    vi.mocked(getCurrentUser).mockResolvedValue(
      axiosResponse<ApiResult<CurrentUserResponseVO>>({
        code: 'SUCCESS',
        message: '成功',
        data: {
          userId: 9,
          username: 'frontend_user',
          nickname: '数据库最新昵称',
          accountType: 'REGULAR',
        },
      }),
    )

    const authStore = useAuthStore()
    await authStore.login({ username: 'frontend_user', password: 'FrontendPass123' })

    expect(window.localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('jwt-token')
    expect(authStore.isAuthenticated).toBe(true)
    expect(authStore.sessionVerified).toBe(true)

    await authStore.refreshCurrentUser()

    expect(getCurrentUser).toHaveBeenCalledOnce()
    expect(authStore.currentUser?.nickname).toBe('数据库最新昵称')
    expect(authStore.sessionVerified).toBe(true)
  })

  it('does not treat a restored local session as verified before the backend confirms it', () => {
    window.localStorage.setItem(TOKEN_STORAGE_KEY, 'restored-token')
    window.localStorage.setItem(
      'stock-system-current-user',
      JSON.stringify({
        userId: 9,
        username: 'frontend_user',
        nickname: '前端用户',
        accountType: 'REGULAR',
      }),
    )
    setActivePinia(createPinia())

    const authStore = useAuthStore()

    expect(authStore.isAuthenticated).toBe(true)
    expect(authStore.sessionVerified).toBe(false)
  })

  it('updates the shared current user after a nickname change', async () => {
    vi.mocked(updateNickname).mockResolvedValue(
      axiosResponse<ApiResult<CurrentUserResponseVO>>({
        code: 'SUCCESS',
        message: '成功',
        data: { userId: 9, username: 'frontend_user', nickname: '新昵称', accountType: 'REGULAR' },
      }),
    )
    const store = useAuthStore()
    await store.updateNickname(' 新昵称 ')
    expect(updateNickname).toHaveBeenCalledWith(' 新昵称 ')
    expect(store.currentUser?.nickname).toBe('新昵称')
    expect(
      JSON.parse(window.localStorage.getItem('stock-system-current-user') ?? '{}').nickname,
    ).toBe('新昵称')
  })

  function session(userId: number, token: string): ApiResult<LoginResponseVO> {
    return {
      code: 'SUCCESS',
      message: '成功',
      data: {
        accessToken: token,
        tokenType: 'Bearer',
        expiresInSeconds: 86400,
        userId,
        username: `user_${userId}`,
        nickname: `用户${userId}`,
        accountType: 'REGULAR',
      },
    }
  }
  async function signIn(userId: number, token: string) {
    vi.mocked(login).mockResolvedValueOnce(axiosResponse(session(userId, token)))
    await useAuthStore().login({ username: `user_${userId}`, password: 'Pass123' })
  }

  it('cannot restore a delayed login after its form is abandoned', async () => {
    let release!: (value: AxiosResponse<ApiResult<LoginResponseVO>>) => void
    vi.mocked(login).mockReturnValueOnce(
      new Promise((resolve) => {
        release = resolve
      }),
    )
    const store = useAuthStore()
    const pending = store.login({ username: 'old_user', password: 'Pass123' })
    store.invalidateLogin()
    release(axiosResponse(session(1, 'old-token')))
    expect(await pending).toBe(false)
    expect(store.isAuthenticated).toBe(false)
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
  })

  it('keeps the newer login when an earlier login finishes last', async () => {
    let release!: (value: AxiosResponse<ApiResult<LoginResponseVO>>) => void
    vi.mocked(login).mockReturnValueOnce(
      new Promise((resolve) => {
        release = resolve
      }),
    )
    const store = useAuthStore()
    const pending = store.login({ username: 'old_user', password: 'Pass123' })
    await signIn(2, 'new-token')
    release(axiosResponse(session(1, 'old-token')))
    expect(await pending).toBe(false)
    expect(store.currentUser?.userId).toBe(2)
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('new-token')
  })

  it.each(['read', 'nickname'] as const)(
    'rejects delayed %s user data after switching accounts',
    async (mode) => {
      await signIn(1, 'first-token')
      let release!: (value: AxiosResponse<ApiResult<CurrentUserResponseVO>>) => void
      const delayed = new Promise<AxiosResponse<ApiResult<CurrentUserResponseVO>>>((resolve) => {
        release = resolve
      })
      const store = useAuthStore()
      if (mode === 'read') vi.mocked(getCurrentUser).mockReturnValueOnce(delayed)
      else vi.mocked(updateNickname).mockReturnValueOnce(delayed)
      const pending =
        mode === 'read' ? store.refreshCurrentUser() : store.updateNickname('A 新昵称')
      store.logout()
      await signIn(2, 'second-token')
      release(
        axiosResponse({
          code: 'SUCCESS',
          message: '成功',
          data: { userId: 1, username: 'user_1', nickname: 'A 新昵称', accountType: 'REGULAR' },
        }),
      )
      await pending
      expect(store.currentUser?.userId).toBe(2)
      expect(store.currentUser?.nickname).toBe('用户2')
      expect(JSON.parse(localStorage.getItem('stock-system-current-user') ?? '{}').userId).toBe(2)
      expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('second-token')
    },
  )

  it('does not restore the current user when a nickname finishes after logout', async () => {
    await signIn(1, 'first-token')
    let release!: (value: AxiosResponse<ApiResult<CurrentUserResponseVO>>) => void
    vi.mocked(updateNickname).mockReturnValueOnce(
      new Promise((resolve) => {
        release = resolve
      }),
    )
    const store = useAuthStore()
    const pending = store.updateNickname('新的名字')
    store.logout()
    release(
      axiosResponse({
        code: 'SUCCESS',
        message: '成功',
        data: { userId: 1, username: 'user_1', nickname: '新的名字', accountType: 'REGULAR' },
      }),
    )
    expect(await pending).toBeNull()
    expect(store.currentUser).toBeNull()
    expect(store.sessionVerified).toBe(false)
    expect(localStorage.getItem('stock-system-current-user')).toBeNull()
  })
})
