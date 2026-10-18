import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { mkdir, writeFile, readFile } from 'node:fs/promises'
import { createServer } from 'node:net'
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
function maintainReport(action, input, expectedExit = 0) {
  assert.ok(process.env.AGENT_REPORT_LIBRARY)
  const result = spawnSync(process.env.AGENT_JAVA_PATH, ['-Dloader.main=com.stock.system.agent.report.ReportMaintenanceCommand',
    '-cp', resolve('../target/stock-system-0.0.1-SNAPSHOT.jar'), 'org.springframework.boot.loader.launch.PropertiesLauncher',
    action, process.env.AGENT_REPORT_LIBRARY, ...(input ? [input] : [])], { cwd: resolve('..'), encoding: 'utf8', timeout: 30_000, windowsHide: true })
  assert.equal(result.status, expectedExit, `Maintenance ${action} failed`)
  return JSON.parse(result.stdout.trim().split(/\r?\n/).at(-1))
}
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
  await page.getByRole('link', { name: '研究助手', exact: true }).click()
  await page.getByRole('button', { name: '开始梳理', exact: true }).waitFor()
  const assistantUrl = page.url()
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
  await page.getByRole('button', { name: '观点日期范围', exact: true }).click()
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
  await page.reload()
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
  await page.reload()
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
  await page.reload()
  await page.getByRole('button', { name: '查看财务数据依据', exact: true }).click()
  await page.getByRole('dialog').getByText('财务依据', { exact: true }).waitFor()
  assert.equal((await fixtureState()).calls, beforeFinancialRestore)
  await page.keyboard.press('Escape')
  checks.push('financial-restore-and-source-access-without-paid-request')
  for (const guard of [{ mode: 'invalid-financial-number', name: 'number' }, { mode: 'invalid-financial-percent', name: 'percentage' }]) {
    await fixtureState(guard.mode)
    const beforeNumberGuard = (await fixtureState()).calls
    const previousAnswers = await page.locator('.agent-answer').count()
    await questionInput.fill('年度财务数字按原报核对')
    const financialPost = page.waitForRequest(request => request.url().endsWith('/agent/messages/stream') && request.method() === 'POST')
    await page.getByRole('button', { name: '发送问题', exact: true }).click()
    const guardedRequestId = (await financialPost).postDataJSON().requestId
    assert.match(guardedRequestId, /^[0-9a-f-]{36}$/)
    await page.getByRole('alert').getByText('研究助手未返回可核对的完整结果', { exact: true }).waitFor()
    assert.equal(await page.locator('.agent-answer').count(), previousAnswers)
    assert.equal((await page.locator('.agent-panel').innerText()).includes('1688.3810251459'), false)
    const numberGuard = JSON.parse(sql(`select json_object('runId', id, 'status', status, 'errorCode', error_code,
      'modelCalls', model_calls, 'costKnown', cost_upper_yuan is not null, 'failure', cast(model_failure_json as json))
      from agent_run where user_id=${owner.userId} and request_id='${guardedRequestId}'`))
    assert.equal(numberGuard.status, 'FAILED')
    assert.equal(numberGuard.errorCode, 'RESEARCH_AGENT_INVALID_RESULT')
    assert.equal(numberGuard.modelCalls, 2)
    assert.equal(numberGuard.costKnown, true)
    assert.deepEqual(numberGuard.failure, { stage: 'QUESTION_REPLY_FINANCIAL', category: 'RESEARCH_AGENT_INVALID_RESULT',
      upstreamStatus: 200, modelCall: 2, runId: numberGuard.runId, requestId: guardedRequestId })
    assert.equal(sql(`select count(*) from agent_message where run_id=${numberGuard.runId}`), '0')
    assert.equal((await fixtureState()).calls, beforeNumberGuard + 2)
    await writeFile(`${output}/financial-${guard.name}-guard.json`, JSON.stringify(numberGuard, null, 2))
    await page.locator('.agent-panel').screenshot({ path: `${output}/financial-${guard.name}-rejected.png` })
    checks.push(`financial-original-unit-${guard.name}-guard-rejects-model-conversion-without-message-or-retry`)
  }
  await fixtureState('valid')
  if (process.env.AGENT_REPORT_RAG_PROBE === 'true') {
    const library = await api(`${base}/reports`, owner.accessToken)
    assert.equal(library.retrievalEnabled, true)
    assert.equal(library.reports.length, 1)
    assert.ok(library.reports[0].title.includes('腾讯'))
    assert.equal((await fetch(`${backend}/api${base}/reports`)).status, 401)
    await page.locator('.agent-capabilities summary').click()
    await page.locator('.agent-library-report').waitFor()
    assert.ok((await page.locator('.agent-library-content').innerText()).includes('营销服务'))
    for (const width of [1440, 960, 390, 320]) {
      await page.setViewportSize({ width, height: 1000 })
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
      assert.ok(await questionInput.isVisible())
      const sendBounds = await page.getByRole('button', { name: '发送问题', exact: true }).boundingBox()
      assert.ok(sendBounds && sendBounds.y >= 0 && sendBounds.y + sendBounds.height <= 1000)
      assert.ok(await page.getByRole('button', { name: '发送问题', exact: true }).evaluate(element => {
        const rect = element.getBoundingClientRect()
        return element.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2))
      }))
      await page.screenshot({ path: `${output}/report-library-${width}.png` })
    }
    await page.locator('.agent-capabilities summary').click()
    const reportQuestion = '请结合成员观点与2025年报告原文，说明营销服务收入增长的解释'
    const beforeReport = (await fixtureState()).calls
    await questionInput.fill(reportQuestion)
    await page.getByRole('button', { name: '发送问题', exact: true }).click()
    await page.getByText(`你的问题：${reportQuestion}`, { exact: true }).waitFor()
    assert.equal((await fixtureState()).calls, beforeReport + 2)
    const reportRun = (await api(base, owner.accessToken)).messages[0]
    const cited = reportRun.answer.followUp.evidence.filter(item => item.type === 'REPORT')
    assert.ok(cited.length > 0 && cited.length <= 3)
    assert.ok(cited.every(item => item.reportPassage.symbol === '00700.HK' && item.reportPassage.reportYear === 2025))
    assert.ok(reportRun.answer.references.length > 0)
    assert.ok(sql(`select result_json from agent_message where run_id=${reportRun.runId}`).includes('reportPassage'))
    await page.getByRole('button', { name: '查看报告原文依据', exact: true }).click()
    const reportDialog = page.getByRole('dialog')
    await reportDialog.getByText('报告原文', { exact: true }).waitFor()
    assert.ok((await reportDialog.innerText()).includes('PDF 第 9 页'))
    assert.ok((await reportDialog.locator('.agent-report-passage').innerText()).includes('營銷服務'))
    assert.ok((await reportDialog.locator('a').getAttribute('href')).endsWith('#page=9'))
    await capture('report-original', [1440, 960, 390, 320])
    await page.keyboard.press('Escape')
    assert.equal(await page.getByRole('button', { name: '查看报告原文依据', exact: true }).evaluate(element => document.activeElement === element), true)
    const beforeReportRestore = (await fixtureState()).calls
    await page.reload()
    await page.getByRole('button', { name: '查看报告原文依据', exact: true }).click()
    await reportDialog.getByText('报告原文', { exact: true }).waitFor()
    assert.equal((await fixtureState()).calls, beforeReportRestore)
    await page.keyboard.press('Escape')
    for (const [question, status] of [['请在2024年报告原文中查询营销服务', 'NO_REPORT'], ['报告原文是否说明飞船发射时间', 'NO_MATCH']]) {
      const absent = await api(`${base}/messages`, owner.accessToken, 'POST', { requestId: crypto.randomUUID(), question })
      assert.equal(absent.status, 'SUCCEEDED')
      assert.equal(absent.answer.followUp.evidence.filter(item => item.type === 'REPORT').length, 0)
      assert.ok(absent.answer.followUp.reply.paragraphs[0].text.includes(status === 'NO_REPORT' ? '没有已入库' : '未找到足够相关'))
    }
    checks.push('real-java-qdrant-retrieval-with-loopback-answer-model')
    checks.push('report-library-current-stock-and-unauthenticated-refusal')
    checks.push('report-and-opinion-combination-with-canonical-text-and-pdf-page')
    checks.push('report-database-snapshot-restore-and-read-without-model-call')
    checks.push('missing-report-year-and-no-match-do-not-fabricate-citations')
    const tencent = JSON.parse(await readFile('../src/main/resources/reports/reviewed-passages.json', 'utf8')).passages.filter(row => row.symbol === '00700.HK')
    const fullReport = `${output}/reviewed-tencent.json`
    const partialReport = `${output}/reviewed-tencent-one-passage.json`
    await writeFile(fullReport, JSON.stringify({ version: 'reviewed-probe', passages: tencent }))
    await writeFile(partialReport, JSON.stringify({ version: 'reviewed-probe', passages: tencent.slice(0, 1) }))
    const reportKey = library.states.find(item => item.title.includes('腾讯')).key
    maintainReport('remove', reportKey)
    const removed = await api(`${base}/reports`, owner.accessToken)
    assert.equal(removed.reports.length, 0)
    assert.equal(removed.states[0].status, 'REMOVED')
    const invalidated = await api(`${base}/runs/${reportRun.runId}`, owner.accessToken)
    assert.equal(invalidated.answer, null)
    assert.equal(invalidated.errorCode, 'RESEARCH_AGENT_SOURCES_CHANGED')
    await page.reload()
    await page.locator('.agent-capabilities summary').click()
    await page.getByText(/已移除，相关旧引用已失效/).waitFor()
    assert.equal(await page.getByRole('button', { name: '查看报告原文依据', exact: true }).count(), 0)
    await capture('report-removed', [1440, 390, 320])
    await page.locator('.agent-capabilities summary').click()
    maintainReport('import', fullReport, 1)
    assert.equal((await api(`${base}/reports`, owner.accessToken)).reports.length, 0)
    maintainReport('restore', fullReport)
    assert.equal((await api(`${base}/reports`, owner.accessToken)).reports.length, 1)
    maintainReport('import', partialReport)
    assert.equal((await api(`${base}/reports`, owner.accessToken)).reports[0].sections.length, 1)
    assert.ok((await api(`${base}/runs/${reportRun.runId}`, owner.accessToken)).answer)
    // A genuinely occupied local index port exercises failure retention without a production fault switch.
    const occupied = createServer(socket => socket.end())
    await new Promise(resolve => occupied.listen(16336, '127.0.0.1', resolve))
    try { maintainReport('import', fullReport, 1) }
    finally { await new Promise(resolve => occupied.close(resolve)) }
    const failedUpdate = await api(`${base}/reports`, owner.accessToken)
    assert.equal(failedUpdate.states[0].status, 'FAILED')
    assert.equal(failedUpdate.states[0].usableVersion, true)
    assert.equal(failedUpdate.reports[0].sections.length, 1)
    await page.locator('.agent-capabilities summary').click()
    await page.getByText(/更新失败，仍可查询上个可用版本/).waitFor()
    await capture('report-update-failed', [1440, 390, 320])
    await page.locator('.agent-capabilities summary').click()
    maintainReport('retry', fullReport)
    assert.ok((await api(`${base}/reports`, owner.accessToken)).reports[0].sections.length > 1)
    checks.push('report-removal-blocks-stale-index-and-historical-original')
    checks.push('report-maintenance-cli-explicit-restore-and-live-update')
    checks.push('report-failed-index-build-keeps-previous-publication-and-retry')
  }
  // 先产生足够的自造问答，再只读加载上一页，实际覆盖长记录与分页。
  for (let index = 1; index <= 8; index++) {
    const question = `分页样本 ${index}：现在最新可用价是多少？`
    await questionInput.fill(question)
    await page.getByRole('button', { name: '发送问题', exact: true }).click()
    await page.getByText(`你的问题：${question}`, { exact: true }).waitFor()
  }
  const beforeEarlier = (await fixtureState()).calls
  await page.getByRole('button', { name: '查看更早记录', exact: true }).click()
  await page.waitForFunction(() => document.querySelectorAll('.agent-answer').length > 6)
  await page.locator('.agent-panel[aria-busy="false"]').waitFor()
  assert.equal((await fixtureState()).calls, beforeEarlier)
  checks.push('read-earlier-chat-page-without-model-request')
  // 长记录只滚动助手内部；输入区始终可见，主页面不被聊天撑长。
  for (const width of [1440, 960, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 })
    await page.waitForFunction(expected => innerWidth === expected, width)
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
    const bounds = await page.evaluate(() => {
      const history = document.querySelector('.agent-history')
      const composer = document.querySelector('.agent-question').getBoundingClientRect()
      const ancestors = []
      for (let node = history; node && node !== document.body; node = node.parentElement) {
        const css = getComputedStyle(node)
        ancestors.push({ class: node.className, height: node.clientHeight, display: css.display,
          flex: css.flex, minHeight: css.minHeight, gridRows: css.gridTemplateRows })
      }
      return { documentHeight: document.documentElement.scrollHeight, viewport: innerHeight,
        historyHeight: history.clientHeight, historyScroll: history.scrollHeight,
        composerTop: composer.top, composerBottom: composer.bottom, ancestors,
        answerHeights: [...document.querySelectorAll('.agent-answer')].map(item => item.clientHeight) }
    })
    assert.ok(bounds.documentHeight <= bounds.viewport + 1, JSON.stringify(bounds))
    assert.ok(bounds.historyScroll > bounds.historyHeight, JSON.stringify(bounds))
    assert.ok(bounds.composerTop >= 0 && bounds.composerBottom <= bounds.viewport)
    await page.locator('.agent-history').evaluate(element => { element.scrollTop = 0 })
    assert.equal(await questionInput.isVisible(), true)
    await page.screenshot({ path: `${output}/assistant-page-${width}.png` })
  }
  checks.push('long-history-internal-scroll-and-visible-composer-four-widths')
  const beforeReturn = (await fixtureState()).calls
  await questionInput.fill('返回研究时保留的未发送草稿')
  await page.getByRole('link', { name: '← 返回研究观点', exact: true }).click()
  await page.locator(`[data-cycle-id="${cycle}"]`).waitFor()
  assert.equal(await page.locator('.agent-panel').count(), 0)
  assert.equal(await page.locator('.agent-answer').count(), 0)
  await page.getByRole('link', { name: '研究助手', exact: true }).click()
  await page.waitForFunction(() => document.querySelector('.agent-question textarea')?.value === '返回研究时保留的未发送草稿')
  assert.equal((await fixtureState()).calls, beforeReturn)
  checks.push('return-to-opinions-without-chat-body-and-restore-unsent-draft')
  // Chrome 丢掉服务端已经完成的 POST 回包，页面只能 GET 原编号恢复。
  const messagesRoute = '**/agent/messages/stream'
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
  const summariesRoute = '**/agent/summaries/stream'
  let missingRequestId
  const loseBeforeServer = async route => {
    missingRequestId = route.request().postDataJSON().requestId
    await route.abort('failed')
  }
  const beforeMissing = (await fixtureState()).calls
  await page.route(summariesRoute, loseBeforeServer)
  await page.locator('.agent-controls button[type="submit"]').click()
  await page.getByRole('alert').getByText(/暂未找到本次任务/).waitFor()
  assert.equal((await fixtureState()).calls, beforeMissing)
  await page.unroute(summariesRoute, loseBeforeServer)
  const retriedPost = page.waitForRequest(request => request.url().endsWith('/agent/summaries/stream') && request.method() === 'POST')
  await page.locator('.agent-controls button[type="submit"]').click()
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
  await page.reload()
  await page.getByText('你的问题：刷新中最新可用价是多少？', { exact: true }).waitFor()
  await page.waitForFunction(() => sessionStorage.getItem('stock-system-agent-pending') === null)
  assert.equal((await fixtureState()).calls, beforeReload + 2)
  await page.locator('.agent-panel').screenshot({ path: `${output}/reload-restored-panel-320.png` })
  checks.push('reload-during-question-restores-original-run-without-new-model-call')

  // SSE 的真实阶段先于正文到达；停止只结束本轮，已发出的调用仍计入用量。
  const beforeStop = (await fixtureState()).calls
  const answersBeforeStop = await page.locator('.agent-answer').count()
  const stoppedPost = page.waitForRequest(request => request.url().endsWith('/agent/messages/stream') && request.method() === 'POST')
  await questionInput.fill('停止后保留的最新报价问题')
  await page.getByRole('button', { name: '发送问题', exact: true }).click()
  const stoppedRequestId = (await stoppedPost).postDataJSON().requestId
  await page.getByRole('status').filter({ hasText: '正在整理回答…' }).waitFor()
  assert.equal(await page.locator('.agent-answer').count(), answersBeforeStop)
  checks.push('sse-progress-arrives-before-validated-body')
  const stop = page.getByRole('button', { name: '停止生成', exact: true })
  await stop.focus()
  assert.ok(await stop.evaluate(element => document.activeElement === element && element.getBoundingClientRect().height >= 44))
  await stop.screenshot({ path: `${output}/sse-stop-focus-320.png` })
  await stop.hover()
  await stop.screenshot({ path: `${output}/sse-stop-hover-320.png` })
  await stop.dispatchEvent('pointerdown', { pointerId: 22, pointerType: 'touch', isPrimary: true, button: 0, buttons: 1 })
  await stop.dispatchEvent('pointercancel', { pointerId: 22, pointerType: 'touch', isPrimary: true })
  assert.equal((await api(`${base}/requests/${stoppedRequestId}`, owner.accessToken)).cancelRequested, false)
  checks.push('stop-focus-touch-size-and-touch-cancel-do-not-submit')
  await stop.click()
  await page.getByRole('button', { name: '正在停止…', exact: true }).waitFor()
  await page.waitForFunction(() => document.querySelector('.agent-stop') === null)
  const stoppedRun = await api(`${base}/requests/${stoppedRequestId}`, owner.accessToken)
  assert.equal(stoppedRun.status, 'CANCELLED')
  assert.equal(stoppedRun.answer, null)
  assert.equal(await questionInput.inputValue(), '停止后保留的最新报价问题')
  assert.equal(await questionInput.evaluate(element => element === document.activeElement), true)
  assert.equal(await page.locator('.agent-answer').count(), answersBeforeStop)
  assert.equal((await fixtureState()).calls, beforeStop + 1)
  const stoppedUsage = JSON.parse(sql(`select json_object('models', model_calls, 'tools', tool_calls, 'cost', cost_upper_yuan) from agent_run where id=${stoppedRun.runId} and user_id=${owner.userId}`))
  assert.equal(stoppedUsage.models, 1); assert.equal(stoppedUsage.tools, 0)
  assert.equal(Number(stoppedUsage.cost), 0.00084)
  await page.locator('.agent-panel').screenshot({ path: `${output}/sse-stopped-draft-320.png` })
  checks.push('stop-prevents-next-model-and-tools-settles-usage-keeps-draft-and-focus')

  const beforeStopReload = (await fixtureState()).calls
  const stopReloadPost = page.waitForRequest(request => request.url().endsWith('/agent/messages/stream') && request.method() === 'POST')
  await questionInput.fill('停止中刷新仍保留的问题')
  await page.getByRole('button', { name: '发送问题', exact: true }).click()
  const stopReloadId = (await stopReloadPost).postDataJSON().requestId
  await page.getByRole('status').filter({ hasText: '正在整理回答…' }).waitFor()
  await page.getByRole('button', { name: '停止生成', exact: true }).click()
  await page.getByRole('button', { name: '正在停止…', exact: true }).waitFor()
  await page.reload()
  await page.getByRole('status').filter({ hasText: '本次生成已停止' }).waitFor()
  assert.equal((await api(`${base}/requests/${stopReloadId}`, owner.accessToken)).status, 'CANCELLED')
  assert.equal((await fixtureState()).calls, beforeStopReload + 1)
  // 草稿沿既有规则只存登录会话内存；刷新仅恢复运行状态，不把问题加入 sessionStorage。
  assert.equal(await questionInput.inputValue(), '')
  checks.push('reload-during-stop-restores-persisted-cancellation-without-model-retry')

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
  await page.waitForURL(/\/workspaces\?reason=access-lost$/)
  await page.getByRole('alert').getByText('当前空间访问权已经失效，请选择仍可访问的空间。', { exact: true }).waitFor()
  assert.equal(await page.locator('.agent-answer').count(), 0)
  assert.equal(await page.locator('.source-link').count(), 0)
  assert.equal((await fixtureState()).calls, beforeAccessLoss + 1)
  await page.screenshot({ path: `${output}/access-lost-panel-320.png`, fullPage: true })
  await page.unroute('**/agent', preventRestore)
  sql(`update workspace_member set status='ACTIVE',last_inactive_at=null where workspace_id=${workspace} and user_id=${owner.userId}`)
  await page.goto(assistantUrl)
  await page.locator('.agent-answer').first().waitFor()
  checks.push('inflight-access-loss-clears-private-body-even-if-restore-disconnects')
  await fixtureState('valid')
  const validCount = await page.locator('.agent-answer').count()
  await fixtureState('invalid')
  await page.locator('.agent-controls button[type="submit"]').click()
  await page.getByRole('alert').getByText('研究助手未返回可核对的完整结果', { exact: true }).waitFor()
  assert.equal(await page.locator('.agent-answer').count(), validCount)
  await capture('failed-keeps-old', [1440, 390])
  await fixtureState('slow')
  await page.locator('.agent-controls button[type="submit"]').click()
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
  await page.reload()
  await page.getByText('部分结果依赖的资料已经撤回或变化，需要重新查询。', { exact: true }).waitFor()
  assert.equal(await page.locator('.agent-prose').count(), 0)
  assert.equal(await page.locator('.source-link').count(), 0)
  await capture('withdrawn', [1440, 390])
  checks.push('withdrawal-invalidates-entire-result')
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.getByRole('button', { name: '清空个人记录', exact: true }).focus()
  assert.ok(await page.getByRole('button', { name: '清空个人记录', exact: true }).evaluate(element => parseFloat(getComputedStyle(element).transitionDuration) <= 0.001))
  checks.push('keyboard-focus-and-reduced-motion')
  // 未知用量会占用本轮 test 的 10 元预算，放到最后，并确认下一次准入会被拒绝。
  await fixtureState('failure')
  const beforeFailure = (await fixtureState()).calls
  const failure = await api(`${base}/messages`, owner.accessToken, 'POST', {
    requestId: crypto.randomUUID(), question: '检查本地故障记录',
  })
  assert.equal(failure.status, 'FAILED')
  assert.equal(failure.errorCode, 'RESEARCH_AGENT_UNAVAILABLE')
  assert.equal((await fixtureState()).calls, beforeFailure + 1)
  const diagnostic = JSON.parse(sql(`select json_object('failure', cast(model_failure_json as json),
    'unknownCost', cost_upper_yuan is null, 'reservedYuan', reserved_yuan)
    from agent_run where id=${failure.runId} and user_id=${owner.userId}`))
  await writeFile(`${output}/model-failure-diagnostic.json`, JSON.stringify(diagnostic, null, 2))
  assert.deepEqual(diagnostic.failure, { stage: 'QUESTION_MODEL', category: 'UPSTREAM_HTTP', upstreamStatus: 503,
    modelCall: 1, runId: failure.runId, requestId: failure.requestId })
  assert.equal(diagnostic.unknownCost, true)
  assert.ok(Number(diagnostic.reservedYuan) > 0)
  assert.equal(JSON.stringify(failure).includes('offline fixture failure'), false)
  const denied = await fetch(`${backend}/api${base}/messages`, { method: 'POST', headers: {
    'Content-Type': 'application/json', Authorization: `Bearer ${owner.accessToken}`,
  }, body: JSON.stringify({ requestId: crypto.randomUUID(), question: '预算不足时不应派发模型请求' }) })
  assert.equal((await denied.json()).code, 'RESEARCH_AGENT_BUDGET_EXCEEDED')
  assert.equal((await fixtureState()).calls, beforeFailure + 1)
  checks.push('actual-http-failure-persists-safe-diagnostics-and-retains-unknown-usage-without-retry')
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
