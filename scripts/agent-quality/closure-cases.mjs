import assert from 'node:assert/strict'
import { artifactHash, resultHash, buildScorecard } from './quality-report.mjs'
import { cases as financialCases, pricing, loadCatalog, expectedFor as financialExpected,
  checkProgramResult as financialCheck } from './financial-cases.mjs'

export { pricing, loadCatalog }
export const suite = 'closure'
export const fixtureVersion = 'closure-opinions-v2-bullish-start'
// 重复是计划内的独立采样，失败后不再执行下一次；不是自动重试。
export const cases = Object.freeze([
  ...['tencent-original-currency', 'moutai-annual', 'missing-comparable-year', 'quarter-not-covered',
    'revenue-is-not-orders', 'quote-focused-gaps'].map(id => ({ ...financialCases.find(item => item.id === id),
    category: id === 'quote-focused-gaps' ? 'QUOTE' : 'FINANCIAL', repeats: id === 'tencent-original-currency' ? 3 : 1,
    requiredTools: [id === 'quote-focused-gaps' ? 'read_latest_quote' : 'read_financial_facts'],
    allowedTools: id === 'revenue-is-not-orders' ? ['read_financial_facts', 'read_cycle_opinions']
      : [id === 'quote-focused-gaps' ? 'read_latest_quote' : 'read_financial_facts'] })),
  { id: 'cycle-summary', symbol: '00700.HK', operation: 'SUMMARY', category: 'RESEARCH', repeats: 1,
    question: null, requiredTools: [], allowedTools: [],
    review: ['使用成员甲最新中性观点和成员乙看空观点，不把甲最初看多当成当前观点',
      '甲的变化引用同一作者前后两条记录，不编造乙的变化', '共识或分歧均限成员判断，订单增长仍未获得事实确认'] },
  { id: 'member-change', symbol: '00700.HK', category: 'RESEARCH', repeats: 1,
    question: '成员甲为什么调整观点？请说明本周期内前后判断、日期和理由，并引用原观点；不要把成员乙的记录算作甲的变化。',
    requiredTools: ['read_cycle_opinions'], allowedTools: ['read_cycle_opinions'],
    review: ['甲由看多转中性，理由从猜测订单可能增长改为证据未确认', '引用同一作者的初始和更新记录，带实际日期', '不将成员判断变成公司事实'] },
  { id: 'opinion-fact-boundary', symbol: '00700.HK', category: 'RESEARCH', repeats: 3,
    question: '成员甲提到订单可能增长，现在能确认订单已经增长了吗？请只按本周期成员观点说明依据和缺口。',
    requiredTools: ['read_cycle_opinions'], allowedTools: ['read_cycle_opinions'],
    review: ['明确不能从成员猜测确认订单已经增长', '使用甲最新判断并引用实际观点', '只指出确认订单所缺权威依据，不扩展无关价格或技术指标缺口'] },
  { id: 'daily-price-change', symbol: '00700.HK', category: 'DAILY_PRICES', repeats: 1,
    question: '请查这只股票2026-09-01至2026-09-04的日K，给出本次实际取得区间的名义收盘价变化，说明是否复权和是否模拟。',
    requiredTools: ['read_daily_prices'], allowedTools: ['read_daily_prices'],
    review: ['使用实际取得首末交易日和收盘价，差额及百分比与依据一致', '交代币种、来源、区间、复权和模拟状态', '名义价格变化不写成投资收益，不列无关财报缺口'] },
])

export function selectCases(ids) {
  if (ids === undefined) return cases
  assert.ok(Array.isArray(ids) && ids.length > 0, '至少选择一个题目编号')
  assert.equal(new Set(ids).size, ids.length, '题目编号不能重复')
  return ids.map(id => { const item = cases.find(row => row.id === id); assert.ok(item, `未知题目编号：${id}`); return item })
}

export function expectedFor(testCase, catalog) {
  return { ...financialExpected(testCase, catalog), fixtureVersion,
    ...(testCase.category === 'RESEARCH' ? { latestStances: { M1: 'NEUTRAL', M2: 'BEARISH' },
      change: { author: 'M1', from: 'BULLISH', to: 'NEUTRAL' } } : {}) }
}

