import assert from 'node:assert/strict'
import { test } from 'node:test'
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { cases, selectCases, loadCatalog, expectedFor, checkProgramResult, checkSourceFixtures, continuationRows, pricing } from './closure-cases.mjs'
import { checkBudgetStart, checkBudgetAfter } from './probe-budget.mjs'
import { artifactHash, resultHash, buildScorecard, compareArtifacts, dimensions, writeReviewArtifacts } from './quality-report.mjs'
import { awaitSourceReview } from './review-gate.mjs'

function artifact(mode = 'real') {
  const planCases = structuredClone(selectCases(['tencent-original-currency', 'quote-focused-gaps']))
    .map(item => ({ ...item, expected: expectedFor(item, loadCatalog()) }))
  const executions = planCases.flatMap(row => Array.from({ length: row.repeats }, (_, i) => ({ id: `${row.id}#${i + 1}`, caseId: row.id, sample: i + 1 })))
  return { mode, plan: { cases: planCases, executions, maxQuestions: 4 }, sourceFixtures: [{ opinions: ['自造观点'] }],
    results: executions.map((row, index) => ({ ...row, elapsedMillis: 100 + index * 100,
      run: { runId: index + 1, requestId: `request-${index}`, status: 'SUCCEEDED', answer: {} },
      trace: { modelCalls: 2, toolCalls: 1, inputTokens: 100, outputTokens: 80, costUpperYuan: '0.000840',
        model: 'fixture-version', promptVersion: 'test-v1', tools: [{ name: index < 3 ? 'read_financial_facts' : 'read_latest_quote', status: 'COMPLETED' }] },
      checks: { programContract: 'PASSED' } })) }
}
function reviewOf(input, verdict = 'PASSED') {
  return { artifactSha256: artifactHash(input), reviewer: 'test-reviewer', reviewedAt: '2026-10-18T15:00:00Z',
    rows: input.results.map(row => ({ id: row.id, dimensions: Object.fromEntries(dimensions.map(key => [key, { verdict, note: '仅为评分器边界测试的自造评级。' }])) })) }
}
const budget = occupied => ({ officialEndpoint: true, budget: { occupiedUpperYuan: occupied, limitYuan: 10, unknownReservations: 1 } })

test('冻结十题十四次采样，原腾讯题排首且原问题不变', () => {
  assert.equal(cases.length, 10)
  assert.equal(cases.reduce((sum, row) => sum + row.repeats, 0), 14)
  assert.equal(cases[0].id, 'tencent-original-currency')
  assert.equal(cases[0].repeats, 3)
  assert.deepEqual(new Set(cases.map(row => row.category)), new Set(['FINANCIAL', 'QUOTE', 'RESEARCH', 'DAILY_PRICES']))
  assert.deepEqual(expectedFor(cases[0], loadCatalog()).facts.map(row => row.current), ['751766', '224842'])
})
test('范围错误与未经审查的续做均拒绝', () => {
  for (const ids of [[], ['unknown'], ['member-change', 'member-change']]) assert.throws(() => selectCases(ids))
  assert.throws(() => continuationRows(), /仅允许复用已审/)
})

test('夹具必须在模型请求前核对真实初始倾向，阻止中性冒充看多转中性', () => {
  const fixture = [{ symbol: '00700.HK', opinions: [
    { authorName: '自造成员甲', type: 'INITIAL', stance: 'BULLISH', createdAt: '2026-10-18T01:00:00' },
    { authorName: '自造成员乙', type: 'UPDATE', stance: 'BEARISH', createdAt: '2026-10-18T01:00:01' },
    { authorName: '自造成员甲', type: 'UPDATE', stance: 'NEUTRAL', createdAt: '2026-10-18T01:00:02' },
  ] }]
  checkSourceFixtures(fixture)
  fixture[0].opinions[0].stance = 'NEUTRAL'
  assert.throws(() => checkSourceFixtures(fixture), /初始倾向/)
})

