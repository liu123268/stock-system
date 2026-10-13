import { createServer } from 'node:http'
import assert from 'node:assert/strict'

// 仅浏览器验收启动的本地 HTTP 替身。没有真实 API Key，不转发任何外部请求。
const port = Number(process.env.AGENT_FIXTURE_PORT ?? 18084)
assert.ok(port >= 18000 && port < 19000)
let mode = 'valid'
let calls = 0
const server = createServer(async (request, response) => {
  let raw = ''
  for await (const chunk of request) {
    raw += chunk.toString('utf8')
    if (Buffer.byteLength(raw) > 65_536) { response.writeHead(413).end(); return }
  }
  if (request.url === '/_test' && request.method === 'POST') {
    mode = JSON.parse(raw).mode
    assert.ok(['valid', 'slow', 'invalid', 'failure'].includes(mode))
  }
  if (request.url === '/_test') {
    response.setHeader('Content-Type', 'application/json')
    response.end(JSON.stringify({ calls, mode, fixture: true })); return
  }
  if (request.url !== '/chat/completions' || request.method !== 'POST') { response.writeHead(404).end(); return }
  calls++
  const usedMode = mode
  const body = JSON.parse(raw)
  assert.equal(body.model, 'deepseek-flash')
  assert.equal(body.max_tokens, 2000)
  assert.equal(body.thinking.type, 'disabled')
  assert.equal(body.response_format.type, 'json_object')
  assert.equal(body.tools, undefined)
  const user = body.messages.find(message => message.role === 'user').content
  const material = JSON.parse(user.slice(user.indexOf('{')))
  const opinions = material.opinions
  const latest = new Map(opinions.map(item => [item.authorId, item]))
  const refs = opinions.slice(-8).map(item => item.sourceId)
  const content = {
    overview: { text: '成员提出订单可能增长的判断。本次只有成员观点，没有公告或财报确认实际增长。', sourceIds: refs },
    changes: [],
    members: [...latest.values()].map(item => ({ authorId: item.authorId,
      text: `${item.authorName}的最新判断：${item.reason}。这是成员观点，仍需权威资料核对。`, sourceIds: [item.sourceId] })),
    pendingItems: [{ text: '缺少对应公告、财报或业绩预告，暂时不能确认订单是否实际增长。', sourceIds: refs }],
  }
  if (usedMode === 'slow') await new Promise(resolve => setTimeout(resolve, 2200))
  response.setHeader('Content-Type', 'application/json')
  if (usedMode === 'failure') { response.writeHead(503).end('{"error":{"message":"offline fixture failure"}}'); return }
  response.end(JSON.stringify({ id: `offline-${calls}`, object: 'chat.completion', created: Math.floor(Date.now() / 1000), model: 'offline-agent-fixture',
    choices: [{ index: 0, finish_reason: 'stop', message: { role: 'assistant', content: usedMode === 'invalid' ? 'invalid-json' : JSON.stringify(content) } }],
    usage: { prompt_tokens: 100, completion_tokens: 80, total_tokens: 180 } }))
})
server.listen(port, '127.0.0.1', () => console.log(`Offline agent fixture listening at 127.0.0.1:${port}`))
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close())
