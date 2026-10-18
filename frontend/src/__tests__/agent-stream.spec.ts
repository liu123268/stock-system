import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TextDecoder, TextEncoder } from 'node:util'
import { ReadableStream } from 'node:stream/web'
import { agentStream } from '@/api/agent-stream'
import { ApiError, TOKEN_STORAGE_KEY } from '@/api/http'

const id = '5b4fbff0-0fea-4312-920a-4a0db07064fe'
const progress = { requestId: id, sequence: 1, runId: 7, stage: 'READING' }
const run = { requestId: id, runId: 7, status: 'SUCCEEDED', answer: { text: '已核实的中文结果' } }
const completed = { requestId: id, sequence: 2, runId: 7, result: run }
function frame(name: string, data: unknown) { return `event: ${name}\ndata: ${JSON.stringify(data)}\n\n` }
let cancelled: boolean
function response(text: string, split = 5) {
  const bytes = new TextEncoder().encode(text)
  let at = 0
  const body = new ReadableStream({
    pull(controller) {
      if (at === bytes.length) controller.close()
      else { controller.enqueue(bytes.slice(at, at + split)); at = Math.min(bytes.length, at + split) }
    }, cancel() { cancelled = true },
  })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, headers: new Headers({ 'Content-Type': 'text/event-stream;charset=UTF-8' }), body }))
}
function execute(onProgress = vi.fn<() => void>()) { return agentStream('/agent/messages/stream', { requestId: id, question: '问题' }, { signal: new AbortController().signal, onProgress }) }
beforeEach(() => {
  cancelled = false
  window.localStorage.setItem(TOKEN_STORAGE_KEY, 'old-token')
  vi.stubGlobal('TextDecoder', TextDecoder)
  vi.stubGlobal('TextEncoder', TextEncoder)
})
afterEach(() => { vi.unstubAllGlobals(); window.localStorage.clear() })

describe('带认证头的助手 SSE', () => {
  it('解析逐字节中文、CRLF、注释和多行 data，并只交付最终结构', async () => {
    const callback = vi.fn<() => void>()
    response(`: keep-alive\r\n\r\n${frame('progress', progress)}event: result\ndata: ${JSON.stringify(completed).slice(0, 1)}\ndata: ${JSON.stringify(completed).slice(1)}\n\n`.replace(/\r?\n/g, '\r\n'), 1)
    expect(await execute(callback)).toEqual(run)
    expect(callback).toHaveBeenCalledExactlyOnceWith(progress)
    expect(fetch).toHaveBeenCalledExactlyOnceWith('/api/agent/messages/stream', expect.objectContaining({
      method: 'POST', headers: expect.objectContaining({ Authorization: 'Bearer old-token', Accept: 'text/event-stream' }),
    }))
  })

  it.each([
    ['其他请求', { ...progress, requestId: 'other' }],
    ['未知阶段', { ...progress, stage: 'THINKING' }],
    ['非法运行号', { ...progress, runId: -1 }],
  ])('拒绝%s', async (_, value) => {
    response(frame('progress', value) + frame('result', completed))
    await expect(execute()).rejects.toBeInstanceOf(ApiError)
  })

  it.each([
    ['重复序号', frame('progress', progress)],
    ['串运行', frame('result', { ...completed, runId: 8, result: { ...run, runId: 8 } })],
    ['串请求结果', frame('result', { ...completed, result: { ...run, requestId: 'other' } })],
    ['结果无运行号', frame('result', { ...completed, runId: null, result: { ...run, runId: null } })],
    ['失败带正文', frame('result', { ...completed, result: { ...run, status: 'CANCELLED' } })],
    ['未完成 JSON', 'event: result\ndata: {\n\n'],
    ['未知事件', frame('thinking', completed)],
  ])('拒绝%s且不重复 POST', async (_, tail) => {
    response(frame('progress', progress) + tail)
    await expect(execute()).rejects.toBeDefined()
    expect(fetch).toHaveBeenCalledOnce()
  })

  it('断流不把已有进度当成回答', async () => {
    response(frame('progress', progress))
    await expect(execute()).rejects.toMatchObject({ code: 'AGENT_STREAM_INTERRUPTED' })
    expect(fetch).toHaveBeenCalledOnce()
  })

  it('限制未分行事件和总字节数', async () => {
    response(`data: ${'a'.repeat(256 * 1024 + 1)}`, 300 * 1024)
    await expect(execute()).rejects.toBeInstanceOf(ApiError)
    response(`:${'a'.repeat(1000)}\n\n`.repeat(600), 1000)
    await expect(execute()).rejects.toBeInstanceOf(ApiError)
  })

  it('最终结果抵达后取消读取并释放连接', async () => {
    response(frame('result', completed) + ': 仍有后续心跳\n\n', 1)
    expect(await execute()).toEqual(run)
    expect(cancelled).toBe(true)
  })

  it('一个网络块可以携带多项合规事件，总大小不当作单事件大小', async () => {
    const padding = 'x'.repeat(96 * 1024)
    response([1, 2, 3].map(sequence => frame('progress', { ...progress, sequence, padding })).join('')
      + frame('result', { ...completed, sequence: 4 }), 512 * 1024)
    expect(await execute()).toEqual(run)
  })

  it('HTTP 认证错误保留旧会话标记', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      window.localStorage.setItem(TOKEN_STORAGE_KEY, 'new-token')
      return { ok: false, status: 401, json: async () => ({ code: 'UNAUTHORIZED', message: '登录失效' }) }
    }))
    await expect(execute()).rejects.toMatchObject({ code: 'UNAUTHORIZED', status: 401, staleSession: true })
  })

  it('流内权限错误沿用 ApiError', async () => {
    response(frame('failure', { requestId: id, sequence: 1, runId: null, errorCode: 'WORKSPACE_MEMBERSHIP_INACTIVE', message: '成员资格失效' }))
    await expect(execute()).rejects.toMatchObject({ code: 'WORKSPACE_MEMBERSHIP_INACTIVE' })
  })

  it('卸载只中断连接，不发送取消请求', async () => {
    vi.stubGlobal('fetch', vi.fn((_url, options: RequestInit) => new Promise((_resolve, reject) => {
      options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
    })))
    const controller = new AbortController()
    const task = agentStream('/agent/messages/stream', { requestId: id }, { signal: controller.signal, onProgress: vi.fn<() => void>() })
    controller.abort()
    await expect(task).rejects.toMatchObject({ name: 'AbortError' })
    expect(fetch).toHaveBeenCalledOnce()
  })
})
