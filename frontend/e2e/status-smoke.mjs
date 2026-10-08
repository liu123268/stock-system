import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'

// Isolated real API/DB/browser check. Only the chart response is controlled.
const backend = 'http://localhost:18082'
const frontend = 'http://localhost:18083'
const run = Date.now().toString(36)
const username = `status_${run}`
const source = `STATUS_QA_${run}`
const spaceName = `状态验证${run}`
const password = 'StatusQa123'
const codes = ['999981.SH', '999982.SH', '999983.SH', '999984.SH']
const properties = new Map()
for (const file of ['application.properties', 'application-test.properties']) {
  const text = await readFile(new URL(`../../src/main/resources/${file}`, import.meta.url), 'utf8')
  for (const line of text.split(/\r?\n/)) {
    const i = line.indexOf('=')
    if (i > 0 && !line.trim().startsWith('#')) properties.set(line.slice(0, i), line.slice(i + 1))
  }
}
const match = properties.get('spring.datasource.url').match(/^jdbc:mysql:\/\/([^:/?]+):(\d+)\/([^?]+)/)
assert.equal(match?.[3], 'stock_system_test')
assert.ok(process.env.DB_PASSWORD)
function sql(statement) {
  return execFileSync(process.env.MYSQL_CLIENT_PATH ?? 'mysql.exe', [
    `--host=${match[1]}`, `--port=${match[2]}`, `--user=${properties.get('spring.datasource.username')}`,
    '--database=stock_system_test', '--default-character-set=utf8mb4', '--batch', '--skip-column-names',
  ], { input: statement, encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.DB_PASSWORD } }).trim()
}
assert.equal(sql('select database()'), 'stock_system_test')
for (const url of [backend, frontend]) {
  const response = await fetch(`${url}/api/test/environment`)
  assert.ok(response.ok)
  const environment = await response.json()
  assert.equal(environment.data.profile, 'test')
  assert.equal(environment.data.database, 'stock_system_test')
}
assert.equal(sql(`select count(*) from stock where code in (${codes.map(code => `'${code}'`).join(',')})`), '0')
let browser
let spaceId
let userCreated = false
let seeded = false
const errors = []
try {
  sql(`insert into stock(code,name,exchange,currency,listing_status,source,trade_status,status_checked_at,status_attempted_at) values
    ('999981.SH','状态停牌验证','SSE','CNY','SUSPENDED','${source}','HALTED',now(3),now(3)),
    ('999982.SH','状态退市验证','SSE','CNY','DELISTED','${source}','DELISTED',now(3),now(3)),
    ('999983.SH','状态过期验证','SSE','CNY','LISTED','${source}','NORMAL',date_sub(now(3),interval 2 day),date_sub(now(3),interval 2 day)),
    ('999984.SH','状态失败验证','SSE','CNY','LISTED','${source}','NORMAL',date_sub(now(3),interval 5 minute),now(3))`)
  seeded = true
  const register = await fetch(`${backend}/api/auth/register`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, nickname: '状态浏览器验证' }),
  })
  assert.equal(register.status, 201)
  userCreated = true
  const login = await (await fetch(`${backend}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password }),
  })).json()
  const response = await fetch(`${backend}/api/workspaces`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${login.data.accessToken}` },
    body: JSON.stringify({ name: spaceName, description: '交易状态与价格时间分离验证' }),
  })
  const space = await response.json()
  assert.equal(response.status, 201)
  spaceId = space.data.workspaceId
  assert.ok(Number.isSafeInteger(spaceId))
  browser = await chromium.launch({
    executablePath: process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true,
  })
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  page.on('pageerror', error => errors.push(error.message))
  // This verifies the date label using controlled historical bars, not live market availability.
  await page.route('**/api/workspaces/*/market/stocks/*/candlesticks?*', route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: {
      code: '999982.SH', name: '状态验证', currency: 'CNY', period: 'day', adjustment: 'none',
      source: 'STATUS_CHART_FIXTURE', simulated: false, retrievedAt: new Date().toISOString(), hasMore: false,
      candlesticks: [{ time: '2026-09-01T15:00:00+08:00', open: 10, high: 12, low: 9, close: 11, volume: 100, turnover: 1100 }],
    } }),
  }))
  await page.goto(`${frontend}/login`)
  await page.getByLabel('登录账号').fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '进入空间', exact: true }).click()
  await page.waitForURL('**/workspaces')
  const cases = [
    ['999981.SH', 'SUSPENDED', '停牌', true],
    ['999982.SH', 'DELISTED', '已退市', true],
    ['999983.SH', 'UNKNOWN', '交易状态待核实', false],
    ['999984.SH', 'UNKNOWN', '交易状态待核实', false],
  ]
  for (const [code, status, label, current] of cases) {
    await page.goto(`${frontend}/workspaces/${spaceId}/stocks`)
    await page.locator('#stock-keyword').fill(code)
    const done = page.waitForResponse(r => r.url().includes('/api/stocks/search') && r.request().method() === 'GET')
    await page.getByRole('button', { name: '搜索股票', exact: true }).click()
    const data = await (await done).json()
    assert.equal(data.data[0].listingStatus, status)
    assert.equal(data.data[0].statusInfo.current, current)
    assert.equal(data.data[0].price, null)
    assert.equal(data.data[0].source, source, 'actual directory mode must be used')
    const candidate = page.locator('.search-results a').filter({ hasText: code })
    await candidate.waitFor()
    assert.match(await candidate.innerText(), new RegExp(label))
    assert.match(await candidate.innerText(), current ? /状态检查于/ : /上次确认：正常/)
    await candidate.click()
    await page.waitForURL(`**/stocks/market/${code}**`)
    await page.locator('.stock-status-notice strong').filter({ hasText: label }).waitFor()
    await page.getByText(/最新 K 线截至：/).waitFor()
    assert.match(await page.getByText(/最新 K 线截至：/).innerText(), /2026\/09\/01/)
    assert.equal(await page.getByText('今天盘中每 30 秒刷新；历史日期只读取一次', { exact: true }).count(), 0)
    assert.equal(await page.getByRole('button', { name: '加入共享自选', exact: true }).isDisabled(), true)
    if (status === 'DELISTED') await page.getByText('这只股票已经退市，历史行情仍可查看，但不能开启新的研究周期。', { exact: true }).waitFor()
    const shots = new URL('../../target/status-browser-probe/', import.meta.url)
    await mkdir(shots, { recursive: true })
    await page.screenshot({ path: fileURLToPath(new URL(`${code}-desktop.png`, shots)), fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false)
    await page.screenshot({ path: fileURLToPath(new URL(`${code}-mobile.png`, shots)), fullPage: true })
    await page.setViewportSize({ width: 1440, height: 1000 })
  }
  assert.equal(sql(`select count(*) from workspace_stock where workspace_id=${spaceId}`), '0')
  assert.deepEqual(errors, [])
  console.log('PASS: no-price HALTED/DELISTED, stale/failure UNKNOWN with last observation, historical K-line date, desktop/mobile; chart controlled')
} finally {
  if (browser) await browser.close()
  if (spaceId) {
    assert.ok(Number.isSafeInteger(spaceId))
    sql(`delete from workspace_invitation where workspace_id=${spaceId};
      delete from workspace_member where workspace_id=${spaceId};
      delete from workspace where id=${spaceId} and name='${spaceName}';`)
  }
  if (userCreated) sql(`delete from app_user where username='${username}'`)
  if (seeded) sql(`delete from stock where source='${source}'`)
  assert.equal(sql(`select (select count(*) from app_user where username='${username}') +
    (select count(*) from workspace where name='${spaceName}') + (select count(*) from stock where source='${source}')`), '0')
  console.log('CLEANUP_REMAINING=0')
}
