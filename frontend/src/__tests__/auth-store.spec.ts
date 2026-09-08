import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosHeaders, type AxiosResponse } from 'axios'
import {
  getCurrentUser,
  login,
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
        },
      }),
    )

    const authStore = useAuthStore()
    await authStore.login({ username: 'frontend_user', password: 'FrontendPass123' })

    expect(window.localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('jwt-token')
    expect(authStore.isAuthenticated).toBe(true)

    await authStore.refreshCurrentUser()

    expect(getCurrentUser).toHaveBeenCalledOnce()
    expect(authStore.currentUser?.nickname).toBe('数据库最新昵称')
  })
})