test('成员题允许只读取所需作者，不强求无关成员，但必须引用甲的最新记录', () => {
  const fixtures = [{ symbol: '00700.HK', opinions: [
    { sourceId: 'op-1', authorName: '自造成员甲' }, { sourceId: 'op-2', authorName: '自造成员乙' },
    { sourceId: 'op-3', authorName: '自造成员甲' } ] }]
  const testCase = cases.find(item => item.id === 'member-change')
  const run = { status: 'SUCCEEDED', answer: { references: [{ sourceId: 'op-1' }, { sourceId: 'op-3' }],
    followUp: { question: testCase.question, reply: { paragraphs: [{ text: '甲的判断变化', sourceIds: ['op-1', 'op-3'] }] } } } }
  const trace = { modelCalls: 2, toolCalls: 1, tools: [{ name: 'read_cycle_opinions', status: 'COMPLETED' }] }
  checkProgramResult(testCase, run, trace, loadCatalog(), fixtures)
  run.answer.followUp.reply.paragraphs[0].sourceIds = ['op-1']
  assert.throws(() => checkProgramResult(testCase, run, trace, loadCatalog(), fixtures), /最新观点/)
  const boundary = cases.find(item => item.id === 'opinion-fact-boundary')
  run.answer.followUp.question = boundary.question; run.answer.followUp.reply.paragraphs[0].sourceIds = ['op-3']
  checkProgramResult(boundary, run, trace, loadCatalog(), fixtures)
})

function resumable() {
  const testCase = cases[0], catalog = loadCatalog(), expected = expectedFor(testCase, catalog)
  const facts = expected.facts.map(fact => ({ metric: fact.metric,
    current: { period: 'FY 2025', amount: fact.current, currency: 'CNY', unit: 'MILLION_YUAN', currencyBasis: 'ISSUER_ORIGINAL', profitBasis: fact.metric === 'NET_PROFIT' ? 'PARENT_SHAREHOLDERS' : 'NOT_APPLICABLE' },
    comparable: { period: 'FY 2024', amount: fact.comparable, currency: 'CNY', unit: 'MILLION_YUAN', currencyBasis: 'ISSUER_ORIGINAL', profitBasis: fact.metric === 'NET_PROFIT' ? 'PARENT_SHAREHOLDERS' : 'NOT_APPLICABLE' } }))
  const input = artifact()
  input.plan = { ...input.plan, suite: 'closure', automaticRetry: false, maxModelCallsPerQuestion: 4, pricing,
    realBudget: 'ORIGINAL_CUMULATIVE_10_YUAN_LEDGER' }
  input.before = { budget: { unknownReservations: 1 } }; input.after = { budget: { unknownReservations: 1 } }
  input.results = input.results.slice(0, 2)
  for (const row of input.results) row.run.answer = { followUp: { question: testCase.question, evidence: [{ type: 'FINANCIAL', financialFacts: {
    symbol: '00700.HK', selectedPeriod: 'FY 2025', facts,
    reports: expected.facts.map(fact => ({ metric: fact.metric, ...expected.report, reportPeriod: 'FY 2025', pdfPages: fact.pdfPages })) } }] } }
  return input
}

test('检查器修正仅可复用已审财务答案，不重新派发已执行采样', () => {
  const input = resumable(), review = reviewOf(input)
  const selected = selectCases(['tencent-original-currency', 'quote-focused-gaps'])
  const rows = continuationRows(input, 'real', loadCatalog(), selected, review)
  assert.deepEqual(rows.map(row => row.id), input.results.map(row => row.id))
  assert.ok(rows.every(row => row.reusedWithoutModelCall && row.reuseSourceSha256 === artifactHash(input)))
  assert.ok(rows.every(row => row.manualReview.resultSha256 === resultHash(row)))
})

