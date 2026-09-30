import axios, { isAxiosError } from 'axios'

const TOKEN_STORAGE_KEY = 'stock-system-access-token'

export const http = axios.create({
  baseURL: '/api',
  timeout: 10_000,
  headers: { 'Content-Type': 'application/json' },
})

export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status?: number,
    public readonly staleSession = false,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

http.interceptors.request.use((config) => {
  const token = window.localStorage.getItem(TOKEN_STORAGE_KEY)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (isAxiosError(error)) {
      const responseData = error.response?.data as { code?: unknown; message?: unknown } | undefined
      if (typeof responseData?.code === 'string' && typeof responseData.message === 'string') {
        const currentToken = window.localStorage.getItem(TOKEN_STORAGE_KEY)
        const requestAuthorization = error.config?.headers.get('Authorization') ?? null
        const staleSession =
          requestAuthorization !== (currentToken ? `Bearer ${currentToken}` : null)
        return Promise.reject(
          new ApiError(
            responseData.code,
            responseData.message,
            error.response?.status,
            staleSession,
          ),
        )
      }
    }
    return Promise.reject(error)
  },
)

export { TOKEN_STORAGE_KEY }
