import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import * as financialSuite from '../../scripts/agent-quality/financial-cases.mjs'
import * as reportSuite from '../../scripts/agent-quality/report-cases.mjs'
import * as closureSuite from '../../scripts/agent-quality/closure-cases.mjs'
import { checkBudgetStart, checkBudgetAfter } from '../../scripts/agent-quality/probe-budget.mjs'
import { artifactHash, buildScorecard, writeReviewArtifacts } from '../../scripts/agent-quality/quality-report.mjs'
import { awaitSourceReview } from '../../scripts/agent-quality/review-gate.mjs'

const args = process.argv.slice(2)
assert.ok(args.every(arg => ['--dry-run', '--review-each', '--stream-answers'].includes(arg) || ['--cases=', '--suite=', '--expected-unknown=', '--fixture-fail-at='].some(prefix => arg.startsWith(prefix))), '不接受未知检查参数')
const streamAnswers = args.includes('--stream-answers')
const suites = args.filter(arg => arg.startsWith('--suite='))
assert.ok(suites.length <= 1, '只能指定一次检查题库')
const suite = suites.length ? suites[0].slice('--suite='.length) : 'financial'
assert.ok(['financial', 'reports', 'closure'].includes(suite), '未知检查题库')
const { selectCases, pricing, loadCatalog, expectedFor, checkProgramResult, continuationRows } = { financial: financialSuite, reports: reportSuite, closure: closureSuite }[suite]
const unknownArguments = args.filter(arg => arg.startsWith('--expected-unknown='))
assert.ok(unknownArguments.length <= 1, '只能指定一次历史未知笔数')
const unknownText = unknownArguments.length ? unknownArguments[0].slice('--expected-unknown='.length) : '0'
assert.match(unknownText, /^(0|[1-9]\d*)$/, '历史未知笔数必须是非负整数')
const expectedUnknownReservations = Number(unknownText)
assert.ok(Number.isSafeInteger(expectedUnknownReservations), '历史未知笔数超限')
const selectors = args.filter(arg => arg.startsWith('--cases='))
assert.ok(selectors.length <= 1, '只能指定一次题目范围')
const cases = selectCases(selectors.length ? selectors[0].slice('--cases='.length).split(',') : undefined)
const executions = cases.flatMap(item => Array.from({ length: item.repeats ?? 1 }, (_, index) => ({
  ...item, executionId: suite === 'closure' ? `${item.id}#${index + 1}` : item.id, sample: index + 1 })))
const catalog = loadCatalog()
const mode = process.env.AGENT_FINANCIAL_PROBE_MODE ?? 'real'
assert.ok(['real', 'fixture'].includes(mode), '只能显式选择真实模型或本地替身')
const failureSelectors = args.filter(arg => arg.startsWith('--fixture-fail-at='))
assert.ok(failureSelectors.length <= 1, '只能指定一次替身故障位置')
const fixtureFailAt = failureSelectors[0]?.slice('--fixture-fail-at='.length)
const reviewEach = args.includes('--review-each')
if (fixtureFailAt !== undefined) {
  assert.ok(executions.some(item => item.executionId === fixtureFailAt), '替身故障必须绑定计划内执行编号')
  assert.ok(args.includes('--dry-run') || mode === 'fixture', '真实检查禁止替身故障参数')
}
const plan = { cases: cases.map(item => ({ ...item, expected: expectedFor(item, catalog) })), pricing,
  maxQuestions: executions.length, maxModelCallsPerQuestion: 4, automaticRetry: false, expectedUnknownReservations,
  reviewMode: reviewEach ? 'SEQUENTIAL_SOURCE_REVIEW' : 'POST_RUN_REVIEW_REQUIRED',
  ...(streamAnswers ? { transport: 'NATIVE_SSE_PARAGRAPHS' } : {}),
  ...(fixtureFailAt !== undefined ? { fixtureFailAt } : {}),
  ...(suite !== 'financial' ? { suite } : {}),
  ...(suite === 'closure' ? { fixtureVersion: closureSuite.fixtureVersion, executions: executions.map(item => ({
    id: item.executionId, caseId: item.id, sample: item.sample })) } : {}),
  data: suite === 'reports' ? 'SELF_MADE_OPINIONS_AND_BOUNDED_REVIEWED_PUBLIC_REPORT_PASSAGES_AND_FINANCIAL_FACTS'
    : 'SELF_MADE_OPINIONS_MOCK_MARKET_AND_REVIEWED_PUBLIC_FINANCIAL_FACTS',
  realBudget: 'ORIGINAL_CUMULATIVE_10_YUAN_LEDGER', semanticReview: 'REQUIRED' }
