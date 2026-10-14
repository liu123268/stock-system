import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium } from 'playwright-core'

const frontend = process.env.FRONTEND_URL ?? 'http://localhost:18083'
const backend = process.env.BACKEND_URL ?? 'http://localhost:18082'
const fixture = 'http://127.0.0.1:18084'
const output = resolve(process.env.AGENT_E2E_OUTPUT ?? '../target/agent-recovery-browser-20261014')
const runId = Date.now().toString(36)
const names = [`ageo_${runId}`, `agem_${runId}`]
const password = 'TestPass123'
const checks = []
const errors = []
let browser
let page
let workspace
let target
let success = false
let cleaned = false
const sql = query => execFileSync(process.env.MYSQL_CLIENT_PATH ?? 'mysql.exe', ['--host=localhost', '--port=3306', '--user=root', '--database=stock_system_test', '--default-character-set=utf8mb4', '--batch', '--skip-column-names', `--execute=${query}`], {
  encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.DB_PASSWORD },
}).trim()
for (const origin of [frontend, backend]) {
  const response = await fetch(`${origin}/api/test/environment`)
  assert.ok(response.ok)
  const { data } = await response.json()
  assert.equal(data.profile, 'test'); assert.equal(data.database, 'stock_system_test')
}
assert.equal(sql('select database()'), 'stock_system_test')
assert.equal((await (await fetch(`${fixture}/_test`)).json()).fixture, true)
const stockExisted = Number(sql("select count(*) from stock where code = '00700.HK'")) > 0
const budgetExisted = Number(sql('select count(*) from agent_budget')) > 0
await mkdir(output, { recursive: true })
async function api(path, token, method = 'GET', body) {
  const response = await fetch(`${backend}/api${path}`, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) })
  const result = await response.json(); assert.ok(response.ok, `${path}: ${result.code}`); return result.data
}
async function fixtureState(mode) {
  return (await fetch(`${fixture}/_test`, mode ? { method: 'POST', body: JSON.stringify({ mode }) } : {})).json()
}
async function capture(name, widths = [1440, 960, 390, 320]) {
  for (const width of widths) {
    await page.setViewportSize({ width, height: 1000 })
    await page.evaluate(() => window.scrollTo(0, 0))
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    await page.screenshot({ path: `${output}/${name}-${width}.png`, fullPage: true })
  }
  checks.push(name)
}
try {
  for (const [index, name] of names.entries()) await api('/auth/register', null, 'POST', { username: name, password, nickname: index ? '成员乙' : '成员甲' })
  const owner = await api('/auth/login', null, 'POST', { username: names[0], password })
  const member = await api('/auth/login', null, 'POST', { username: names[1], password })
  workspace = (await api('/workspaces', owner.accessToken, 'POST', { name: `助手验收${runId}` })).workspaceId
  const invitation = await api(`/workspaces/${workspace}/invitations`, owner.accessToken, 'POST', { expectedInvitationId: null })
  await api('/workspaces/join', member.accessToken, 'POST', { inviteCode: invitation.inviteCode })
  const initial = { code: '00700.HK', stance: 'BULLISH', reason: '成员甲认为新订单可能增长', observationNote: '等待财报或权威公告核对', horizon: 'MEDIUM', targetPrice: null, stopLossPrice: null }
  target = await api(`/workspaces/${workspace}/stocks`, owner.accessToken, 'POST', initial)
  const cycle = target.researchCycleId
  const stock = target.workspaceStockId
  const base = `/workspaces/${workspace}/stocks/${stock}/research/cycles/${cycle}/agent`
  const researchUrl = `${frontend}/workspaces/${workspace}/stocks/${stock}#research-timeline`
  browser = await chromium.launch({ executablePath: process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
  page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${frontend}/login`)
  await page.getByLabel('登录账号').fill(names[0]); await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '进入空间', exact: true }).click(); await page.waitForURL(/\/workspaces/)
  await page.goto(researchUrl)
  await page.getByRole('button', { name: '研究助手', exact: true }).click()
  await page.getByRole('button', { name: '开始梳理', exact: true }).waitFor()
  await capture('empty')
  const trigger = page.getByRole('button', { name: '开始梳理', exact: true })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await trigger.scrollIntoViewIfNeeded(); await trigger.hover(); await page.waitForTimeout(280)
  const bounds = await trigger.boundingBox()
  await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
  await page.mouse.down(); await page.waitForTimeout(180)
  assert.ok(await trigger.evaluate(element => parseFloat(getComputedStyle(element).scale) < 1))
  await page.screenshot({ path: `${output}/pressed-1440.png` })
  await page.mouse.move(2, 2); await page.mouse.up()
  await trigger.focus()
  assert.ok(await trigger.evaluate(element => parseFloat(getComputedStyle(element).outlineWidth) >= 2))
  await trigger.evaluate(element => {
    element.dispatchEvent(new PointerEvent('pointerdown', { pointerType: 'touch', pointerId: 81, bubbles: true }))
    if (!element.hasAttribute('data-touch-pressed')) throw new Error('Touch press feedback is missing')
    element.dispatchEvent(new PointerEvent('pointercancel', { pointerType: 'touch', pointerId: 81, bubbles: true }))
    if (element.hasAttribute('data-touch-pressed')) throw new Error('Touch cancellation did not clear feedback')
  })
  checks.push('default-hover-press-and-keyboard-focus')
  checks.push('touch-pointercancel-clears-feedback-probe')
  const before = (await fixtureState()).calls
  await page.getByRole('button', { name: '开始梳理', exact: true }).click()
  await page.locator('.agent-answer').waitFor()
  assert.equal((await fixtureState()).calls, before + 1)
  await capture('generated')
  await page.setViewportSize({ width: 390, height: 1000 })
  await page.locator('.agent-panel').screenshot({ path: `${output}/generated-panel-390.png` })
  const run = (await api(base, owner.accessToken)).messages[0]
  assert.equal(run.status, 'SUCCEEDED')
  assert.equal((await api(base, member.accessToken)).messages.length, 0)
  await api(`${base}/summaries`, owner.accessToken, 'POST', { requestId: run.requestId })
  assert.equal((await fixtureState()).calls, before + 1)
  checks.push('private-record-and-idempotence')
  const date = page.getByLabel('观点开始日期')
  await page.setViewportSize({ width: 390, height: 1000 })
  await date.fill(new Date().toISOString().slice(0, 10))
  await page.getByRole('button', { name: '查看 成员甲 的原文', exact: true }).click()
  await page.getByRole('dialog').getByText(initial.reason, { exact: true }).waitFor()
  await page.screenshot({ path: `${output}/original-390.png` })
  await page.keyboard.press('Escape')
  assert.equal(await date.inputValue(), new Date().toISOString().slice(0, 10))
  assert.ok((await page.locator('.agent-answer').innerText()).includes('订单可能增长'))
  checks.push('original-dialog-escape-preserves-filter-and-answer')
  await page.reload(); await page.getByRole('button', { name: '研究助手', exact: true }).click()
  await page.locator('.agent-answer').waitFor()
  assert.equal((await fixtureState()).calls, before + 1)
  checks.push('reload-restores-without-model-call')
  const questionInput = page.getByLabel('继续提问')
  const beforeQuestions = (await fixtureState()).calls
  await questionInput.fill('成员甲最新观点和现在最新可用价分别是什么？')
  await page.getByRole('button', { name: '发送问题', exact: true }).click()
  await page.getByText('你的问题：成员甲最新观点和现在最新可用价分别是什么？', { exact: true }).waitFor()
  assert.equal((await fixtureState()).calls, beforeQuestions + 2)
  await questionInput.fill('这个价格什么时候取得？')
  await page.getByRole('button', { name: '查看最新可用报价依据', exact: true }).click()
  await page.getByRole('dialog').getByText(/生成时取得的快照/).waitFor()
  await page.screenshot({ path: `${output}/quote-evidence-390.png` })
  await page.keyboard.press('Escape')
  assert.equal(await questionInput.inputValue(), '这个价格什么时候取得？')
  checks.push('native-two-tool-question-and-quote-evidence')
  await questionInput.fill('请查询2026-09-01至2026-09-04的名义收盘价变化')
  await page.getByRole('button', { name: '发送问题', exact: true }).click()
  await page.getByText('你的问题：请查询2026-09-01至2026-09-04的名义收盘价变化', { exact: true }).waitFor()
  await page.getByRole('button', { name: '查看区间日 K 与名义变化依据', exact: true }).click()
  await page.getByRole('dialog').locator('table tbody tr').first().waitFor()
  await page.keyboard.press('Escape')
  await capture('follow-up', [1440, 960, 390, 320])
  checks.push('daily-price-question-and-java-evidence')
  await page.reload(); await page.getByRole('button', { name: '研究助手', exact: true }).click()
  await page.getByText('你的问题：请查询2026-09-01至2026-09-04的名义收盘价变化', { exact: true }).waitFor()
  checks.push('follow-up-restores-without-model-request')
  const beforeFinancial = (await fixtureState()).calls
  await questionInput.fill('成员判断、最新可用价和年度财务数字一起核对')
  await page.getByRole('button', { name: '发送问题', exact: true }).click()
  await page.getByText('你的问题：成员判断、最新可用价和年度财务数字一起核对', { exact: true }).waitFor()
  assert.equal((await fixtureState()).calls, beforeFinancial + 2)
  const financialRun = (await api(base, owner.accessToken)).messages[0]
  assert.equal(financialRun.answer.followUp.evidence.filter(item => item.type === 'FINANCIAL').length, 1)
  assert.equal(Number(sql(`select tool_calls from agent_run where id=${financialRun.runId}`)), 3)
  assert.equal(financialRun.answer.followUp.evidence.find(item => item.type === 'FINANCIAL').financialFacts.facts[0].current.amount, '751766')
  checks.push('native-three-tool-opinions-quote-and-financial-question')
  await page.getByRole('button', { name: '查看财务数据依据', exact: true }).click()
  const financialDialog = page.getByRole('dialog')
  await financialDialog.getByText('财务依据', { exact: true }).waitFor()
  assert.ok((await financialDialog.innerText()).includes('751766 人民币百万元'))
  assert.ok((await financialDialog.innerText()).includes('224842 人民币百万元'))
  assert.ok((await financialDialog.innerText()).includes('PDF 第 130 页'))
  assert.ok((await financialDialog.locator('a').getAttribute('href')).endsWith('#page=130'))
  await capture('financial-evidence', [1440, 960, 390, 320])
  assert.ok(await financialDialog.locator('.agent-price-table').evaluate(element => element.scrollWidth > element.clientWidth))
  assert.ok(await financialDialog.locator('tbody th').first().evaluate(element => element.getBoundingClientRect().width >= 160))
  await financialDialog.locator('.agent-price-table').evaluate(element => { element.scrollLeft = element.scrollWidth })
  assert.ok(await financialDialog.locator('.agent-price-table').evaluate(element => element.scrollLeft > 0))
  await page.screenshot({ path: `${output}/financial-evidence-scrolled-320.png` })
  checks.push('financial-mobile-horizontal-scroll-keeps-row-labels-readable')
  await page.keyboard.press('Escape')
  assert.equal(await page.getByRole('button', { name: '查看财务数据依据', exact: true }).evaluate(element => document.activeElement === element), true)
  checks.push('financial-exact-amount-original-unit-source-pages-four-widths-and-focus')
  const beforeFinancialRestore = (await fixtureState()).calls
  await page.reload(); await page.getByRole('button', { name: '研究助手', exact: true }).click()
  await page.getByRole('button', { name: '查看财务数据依据', exact: true }).click()
  await page.getByRole('dialog').getByText('财务依据', { exact: true }).waitFor()
  assert.equal((await fixtureState()).calls, beforeFinancialRestore)
  await page.keyboard.press('Escape')
  checks.push('financial-restore-and-source-access-without-paid-request')
  // Chrome 丢掉服务端已经完成的 POST 回包，页面只能 GET 原编号恢复。
  const messagesRoute = '**/agent/messages'
  let lostQuestionId
  const loseReply = async route => {
    lostQuestionId = route.request().postDataJSON().requestId
    await route.fetch()
    await route.abort('failed')
  }
  const beforeLostReply = (await fixtureState()).calls
  await page.route(messagesRoute, loseReply)
  await questionInput.fill('回包丢失时的最新可用价是多少？')
  await page.getByRole('button', { name: '发送问题', exact: true }).click()
  await page.getByText('你的问题：回包丢失时的最新可用价是多少？', { exact: true }).waitFor()
  await page.unroute(messagesRoute, loseReply)
  assert.equal((await fixtureState()).calls, beforeLostReply + 2)
  assert.equal((await api(`${base}/requests/${lostQuestionId}`, owner.accessToken)).status, 'SUCCEEDED')
  assert.equal(await page.evaluate(() => sessionStorage.getItem('stock-system-agent-pending')), null)
  checks.push('lost-success-response-restores-by-request-without-second-post')

  // 失败没有写成功消息，也必须恢复准确终态并保留输入。
  await fixtureState('invalid')
  const beforeLostFailure = (await fixtureState()).calls
  const priorAnswers = await page.locator('.agent-answer').count()
  await page.route(messagesRoute, loseReply)
  await questionInput.fill('失败回包丢失的自造问题')
  await page.getByRole('button', { name: '发送问题', exact: true }).click()
  await page.getByRole('alert').getByText('研究助手未返回可核对的完整结果', { exact: true }).waitFor()
  await page.unroute(messagesRoute, loseReply)
  assert.equal((await fixtureState()).calls, beforeLostFailure + 1)
  assert.equal(await page.locator('.agent-answer').count(), priorAnswers)
  assert.equal(await questionInput.inputValue(), '失败回包丢失的自造问题')
  assert.equal((await api(`${base}/requests/${lostQuestionId}`, owner.accessToken)).status, 'FAILED')
  await page.locator('.agent-panel').screenshot({ path: `${output}/lost-failure-panel-320.png` })
  checks.push('lost-failure-response-restores-terminal-error-and-keeps-draft')

  // 请求未交给服务端时，用户再次提交仍用原编号，实际只执行一次。
  await fixtureState('valid')
  const summariesRoute = '**/agent/summaries'
  let missingRequestId
  const loseBeforeServer = async route => {
    missingRequestId = route.request().postDataJSON().requestId
    await route.abort('failed')
  }
  const beforeMissing = (await fixtureState()).calls
  await page.route(summariesRoute, loseBeforeServer)
  await page.getByRole('button', { name: '重新梳理', exact: true }).click()
  await page.getByRole('alert').getByText(/暂未找到本次任务/).waitFor()
  assert.equal((await fixtureState()).calls, beforeMissing)
  await page.unroute(summariesRoute, loseBeforeServer)
  const retriedPost = page.waitForRequest(request => request.url().endsWith('/agent/summaries') && request.method() === 'POST')
  await page.getByRole('button', { name: '重新梳理', exact: true }).click()
  assert.equal((await retriedPost).postDataJSON().requestId, missingRequestId)
  await page.waitForFunction(() => sessionStorage.getItem('stock-system-agent-pending') === null)
  assert.equal((await fixtureState()).calls, beforeMissing + 1)
  checks.push('missing-run-manual-submit-reuses-request-id')

  // 两轮工具调用间刷新；新页面恢复 RUNNING 并只轮询本地状态。
  await fixtureState('slow')
  const beforeReload = (await fixtureState()).calls
  await questionInput.fill('刷新中最新可用价是多少？')
  await page.getByRole('button', { name: '发送问题', exact: true }).click()
  await page.waitForFunction(() => sessionStorage.getItem('stock-system-agent-pending') !== null)
  for (let attempt = 0; attempt < 40 && (await fixtureState()).calls === beforeReload; attempt++)
    await page.waitForTimeout(50)
  assert.equal((await fixtureState()).calls, beforeReload + 1)
  await page.reload(); await page.getByRole('button', { name: '研究助手', exact: true }).click()
  await page.getByText('你的问题：刷新中最新可用价是多少？', { exact: true }).waitFor()
  await page.waitForFunction(() => sessionStorage.getItem('stock-system-agent-pending') === null)
  assert.equal((await fixtureState()).calls, beforeReload + 2)
  await page.locator('.agent-panel').screenshot({ path: `${output}/reload-restored-panel-320.png` })
  checks.push('reload-during-question-restores-original-run-without-new-model-call')

  // 本轮自造账号在模型第一轮期间失权；即使随后恢复 GET 被阻断也立即隐藏正文。
  const beforeAccessLoss = (await fixtureState()).calls
  const preventRestore = async route => {
    if (route.request().method() === 'GET') await route.abort('failed')
    else await route.continue()
  }
  await page.route('**/agent', preventRestore)
  await questionInput.fill('查询中失权的最新可用价？')
  await page.getByRole('button', { name: '发送问题', exact: true }).click()
  for (let attempt = 0; attempt < 40 && (await fixtureState()).calls === beforeAccessLoss; attempt++)
    await page.waitForTimeout(50)
  assert.equal((await fixtureState()).calls, beforeAccessLoss + 1)
  sql(`update workspace_member set status='LEFT',last_inactive_at=current_timestamp(3) where workspace_id=${workspace} and user_id=${owner.userId}`)
  await page.getByRole('alert').getByText('当前空间成员关系已失效', { exact: true }).waitFor()
  assert.equal(await page.locator('.agent-answer').count(), 0)
  assert.equal(await page.locator('.source-link').count(), 0)
  assert.equal((await fixtureState()).calls, beforeAccessLoss + 1)
  await page.locator('.agent-panel').screenshot({ path: `${output}/access-lost-panel-320.png` })
  await page.unroute('**/agent', preventRestore)
  sql(`update workspace_member set status='ACTIVE',last_inactive_at=null where workspace_id=${workspace} and user_id=${owner.userId}`)
  await page.reload(); await page.getByRole('button', { name: '研究助手', exact: true }).click()
  await page.locator('.agent-answer').first().waitFor()
  checks.push('inflight-access-loss-clears-private-body-even-if-restore-disconnects')
  await fixtureState('valid')
  const validCount = await page.locator('.agent-answer').count()
  await fixtureState('invalid')
  await page.getByRole('button', { name: '重新梳理', exact: true }).click()
  await page.getByRole('alert').getByText('研究助手未返回可核对的完整结果', { exact: true }).waitFor()
  assert.equal(await page.locator('.agent-answer').count(), validCount)
  await capture('failed-keeps-old', [1440, 390])
  await fixtureState('slow')
  await page.getByRole('button', { name: '重新梳理', exact: true }).click()
  await page.getByRole('button', { name: '正在梳理…', exact: true }).waitFor()
  await page.getByRole('button', { name: '清空个人记录', exact: true }).click()
  await page.getByRole('button', { name: '确认清空', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '已清空本周期' }).waitFor()
  await page.waitForTimeout(2600)
  assert.equal(await page.locator('.agent-answer').count(), 0)
  assert.equal((await api(base, owner.accessToken)).messages.length, 0)
  assert.ok(Number(sql(`select count(*) from agent_run where user_id = ${owner.userId}`)) >= 3)
  checks.push('clear-during-generation-late-result-blocked-ledger-retained')
  await fixtureState('valid')
  await page.getByRole('button', { name: '开始梳理', exact: true }).click(); await page.locator('.agent-answer').waitFor()
  const originalId = (await api(base, owner.accessToken)).messages[0].answer.references[0].opinionId
  await api(`/workspaces/${workspace}/stocks/${stock}/opinions/${originalId}`, owner.accessToken, 'DELETE')
  await page.reload(); await page.getByRole('button', { name: '研究助手', exact: true }).click()
  await page.getByText('部分结果依赖的观点已经撤回或变化，需要重新梳理。', { exact: true }).waitFor()
  assert.equal(await page.locator('.agent-prose').count(), 0)
  assert.equal(await page.locator('.source-link').count(), 0)
  await capture('withdrawn', [1440, 390])
  checks.push('withdrawal-invalidates-entire-result')
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.getByRole('button', { name: '清空个人记录', exact: true }).focus()
  assert.ok(await page.getByRole('button', { name: '清空个人记录', exact: true }).evaluate(element => parseFloat(getComputedStyle(element).transitionDuration) <= 0.001))
  checks.push('keyboard-focus-and-reduced-motion')
  assert.deepEqual(errors, [])
  success = true
} catch (error) {
  if (page) await page.screenshot({ path: `${output}/failure.png`, fullPage: true })
  throw error
} finally {
  try { if (browser) await browser.close() }
  finally {
    assert.ok(names.every(name => /^age[om]_[a-z0-9]+$/.test(name)))
    const list = names.map(name => `'${name}'`).join(',')
    // 测试中断时也等本轮有界请求收敛，避免删除账本后再有迟到写入。
    for (let attempt = 0; attempt < 65; attempt++) {
      const active = Number(sql(`select count(*) from agent_run r join app_user u on u.id=r.user_id where u.username in (${list}) and r.status='RUNNING'`))
      if (!active) break
      if (attempt === 64) assert.equal(active, 0, '本轮生成尚未结束，禁止清理运行中的 fixture')
      await new Promise(resolve => setTimeout(resolve, 1000))
    }
    const result = sql(`create temporary table agent_test_users as select id from app_user where username in (${list});
      create temporary table agent_test_spaces as select id from workspace where created_by_user_id in (select id from agent_test_users);
      create temporary table agent_test_stocks as select id from workspace_stock where workspace_id in (select id from agent_test_spaces);
      create temporary table agent_test_cycles as select id from research_cycle where workspace_stock_id in (select id from agent_test_stocks);
      set @agent_own_cost=(select coalesce(sum(coalesce(cost_upper_yuan, reserved_yuan)),0) from agent_run where user_id in (select id from agent_test_users));
      delete m from agent_message m join agent_session s on s.id=m.session_id where s.user_id in (select id from agent_test_users);
      delete from agent_run where user_id in (select id from agent_test_users);
      delete from agent_session where user_id in (select id from agent_test_users);
      update agent_budget set occupied_yuan=occupied_yuan-@agent_own_cost where id=1;
      ${budgetExisted ? '' : 'delete from agent_budget where occupied_yuan=0 and not exists(select 1 from agent_run);'}
      delete from research_attention_change where research_cycle_id in (select id from agent_test_cycles);
      delete from stock_opinion where research_cycle_id in (select id from agent_test_cycles);
      delete from research_cycle where id in (select id from agent_test_cycles);
      delete from workspace_stock where id in (select id from agent_test_stocks);
      delete from workspace_invitation where workspace_id in (select id from agent_test_spaces);
      delete from workspace_member where workspace_id in (select id from agent_test_spaces);
      delete from workspace where id in (select id from agent_test_spaces);
      delete from invitation_join_attempt where user_id in (select id from agent_test_users);
      delete from app_user where id in (select id from agent_test_users);
      ${stockExisted ? '' : "delete from stock where code='00700.HK' and source='LOCAL_MOCK' and not exists(select 1 from workspace_stock where stock_id=stock.id);"}
      select count(*) from agent_message m join agent_session s on s.id=m.session_id where s.user_id in (select id from agent_test_users);
      select count(*) from agent_run where user_id in (select id from agent_test_users);
      select count(*) from agent_session where user_id in (select id from agent_test_users);
      select count(*) from app_user where username in (${list});
      select count(*) from workspace where id in (select id from agent_test_spaces);
      select count(*) from workspace_member where workspace_id in (select id from agent_test_spaces);
      select count(*) from workspace_invitation where workspace_id in (select id from agent_test_spaces);
      select count(*) from workspace_stock where id in (select id from agent_test_stocks);
      select count(*) from research_cycle where id in (select id from agent_test_cycles);
      select count(*) from stock_opinion where research_cycle_id in (select id from agent_test_cycles);`)
    result.split(/\r?\n/).forEach(value => assert.equal(Number(value), 0))
    cleaned = true
    await writeFile(`${output}/verification.json`, JSON.stringify({ success, runId, checks, errors, databaseRemaining: cleaned ? 0 : null, model: 'LOCAL_HTTP_FIXTURE', paidRequests: 0 }, null, 2))
  }
}
console.log(JSON.stringify({ success, checks, databaseRemaining: 0, paidRequests: 0 }))
