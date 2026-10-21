import { ApiError, TOKEN_STORAGE_KEY } from './http'
import type { AgentRun } from './agent'

export type AgentStage = 'READING' | 'QUERYING' | 'GENERATING' | 'VALIDATING' | 'SAVING'
export interface AgentProgress { requestId: string; sequence: number; runId: number | null; stage: AgentStage }
export interface AgentDraft { requestId: string; sequence: number; runId: number; paragraphs: string[] }
export interface AgentStreamOptions { signal: AbortSignal; onProgress: (progress: AgentProgress) => void; onDraft?: (draft: AgentDraft) => void }
const stages: AgentStage[] = ['READING', 'QUERYING', 'GENERATING', 'VALIDATING', 'SAVING']
const interrupted = () => new ApiError('AGENT_STREAM_INTERRUPTED', '连接已中断，正在查询原任务状态。')

/** POST fetch 保留认证头；临时段落与最终结果分开，不自动重发生成请求。 */
export async function agentStream(url: string, body: { requestId: string } & Record<string, unknown>, options: AgentStreamOptions): Promise<AgentRun> {
  const token = window.localStorage.getItem(TOKEN_STORAGE_KEY)
  const controller = new AbortController()
  const abort = () => controller.abort()
  options.signal.addEventListener('abort', abort, { once: true })
  if (options.signal.aborted) abort()
  const timeout = setTimeout(abort, 80_000)
  const apiError = (code: string, message: string, status?: number) =>
    new ApiError(code, message, status, token !== window.localStorage.getItem(TOKEN_STORAGE_KEY))
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
  try {
    const response = await fetch(`/api${url}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream',
        ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify(body), signal: controller.signal,
    })
    if (!response.ok) {
      const error = await response.json().catch(() => null) as { code?: string; message?: string } | null
      throw apiError(error?.code ?? 'AGENT_STREAM_HTTP_ERROR', error?.message ?? '暂时无法连接，请恢复运行状态。', response.status)
    }
    if (!response.headers.get('content-type')?.toLowerCase().startsWith('text/event-stream') || !response.body) throw interrupted()
    reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8', { fatal: true })
    const encoder = new TextEncoder()
    let buffer = '', name = '', lines: string[] = [], eventSize = 0, totalBytes = 0, lastSequence = 0
    let boundRun: number | null = null
    const event = (): AgentRun | undefined => {
      if (!lines.length) { name = ''; eventSize = 0; return }
      const value = JSON.parse(lines.join('\n')) as Record<string, unknown>
      const kind = name
      name = ''; lines = []; eventSize = 0
      if (value.requestId !== body.requestId || !Number.isSafeInteger(value.sequence) || (value.sequence as number) <= lastSequence) throw interrupted()
      lastSequence = value.sequence as number
      if (value.runId != null) {
        if (!Number.isSafeInteger(value.runId) || (value.runId as number) <= 0 || (boundRun !== null && boundRun !== value.runId)) throw interrupted()
        boundRun = value.runId as number
      }
      if (kind === 'progress') {
        if (!stages.includes(value.stage as AgentStage)) throw interrupted()
        options.onProgress(value as unknown as AgentProgress)
      } else if (kind === 'draft') {
        if (value.runId == null || !Array.isArray(value.paragraphs) || value.paragraphs.length > 8 ||
          value.paragraphs.some(text => typeof text !== 'string' || !text.trim() || Array.from(text).length > 2000)) throw interrupted()
        options.onDraft?.(value as unknown as AgentDraft)
      } else if (kind === 'failure') {
        if (typeof value.errorCode !== 'string' || typeof value.message !== 'string') throw interrupted()
        throw apiError(value.errorCode, value.message)
      } else if (kind === 'result') {
        const run = value.result as AgentRun | undefined
        if (!run || run.requestId !== body.requestId || !Number.isSafeInteger(run.runId) || run.runId <= 0 ||
          value.runId !== run.runId || run.runId !== boundRun ||
          !['RUNNING', 'SUCCEEDED', 'FAILED', 'CLEARED', 'INTERRUPTED', 'CANCELLED', 'INVALIDATED'].includes(run.status) ||
          (run.status !== 'SUCCEEDED' && run.answer != null) || (run.status === 'SUCCEEDED' && !run.answer)) throw interrupted()
        return run
      } else throw interrupted()
    }
    while (true) {
      const chunk = await reader.read()
      if (chunk.value) totalBytes += chunk.value.byteLength
      if (totalBytes > 2 * 1024 * 1024) throw interrupted()
      buffer += decoder.decode(chunk.value, { stream: !chunk.done })
      while (true) {
        const end = buffer.search(/[\r\n]/)
        if (end < 0 || (!chunk.done && end === buffer.length - 1 && buffer[end] === '\r')) break
        const line = buffer.slice(0, end)
        buffer = buffer.slice(end + (buffer[end] === '\r' && buffer[end + 1] === '\n' ? 2 : 1))
        if (!line) { const run = event(); if (run) return run }
        else if (!line.startsWith(':')) {
          eventSize += encoder.encode(line).byteLength
          if (eventSize > 256 * 1024) throw interrupted()
          const colon = line.indexOf(':')
          const field = colon < 0 ? line : line.slice(0, colon)
          const data = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
          if (field === 'event') name = data
          else if (field === 'data') lines.push(data)
        }
      }
      if (encoder.encode(buffer).byteLength + eventSize > 256 * 1024) throw interrupted()
      if (chunk.done) throw interrupted()
    }
  } finally {
    clearTimeout(timeout)
    options.signal.removeEventListener('abort', abort)
    await reader?.cancel().catch(() => {})
    reader?.releaseLock()
    controller.abort()
  }
}
