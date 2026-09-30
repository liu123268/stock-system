import { afterEach, describe, expect, it } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { http, TOKEN_STORAGE_KEY } from '@/api/http'

describe('HTTP response session ownership', () => {
  afterEach(() => window.localStorage.clear())

  it.each([
    ['old-token', false],
    ['new-token', true],
    [null, true],
  ] as const)(
    'marks a delayed failure when the current token is %s',
    async (currentToken, staleSession) => {
      window.localStorage.setItem(TOKEN_STORAGE_KEY, 'old-token')
      await expect(
        http.get('/workspaces', {
          adapter: async (config) => {
            expect(config.headers.get('Authorization')).toBe('Bearer old-token')
            if (currentToken) window.localStorage.setItem(TOKEN_STORAGE_KEY, currentToken)
            else window.localStorage.removeItem(TOKEN_STORAGE_KEY)
            throw new AxiosError('unauthorized', 'ERR_BAD_REQUEST', config, undefined, {
              config,
              status: 401,
              statusText: 'Unauthorized',
              headers: new AxiosHeaders(),
              data: { code: 'UNAUTHENTICATED', message: '未登录' },
            })
          },
        }),
      ).rejects.toMatchObject({ code: 'UNAUTHENTICATED', staleSession })
    },
  )
})
