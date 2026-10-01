import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  getCurrentUser,
  login as loginRequest,
  updateNickname as updateNicknameRequest,
  type CurrentUserResponseVO,
  type LoginRequestDTO,
  type LoginResponseVO,
} from '@/api/auth'
import { TOKEN_STORAGE_KEY } from '@/api/http'
import { useStockStore } from '@/stores/stock'
import { useAdminStore } from '@/stores/admin'

const USER_STORAGE_KEY = 'stock-system-current-user'

interface CurrentUser {
  userId: number
  username: string
  nickname: string
  accountType: 'REGULAR' | 'SUPER_ADMIN'
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
  const isSuperAdmin = computed(() => currentUser.value?.accountType === 'SUPER_ADMIN')
  // 读取／资料回调属于发起时的会话；退出或重新登录后不能恢复旧账号。
  const sessionVersion = ref(0)
  let loginVersion = 0

  function invalidateLogin() {
    loginVersion += 1
  }

  async function login(request: LoginRequestDTO) {
    const version = ++loginVersion
    const session = sessionVersion.value
    const result = (await loginRequest(request)).data
    if (version !== loginVersion || session !== sessionVersion.value) return false
    if (result.code !== 'SUCCESS') throw new Error(result.message || '登录失败，请稍后重试')
    saveSession(result.data)
    return true
  }

  async function refreshCurrentUser() {
    const session = sessionVersion.value
    const result = (await getCurrentUser()).data
    if (session !== sessionVersion.value) return
    if (result.code !== 'SUCCESS') throw new Error(result.message || '登录状态验证失败')
    saveCurrentUser(result.data)
    sessionVerified.value = true
  }

  async function updateNickname(nickname: string) {
    const session = sessionVersion.value
    const result = (await updateNicknameRequest(nickname)).data
    if (session !== sessionVersion.value) return null
    saveCurrentUser(result.data)
    return result.data
  }

  function saveSession(loginResponse: LoginResponseVO) {
    sessionVersion.value += 1
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
      accountType: userResponse.accountType,
    }

    currentUser.value = user
    window.localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(user))
  }

  function logout() {
    sessionVersion.value += 1
    invalidateLogin()
    accessToken.value = null
    currentUser.value = null
    sessionVerified.value = false
    window.localStorage.removeItem(TOKEN_STORAGE_KEY)
    window.localStorage.removeItem(USER_STORAGE_KEY)
    useStockStore().clearAll()
    useAdminStore().clearAll()
  }

  return {
    accessToken,
    currentUser,
    sessionVerified,
    sessionVersion,
    isAuthenticated,
    isSuperAdmin,
    login,
    invalidateLogin,
    refreshCurrentUser,
    updateNickname,
    logout,
  }
})
