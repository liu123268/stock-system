import { readFile, writeFile, rename } from 'node:fs/promises'
import { resolve } from 'node:path'
import { checkDecision, resultHash } from './quality-report.mjs'

// 来源核对可能跨越上下文交接；等待期间不派发模型，超时仍保留结果并停止。
export async function awaitSourceReview(directory, row, { timeoutMillis = 900_000, intervalMillis = 500 } = {}) {
  const request = { id: row.id, resultSha256: resultHash(row), state: 'PENDING_SOURCE_REVIEW' }
  const temporary = resolve(directory, 'review-request.tmp')
  await writeFile(temporary, JSON.stringify(request, null, 2))
  await rename(temporary, resolve(directory, 'review-request.json'))
  const deadline = Date.now() + timeoutMillis
  do {
    let decision
    try { decision = JSON.parse(await readFile(resolve(directory, 'review-decision.json'), 'utf8')) }
    catch (error) { if (error.code !== 'ENOENT') throw error }
    if (decision?.id === row.id) {
      checkDecision(row, decision)
      await writeFile(resolve(directory, 'review-request.json'), JSON.stringify({ ...request,
        state: Object.values(decision.dimensions).every(item => item.verdict === 'PASSED') ? 'APPROVED' : 'REJECTED' }, null, 2))
      return decision
    }
    await new Promise(resolve => setTimeout(resolve, intervalMillis))
  } while (Date.now() < deadline)
  await writeFile(resolve(directory, 'review-request.json'), JSON.stringify({ ...request, state: 'TIMED_OUT' }, null, 2))
  throw new Error('逐题原文审查未完成，停止后续模型派发；已有回答与费用保留')
}
