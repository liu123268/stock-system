import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFile, mkdir } from 'node:fs/promises'
import { chromium } from 'playwright-core'

const backend = 'http://localhost:18082'
const frontend = 'http://localhost:18083'
const run = Date.now().toString(36)
const username = `dir_${run}`
const source = `DIR_QA_${run}`
const spaceName = `目录验证${run}`
const password = 'DirectoryQa123'
const properties = new Map()
for (const file of ['application.properties', 'application-test.properties']) {
  const text = await readFile(new URL(`../../src/main/resources/${file}`, import.meta.url), 'utf8')
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim() || line.startsWith('#')) continue
    const i = line.indexOf('=')
    if (i > 0) properties.set(line.slice(0, i), line.slice(i + 1))
  }
}
const match = properties.get('spring.datasource.url').match(/^jdbc:mysql:\/\/([^:/?]+):(\d+)\/([^?]+)/)
assert.equal(match?.[3], 'stock_system_test')
assert.ok(process.env.DB_PASSWORD, 'DB_PASSWORD is required for directory browser cleanup')
function sql(statement) {
  return execFileSync(process.env.MYSQL_CLIENT_PATH ?? 'mysql.exe', [
    `--host=${match[1]}`, `--port=${match[2]}`, `--user=${properties.get('spring.datasource.username')}`,
    '--database=stock_system_test', '--default-character-set=utf8mb4', '--batch', '--skip-column-names',
  ], { input: statement, encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.DB_PASSWORD } }).trim()
}
assert.equal(sql('SELECT DATABASE()'), 'stock_system_test')
const environment = await (await fetch(`${backend}/api/test/environment`)).json()
assert.equal(environment.data.profile, 'test')
assert.equal(environment.data.database, 'stock_system_test')
assert.equal(sql("SELECT COUNT(*) FROM stock WHERE code IN ('00700.HK','000001.SZ','600089.SH','920002.BJ')"), '0')
let browser
let spaceId
let seeded = false
let userCreated = false
const errors = []
try {
  sql(`INSERT INTO stock(code,name,exchange,currency,listing_status,source,search_aliases,pinyin_initials) VALUES
    ('00700.HK','腾讯控股','HKEX','HKD','UNKNOWN','${source}','騰訊控股 TENCENT','txkg'),
    ('000001.SZ','平安银行','SZSE','CNY','UNKNOWN','${source}','平安银行','payh'),
    ('600089.SH','特变电工','SSE','CNY','UNKNOWN','${source}','特變電工','tbdg'),
    ('920002.BJ','万达轴承','BSE','CNY','UNKNOWN','${source}','万达轴承','wdzc')`)
  seeded = true
  const register = await fetch(`${backend}/api/auth/register`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, nickname: '目录浏览器验证' }) })
  assert.equal(register.status, 201)
  userCreated = true
  browser = await chromium.launch({ executablePath: process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  page.on('pageerror', e => errors.push(e.message))
  await page.goto(`${frontend}/login`)
  await page.locator('input').nth(0).fill(username)
  await page.locator('input').nth(1).fill(password)
  await page.locator('button[type=submit]').click()
  await page.waitForURL('**/workspaces')
  const login = await (await fetch(`${backend}/api/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }) })).json()
  const response = await fetch(`${backend}/api/workspaces`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${login.data.accessToken}` },
    body: JSON.stringify({ name: spaceName, description: '目录只读搜索验证' }) })
  const space = await response.json()
  assert.equal(response.status, 201)
  spaceId = space.data.workspaceId
  assert.ok(Number.isSafeInteger(spaceId))
  await page.goto(`${frontend}/workspaces/${spaceId}/stocks`)
  for (const [keyword, code] of [['腾讯','00700.HK'],['騰訊','00700.HK'],['TENCENT','00700.HK'],['000001.SZ','000001.SZ'],['tbdg','600089.SH'],['TB','600089.SH'],['920002','920002.BJ']]) {
    await page.locator('#stock-keyword').fill(keyword)
    const done = page.waitForResponse(r => r.url().includes('/api/stocks/search') && r.request().method() === 'GET')
    await page.getByRole('button', { name: '搜索股票', exact: true }).click()
    const data = await (await done).json()
    assert.equal(data.data[0].code, code)
    assert.equal(data.data[0].price, null)
    const candidate = page.locator('.search-results a').filter({ hasText: code })
    await candidate.waitFor()
    assert.match(await candidate.innerText(), /暂无报价/)
    await candidate.click()
    await page.waitForURL(`**/workspaces/${spaceId}/stocks/market/${code}**`)
    await page.getByText('这只股票的交易状态暂时无法核实，行情仍可查看，但暂时不能加入关注。', { exact: true }).waitFor()
    assert.equal(await page.locator('.opinion-form').count(), 0)
    await page.goBack()
    await page.waitForURL(`**/workspaces/${spaceId}/stocks**`)
    await page.locator('#stock-keyword').waitFor()
  }
  assert.equal(sql(`SELECT
    (SELECT COUNT(*) FROM workspace_stock WHERE workspace_id=${spaceId}) +
    (SELECT COUNT(*) FROM research_cycle rc JOIN workspace_stock ws ON ws.id=rc.workspace_stock_id WHERE ws.workspace_id=${spaceId}) +
    (SELECT COUNT(*) FROM stock_opinion so JOIN research_cycle rc ON rc.id=so.research_cycle_id JOIN workspace_stock ws ON ws.id=rc.workspace_stock_id WHERE ws.workspace_id=${spaceId})`), '0')
  const shots = new URL('../../target/market-directory-probe/', import.meta.url)
  await mkdir(shots, { recursive: true })
  await page.screenshot({ path: new URL('directory-desktop.png', shots).pathname.replace(/^\/([A-Za-z]:)/, '$1'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false)
  await page.screenshot({ path: new URL('directory-mobile.png', shots).pathname.replace(/^\/([A-Za-z]:)/, '$1'), fullPage: true })
  assert.deepEqual(errors, [])
  console.log('PASS: directory search, independent read-only market page, no research writes, desktop/mobile')
} finally {
  if (browser) await browser.close()
  if (spaceId) {
    assert.ok(Number.isSafeInteger(spaceId))
    sql(`DELETE so FROM stock_opinion so JOIN research_cycle rc ON rc.id=so.research_cycle_id JOIN workspace_stock ws ON ws.id=rc.workspace_stock_id WHERE ws.workspace_id=${spaceId};
      DELETE rc FROM research_cycle rc JOIN workspace_stock ws ON ws.id=rc.workspace_stock_id WHERE ws.workspace_id=${spaceId};
      DELETE FROM workspace_stock WHERE workspace_id=${spaceId};
      DELETE FROM workspace_invitation WHERE workspace_id=${spaceId};
      DELETE FROM workspace_member WHERE workspace_id=${spaceId};
      DELETE FROM workspace WHERE id=${spaceId} AND name='${spaceName}';`)
  }
  if (userCreated) sql(`DELETE FROM app_user WHERE username='${username}'`)
  if (seeded) sql(`DELETE FROM stock WHERE source='${source}' AND code IN ('00700.HK','000001.SZ','600089.SH','920002.BJ')`)
  const remaining = sql(`SELECT (SELECT COUNT(*) FROM app_user WHERE username='${username}') + (SELECT COUNT(*) FROM workspace WHERE name='${spaceName}') + (SELECT COUNT(*) FROM stock WHERE source='${source}')`)
  assert.equal(remaining, '0')
  console.log('CLEANUP_REMAINING=0')
}
