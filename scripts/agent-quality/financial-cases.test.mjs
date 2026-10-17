import assert from 'node:assert/strict'
import { test } from 'node:test'
import { cases, pricing, loadCatalog, expectedFor, checkProgramResult, continuationRows } from './financial-cases.mjs'

const catalog = loadCatalog()
const annual = cases.find(item => item.id === 'tencent-original-currency')
const trace = { modelCalls: 2, toolCalls: 1 }
function sample(testCase = annual) {
  const expected = expectedFor(testCase, catalog)
  const financialFacts = { symbol: expected.symbol, selectedPeriod: expected.period,
    facts: expected.facts.map(fact => ({ metric: fact.metric,
      current: { period: expected.period, amount: fact.current, currency: expected.currency, unit: expected.unit, currencyBasis: 'ISSUER_ORIGINAL', profitBasis: fact.metric === 'NET_PROFIT' ? 'PARENT_SHAREHOLDERS' : 'NOT_APPLICABLE' },
      comparable: fact.comparable === null ? null : { period: `FY ${Number(expected.period.slice(3)) - 1}`, amount: fact.comparable, currency: expected.currency, unit: expected.unit, currencyBasis: 'ISSUER_ORIGINAL', profitBasis: fact.metric === 'NET_PROFIT' ? 'PARENT_SHAREHOLDERS' : 'NOT_APPLICABLE' },
      change: { percent: null, unavailableReason: fact.comparable === null ? 'MISSING_COMPARABLE' : null } })),
    reports: expected.facts.map(fact => ({ metric: fact.metric, ...expected.report, reportPeriod: 'FY 2025', pdfPages: fact.pdfPages })) }
  return { status: 'SUCCEEDED', answer: { followUp: { question: testCase.question,
    reply: { paragraphs: [{ text: '本测试不自动评价自然语言正确性。', sourceIds: ['f1'] }] },
    evidence: [{ type: 'FINANCIAL', sourceId: 'f1', financialFacts }] } } }
}
test('从原报目录读取精确数字并明确保留人工语义核对', () => {
  assert.deepEqual(expectedFor(annual, catalog).facts.map(row => row.current), ['751766', '224842'])
  assert.deepEqual(checkProgramResult(annual, sample(), trace, catalog), { programContract: 'PASSED', semanticReview: 'PENDING_HUMAN_REVIEW' })
})
test('拒绝把港股交易币种套到人民币原报', () => {
  const run = sample(); run.answer.followUp.evidence[0].financialFacts.facts[0].current.currency = 'HKD'
  assert.throws(() => checkProgramResult(annual, run, trace, catalog))
})
test('拒绝错误单位、改写金额或浮点数', () => {
  for (const [field, value] of [['unit', 'YUAN'], ['amount', '751766000000'], ['amount', 751766]]) {
    const run = sample(); run.answer.followUp.evidence[0].financialFacts.facts[0].current[field] = value
    assert.throws(() => checkProgramResult(annual, run, trace, catalog))
  }
})
test('拒绝错误股票、报告版本和页码', () => {
  const variants = [facts => { facts.symbol = '600519.SH' }, facts => { facts.reports[0].sha256 = 'a'.repeat(64) }, facts => { facts.reports[0].pdfPages = [1] }]
  for (const mutate of variants) {
    const run = sample(); mutate(run.answer.followUp.evidence[0].financialFacts)
    assert.throws(() => checkProgramResult(annual, run, trace, catalog))
  }
})

test('允许为年度比较单独读取上年，但仍须取得所问本期', () => {
  const run = sample()
  const comparison = sample({ ...annual, period: 'FY 2024' }).answer.followUp.evidence[0]
  run.answer.followUp.evidence.push(comparison)
  checkProgramResult(annual, run, { ...trace, toolCalls: 2 }, catalog)
  run.answer.followUp.evidence.shift()
  assert.throws(() => checkProgramResult(annual, run, trace, catalog), /必须实际取得所问年度/)
})

test('比较工具也不能读取未请求的其他年度', () => {
  const run = sample()
  const comparison = sample({ ...annual, period: 'FY 2024' }).answer.followUp.evidence[0]
  comparison.financialFacts.selectedPeriod = 'FY 2023'
  run.answer.followUp.evidence.push(comparison)
  assert.throws(() => checkProgramResult(annual, run, trace, catalog), /只允许题目要求/)
})
test('拒绝把未核实展示币种或合并利润当成原报归母口径', () => {
  for (const [field, value] of [['currencyBasis', 'PROVIDER_DISPLAY_UNVERIFIED'], ['profitBasis', 'CONSOLIDATED']]) {
    const run = sample(); run.answer.followUp.evidence[0].financialFacts.facts[1].current[field] = value
    assert.throws(() => checkProgramResult(annual, run, trace, catalog))
  }
})
test('季度缺失时拒绝全年财务引用冒充', () => {
  const testCase = cases.find(item => item.noFinancialEvidence)
  const run = sample(); run.answer.followUp.question = testCase.question
  assert.throws(() => checkProgramResult(testCase, run, trace, catalog))
})
test('缺少2023比较列时禁止给出同比', () => {
  const testCase = cases.find(item => item.id === 'missing-comparable-year')
  const run = sample(testCase)
  checkProgramResult(testCase, run, trace, catalog)
  run.answer.followUp.evidence[0].financialFacts.facts[0].change.percent = '10'
  assert.throws(() => checkProgramResult(testCase, run, trace, catalog))
})
test('单独核对报价问题和四次调用上限', () => {
  const testCase = cases.find(item => item.quote)
  const run = { status: 'SUCCEEDED', answer: { followUp: { question: testCase.question, evidence: [{ type: 'QUOTE', quote: { simulated: true } }] } } }
  checkProgramResult(testCase, run, trace, catalog)
  assert.throws(() => checkProgramResult(testCase, run, { ...trace, modelCalls: 5 }, catalog))
})

function partial() {
  return { mode: 'real', plan: { cases, pricing, maxQuestions: 6, maxModelCallsPerQuestion: 4,
    automaticRetry: false, realBudget: 'ORIGINAL_CUMULATIVE_10_YUAN_LEDGER' },
  results: [{ id: annual.id, run: sample(), trace }] }
}

test('续做复用已核对答案，并拒绝跨模式、换题和重复记录', () => {
  const previous = partial()
  assert.equal(continuationRows(previous, 'real', catalog)[0].reusedWithoutModelCall, true)
  assert.throws(() => continuationRows(previous, 'fixture', catalog))
  const changed = structuredClone(previous)
  changed.plan.cases[0].question = '不在本轮授权中的问题'
  assert.throws(() => continuationRows(changed, 'real', catalog))
  previous.results.push(previous.results[0])
  assert.throws(() => continuationRows(previous, 'real', catalog), /不能重复/)
})
