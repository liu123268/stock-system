import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { chromium } from 'playwright-core'

// 需显式启用，正式应用 test profile + 旧实验账本文件锁，仍只用自造观点和模拟行情。
assert.equal(process.env.AGENT_REAL_MODEL_PROBE, 'true', '真实模型验收必须显式启用')
const backend = 'http://localhost:18082'
const frontend = 'http://localhost:18083'
const output = '../target/agent-real-20261014'
const runId = Date.now().toString(36)
const names = [`agro_${runId}`, `agrm_${runId}`]
const password = 'TestPass123'
const results = []
let browser
let page
let success = false
const sql = query => execFileSync(process.env.MYSQL_CLIENT_PATH, ['--user=root', '--host=localhost', '--port=3306', '--database=stock_system_test', '--default-character-set=utf8mb4', '--batch', '--skip-column-names', `--execute=${query}`], { encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.DB_PASSWORD } }).trim()
for (const origin of [backend, frontend]) {
  const response = await fetch(`${origin}/api/test/environment`)
  assert.ok(response.ok)
  const { data } = await response.json(); assert.equal(data.profile, 'test'); assert.equal(data.database, 'stock_system_test')
}
assert.equal(sql('select database()'), 'stock_system_test')
const stockExisted = Number(sql("select count(*) from stock where code='00700.HK'")) > 0
const budgetExisted = Number(sql('select count(*) from agent_budget')) > 0
await mkdir(output, { recursive: true })
async function api(path, token, method = 'GET', body) {
  const response = await fetch(`${backend}/api${path}`, { method, signal: AbortSignal.timeout(75_000), headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) })
  const result = await response.json(); assert.ok(response.ok, `${path}: ${result.code}`); return result.data
}
try {
  for (const [index, name] of names.entries()) await api('/auth/register', null, 'POST', { username: name, password, nickname: index ? '成员乙' : '成员甲' })
  const owner = await api('/auth/login', null, 'POST', { username: names[0], password })
  const member = await api('/auth/login', null, 'POST', { username: names[1], password })
  const before = await api('/test/agent-budget', owner.accessToken)
  assert.equal(before.officialEndpoint, true)
  assert.equal(before.budget.limitYuan, 10)
  assert.equal(before.budget.unknownReservations, 0)
  const workspace = (await api('/workspaces', owner.accessToken, 'POST', { name: `真实模型样本${runId}` })).workspaceId
  const invitation = await api(`/workspaces/${workspace}/invitations`, owner.accessToken, 'POST', { expectedInvitationId: null })
  await api('/workspaces/join', member.accessToken, 'POST', { inviteCode: invitation.inviteCode })
  const initial = { code: '00700.HK', stance: 'BULLISH', reason: '验收自造样本：成员甲认为新订单可能增长，没有权威公告确认。', observationNote: '需要对应财报、业绩预告或其他权威披露核对。', horizon: 'MEDIUM', targetPrice: null, stopLossPrice: null }
  const target = await api(`/workspaces/${workspace}/stocks`, owner.accessToken, 'POST', initial)
  await api(`/workspaces/${workspace}/stocks/${target.workspaceStockId}/opinions`, member.accessToken, 'POST', { researchCycleId: target.researchCycleId, type: 'UPDATE', ...initial, stance: 'NEUTRAL', reason: '验收自造样本：成员乙担心盈利能力，尚未查询财报。' })
  const base = `/workspaces/${workspace}/stocks/${target.workspaceStockId}/research/cycles/${target.researchCycleId}/agent`
  const steps = [
    { name: 'summary', path: `${base}/summaries`, body: {} },
    { name: 'opinion-fact-boundary', path: `${base}/messages`, body: { question: '成员甲提到订单可能增长，现在能确认订单已经增长了吗？请按本次资料说明依据和缺口。' } },
    { name: 'current-price', path: `${base}/messages`, body: { question: '现在最新可用价是多少？请说明币种、数据时间、来源和是否模拟。' } },
    { name: 'daily-price-change', path: `${base}/messages`, body: { question: '请查这只股票2026-09-01至2026-09-04的日K，给出本次实际取得区间的名义收盘价变化，说明是否复权和是否模拟。' } },
  ]
  for (const step of steps) {
    const started = Date.now()
    const run = await api(step.path, owner.accessToken, 'POST', { requestId: crypto.randomUUID(), ...step.body })
    results.push({ name: step.name, elapsedMillis: Date.now() - started, ...run })
    await writeFile(`${output}/results.json`, JSON.stringify({ runId, before, results }, null, 2))
    assert.equal(run.status, 'SUCCEEDED', `${step.name}: ${run.errorCode}`)
    assert.ok(run.answer)
    if (step.name === 'current-price') {
      const quote = run.answer.followUp.evidence.find(item => item.type === 'QUOTE')
      assert.ok(quote); assert.equal(quote.quote.simulated, true)
    }
    if (step.name === 'daily-price-change') assert.ok(run.answer.followUp.evidence.some(item => item.type === 'DAILY_PRICES'))
    // 查状态不调用模型；相同请求重放复用账本。
    const originalCalls = Number(sql(`select model_calls from agent_run where id=${run.runId}`))
    assert.ok(originalCalls >= 1 && originalCalls <= 3)
    await api(`${base}/runs/${run.runId}`, owner.accessToken)
    const replay = await api(step.path, owner.accessToken, 'POST', { requestId: run.requestId, ...step.body })
    assert.equal(replay.runId, run.runId)
    assert.equal(Number(sql(`select model_calls from agent_run where id=${run.runId}`)), originalCalls)
  }
  assert.equal((await api(base, member.accessToken)).messages.length, 0)
  browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
  page = await browser.newPage({ viewport: { width: 1440, height: 1100 } })
  await page.goto(`${frontend}/login`)
  await page.getByLabel('登录账号').fill(names[0]); await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '进入空间', exact: true }).click(); await page.waitForURL(/\/workspaces/)
  await page.goto(`${frontend}/workspaces/${workspace}/stocks/${target.workspaceStockId}#research-timeline`)
  await page.getByRole('button', { name: '研究助手', exact: true }).click()
  await page.getByText(`你的问题：${steps[3].body.question}`, { exact: true }).waitFor()
  assert.equal(await page.locator('.agent-answer').count(), 4)
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 1100 }); await page.evaluate(() => window.scrollTo(0, 0))
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    await page.screenshot({ path: `${output}/real-answer-${width}.png`, fullPage: true })
  }
  const after = await api('/test/agent-budget', owner.accessToken)
  assert.equal(after.budget.unknownReservations, 0)
  assert.ok(after.budget.occupiedUpperYuan <= 10)
  const usage = sql(`select id, model_calls, tool_calls, input_tokens, output_tokens, cost_upper_yuan, tool_trace_json from agent_run where user_id=${owner.userId} order by id`)
  await writeFile(`${output}/results.json`, JSON.stringify({ runId, before, after, results, usage, data: 'SELF_MADE_OPINIONS_AND_MOCK_MARKET', realModel: true, qualityReview: 'PENDING_HUMAN_REVIEW' }, null, 2))
  success = true
} finally {
  if (browser) await browser.close()
  assert.ok(names.every(name => /^agr[om]_[a-z0-9]+$/.test(name)))
  const list = names.map(name => `'${name}'`).join(',')
  for (let attempt = 0; attempt < 125; attempt++) {
    const active = Number(sql(`select count(*) from agent_run r join app_user u on u.id=r.user_id where u.username in (${list}) and r.status='RUNNING'`))
    if (!active) break
    if (attempt === 124) assert.equal(active, 0, '本轮付费运行尚未结束，禁止删运行 fixture')
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  const remaining = sql(`create temporary table real_agent_users as select id from app_user where username in (${list});
    create temporary table real_agent_spaces as select id from workspace where created_by_user_id in (select id from real_agent_users);
    create temporary table real_agent_stocks as select id from workspace_stock where workspace_id in (select id from real_agent_spaces);
    create temporary table real_agent_cycles as select id from research_cycle where workspace_stock_id in (select id from real_agent_stocks);
    set @real_agent_cost=(select coalesce(sum(coalesce(cost_upper_yuan,reserved_yuan)),0) from agent_run where user_id in (select id from real_agent_users));
    delete m from agent_message m join agent_session s on s.id=m.session_id where s.user_id in (select id from real_agent_users);
    delete from agent_run where user_id in (select id from real_agent_users);
    delete from agent_session where user_id in (select id from real_agent_users);
    update agent_budget set occupied_yuan=occupied_yuan-@real_agent_cost where id=1;
    ${budgetExisted ? '' : 'delete from agent_budget where occupied_yuan=0 and not exists(select 1 from agent_run);'}
    delete from research_attention_change where research_cycle_id in (select id from real_agent_cycles);
    delete from stock_opinion where research_cycle_id in (select id from real_agent_cycles);
    delete from research_cycle where id in (select id from real_agent_cycles);
    delete from workspace_stock where id in (select id from real_agent_stocks);
    delete from workspace_invitation where workspace_id in (select id from real_agent_spaces);
    delete from workspace_member where workspace_id in (select id from real_agent_spaces);
    delete from workspace where id in (select id from real_agent_spaces);
    delete from invitation_join_attempt where user_id in (select id from real_agent_users);
    delete from app_user where id in (select id from real_agent_users);
    ${stockExisted ? '' : "delete from stock where code='00700.HK' and source='LOCAL_MOCK' and not exists(select 1 from workspace_stock where stock_id=stock.id);"}
    select count(*) from app_user where username in (${list});
    select count(*) from agent_run where user_id in (select id from real_agent_users);
    select count(*) from agent_session where user_id in (select id from real_agent_users);
    select count(*) from workspace where id in (select id from real_agent_spaces);
    select count(*) from workspace_member where workspace_id in (select id from real_agent_spaces);
    select count(*) from workspace_invitation where workspace_id in (select id from real_agent_spaces);
    select count(*) from workspace_stock where id in (select id from real_agent_stocks);
    select count(*) from research_cycle where id in (select id from real_agent_cycles);
    select count(*) from stock_opinion where research_cycle_id in (select id from real_agent_cycles);`)
  remaining.split(/\r?\n/).forEach(value => assert.equal(Number(value), 0))
  await writeFile(`${output}/verification.json`, JSON.stringify({ success, runId, databaseRemaining: 0, realModel: true, budget: 'ORIGINAL_CUMULATIVE_10_YUAN_LEDGER' }, null, 2))
}
console.log(JSON.stringify({ success, cases: results.length, databaseRemaining: 0, budget: 'original-10-yuan' }))
