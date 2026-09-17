import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  getCurrentUser,
  login as loginRequest,
  type CurrentUserResponseVO,
  type LoginRequestDTO,
  type LoginResponseVO,
} from '@/api/auth'
import { TOKEN_STORAGE_KEY } from '@/api/http'
import { useStockStore } from '@/stores/stock'

const USER_STORAGE_KEY = 'stock-system-current-user'

interface CurrentUser {
  userId: number
  username: string
  nickname: string
}

function readStoredUser(): CurrentUser | null {
  const storedValue = window.localStorage.getItem(USER_STORAGE_KEY)
  if (!storedValue) return null

  try {
    return JSON.parse(storedValue) as CurrentUser
  } catch {
    window.localStorage.removeItem(USER_STORAGE_KEY)
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref(window.localStorage.getItem(TOKEN_STORAGE_KEY))
  const currentUser = ref<CurrentUser | null>(readStoredUser())
  const sessionVerified = ref(false)
  const isAuthenticated = computed(() => Boolean(accessToken.value))

  async function login(request: LoginRequestDTO) {
    const result = (await loginRequest(request)).data
    if (result.code !== 'SUCCESS') throw new Error(result.message || '登录失败，请稍后重试')
    saveSession(result.data)
  }

  async function refreshCurrentUser() {
    const result = (await getCurrentUser()).data
    if (result.code !== 'SUCCESS') throw new Error(result.message || '登录状态验证失败')
    saveCurrentUser(result.data)
    sessionVerified.value = true
  }

  function saveSession(loginResponse: LoginResponseVO) {
    accessToken.value = loginResponse.accessToken
    window.localStorage.setItem(TOKEN_STORAGE_KEY, loginResponse.accessToken)
    saveCurrentUser(loginResponse)
    sessionVerified.value = true
  }

  function saveCurrentUser(userResponse: CurrentUserResponseVO | LoginResponseVO) {
    const user: CurrentUser = {
      userId: userResponse.userId,
      username: userResponse.username,
      nickname: userResponse.nickname,
    }

    currentUser.value = user
    window.localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(user))
  }

  function logout() {
    accessToken.value = null
    currentUser.value = null
    sessionVerified.value = false
    window.localStorage.removeItem(TOKEN_STORAGE_KEY)
    window.localStorage.removeItem(USER_STORAGE_KEY)
    useStockStore().clearAll()
  }

  return {
    accessToken,
    currentUser,
    sessionVerified,
    isAuthenticated,
    login,
    refreshCurrentUser,
    logout,
  }
})