if (args.includes('--dry-run')) {
  process.stdout.write(JSON.stringify(plan, null, 2) + '\n')
  process.exit(0)
}
if (mode === 'real') {
  if (streamAnswers) assert.equal(reviewEach, true, '流式真实检查须逐题核对来源后放行')
  if (suite === 'reports') assert.equal(process.env.AGENT_REPORT_MODEL_PROBE, 'true', '真实报告原文检查须另行显式启用，并核对该轮授权')
  else assert.equal(process.env.AGENT_FINANCIAL_MODEL_PROBE, 'true', '真实财务资料检查须另行显式启用，并核对该轮授权')
  if (suite === 'closure') assert.equal(reviewEach, true, '综合真实验收须逐题核对正文和依据后放行')
}
const continuationPath = process.env.AGENT_FINANCIAL_CONTINUE_FROM
if (streamAnswers) assert.ok(!continuationPath, '流式验收不复用其他传输或旧批次答案')
const previous = continuationPath ? JSON.parse(await readFile(resolve(continuationPath), 'utf8')) : null
const previousReview = previous && suite === 'closure' ? JSON.parse(await readFile(resolve(dirname(continuationPath), 'review.json'), 'utf8')) : undefined
const results = previous ? continuationRows(previous, mode, catalog, cases, previousReview) : []
const alreadyRun = new Set(results.map(item => item.id))
const pendingCases = executions.filter(item => !alreadyRun.has(item.executionId))
const continuation = previous ? { source: resolve(continuationPath), reusedCases: [...alreadyRun],
  pendingCases: pendingCases.map(item => item.executionId), originalBefore: previous.before } : null
const backend = 'http://localhost:18082'
const frontend = 'http://localhost:18083'
const output = resolve(process.env.AGENT_FINANCIAL_PROBE_OUTPUT ?? `../target/agent-${suite}-${mode}/${Date.now().toString(36)}`)
const runId = Date.now().toString(36)
const names = [`agfo_${runId}`, `agfm_${runId}`]
const stocksBefore = new Map()
let budgetExisted
let success = false
let before = null
let after = null
let owner
let databaseRemaining
const sourceFixtures = []
const streamEvidence = []
let streamBrowser, streamPage
const sql = query => execFileSync(process.env.MYSQL_CLIENT_PATH ?? 'mysql.exe', [
  '--user=root', '--host=localhost', '--port=3306', '--database=stock_system_test',
  '--default-character-set=utf8mb4', '--batch', '--skip-column-names', `--execute=${query}`,
], { encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.DB_PASSWORD }, windowsHide: true }).trim()
async function api(path, token, method = 'GET', body) {
  if (streamAnswers && method === 'POST' && (path.endsWith('/messages') || path.endsWith('/summaries'))) {
    const observed = await streamPage.evaluate(async ({ path, token, body }) => {
      const { agentStream } = await import('/src/api/agent-stream.ts')
      const { TOKEN_STORAGE_KEY } = await import('/src/api/http.ts')
      localStorage.setItem(TOKEN_STORAGE_KEY, token)
      const started = performance.now()
      let draftEvents = 0, firstDraftMillis = null
      try {
        const run = await agentStream(`${path}/stream`, body, { signal: AbortSignal.timeout(75_000), onProgress: () => {},
          onDraft: draft => { if (draft.paragraphs.length) { draftEvents++; firstDraftMillis ??= performance.now() - started } } })
        return { run, draftEvents, firstDraftMillis, resultMillis: performance.now() - started }
      } catch (error) { return { failure: { code: error.code, message: error.message }, draftEvents, firstDraftMillis } }
    }, { path, token, body })
    streamEvidence.push({ requestId: body.requestId, ...observed, run: undefined })
    assert.ok(!observed.failure, `流式返回失败：${observed.failure?.code}`)
    return observed.run
  }
  const response = await fetch(`${backend}/api${path}`, { method, signal: AbortSignal.timeout(75_000),
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) })
  const result = await response.json()
  assert.ok(response.ok, `${path}: ${result.code}`)
  return result.data
}
function id(value) { assert.ok(Number.isSafeInteger(value) && value > 0); return value }
async function save() {
  const artifact = { runId, mode, plan, continuation, before, after, sourceFixtures, results,
    programSuccess: success, semanticReview: reviewEach ? 'PER_ROW_SOURCE_REVIEW_SEE_SCORECARD' : 'PENDING_HUMAN_REVIEW' }
  await writeFile(`${output}/results.json`, JSON.stringify(artifact, null, 2))
  await writeFile(`${output}/scorecard.json`, JSON.stringify(buildScorecard(artifact), null, 2))
}
// 必须先通过前端代理、后端和实际 catalog 三重检查，再产生任何业务写入。
for (const origin of [backend, frontend]) {
  const response = await fetch(`${origin}/api/test/environment`)
  assert.ok(response.ok)
  const { data } = await response.json()
  assert.equal(data.profile, 'test'); assert.equal(data.database, 'stock_system_test')
}
assert.equal(sql('select database()'), 'stock_system_test')
if (mode === 'fixture') {
  const response = await fetch('http://127.0.0.1:18084/_test')
  assert.equal((await response.json()).fixture, true)
}
for (const symbol of new Set(cases.map(item => item.symbol)))
  stocksBefore.set(symbol, Number(sql(`select count(*) from stock where code='${symbol}'`)) > 0)
