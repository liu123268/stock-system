import assert from 'node:assert/strict'
import { test } from 'node:test'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { cases, checkProgramResult, checkRealQuote, nominalChange, continuationRows } from './market-cases.mjs'

const quote = { status: 'AVAILABLE', code: '600519.SH', price: 1500, currency: 'CNY',
  quoteTime: '2026-10-22T15:00:00', source: 'LONGBRIDGE_OPENAPI', simulated: false }
const points = [{ time: '2026-10-19T00:00:00', close: 1510.12 }, { time: '2026-10-22T00:00:00', close: 1500.01 }]
function dailyRun() {
  return { status: 'SUCCEEDED', answer: { followUp: { question: cases[1].question,
    evidence: [{ type: 'DAILY_PRICES', dailyPrices: { status: 'AVAILABLE', code: '600519.SH', currency: 'CNY',
      source: 'LONGBRIDGE_OPENAPI', simulated: false, adjustment: 'none', requestedFrom: '2026-10-19', requestedTo: '2026-10-22',
      actualFrom: '2026-10-19', actualTo: '2026-10-22', prices: structuredClone(points),
      nominalPriceDifference: -10.11, nominalPriceChangePercent: -0.6695 } }] } } }
}
const fixtures = [{ symbol: '600519.SH', market: { quote, dailyPrices: points } }]
const trace = { modelCalls: 2, toolCalls: 1, tools: [{ name: 'read_daily_prices', status: 'COMPLETED' }] }

test('真实报价拒绝模拟、缺失、错对象、错币种和未来时间，同时间戳必须与页面一致', () => {
  checkRealQuote(quote, { ...quote })
  for (const patch of [{ simulated: true }, { status: 'UNAVAILABLE' }, { source: 'LOCAL_MOCK' },
    { code: '00700.HK' }, { currency: 'HKD' }, { price: null }, { quoteTime: 'bad' }, { quoteTime: '2099-01-01T00:00:00' }])
    assert.throws(() => checkRealQuote({ ...quote, ...patch }))
  assert.throws(() => checkRealQuote({ ...quote, price: 1501 }, quote))
  checkRealQuote({ ...quote, price: 1501, quoteTime: '2026-10-22T15:01:00' }, quote)
})

test('名义变化独立核对十进制舍入、正负和零变化', () => {
  assert.deepEqual(nominalChange(1510.12, 1500.01), { difference: -10.11, percent: -0.6695 })
  assert.equal(nominalChange('32', '32.01').percent, 0.0313)
  assert.equal(nominalChange('32', '31.99').percent, -0.0313)
  assert.deepEqual(nominalChange(100, 100), { difference: 0, percent: 0 })
  assert.throws(() => nominalChange(0, 1)); assert.throws(() => nominalChange(1, 1.001))
})

test('真实日K依据必须匹配页面接口和独立计算，错误数据或工具不能判通过', () => {
  checkProgramResult(cases[1], dailyRun(), trace, null, fixtures)
  for (const patch of [{ simulated: true }, { source: 'LOCAL_MOCK' }, { adjustment: 'forward' },
    { nominalPriceDifference: 10.11 }, { nominalPriceChangePercent: -0.67 }, { actualFrom: '2026-10-20' },
    { prices: [points[0], { ...points[1], close: 999 }] }]) {
    const run = dailyRun(); Object.assign(run.answer.followUp.evidence[0].dailyPrices, patch)
    assert.throws(() => checkProgramResult(cases[1], run, trace, null, fixtures))
  }
  assert.throws(() => checkProgramResult(cases[1], dailyRun(), { ...trace, tools: [] }, null, fixtures))
  assert.throws(() => continuationRows())
})

const script = fileURLToPath(new URL('../../frontend/e2e/research-agent-financial-probe.mjs', import.meta.url))
function execute(args, variables = {}) {
  return spawnSync(process.execPath, [script, '--suite=market', ...args], { encoding: 'utf8', windowsHide: true,
    env: { ...process.env, DB_PASSWORD: '', JWT_SECRET: '', DEEPSEEK_API_KEY: '', MYSQL_CLIENT_PATH: 'not-a-mysql-client',
      AGENT_FINANCIAL_PROBE_MODE: 'real', AGENT_FINANCIAL_MODEL_PROBE: 'true', AGENT_MARKET_MODEL_PROBE: 'false', ...variables } })
}
test('真实行情计划固定三题且标注真实公开数据，干跑不需要配置或服务', () => {
  const result = execute(['--dry-run', '--stream-answers', '--review-each', '--expected-unknown=1'])
  assert.equal(result.status, 0, result.stderr)
  const plan = JSON.parse(result.stdout)
  assert.equal(plan.maxQuestions, 3); assert.equal(plan.automaticRetry, false)
  assert.equal(plan.data, 'SELF_MADE_OPINIONS_AND_REAL_LONGBRIDGE_SDK_PUBLIC_MARKET_DATA')
  assert.equal(plan.expectedUnknownReservations, 1)
  assert.deepEqual(plan.cases.map(item => item.id), cases.map(item => item.id))
})
test('原财务开关不能启用真实行情，且禁止缺少流式审查或降为替身', () => {
  const unauthorized = execute(['--stream-answers', '--review-each'])
  assert.notEqual(unauthorized.status, 0); assert.match(unauthorized.stderr, /真实行情联用须显式启用/)
  assert.doesNotMatch(unauthorized.stderr, /ECONNREFUSED|not-a-mysql-client/)
  const noReview = execute([], { AGENT_MARKET_MODEL_PROBE: 'true' })
  assert.notEqual(noReview.status, 0); assert.match(noReview.stderr, /须原生流式并逐题审查/)
  const fixture = execute(['--stream-answers', '--review-each'], { AGENT_FINANCIAL_PROBE_MODE: 'fixture' })
  assert.notEqual(fixture.status, 0); assert.match(fixture.stderr, /不能用模型替身或模拟行情代替/)
})
