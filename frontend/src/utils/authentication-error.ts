import { ApiError } from '@/api/http'

export function isAuthenticationFailure(error: unknown) {
  return (
    error instanceof ApiError &&
    (error.status === 401 || error.code === 'UNAUTHENTICATED' || error.code === 'ACCOUNT_DISABLED')
  )
}