export function checkProgramResult(testCase, run, trace, catalog, sourceFixtures) {
  if (testCase.category === 'FINANCIAL' || testCase.category === 'QUOTE') financialCheck(testCase, run, trace, catalog)
  else {
    assert.equal(run.status, 'SUCCEEDED', `${testCase.id}: ${run.errorCode ?? '运行失败'}`)
    assert.ok(trace.modelCalls >= 1 && trace.modelCalls <= 4)
    assert.ok(trace.toolCalls <= 3)
    if (testCase.operation === 'SUMMARY') {
      assert.ok(run.answer?.summary)
      assert.equal(run.answer.followUp, null)
      assert.equal(run.answer.quote?.simulated, true)
      assert.equal(run.answer.summary.members.length, 2)
    } else assert.equal(run.answer?.followUp?.question, testCase.question)
  }
  if (testCase.category === 'RESEARCH') {
    const originals = sourceFixtures?.find(item => item.symbol === testCase.symbol)?.opinions
    assert.ok(originals?.length, '研究题须对照模型派发前核对的实际夹具')
    const owner = originals.filter(item => item.authorName === '自造成员甲')
    const cited = new Set(testCase.operation === 'SUMMARY'
      ? [run.answer.summary.overview, ...run.answer.summary.changes, ...run.answer.summary.members, ...run.answer.summary.pendingItems].flatMap(item => item.sourceIds)
      : run.answer.followUp.reply.paragraphs.flatMap(item => item.sourceIds))
    assert.ok(cited.has(owner.at(-1).sourceId), '必须实际引用甲的最新观点，不能用旧观点代替当前判断')
    if (testCase.id === 'member-change') assert.ok(cited.has(owner[0].sourceId), '变化须引用甲本人的前后两条记录')
    if (testCase.operation === 'SUMMARY') assert.equal(run.answer.references.length, originals.length)
  }
  if (testCase.category === 'DAILY_PRICES') {
    const evidence = run.answer.followUp.evidence
    assert.ok(evidence.some(item => item.type === 'DAILY_PRICES' && item.dailyPrices?.simulated === true))
    assert.ok(evidence.every(item => item.type === 'DAILY_PRICES'), '日K问题不混入其他类别依据')
  }
  const completed = trace.tools.filter(item => item.status === 'COMPLETED').map(item => item.name)
  for (const name of testCase.requiredTools) assert.ok(completed.includes(name), `未实际完成必要工具：${name}`)
  // 额外工具计入独立选择评分，不将其混成资料契约或任务语义通过。
  return { programContract: 'PASSED', semanticReview: 'PENDING_HUMAN_REVIEW' }
}

export function checkSourceFixtures(fixtures, selectedCases = cases) {
  if (!selectedCases.some(item => item.symbol === '00700.HK')) return
  const opinions = fixtures.find(item => item.symbol === '00700.HK')?.opinions
  assert.ok(opinions?.length === 3, '综合夹具必须包含三条真实测试观点')
  const owner = opinions.filter(item => item.authorName === '自造成员甲')
  const member = opinions.filter(item => item.authorName === '自造成员乙')
  assert.equal(owner.length, 2); assert.equal(member.length, 1)
  assert.equal(owner[0].type, 'INITIAL'); assert.equal(owner[0].stance, 'BULLISH', '初始倾向必须与固定题集一致')
  assert.equal(owner[1].type, 'UPDATE'); assert.equal(owner[1].stance, 'NEUTRAL')
  assert.equal(member[0].stance, 'BEARISH')
  assert.ok(owner.every(item => Number.isFinite(Date.parse(item.createdAt))))
  assert.ok(Date.parse(owner[0].createdAt) <= Date.parse(owner[1].createdAt))
}

// 仅检查器／夹具修正可复用已审财务答案；失败答案、研究输入和未知用量不能借此续做。
export function continuationRows(previous, mode, catalog, selectedCases = cases, review) {
  assert.ok(previous?.plan && previous.results?.length, '仅允许复用已审且不受夹具修正影响的财务样本')
  assert.equal(previous.mode, mode); assert.equal(previous.plan.suite, suite)
  assert.equal(previous.plan.automaticRetry, false); assert.equal(previous.plan.maxModelCallsPerQuestion, 4)
  assert.equal(previous.plan.realBudget, 'ORIGINAL_CUMULATIVE_10_YUAN_LEDGER')
  assert.deepEqual(previous.plan.pricing, pricing)
  assert.deepEqual(previous.plan.cases.map(item => [item.id, item.question]), selectedCases.map(item => [item.id, item.question]))
  const expectedExecutions = selectedCases.flatMap(item => Array.from({ length: item.repeats }, (_, i) => ({ id: `${item.id}#${i + 1}`, caseId: item.id, sample: i + 1 })))
  assert.deepEqual(previous.plan.executions, expectedExecutions)
  assert.equal(previous.plan.maxQuestions, expectedExecutions.length)
  assert.ok(previous.results.length < expectedExecutions.length)
  assert.equal(previous.before?.budget?.unknownReservations, previous.after?.budget?.unknownReservations, '出现新未知用量不能续做')
  const score = buildScorecard(previous, review)
  return previous.results.map(row => {
    const item = selectedCases.find(testCase => testCase.id === row.caseId)
    assert.equal(item?.category, 'FINANCIAL', '研究或其他夹具依赖不得跨修正复用')
    assert.ok(row.trace.tools.every(tool => tool.name === 'read_financial_facts'), '已读成员资料的题不得跨夹具复用')
    assert.ok(row.trace.costUpperYuan != null && row.trace.failure == null, '失败或未知费用不能作为已审财务样本复用')
    assert.equal(score.rows.find(saved => saved.id === row.id).semantic, 'PASSED', '复用前必须完整审查正文和依据')
    const reused = { ...row, checks: checkProgramResult(item, row.run, row.trace, catalog), reusedWithoutModelCall: true,
      reuseSourceSha256: artifactHash(previous), originalResultSha256: resultHash(row),
      fixtureFingerprint: row.fixtureFingerprint ?? artifactHash(previous.sourceFixtures),
      reuseBoundary: 'FINANCIAL_FACTS_UNCHANGED_NOT_SAME_FIXTURE_REPETITION' }
    const ratings = score.rows.find(saved => saved.id === row.id).dimensions
    reused.manualReview = { id: reused.id, resultSha256: resultHash(reused), reviewer: 'Verified source-bound financial reuse',
      reviewedAt: new Date().toISOString(), dimensions: ratings }
    return reused
  })
}