test('失败、新未知用量、未审或已读取成员资料均不能跨夹具复用', () => {
  for (const change of [input => { input.results[0].run.status = 'FAILED' },
    input => { input.after.budget.unknownReservations = 2 }, input => { input.results[0].trace.tools.push({ name: 'read_cycle_opinions', status: 'COMPLETED' }) }]) {
    const input = resumable(); change(input)
    assert.throws(() => continuationRows(input, 'real', loadCatalog(), selectCases(['tencent-original-currency','quote-focused-gaps']), reviewOf(input)))
  }
  const input = resumable()
  assert.throws(() => continuationRows(input, 'real', loadCatalog(), selectCases(['tencent-original-currency','quote-focused-gaps'])), /完整审查/)
})
test('保留旧未知预留须显式匹配笔数，不默许清零或新增', () => {
  checkBudgetStart(budget(4.601656), 1)
  assert.throws(() => checkBudgetStart(budget(4.601656), 0), /显式认可/)
  const newUnknown = budget(4.7); newUnknown.budget.unknownReservations = 2
  assert.throws(() => checkBudgetAfter(budget(4.601656), newUnknown), /显式认可/)
  assert.throws(() => checkBudgetAfter(budget(4.601656), budget(4.5)), /不能下降/)
})
test('额度不足、非官方端点、扩预算和非有限值均拒绝', () => {
  for (const value of [6, NaN, Infinity, -1]) assert.throws(() => checkBudgetStart(budget(value), 1))
  const wrong = budget(4.601656); wrong.officialEndpoint = false
  assert.throws(() => checkBudgetStart(wrong, 1))
  wrong.officialEndpoint = true; wrong.budget.limitYuan = 20
  assert.throws(() => checkBudgetStart(wrong, 1))
  checkBudgetAfter(budget(4.601656), budget(4.7))
})
test('程序全过仍是待人工核对，不默认真实质量通过', () => {
  const score = buildScorecard(artifact())
  assert.equal(score.program.passed, 4)
  assert.equal(score.toolSelection.passed, 4)
  assert.equal(score.semantic.pending, 4)
  assert.equal(score.realQualityPassed, false)
})
test('中途停止保留完整分母，未执行采样不计通过', () => {
  const input = artifact(); input.results = input.results.slice(0, 1)
  const score = buildScorecard(input, reviewOf(input))
  assert.equal(score.denominator, 4); assert.equal(score.notRun, 3)
  assert.deepEqual(score.taskCompletion, { passed: 1, total: 4, rate: 0.25 })
  assert.equal(score.repetitions[0].allSamplesPassed, false)
  assert.equal(score.realQualityPassed, false)
})
test('失败运行保存诊断，不被未知正文或伪造通过项覆盖', () => {
  const input = artifact(); input.results[0].run.status = 'FAILED'; input.results[0].run.answer = null
  input.results[0].trace.failure = { stage: 'QUESTION_MODEL_HTTP', category: 'TIMEOUT', modelCall: 2 }
  assert.equal(buildScorecard(input).rows[0].program, 'FAILED')
  assert.equal(buildScorecard(input).rows[0].failure.category, 'TIMEOUT')
  assert.throws(() => buildScorecard(input, reviewOf(input)), /没有完整回答/)
})
test('工具选择与任务完成分开，正确回答仍可指出多余工具', () => {
  const input = artifact(); input.results[0].trace.tools.push({ name: 'read_daily_prices', status: 'COMPLETED' })
  const score = buildScorecard(input, reviewOf(input))
  assert.equal(score.program.passed, 4); assert.equal(score.toolSelection.passed, 3)
  assert.equal(score.taskCompletion.passed, 4)
  assert.equal(score.realQualityPassed, false)
})
test('只有完整审查的真实采样可通过，替身永不冒充真实通过', () => {
  const input = artifact()
  assert.equal(buildScorecard(input, reviewOf(input)).realQualityPassed, true)
  input.mode = 'fixture'
  assert.equal(buildScorecard(input, reviewOf(input)).realQualityPassed, false)
})
test('已发现的语义失败优先于其他维度尚未评分', () => {
  const input = artifact(), review = reviewOf(input, 'PENDING')
  review.rows[0].dimensions.relevance = { verdict: 'FAILED', note: '出现无关日K缺口' }
  assert.equal(buildScorecard(input, review).semantic.failed, 1)
})
test('拒绝旧哈希、缺评分维度、空依据和未知审查编号', () => {
  for (const change of [row => { row.artifactSha256 = 'old' }, row => { delete row.rows[0].dimensions.relevance },
    row => { row.rows[0].dimensions.relevance.note = '' }, row => { row.rows[0].id = 'unknown' }]) {
    const input = artifact(), review = reviewOf(input); change(review)
    assert.throws(() => buildScorecard(input, review))
  }
})
test('拒绝复制同一运行冒充独立重复采样或改小分母', () => {
  for (const change of [input => { input.results[1].run.requestId = input.results[0].run.requestId },
    input => { input.results[1].run.runId = input.results[0].run.runId },
    input => { input.plan.executions.pop(); input.plan.maxQuestions-- },
    input => { input.results.push(input.results[0]) }]) {
    const input = artifact(); change(input); assert.throws(() => buildScorecard(input))
  }
})
test('耗时分位只使用实际运行次数，不让未执行题变零毫秒', () => {
  const input = artifact(); input.results = input.results.slice(0, 2)
  assert.deepEqual(buildScorecard(input).latencyMillis, { count: 2, p50: 100, p95: 200 })
})
test('历史对照要求同题，输入不同只给描述比较，禁止混合替身与真实效果', () => {
  const before = artifact(), after = artifact()
  assert.equal(compareArtifacts(before, after).interpretation, 'SAME_INPUT_COMPARISON')
  after.sourceFixtures = [{ opinions: ['不同输入'] }]
  assert.equal(compareArtifacts(before, after).interpretation, 'DESCRIPTIVE_HISTORY_NOT_CONTROLLED')
  after.sourceFixtures = before.sourceFixtures; after.mode = 'fixture'
  assert.equal(compareArtifacts(before, after).interpretation, 'DESCRIPTIVE_HISTORY_NOT_CONTROLLED')
  after.plan.cases[0].question = '另一题'; after.plan.cases[1].question = '另一道题'
  assert.throws(() => compareArtifacts(before, after), /没有同题/)
})
test('写审查模板不覆盖已填写内容，已填评分必须重新按哈希核对', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'agent-quality-'))
  try {
    const input = artifact()
    await writeFile(join(directory, 'results.json'), JSON.stringify(input, null, 2))
    await writeReviewArtifacts(directory)
    const templatePath = join(directory, 'review-template.json')
    await writeFile(templatePath, 'user-owned-existing-review')
    await writeReviewArtifacts(directory)
    assert.equal(await readFile(templatePath, 'utf8'), 'user-owned-existing-review')
    const reviewPath = join(directory, 'review.json')
    await writeFile(reviewPath, JSON.stringify(reviewOf(input)))
    await writeReviewArtifacts(directory, reviewPath)
    assert.equal(JSON.parse(await readFile(join(directory, 'scorecard.json'), 'utf8')).realQualityPassed, true)
  } finally { await rm(directory, { recursive: true, force: true }) }
})

