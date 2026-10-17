import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { cases, pricing, loadCatalog, expectedFor, checkProgramResult, continuationRows } from '../../scripts/agent-quality/financial-cases.mjs'

const catalog = loadCatalog()
const mode = process.env.AGENT_FINANCIAL_PROBE_MODE ?? 'real'
assert.ok(['real', 'fixture'].includes(mode), '只能显式选择真实模型或本地替身')
const plan = { cases: cases.map(item => ({ ...item, expected: expectedFor(item, catalog) })), pricing,
  maxQuestions: cases.length, maxModelCallsPerQuestion: 4, automaticRetry: false,
  data: 'SELF_MADE_OPINIONS_MOCK_MARKET_AND_REVIEWED_PUBLIC_FINANCIAL_FACTS',
  realBudget: 'ORIGINAL_CUMULATIVE_10_YUAN_LEDGER', semanticReview: 'REQUIRED' }
if (process.argv.includes('--dry-run')) {
  process.stdout.write(JSON.stringify(plan, null, 2) + '\n')
  process.exit(0)
}
if (mode === 'real') assert.equal(process.env.AGENT_FINANCIAL_MODEL_PROBE, 'true', '真实财务资料检查须另行显式启用，并核对该轮授权')
const continuationPath = process.env.AGENT_FINANCIAL_CONTINUE_FROM
const previous = continuationPath ? JSON.parse(await readFile(resolve(continuationPath), 'utf8')) : null
const results = previous ? continuationRows(previous, mode, catalog) : []
const alreadyRun = new Set(results.map(item => item.id))
const pendingCases = cases.filter(item => !alreadyRun.has(item.id))
const continuation = previous ? { source: resolve(continuationPath), reusedCases: [...alreadyRun],
  pendingCases: pendingCases.map(item => item.id), originalBefore: previous.before } : null
