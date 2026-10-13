import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium } from 'playwright-core'

const frontend = process.env.FRONTEND_URL ?? 'http://localhost:18083'
const backend = process.env.BACKEND_URL ?? 'http://localhost:18082'
const fixture = 'http://127.0.0.1:18084'
const output = resolve('../target/agent-browser-20261013')
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
  await fixtureState('invalid')
  await page.getByRole('button', { name: '重新梳理', exact: true }).click()
  await page.getByRole('alert').getByText('研究助手未返回可核对的完整结果', { exact: true }).waitFor()
  assert.equal(await page.locator('.agent-answer').count(), 1)
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