function decisionOf(row, verdict = 'PASSED') {
  return { id: row.id, resultSha256: resultHash(row), reviewer: 'test-only-reviewer', reviewedAt: '2026-10-18T15:00:00Z',
    dimensions: Object.fromEntries(dimensions.map(key => [key, { verdict, note: '评分门禁的合成检查。' }])) }
}
test('逐题门禁只接受当前正文哈希绑定的完整决定，保留拒绝结论', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'agent-review-'))
  try {
    const row = artifact().results[0]
    const decision = decisionOf(row)
    await writeFile(join(directory, 'review-decision.json'), JSON.stringify(decision))
    assert.deepEqual(await awaitSourceReview(directory, row), decision)
    assert.equal(JSON.parse(await readFile(join(directory, 'review-request.json'), 'utf8')).state, 'APPROVED')
    await writeFile(join(directory, 'review-decision.json'), JSON.stringify(decisionOf(row, 'FAILED')))
    await awaitSourceReview(directory, row)
    assert.equal(JSON.parse(await readFile(join(directory, 'review-request.json'), 'utf8')).state, 'REJECTED')
    decision.resultSha256 = 'wrong'
    await writeFile(join(directory, 'review-decision.json'), JSON.stringify(decision))
    await assert.rejects(awaitSourceReview(directory, row), /须重新审查/)
  } finally { await rm(directory, { recursive: true, force: true }) }
})
test('未审或旧执行决定不能放行，超时保留停止状态', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'agent-review-'))
  try {
    const row = artifact().results[0]
    const decision = decisionOf(row); decision.id = 'old-request'
    await writeFile(join(directory, 'review-decision.json'), JSON.stringify(decision))
    await assert.rejects(awaitSourceReview(directory, row, { timeoutMillis: 15, intervalMillis: 5 }), /停止后续模型派发/)
    assert.equal(JSON.parse(await readFile(join(directory, 'review-request.json'), 'utf8')).state, 'TIMED_OUT')
  } finally { await rm(directory, { recursive: true, force: true }) }
})
test('逐题审查直接汇入总评分，后改正文不能沿用通过决定', () => {
  const input = artifact()
  input.results = input.results.map(row => ({ ...row, manualReview: decisionOf(row) }))
  assert.equal(buildScorecard(input).realQualityPassed, true)
  input.results[0].run.answer = { changed: '后改正文' }
  assert.throws(() => buildScorecard(input), /须重新审查/)
})