const backend = 'http://localhost:18082'
const frontend = 'http://localhost:18083'
const output = resolve(process.env.AGENT_FINANCIAL_PROBE_OUTPUT ?? `../target/agent-financial-${mode}-20261017/${Date.now().toString(36)}`)
const runId = Date.now().toString(36)
const names = [`agfo_${runId}`, `agfm_${runId}`]
const stocksBefore = new Map()
let budgetExisted
let success = false
let before
let after
let owner
let databaseRemaining = null
const sql = query => execFileSync(process.env.MYSQL_CLIENT_PATH ?? 'mysql.exe', [
  '--user=root', '--host=localhost', '--port=3306', '--database=stock_system_test',
  '--default-character-set=utf8mb4', '--batch', '--skip-column-names', `--execute=${query}`,
], { encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.DB_PASSWORD }, windowsHide: true }).trim()
async function api(path, token, method = 'GET', body) {
  const response = await fetch(`${backend}/api${path}`, { method, signal: AbortSignal.timeout(75_000),
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) })
  const result = await response.json()
  assert.ok(response.ok, `${path}: ${result.code}`)
  return result.data
}
function id(value) { assert.ok(Number.isSafeInteger(value) && value > 0); return value }
async function save() {
  await writeFile(`${output}/results.json`, JSON.stringify({ runId, mode, plan, continuation, before, after, results,
    programSuccess: success, semanticReview: 'PENDING_HUMAN_REVIEW' }, null, 2))
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
  for (const [index, name] of names.entries())
    await api('/auth/register', null, 'POST', { username: name, password: 'TestPass123', nickname: index ? '自造成员乙' : '自造成员甲' })
  owner = await api('/auth/login', null, 'POST', { username: names[0], password: 'TestPass123' })
  const member = await api('/auth/login', null, 'POST', { username: names[1], password: 'TestPass123' })
  if (mode === 'real') {
    before = await api('/test/agent-budget', owner.accessToken)
    assert.equal(before.officialEndpoint, true)
    assert.equal(before.budget.limitYuan, 10)
    assert.equal(before.budget.unknownReservations, 0)
    assert.ok(before.budget.occupiedUpperYuan + 4.016 <= 10)
  }
  const workspace = id((await api('/workspaces', owner.accessToken, 'POST', { name: `财务核对自造样本${runId}` })).workspaceId)
  const invitation = await api(`/workspaces/${workspace}/invitations`, owner.accessToken, 'POST', { expectedInvitationId: null })
  await api('/workspaces/join', member.accessToken, 'POST', { inviteCode: invitation.inviteCode })
  const targets = new Map()
  for (const symbol of stocksBefore.keys()) {
    const target = await api(`/workspaces/${workspace}/stocks`, owner.accessToken, 'POST', { code: symbol,
      stance: 'NEUTRAL', reason: '验收自造观点：成员甲猜测新订单可能增长，尚待权威资料核对。',
      observationNote: '这是新建测试样本，不是实际研究记录。', horizon: 'MEDIUM', targetPrice: null, stopLossPrice: null })
    id(target.workspaceStockId); id(target.researchCycleId)
    targets.set(symbol, `/workspaces/${workspace}/stocks/${target.workspaceStockId}/research/cycles/${target.researchCycleId}/agent`)
  }
  for (const testCase of pendingCases) {
    const base = targets.get(testCase.symbol)
    // 每题使用同一新建测试对象，清空上一题消息以免历史答案暗示工具选择；不重置费用。
    await api(base, owner.accessToken, 'DELETE')
    const started = Date.now()
    const run = await api(`${base}/messages`, owner.accessToken, 'POST', { requestId: crypto.randomUUID(), question: testCase.question })
    const row = JSON.parse(sql(`select json_object('modelCalls',model_calls,'toolCalls',tool_calls,'inputTokens',input_tokens,'outputTokens',output_tokens,'costUpperYuan',cost_upper_yuan,'model',model,'promptVersion',prompt_version,'tools',cast(tool_trace_json as json)) from agent_run where id=${id(run.runId)}`))
    const result = { id: testCase.id, elapsedMillis: Date.now() - started, expected: expectedFor(testCase, catalog), trace: row, run }
    results.push(result); await save()
    result.checks = checkProgramResult(testCase, run, row, catalog)
    const restored = await api(`${base}/runs/${run.runId}`, owner.accessToken)
    assert.deepEqual(restored.answer, run.answer, '恢复必须保留同一问答和依据')
    const repeated = await api(`${base}/messages`, owner.accessToken, 'POST', { requestId: run.requestId, question: testCase.question })
    assert.equal(repeated.runId, run.runId)
    assert.equal(Number(sql(`select model_calls from agent_run where id=${id(run.runId)}`)), row.modelCalls, '幂等恢复不能新增模型调用')
    assert.equal((await api(base, member.accessToken)).messages.length, 0)
    if (mode === 'real') {
      after = await api('/test/agent-budget', owner.accessToken)
      assert.equal(after.budget.unknownReservations, 0)
      assert.ok(after.budget.occupiedUpperYuan <= 10)
    }
    await save()
    process.stdout.write(JSON.stringify({ case: testCase.id, programContract: 'PASSED', semanticReview: 'PENDING', elapsedMillis: result.elapsedMillis }) + '\n')
  }
  success = true
} finally {
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
  await writeFile(`${output}/verification.json`, JSON.stringify({ success, runId, mode, cases: results.length,
    databaseRemaining, realModel: mode === 'real', semanticReview: 'PENDING_HUMAN_REVIEW',
    reusedCases: [...alreadyRun], newCases: results.filter(item => !alreadyRun.has(item.id)).length,
    paidRequests: mode === 'fixture' ? 0 : results.filter(item => !alreadyRun.has(item.id)).reduce((sum, item) => sum + item.trace.modelCalls, 0),
    budget: mode === 'real' ? 'ORIGINAL_CUMULATIVE_10_YUAN_LEDGER_PRESERVED' : 'LOCAL_FIXTURE' }, null, 2))
}
process.stdout.write(JSON.stringify({ success, cases: results.length, databaseRemaining, mode, output }) + '\n')
