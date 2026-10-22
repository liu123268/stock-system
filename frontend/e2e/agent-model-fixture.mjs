import { createServer } from 'node:http'
import assert from 'node:assert/strict'

// 仅浏览器验收启动的本地 HTTP 替身。没有真实 API Key，不转发任何外部请求。
const port = Number(process.env.AGENT_FIXTURE_PORT ?? 18084)
assert.ok(port >= 18000 && port < 19000)
let mode = 'valid'
let calls = 0
const heldBodies = new Set()
async function finishCompletion(response, body, message, finish, usedMode) {
  const responseId = `offline-${calls}`
  const usage = { prompt_tokens: 100, completion_tokens: 80, total_tokens: 180 }
  if (!body.stream) {
    response.setHeader('Content-Type', 'application/json')
    response.end(JSON.stringify({ id: responseId, object: 'chat.completion', created: 1, model: 'offline-agent-fixture', choices: [{ index: 0, finish_reason: finish, message }], usage }))
    return
  }
  response.setHeader('Content-Type', 'text/event-stream')
  const frame = (delta, reason = null, used = null) => response.write(`data: ${JSON.stringify({
    id: responseId, object: 'chat.completion.chunk', created: 1, model: 'offline-agent-fixture',
    choices: [{ index: 0, delta, finish_reason: reason }], usage: used,
  })}\n\n`)
  if (message.tool_calls) {
    frame({ role: 'assistant', tool_calls: message.tool_calls.map((call, index) => ({ ...call, index })) })
  } else {
    const content = message.content
    let split = Math.floor(content.length / 2)
    try {
      const parsed = JSON.parse(content.startsWith('```json\n') ? content.slice(8, -4) : content)
      const paragraph = JSON.stringify(parsed.paragraphs?.[0] ?? parsed.overview)
      split = content.indexOf(paragraph) + paragraph.length
    } catch { /* 非法最终 JSON 仍交给后端拒绝，不修补模型回复。 */ }
    frame({ role: 'assistant', content: content.slice(0, split) })
    if (['stream-hold', 'summary-late-invalid', 'summary-fence-hold'].includes(usedMode)) await new Promise(resolve => {
      const release = () => { clearTimeout(timer); heldBodies.delete(release); resolve() }
      const timer = setTimeout(release, 10_000)
      heldBodies.add(release)
    })
    else await new Promise(resolve => setTimeout(resolve, usedMode.includes('-gap') ? 450 : 100))
    frame({ content: content.slice(split) })
  }
  frame({ content: '' }, finish, usage)
  response.end('data: [DONE]\n\n')
}
const server = createServer(async (request, response) => {
  let raw = ''
  for await (const chunk of request) {
    raw += chunk.toString('utf8')
    if (Buffer.byteLength(raw) > 65_536) { response.writeHead(413).end(); return }
  }
  if (request.url === '/_test' && request.method === 'POST') {
    mode = JSON.parse(raw).mode
    assert.ok(['valid', 'slow', 'invalid', 'failure', 'failure-after-tool', 'invalid-financial-number', 'invalid-financial-percent',
      'invalid-financial-number-gap', 'invalid-financial-percent-gap', 'invalid-financial-no-citation',
      'invalid-financial-no-citation-gap', 'missing-comparison', 'invalid-summary-identifiers', 'stream-hold', 'summary-late-invalid', 'summary-fence-hold'].includes(mode))
    if (!['stream-hold', 'summary-late-invalid', 'summary-fence-hold'].includes(mode)) for (const release of heldBodies) release()
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
  const isQuestion = body.messages[0].content.includes('"paragraphs"')
  if (isQuestion) {
    const tools = body.tools ?? []
    const toolResponses = body.messages.filter(message => message.role === 'tool').map(message => JSON.parse(message.content))
    const question = body.messages.filter(message => message.role === 'user').at(-1).content
    let message
    let finish = 'stop'
    if (tools.length && !toolResponses.length && usedMode !== 'invalid') {
      const names = tools.map(tool => tool.function.name)
      assert.deepEqual(new Set(names), new Set(['read_cycle_opinions', 'read_latest_quote', 'read_daily_prices', 'read_financial_facts', 'search_report_passages']))
      let selected = ['read_cycle_opinions']
      if (question.includes('最新可用价')) selected = question.includes('成员') ? ['read_cycle_opinions', 'read_latest_quote'] : ['read_latest_quote']
      if (question.includes('名义收盘价')) selected = ['read_daily_prices']
      if (question.includes('年度财务')) selected = question.includes('成员') ? ['read_cycle_opinions', 'read_latest_quote', 'read_financial_facts'] : ['read_financial_facts']
      if (/营业收入|归母净利润|应占盈利|2024年收入/.test(question)) selected = ['read_financial_facts']
      if (question.includes('报告原文')) selected = question.includes('成员') ? ['read_cycle_opinions', 'search_report_passages'] : ['search_report_passages']
      if (question.includes('报告原文') && question.includes('营业收入') && question.includes('成员')) selected = ['read_cycle_opinions', 'read_financial_facts', 'search_report_passages']
      const financialArgs = question.includes('第三季度') ? { series: 'QUARTER', period: 'Q3 2025', metrics: ['REVENUE'] }
        : { series: 'ANNUAL', period: question.includes('2024年收入') ? 'FY 2024' : 'FY 2025', metrics: ['REVENUE', 'NET_PROFIT'] }
      const reportArgs = { query: question.includes('飞船') ? '载人飞船的发射时间和轨道高度' : '管理层怎样解释营销服务的收入增长', year: question.includes('2024') ? 2024 : 2025 }
      if (question.includes('渠道')) reportArgs.query = '报告计划怎样结合线上线下销售渠道'
      if (question.includes('采购销售')) reportArgs.query = '酿酒原料采购模式和销售渠道'
      if (question.includes('合同余额')) reportArgs.query = '新增订单总金额和未交付合同余额分别是多少'
      message = { role: 'assistant', content: '', tool_calls: selected.map((name, index) => ({ id: `fixture-call-${calls}-${index}`, type: 'function', function: { name, arguments: name === 'read_daily_prices' ? '{"dateFrom":"2026-09-01","dateTo":"2026-09-04"}' : name === 'read_financial_facts' ? JSON.stringify(financialArgs) : name === 'search_report_passages' ? JSON.stringify(reportArgs) : '{}' } })) }
      finish = 'tool_calls'
    } else {
      const paragraphs = toolResponses.map(result => {
        if (result.opinions) return { text: `成员观点仍需权威资料核对：${result.opinions.map(item => item.reason).join('；')}`, sourceIds: result.opinions.slice(-8).map(item => item.sourceId) }
        if (result.quote) return { text: `本次取得的最新可用报价为${result.quote.price} ${result.quote.currency}，时间${result.quote.quoteTime}，来源${result.quote.source}，这是模拟行情。`, sourceIds: result.quote.status === 'AVAILABLE' ? [result.sourceId] : [] }
        if (result.dailyPrices) return { text: `本次区间名义收盘价差为${result.dailyPrices.nominalPriceDifference} ${result.dailyPrices.currency}，未复权，不代表投资回报。`, sourceIds: result.dailyPrices.status === 'AVAILABLE' ? [result.sourceId] : [] }
        if (result.financialFacts) return { text: result.sourceId ? usedMode === 'invalid-financial-number'
          ? '2025年度营业收入为1688.3810251459亿元。'
          : usedMode === 'invalid-financial-percent' ? `同比变化为${result.financialFacts.facts[0].change.percent}。`
          : usedMode === 'missing-comparison' ? '2024年收入660257百万元人民币，本次缺少2023年比较列，不能计算2024年相对2023年的同比。'
          : '已核对所选年度报告财务科目，数值与口径见本次依据；不能据此证明订单增长。'
          : '本次资料没有可核实的所问财务期间或科目，不用其他年度数字替代。', sourceIds: result.sourceId ? [result.sourceId] : [] }
        if (result.passages) {
          const selected = result.passages.find(item => item.passage.section === 'tx-marketing') ?? result.passages[0]
          return { text: selected ? '本次报告原文候选已取得，具体表述与页码请查看原文；这是历史报告，不能证明当前订单增长。' : result.note, sourceIds: selected ? [selected.sourceId] : [] }
        }
        return { text: '本次查询未取得可用资料。', sourceIds: [] }
      })
      const financial = toolResponses.find(result => result.financialFacts)?.financialFacts
      if (usedMode === 'invalid-financial-no-citation' || usedMode === 'invalid-financial-no-citation-gap') {
        paragraphs.forEach(paragraph => { paragraph.sourceIds = [] })
        if (usedMode === 'invalid-financial-no-citation') paragraphs[0].text = '营业收入168838102514.79元。'
      }
      const gap = usedMode === 'invalid-financial-number-gap' || usedMode === 'invalid-financial-no-citation-gap' ? '尚需核对营业收入1688.3810251459亿元是否准确。'
        : usedMode === 'invalid-financial-percent-gap' ? `尚需核对同比变化${financial.facts[0].change.percent}。`
        : usedMode === 'missing-comparison' ? '未取得2023年比较列，所问同比尚无法核对。'
        : financial ? '未读取订单资料，财务数字不证明订单变化。' : '未读取财报与公告，无法确认实际增长。'
      message = { role: 'assistant', content: usedMode === 'invalid' ? 'invalid-json' : JSON.stringify({ paragraphs: paragraphs.length ? paragraphs : [{ text: '这是通用概念说明。', sourceIds: [] }], missingInformation: [gap] }) }
    }
    if (usedMode === 'slow') await new Promise(resolve => setTimeout(resolve, 2200))
    response.setHeader('Content-Type', 'application/json')
    if (usedMode === 'failure' || usedMode === 'failure-after-tool' && toolResponses.length) {
      response.writeHead(503).end('{"error":{"message":"offline fixture failure"}}'); return
    }
    await finishCompletion(response, body, message, finish, usedMode)
    return
  }
  assert.equal(body.response_format.type, 'json_object')
  assert.equal(body.tools, undefined)
  const user = body.messages.find(message => message.role === 'user').content
  const material = JSON.parse(user.slice(user.indexOf('{')))
  const opinions = material.opinions
  assert.ok(material.quote && ['AVAILABLE', 'UNAVAILABLE'].includes(material.quote.status))
  const latest = new Map(opinions.map(item => [item.authorId, item]))
  const refs = opinions.slice(-8).map(item => item.sourceId)
  const content = {
    overview: { text: '成员提出订单可能增长的判断。本次只有成员观点，没有公告或财报确认实际增长。', sourceIds: refs },
    changes: [],
    members: [...latest.values()].map(item => ({ authorId: item.authorId,
      text: `${item.authorName}的最新判断：${item.reason}。这是成员观点，仍需权威资料核对。`, sourceIds: [item.sourceId] })),
    pendingItems: [{ text: '缺少对应公告、财报或业绩预告，暂时不能确认订单是否实际增长。', sourceIds: refs }],
  }
  if (usedMode === 'invalid-summary-identifiers') {
    content.overview.text = `成员M1提出订单可能增长（${refs[0]}），未获权威依据核实。`
    content.pendingItems[0].text = '本次未提供公告、财报、新闻或行情资料。'
  }
  if (usedMode === 'summary-late-invalid') content.members[0].sourceIds = ['unprovided-reference']
  if (usedMode === 'slow') await new Promise(resolve => setTimeout(resolve, 2200))
  response.setHeader('Content-Type', 'application/json')
  if (usedMode === 'failure') { response.writeHead(503).end('{"error":{"message":"offline fixture failure"}}'); return }
  const summaryText = JSON.stringify(content)
  await finishCompletion(response, body, { role: 'assistant', content: usedMode === 'invalid' ? 'invalid-json'
    : usedMode === 'summary-fence-hold' ? `\`\`\`json\n${summaryText}\n\`\`\`` : summaryText }, 'stop', usedMode)
})
server.listen(port, '127.0.0.1', () => console.log(`Offline agent fixture listening at 127.0.0.1:${port}`))
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close())
