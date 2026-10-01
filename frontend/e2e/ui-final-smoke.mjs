import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium } from 'playwright-core'

const frontendUrl = process.env.FRONTEND_URL ?? 'http://localhost:18083'
const backendUrl = process.env.BACKEND_URL ?? 'http://localhost:18082'
const phase = process.env.E2E_UI_PHASE ?? 'after'
assert.ok(['before', 'after'].includes(phase))
const output = resolve(process.env.E2E_UI_OUTPUT ?? '../../ui-preview/ui-10')
const runId = Date.now().toString(36)
const usernames = ['o', 'm', 'a'].map((key) => `u10_${key}_${runId}`)
const password = 'BrowserPass123'
const workspaceName = `迁移验收${runId}`
const checks = []
const errors = []
let browser
let currentPage
let success = false

// 同时检查后端实际连接和前端代理，任何写入都必须在此之后。
for (const url of [backendUrl, frontendUrl]) {
  const response = await fetch(`${url}/api/test/environment`)
  assert.ok(response.ok, `${url} 缺少 test 环境探针`)
  const { data } = await response.json()
  assert.equal(data.profile, 'test')
  assert.equal(data.database, 'stock_system_test')
}
const properties = new Map()
for (const file of ['application.properties', 'application-test.properties']) {
  const content = await readFile(
    new URL(`../../src/main/resources/${file}`, import.meta.url),
    'utf8',
  )
  for (const line of content.split(/\r?\n/)) {
    const index = line.indexOf('=')
    if (index > 0 && !line.startsWith('#'))
      properties.set(line.slice(0, index), line.slice(index + 1).trim())
  }
}
const jdbc = properties
  .get('spring.datasource.url')
  .match(/^jdbc:mysql:\/\/([^:/?]+)(?::(\d+))?\/([^?]+)/)
assert.equal(jdbc?.[3], 'stock_system_test')
assert.equal(properties.get('stock-system.market.mode'), 'mock')
const resolveEnv = (value) =>
  value.replace(/\$\{([A-Z_]+)(?::([^}]*))?\}/g, (_, name, fallback) => {
    const result = process.env[name] ?? fallback
    assert.ok(result, `缺少 ${name}`)
    return result
  })
const sql = (query) =>
  execFileSync(
    process.env.MYSQL_CLIENT_PATH ?? 'mysql.exe',
    [
      `--host=${jdbc[1]}`,
      `--port=${jdbc[2] ?? 3306}`,
      `--user=${resolveEnv(properties.get('spring.datasource.username'))}`,
      '--database=stock_system_test',
      '--default-character-set=utf8mb4',
      '--batch',
      '--skip-column-names',
      `--execute=${query}`,
    ],
    {
      encoding: 'utf8',
      env: { ...process.env, MYSQL_PWD: resolveEnv(properties.get('spring.datasource.password')) },
    },
  ).trim()
assert.equal(sql('SELECT DATABASE()'), 'stock_system_test')
const stockExistedBefore = Number(sql("SELECT COUNT(*) FROM stock WHERE code='600519.SH'")) > 0

