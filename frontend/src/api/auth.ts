import { http } from './http'

export interface ApiResult<T> {
  code: string
  message: string
  data: T
}

export interface LoginRequestDTO {
  username: string
  password: string
}

export interface LoginResponseVO {
  accessToken: string
  tokenType: 'Bearer'
  expiresInSeconds: number
  userId: number
  username: string
  nickname: string
  accountType: 'REGULAR' | 'SUPER_ADMIN'
}

export interface RegisterRequestDTO {
  username: string
  password: string
  nickname: string
}

export interface RegisterResponseVO {
  userId: number
  username: string
  nickname: string
}

export interface CurrentUserResponseVO {
  userId: number
  username: string
  nickname: string
  accountType: 'REGULAR' | 'SUPER_ADMIN'
}

export function login(request: LoginRequestDTO) {
  return http.post<ApiResult<LoginResponseVO>>('/auth/login', request)
}

export function register(request: RegisterRequestDTO) {
  return http.post<ApiResult<RegisterResponseVO>>('/auth/register', request)
}

export function getCurrentUser() {
  return http.get<ApiResult<CurrentUserResponseVO>>('/auth/me')
}

export function updateNickname(nickname: string) {
  return http.patch<ApiResult<CurrentUserResponseVO>>('/auth/me', { nickname })
}

export function changePassword(currentPassword: string, newPassword: string) {
  return http.post<ApiResult<null>>('/auth/password', { currentPassword, newPassword })
}
