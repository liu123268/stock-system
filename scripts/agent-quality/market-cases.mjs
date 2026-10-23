import assert from 'node:assert/strict'
import { pricing as financialPricing } from './financial-cases.mjs'

export const pricing = Object.freeze({ ...financialPricing, checkedOn: '2026-10-23' })
export const cases = Object.freeze([
  { id: 'real-latest-quote', symbol: '600519.SH', category: 'QUOTE',
    question: '请查询这只股票最新可用报价，说明价格、币种、报价数据时间、来源和是否模拟；区分最新可用报价与此刻实时成交，不要扩展无关的财务或日K缺口。',
    requiredTools: ['read_latest_quote'], allowedTools: ['read_latest_quote'],
    review: ['价格、币种、时间及非模拟状态与本次真实快照一致', '休市时不把上个交易日的最新可用价称为此刻实时成交'] },
  { id: 'real-daily-price-change', symbol: '600519.SH', category: 'DAILY_PRICES',
    question: '请查询这只股票2026-10-19至2026-10-22的日K，给出本次实际取得首末交易日的收盘价、名义价差和变化百分比，说明币种、来源、是否复权及是否模拟。名义价差不能说成投资收益。',
    requiredTools: ['read_daily_prices'], allowedTools: ['read_daily_prices'],
    review: ['实际交易日、收盘价和Java名义变化与真实日K及页面接口一致', '明确未复权、非模拟，名义变化不充当投资回报'] },
  { id: 'real-quote-cycle-summary', symbol: '600519.SH', category: 'RESEARCH', operation: 'SUMMARY', question: null,
    requiredTools: [], allowedTools: [],
    review: ['梳理使用当前真实报价，不把可用行情说成未接入', '成员甲由看多转中性、乙看空，成员猜测不当作公司事实，引用对应当前自造观点'] },
])

export function selectCases(ids) {
  if (ids === undefined) return cases
  assert.ok(Array.isArray(ids) && ids.length > 0, '至少选择一个题目编号')
  assert.equal(new Set(ids).size, ids.length, '题目编号不能重复')
  return ids.map(id => { const item = cases.find(row => row.id === id); assert.ok(item, `未知题目编号：${id}`); return item })
}
export function loadCatalog() { return null }
export function expectedFor(testCase) {
  return { symbol: testCase.symbol, currency: 'CNY', source: 'LONGBRIDGE_OPENAPI', simulated: false,
    ...(testCase.category === 'DAILY_PRICES' ? { requestedFrom: '2026-10-19', requestedTo: '2026-10-22', adjustment: 'none' } : {}) }
}
export function continuationRows() { throw new Error('真实行情批次不能复用旧运行或旧报价') }

export function checkRealQuote(quote, baseline) {
  assert.equal(quote?.status ?? 'AVAILABLE', 'AVAILABLE', '必须实际取得真实可用报价')
  assert.equal(quote.code, '600519.SH'); assert.equal(quote.currency, 'CNY')
  assert.equal(quote.source, 'LONGBRIDGE_OPENAPI'); assert.equal(quote.simulated, false)
  assert.ok(Number(quote.price) > 0)
  assert.ok(Number.isFinite(Date.parse(`${quote.quoteTime}+08:00`)), '报价必须带有效数据时间')
  assert.ok(Date.parse(`${quote.quoteTime}+08:00`) <= Date.now() + 60_000, '报价时间不得在未来')
  if (baseline?.quoteTime === quote.quoteTime) assert.equal(Number(quote.price), Number(baseline.price), '同时间戳报价与页面接口一致')
}

// 以十进制整数核对 Java 的 HALF_UP 四位百分比，避免测试自身引入浮点舍入。
export function nominalChange(first, last) {
  const cents = value => {
    assert.match(String(value), /^\d+(?:\.\d{1,2})?$/, '本题仅核对两位以内的人民币收盘价')
    const [whole, fraction = ''] = String(value).split('.')
    return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'))
  }
  const a = cents(first), difference = cents(last) - a
  assert.ok(a > 0n)
  const scaled = difference * 1_000_000n
  const magnitude = scaled < 0n ? -scaled : scaled
  const rounded = (magnitude * 2n + a) / (2n * a)
  return { difference: Number(difference) / 100, percent: Number(scaled < 0n ? -rounded : rounded) / 10_000 }
}

export function checkProgramResult(testCase, run, trace, _catalog, fixtures) {
  assert.equal(run.status, 'SUCCEEDED', `${testCase.id}: ${run.errorCode ?? '运行失败'}`)
  assert.ok(trace.modelCalls >= 1 && trace.modelCalls <= 4); assert.ok(trace.toolCalls <= 3)
  const original = fixtures.find(item => item.symbol === testCase.symbol)
  assert.ok(original?.market?.quote, '须在模型派发前保存独立页面接口的真实快照')
  if (testCase.operation === 'SUMMARY') {
    checkRealQuote(run.answer.quote, original.market.quote)
    assert.equal(run.answer.summary.members.length, 2); assert.equal(run.answer.references.length, 3)
    const latestOwner = original.opinions.filter(item => item.authorName === '自造成员甲').at(-1)
    assert.ok(run.answer.summary.members.some(item => item.sourceIds.includes(latestOwner.sourceId)))
  } else {
    assert.equal(run.answer.followUp.question, testCase.question)
    const evidence = run.answer.followUp.evidence
    assert.ok(evidence.length > 0); assert.ok(evidence.every(item => item.type === testCase.category), '不混入无关依据')
    if (testCase.category === 'QUOTE') evidence.forEach(item => checkRealQuote(item.quote, original.market.quote))
    else for (const item of evidence) {
      const daily = item.dailyPrices
      assert.equal(daily.status, 'AVAILABLE'); assert.equal(daily.code, testCase.symbol)
      assert.equal(daily.source, 'LONGBRIDGE_OPENAPI'); assert.equal(daily.simulated, false); assert.equal(daily.currency, 'CNY')
      assert.equal(daily.adjustment, 'none'); assert.equal(daily.requestedFrom, '2026-10-19'); assert.equal(daily.requestedTo, '2026-10-22')
      assert.ok(daily.prices.length >= 2)
      assert.deepEqual(daily.prices, original.market.dailyPrices, '助手日K与同对象页面行情接口一致')
      assert.equal(daily.actualFrom, daily.prices[0].time.slice(0, 10)); assert.equal(daily.actualTo, daily.prices.at(-1).time.slice(0, 10))
      const expected = nominalChange(daily.prices[0].close, daily.prices.at(-1).close)
      assert.equal(Number(daily.nominalPriceDifference), expected.difference)
      assert.equal(Number(daily.nominalPriceChangePercent), expected.percent)
    }
  }
  const completed = trace.tools.filter(item => item.status === 'COMPLETED').map(item => item.name)
  assert.ok(testCase.requiredTools.every(name => completed.includes(name)), '必须实际完成所问行情工具')
  assert.ok(trace.tools.every(item => testCase.allowedTools.includes(item.name)), '不能用额外工具掩盖取数问题')
  return { programContract: 'PASSED', semanticReview: 'PENDING_HUMAN_REVIEW' }
}