async function api(path, token, method = 'GET', body) {
  const response = await fetch(`${backendUrl}/api${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  const result = await response.json()
  assert.ok(response.ok, `${method} ${path}: ${result.code} ${result.message}`)
  return result.data
}
const widths = [1440, 960, 390, 320]
const routes = []
let adminId, ownerToken, workspaceId, otherId, stockId
const check = (name) => checks.push({ name, passed: true })
const routeTo = (page, path) =>
  page.evaluate(
    (path) => document.querySelector('#app').__vue_app__.config.globalProperties.$router.push(path),
    path,
  )
async function login(page, username, admin = false) {
  await page.goto(`${frontendUrl}/login`)
  await page.getByLabel('登录账号', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '进入空间', exact: true }).click()
  await page.waitForURL(admin ? '**/admin/users' : /\/workspaces/)
}
async function capture(page, name, samples = widths) {
  for (const width of samples) {
    await page.setViewportSize({ width, height: 1000 })
    await page.waitForTimeout(180)
    const state = await page.evaluate(() => {
      const root = document.querySelector('.research-theme')
      const controls = [...document.querySelectorAll('button,input,select,textarea,a')]
        .filter((e) => e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden')
        .filter((e) => !e.closest('.admin-table-wrap') && !e.classList.contains('ui-skip-link'))
        .filter((e) => {
          const r = e.getBoundingClientRect()
          return r.left < -1 || r.right > innerWidth + 1
        })
        .map((e) => e.textContent.trim() || e.id)
      return {
        title: document.title,
        overflow: document.documentElement.scrollWidth > innerWidth,
        controls,
        theme: root ? getComputedStyle(root).getPropertyValue('--color-canvas').trim() : null,
        heading: document.querySelector('main h1,main h2,h1')?.textContent,
        canvases: document.querySelectorAll('canvas').length,
      }
    })
    if (phase === 'after') {
      assert.equal(state.title, '基点 · 共享股票研究')
      assert.equal(state.overflow, false, `${name} ${width}px 页面溢出`)
      assert.deepEqual(state.controls, [], `${name} ${width}px 控件越界`)
      assert.equal(state.theme, '#faf8f4')
      assert.equal(await page.locator('.design-orbit,.design-preview').count(), 0)
      assert.equal(await page.locator('main').count(), 1, `${name} 只保留一个主要内容区域`)
    }
    checks.push({ name: `${name}-${width}`, passed: true, ...state })
    await page.screenshot({ path: `${output}/${phase}-${name}-${width}.png`, fullPage: true })
  }
}
async function visit(page, path, ready, name) {
  await page.goto(`${frontendUrl}${path}`)
  await page.locator(ready).first().waitFor()
  await page.waitForLoadState('networkidle')
  if (phase === 'after') assert.equal(new URL(page.url()).pathname, path.split('?')[0])
  routes.push({ path, name })
  await capture(page, name)
}
try {
  await mkdir(output, { recursive: true })
  assert.equal(Number(sql('SELECT COUNT(*) FROM super_admin')), 0, '拒绝覆盖已有管理员')
  for (const [index, username] of usernames.entries()) {
    const user = await api('/auth/register', null, 'POST', {
      username,
      password,
      nickname: ['全站研究所有者', '全站研究成员', '全站治理管理员'][index],
    })
    if (index === 2) adminId = user.userId
  }
  sql(`INSERT INTO super_admin(id,user_id) VALUES(1,${adminId})`)
  ownerToken = (await api('/auth/login', null, 'POST', { username: usernames[0], password }))
    .accessToken
  workspaceId = (await api('/workspaces', ownerToken, 'POST', { name: workspaceName })).workspaceId
  otherId = (await api('/workspaces', ownerToken, 'POST', { name: `切换核对${runId}` })).workspaceId
  const invitation = await api(`/workspaces/${workspaceId}/invitations`, ownerToken, 'POST', {
    expectedInvitationId: null,
  })
  const memberToken = (await api('/auth/login', null, 'POST', { username: usernames[1], password }))
    .accessToken
  await api('/workspaces/join', memberToken, 'POST', { inviteCode: invitation.inviteCode })
  stockId = (
    await api(`/workspaces/${workspaceId}/stocks`, ownerToken, 'POST', {
      code: '600519.SH',
      stance: 'NEUTRAL',
      reason: '全站收口，保留作者、周期与共享记录的数据归属。',
      observationNote: null,
      horizon: 'LONG',
      targetPrice: null,
      stopLossPrice: null,
    })
  ).workspaceStockId
  browser = await chromium.launch({
    executablePath:
      process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: true,
  })
  const context = await browser.newContext({ reducedMotion: 'reduce' })
  const page = await context.newPage()
  currentPage = page
  page.on('pageerror', (e) => errors.push(e.message))
  await visit(page, '/', 'h1', 'home')
  await visit(page, '/login', 'form', 'login')
  await visit(page, '/register', 'form', 'register')
  if (phase === 'before') {
    await visit(page, '/design-preview', '.design-preview', 'legacy-preview')
  } else {
    await page.goto(`${frontendUrl}/design-preview`)
    await page.waitForURL(`${frontendUrl}/`)
    await page.goto(`${frontendUrl}/unregistered-ui-path`)
    await page.waitForURL(`${frontendUrl}/`)
    check('retired-preview-and-unknown-route-return-home')
    const favicon = await page.locator('link[rel="icon"]').getAttribute('href')
    assert.equal(favicon, '/favicon.svg')
    const asset = await page.request.get(`${frontendUrl}${favicon}`)
    assert.equal(asset.status(), 200)
    assert.match(await asset.text(), /基/)
    check('document-title-and-brand-favicon')
  }
  await login(page, usernames[0])
  await visit(page, '/workspaces?reason=switch', '.space-picker', 'picker')
  const base = `/workspaces/${workspaceId}`
  for (const [path, ready, name] of [
    [`${base}/overview`, '.overview-heading', 'overview'],
    [`${base}/stocks`, '.page-heading', 'stocks'],
    [`${base}/stocks/market/600519.SH`, 'canvas', 'market'],
    [`${base}/stocks/${stockId}`, '.research-header', 'research'],
    [`${base}/members`, '.management-heading', 'members'],
    [`${base}/settings`, '.management-heading', 'settings'],
    ['/profile', '.profile-page', 'profile'],
  ])
    await visit(page, path, ready, name)
  if (phase === 'after') {
    await page.setViewportSize({ width: 390, height: 1000 })
    await routeTo(page, `${base}/members`)
    await page.getByText('全站研究成员', { exact: true }).first().waitFor()
    await routeTo(page, `/workspaces/${otherId}/members`)
    await page.locator('.members-heading').waitFor()
    await page.waitForLoadState('networkidle')
    assert.equal(await page.getByText('全站研究成员', { exact: true }).count(), 0)
    await routeTo(page, `${base}/stocks/${stockId}#research-timeline`)
    await page.locator('#research-timeline').waitFor()
    await page.waitForLoadState('networkidle')
    assert.ok(
      await page
        .getByText('全站收口，保留作者、周期与共享记录的数据归属。', { exact: true })
        .count(),
    )
    await page.reload()
    await page.locator('#research-timeline').waitFor()
    await page.waitForLoadState('networkidle')
    assert.equal(new URL(page.url()).hash, '#research-timeline')
    check('same-component-space-switch-and-research-refresh')
    await api(`/workspaces/${workspaceId}/archive`, ownerToken, 'POST')
    await routeTo(page, `${base}/settings`)
    await page
      .getByText('该空间由 OWNER 归档，现在只读。当前 OWNER 可以恢复。', { exact: true })
      .waitFor()
    await page.waitForLoadState('networkidle')
    await capture(page, 'archived-settings', [390, 320])
    await api(`/workspaces/${workspaceId}/restore`, ownerToken, 'POST')
    check('archived-space-remains-readable')
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await page.waitForURL(`${frontendUrl}/`)
    assert.equal(await page.evaluate(() => localStorage.getItem('stock-system.jwt')), null)
    await login(page, usernames[1])
    await page.waitForURL(`${frontendUrl}${base}/overview`)
    assert.equal(await page.getByText('切换核对' + runId, { exact: true }).count(), 0)
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await page.waitForURL(`${frontendUrl}/`)
    check('owner-logout-member-login-without-other-space-state')
  } else {
    await page.goto(`${frontendUrl}/`)
    await page.evaluate(() => {
      localStorage.clear()
      location.reload()
    })
    await page.waitForLoadState('networkidle')
  }
  await login(page, usernames[2], true)
  for (const [path, name] of [
    ['users', 'admin-users'],
    ['workspaces', 'admin-workspaces'],
    ['operation-logs', 'admin-logs'],
  ])
    await visit(page, `/admin/${path}`, '.admin-page', name)
  if (phase === 'after') {
    // 懒加载后台 CSS 后再切回普通账号，检查共享主题与控制台没有被后台样式污染。
    await page.getByRole('button', { name: '退出治理后台', exact: true }).click()
    await page.waitForURL('**/login')
    await login(page, usernames[0])
    await routeTo(page, `${base}/stocks`)
    await page.locator('.page-heading').waitFor()
    await page.waitForLoadState('networkidle')
    await capture(page, 'stocks-after-admin', [1440, 390])
    check('lazy-admin-style-does-not-change-workspace')
  }
  assert.deepEqual(errors, [])
  success = true
} catch (error) {
  if (currentPage && !currentPage.isClosed())
    await currentPage
      .screenshot({ path: `${output}/${phase}-failure.png`, fullPage: true })
      .catch(() => {})
  throw error
} finally {
  try {
    if (browser) await browser.close()
  } finally {
    assert.ok(usernames.every((name) => /^u10_[oma]_[a-z0-9]+$/.test(name)))
    const users = usernames.map((name) => `'${name}'`).join(',')
    const remaining = sql(`
      CREATE TEMPORARY TABLE ui_users AS SELECT id FROM app_user WHERE username IN (${users});
      CREATE TEMPORARY TABLE ui_spaces AS SELECT id FROM workspace WHERE created_by_user_id IN (SELECT id FROM ui_users);
      CREATE TEMPORARY TABLE ui_stocks AS SELECT id FROM workspace_stock WHERE workspace_id IN (SELECT id FROM ui_spaces);
      CREATE TEMPORARY TABLE ui_cycles AS SELECT id FROM research_cycle WHERE workspace_stock_id IN (SELECT id FROM ui_stocks);
      DELETE FROM research_attention_change WHERE research_cycle_id IN (SELECT id FROM ui_cycles);
      DELETE FROM stock_opinion WHERE research_cycle_id IN (SELECT id FROM ui_cycles);
      DELETE FROM research_cycle WHERE id IN (SELECT id FROM ui_cycles);
      DELETE FROM workspace_stock WHERE id IN (SELECT id FROM ui_stocks);
      DELETE FROM super_admin_operation_log WHERE (action IN ('DISABLE_USER','RESTORE_USER') AND target_id IN (SELECT id FROM ui_users)) OR (action IN ('ARCHIVE_WORKSPACE','RESTORE_WORKSPACE') AND target_id IN (SELECT id FROM ui_spaces));
      DELETE FROM workspace_invitation WHERE workspace_id IN (SELECT id FROM ui_spaces);
      DELETE FROM workspace_member WHERE workspace_id IN (SELECT id FROM ui_spaces);
      DELETE FROM workspace WHERE id IN (SELECT id FROM ui_spaces);
      DELETE FROM invitation_join_attempt WHERE user_id IN (SELECT id FROM ui_users);
      DELETE FROM super_admin WHERE user_id IN (SELECT id FROM ui_users);
      DELETE FROM app_user WHERE id IN (SELECT id FROM ui_users);
      ${stockExistedBefore ? '' : "DELETE FROM stock WHERE code='600519.SH' AND source='LOCAL_MOCK' AND NOT EXISTS(SELECT 1 FROM workspace_stock WHERE stock_id=stock.id);"}
      SELECT COUNT(*) FROM app_user WHERE id IN (SELECT id FROM ui_users);
      SELECT COUNT(*) FROM workspace WHERE id IN (SELECT id FROM ui_spaces);
      SELECT COUNT(*) FROM workspace_member WHERE workspace_id IN (SELECT id FROM ui_spaces);
      SELECT COUNT(*) FROM workspace_invitation WHERE workspace_id IN (SELECT id FROM ui_spaces);
      SELECT COUNT(*) FROM workspace_stock WHERE id IN (SELECT id FROM ui_stocks);
      SELECT COUNT(*) FROM research_cycle WHERE id IN (SELECT id FROM ui_cycles);
      SELECT COUNT(*) FROM stock_opinion WHERE research_cycle_id IN (SELECT id FROM ui_cycles);
      SELECT COUNT(*) FROM research_attention_change WHERE research_cycle_id IN (SELECT id FROM ui_cycles);
      SELECT COUNT(*) FROM super_admin WHERE user_id IN (SELECT id FROM ui_users);
      SELECT COUNT(*) FROM super_admin_operation_log WHERE target_id IN (SELECT id FROM ui_users) OR target_id IN (SELECT id FROM ui_spaces);
    `).split(/\r?\n/)
    remaining.forEach((count) => assert.equal(Number(count), 0))
  }
  await writeFile(
    `${output}/${phase}-verification.json`,
    JSON.stringify(
      { success, phase, runId, routes, checks, errors, databaseRemaining: 0 },
      null,
      2,
    ),
  )
}
console.log(
  JSON.stringify({ success, phase, runId, checks: checks.length, errors, databaseRemaining: 0 }),
)