budgetExisted = Number(sql('select count(*) from agent_budget')) > 0
await mkdir(output, { recursive: true })
await save()
try {
  if (streamAnswers) {
    const { chromium } = await import('playwright-core')
    streamBrowser = await chromium.launch({ executablePath: process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
    streamPage = await streamBrowser.newPage()
    await streamPage.goto(`${frontend}/login`)
  }
  for (const [index, name] of names.entries())
    await api('/auth/register', null, 'POST', { username: name, password: 'TestPass123', nickname: index ? '自造成员乙' : '自造成员甲' })
  owner = await api('/auth/login', null, 'POST', { username: names[0], password: 'TestPass123' })
  const member = await api('/auth/login', null, 'POST', { username: names[1], password: 'TestPass123' })
  if (mode === 'real') {
    before = await api('/test/agent-budget', owner.accessToken)
    checkBudgetStart(before, expectedUnknownReservations)
  }
  const workspace = id((await api('/workspaces', owner.accessToken, 'POST', { name: `财务核对自造样本${runId}` })).workspaceId)
  const invitation = await api(`/workspaces/${workspace}/invitations`, owner.accessToken, 'POST', { expectedInvitationId: null })
  await api('/workspaces/join', member.accessToken, 'POST', { inviteCode: invitation.inviteCode })
  const targets = new Map()
  for (const symbol of stocksBefore.keys()) {
    const target = await api(`/workspaces/${workspace}/stocks`, owner.accessToken, 'POST', { code: symbol,
      stance: suite === 'closure' && symbol === '00700.HK' ? 'BULLISH' : 'NEUTRAL', reason: '验收自造观点：成员甲猜测新订单可能增长，尚待权威资料核对。',
      observationNote: '这是新建测试样本，不是实际研究记录。', horizon: 'MEDIUM', targetPrice: null, stopLossPrice: null })
    id(target.workspaceStockId); id(target.researchCycleId)
    const cycleBase = `/workspaces/${workspace}/stocks/${target.workspaceStockId}/research/cycles/${target.researchCycleId}`
    targets.set(symbol, `${cycleBase}/agent`)
    if (suite === 'closure' && symbol === '00700.HK') {
      const opinionPath = `/workspaces/${workspace}/stocks/${target.workspaceStockId}/opinions`
      const common = { researchCycleId: target.researchCycleId, type: 'UPDATE', horizon: 'MEDIUM', targetPrice: null, stopLossPrice: null }
      await api(opinionPath, member.accessToken, 'POST', { ...common, stance: 'BEARISH',
        reason: '验收自造观点：成员乙担心盈利能力，尚未查询财报。', observationNote: '盈利下降仍是担忧，不是已核对事实。' })
      await api(opinionPath, owner.accessToken, 'POST', { ...common, stance: 'NEUTRAL',
        reason: '验收自造观点：成员甲调整为中性，因为订单增长尚无权威依据确认。', observationNote: '等待权威订单披露，不凭成员推测下结论。' })
    }
    const sources = await api(`${cycleBase}/sources`, owner.accessToken)
    sourceFixtures.push({ symbol, scope: sources.scope, opinions: sources.opinions.map(item => ({
      sourceId: `op-${item.id}`, authorName: item.authorNickname, createdAt: item.createdAt,
      type: item.type, stance: item.stance, reason: item.reason, observationNote: item.observationNote })) })
  }
  if (suite === 'closure') closureSuite.checkSourceFixtures(sourceFixtures, cases)
  for (const testCase of pendingCases) {
    if (mode === 'real') checkBudgetStart(after ?? before, expectedUnknownReservations)
    if (testCase.executionId === fixtureFailAt) {
      const response = await fetch('http://127.0.0.1:18084/_test', { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ mode: 'failure-after-tool' }) })
      assert.ok(response.ok)
    }
    const base = targets.get(testCase.symbol)
    // 每题使用同一新建测试对象，清空上一题消息以免历史答案暗示工具选择；不重置费用。
    await api(base, owner.accessToken, 'DELETE')
    const started = Date.now()
    const requestId = crypto.randomUUID()
    const path = `${base}/${testCase.operation === 'SUMMARY' ? 'summaries' : 'messages'}`
    const body = { requestId, ...(testCase.operation === 'SUMMARY' ? {} : { question: testCase.question }) }
    let run, dispatchError
    try { run = await api(path, owner.accessToken, 'POST', body) }
    catch (error) {
      dispatchError = error
      // 只读恢复；回包丢失也不能重发付费 POST。失败同样保留 requestId 和安全诊断。
      try { run = await api(`${base}/requests/${requestId}`, owner.accessToken) } catch { /* 下方保存 UNKNOWN */ }
    }
    run ??= { requestId, status: 'UNKNOWN', answer: null }
    const traceJson = sql(`select json_object('modelCalls',model_calls,'toolCalls',tool_calls,'inputTokens',input_tokens,'outputTokens',output_tokens,'costUpperYuan',cost_upper_yuan,'reservedYuan',reserved_yuan,'model',model,'promptVersion',prompt_version,'tools',cast(tool_trace_json as json),'failure',cast(model_failure_json as json)) from agent_run where user_id=${id(owner.userId)} and request_id='${requestId}'`)
    const row = traceJson ? JSON.parse(traceJson) : { modelCalls: 0, toolCalls: 0, tools: [], failure: null }
    const result = { id: testCase.executionId, caseId: testCase.id, sample: testCase.sample, elapsedMillis: Date.now() - started,
      expected: expectedFor(testCase, catalog), fixtureFingerprint: artifactHash(sourceFixtures), trace: row, run,
      ...(streamAnswers ? { streaming: streamEvidence.find(item => item.requestId === requestId) } : {}) }
    results.push(result); await save()
    if (dispatchError) throw dispatchError
    result.checks = checkProgramResult(testCase, run, row, catalog, sourceFixtures)
    const restored = await api(`${base}/runs/${run.runId}`, owner.accessToken)
    assert.deepEqual(restored.answer, run.answer, '恢复必须保留同一问答和依据')
    const repeated = await api(path, owner.accessToken, 'POST', body)
    assert.equal(repeated.runId, run.runId)
    assert.equal(Number(sql(`select model_calls from agent_run where id=${id(run.runId)}`)), row.modelCalls, '幂等恢复不能新增模型调用')
    assert.equal((await api(base, member.accessToken)).messages.length, 0)
    if (mode === 'real') {
      const latest = await api('/test/agent-budget', owner.accessToken)
      checkBudgetAfter(after ?? before, latest)
      after = latest
    }
    await save()
    if (reviewEach) {
      result.manualReview = await awaitSourceReview(output, result)
      await save()
      assert.ok(Object.values(result.manualReview.dimensions).every(item => item.verdict === 'PASSED'), '逐题语义核对失败，停止后续派发')
    }
    process.stdout.write(JSON.stringify({ case: testCase.executionId, programContract: 'PASSED', semanticReview: result.manualReview ? 'PASSED' : 'PENDING', elapsedMillis: result.elapsedMillis }) + '\n')
  }
  success = true
} finally {
  let browserCloseFailed = false
  try { if (streamBrowser) await streamBrowser.close() }
  catch { browserCloseFailed = true; success = false }
  if (mode === 'real' && owner) {
    try { after = await api('/test/agent-budget', owner.accessToken) }
    catch { after = { unavailable: true } }
  }
  assert.ok(names.every(name => /^agf[om]_[a-z0-9]+$/.test(name)))
  const list = names.map(name => `'${name}'`).join(',')
  // 网络断开不等于执行终止；先等本轮有界运行收敛，再按独有用户集合清理。
  for (let attempt = 0; attempt < 125; attempt++) {
    const active = Number(sql(`select count(*) from agent_run r join app_user u on u.id=r.user_id where u.username in (${list}) and r.status='RUNNING'`))
    if (!active) break
    assert.notEqual(attempt, 124, '本轮运行尚未收敛，禁止删除它的 fixture')
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  const stockCleanup = [...stocksBefore].filter(([, existed]) => !existed).map(([symbol]) =>
    `delete from stock where code='${symbol}' and source='LOCAL_MOCK' and not exists(select 1 from workspace_stock where stock_id=stock.id);`).join('\n')
  const remaining = sql(`create temporary table q_users as select id from app_user where username in (${list});
    create temporary table q_spaces as select id from workspace where created_by_user_id in (select id from q_users);
    create temporary table q_stocks as select id from workspace_stock where workspace_id in (select id from q_spaces);
    create temporary table q_cycles as select id from research_cycle where workspace_stock_id in (select id from q_stocks);
    create temporary table q_runs as select id from agent_run where user_id in (select id from q_users);
    set @quality_cost=(select coalesce(sum(coalesce(cost_upper_yuan,reserved_yuan)),0) from agent_run where id in (select id from q_runs));
    delete from agent_message where run_id in (select id from q_runs);
    delete from agent_run where id in (select id from q_runs);
    delete from agent_session where user_id in (select id from q_users);
    update agent_budget set occupied_yuan=occupied_yuan-@quality_cost where id=1;
    ${budgetExisted ? '' : 'delete from agent_budget where occupied_yuan=0 and not exists(select 1 from agent_run);'}
    delete from research_attention_change where research_cycle_id in (select id from q_cycles);
    delete from stock_opinion where research_cycle_id in (select id from q_cycles);
    delete from research_cycle where id in (select id from q_cycles);
    delete from workspace_stock where id in (select id from q_stocks);
    delete from workspace_invitation where workspace_id in (select id from q_spaces);
    delete from workspace_member where workspace_id in (select id from q_spaces);
    delete from workspace where id in (select id from q_spaces);
    delete from invitation_join_attempt where user_id in (select id from q_users);
    delete from app_user where id in (select id from q_users);
    ${stockCleanup}
    select count(*) from app_user where username in (${list});
    select count(*) from agent_run where id in (select id from q_runs);
    select count(*) from agent_message where run_id in (select id from q_runs);
    select count(*) from agent_session where user_id in (select id from q_users);
    select count(*) from workspace where id in (select id from q_spaces);
    select count(*) from workspace_member where workspace_id in (select id from q_spaces);
    select count(*) from workspace_invitation where workspace_id in (select id from q_spaces);
    select count(*) from workspace_stock where id in (select id from q_stocks);
    select count(*) from research_cycle where id in (select id from q_cycles);
    select count(*) from stock_opinion where research_cycle_id in (select id from q_cycles);`)
  remaining.split(/\r?\n/).forEach(value => assert.equal(Number(value), 0))
  databaseRemaining = 0
  await save()
  await writeReviewArtifacts(output)
  await writeFile(`${output}/verification.json`, JSON.stringify({ success, runId, mode, cases: results.length,
    databaseRemaining, realModel: mode === 'real', semanticReview: reviewEach ? 'PER_ROW_SOURCE_REVIEW_SEE_SCORECARD' : 'PENDING_HUMAN_REVIEW',
    reusedCases: [...alreadyRun], newCases: results.filter(item => !alreadyRun.has(item.id)).length,
    paidRequests: mode === 'fixture' ? 0 : results.filter(item => !alreadyRun.has(item.id)).reduce((sum, item) => sum + item.trace.modelCalls, 0),
    budget: mode === 'real' ? 'ORIGINAL_CUMULATIVE_10_YUAN_LEDGER_PRESERVED' : 'LOCAL_FIXTURE' }, null, 2))
  assert.equal(browserCloseFailed, false, '本轮流式浏览器未正常关闭，数据库清理已独立执行')
}
process.stdout.write(JSON.stringify({ success, cases: results.length, databaseRemaining, mode, output }) + '\n')
