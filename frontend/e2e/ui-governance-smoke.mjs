import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { chromium } from 'playwright-core'

const frontendUrl = process.env.FRONTEND_URL ?? 'http://localhost:18083'
const backendUrl = process.env.BACKEND_URL ?? 'http://localhost:18082'
const phase = process.env.E2E_UI_PHASE ?? 'after'
assert.ok(['before', 'after'].includes(phase))
const output = resolve(process.env.E2E_UI_OUTPUT ?? '../../ui-preview/ui-09')
const runId = Date.now().toString(36)
const prefix = `u9_${runId}_`
const usernames = ['a', 'o', 'm', ...Array.from({ length: 20 }, (_, i) => `b${i}`)].map(
  (key) => `${prefix}${key}`,
)
const password = 'BrowserPass123'
const workspacePrefix = `治理${runId}·`
const workspaceName = `${workspacePrefix}共同研究与长期观察的完整名称`
let ownerToken, memberToken, adminToken, adminId, ownerId, memberId
const spaces = []
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

const check = (name) => checks.push({ name, passed: true })
async function login(page, username) {
  await page.goto(`${frontendUrl}/login`)
  await page.getByLabel('登录账号', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '进入空间', exact: true }).click()
  await page.waitForURL(/\/(admin|workspaces)/)
}
async function capture(page, name, widths = [1440, 960, 390, 320]) {
  for (const width of widths) {
    await page.setViewportSize({ width, height: 1000 })
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.waitForTimeout(80)
    assert.equal(
      await page.evaluate(() => document.documentElement.scrollWidth > innerWidth),
      false,
      name,
    )
    await page.screenshot({ path: `${output}/${phase}-${name}-${width}.png`, fullPage: true })
    check(`${name}-${width}`)
  }
}
async function seed() {
  assert.equal(Number(sql('SELECT COUNT(*) FROM super_admin')), 0, '拒绝覆盖已有管理员')
  for (const [index, nickname] of [
    '本轮治理管理员',
    '共同研究与长期观察的所有者',
    '共同研究空间的普通成员',
  ].entries()) {
    await api('/auth/register', null, 'POST', { username: usernames[index], nickname, password })
  }
  adminId = Number(sql(`SELECT id FROM app_user WHERE username='${usernames[0]}'`))
  ownerId = Number(sql(`SELECT id FROM app_user WHERE username='${usernames[1]}'`))
  memberId = Number(sql(`SELECT id FROM app_user WHERE username='${usernames[2]}'`))
  sql(`INSERT INTO super_admin(id,user_id) VALUES(1,${adminId})`)
  for (const [key, target] of [
    ['admin', 0],
    ['owner', 1],
    ['member', 2],
  ]) {
    const session = await api('/auth/login', null, 'POST', {
      username: usernames[target],
      password,
    })
    if (key === 'admin') adminToken = session.accessToken
    if (key === 'owner') ownerToken = session.accessToken
    if (key === 'member') memberToken = session.accessToken
  }
  for (const [i, username] of usernames.slice(3).entries()) {
    sql(
      `INSERT INTO app_user(username,nickname,password_hash) SELECT '${username}','分页验收成员${i}',password_hash FROM app_user WHERE id=${ownerId}`,
    )
  }
  for (let i = 0; i < 22; i++) {
    spaces.push(
      await api('/workspaces', ownerToken, 'POST', {
        name: i === 0 ? workspaceName : `${workspacePrefix}分页空间${String(i).padStart(2, '0')}`,
      }),
    )
  }
  const invitation = await api(
    `/workspaces/${spaces[0].workspaceId}/invitations`,
    ownerToken,
    'POST',
    {},
  )
  await api('/workspaces/join', memberToken, 'POST', { inviteCode: invitation.inviteCode })
  await api(`/workspaces/${spaces[1].workspaceId}/archive`, ownerToken, 'POST')
  await api(`/admin/workspaces/${spaces[2].workspaceId}/archive`, adminToken, 'POST')
  await api(`/admin/users/${memberId}/disable`, adminToken, 'POST')
  await api(`/admin/users/${memberId}/restore`, adminToken, 'POST')
  const failure = await fetch(
    `${backendUrl}/api/admin/workspaces/${spaces[1].workspaceId}/restore`,
    { method: 'POST', headers: { Authorization: `Bearer ${adminToken}` } },
  )
  assert.equal(failure.status, 403)
}

async function settle(page) {
  await page.evaluate(
    () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
  )
}
async function search(page, kind, keyword = '', status = '') {
  await page.getByLabel(kind === 'users' ? '搜索用户' : '搜索空间', { exact: true }).fill(keyword)
  await page
    .getByLabel(kind === 'users' ? '账号状态' : '空间状态', { exact: true })
    .selectOption(status)
  await Promise.all([
    page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        new URL(response.url()).pathname === `/api/admin/${kind}`,
    ),
    page.getByRole('button', { name: '查询', exact: true }).click(),
  ])
  await settle(page)
}
function row(page, text) {
  return page.locator('.admin-panel > .admin-table-wrap tbody tr').filter({ hasText: text })
}
async function confirm(page, label) {
  await page.getByRole('alertdialog').getByRole('button', { name: label, exact: true }).click()
  await page.getByRole('alertdialog').waitFor({ state: 'hidden' })
  await page.getByRole('button', { name: '查询', exact: true }).waitFor({ state: 'visible' })
  await page.waitForFunction(() => !document.querySelector('.admin-toolbar button')?.disabled)
}
async function delay(page, path, method = 'GET', force401 = false) {
  let receive,
    release,
    deliveredResolve,
    count = 0
  const pending = new Promise((resolve) => (receive = resolve))
  const delivered = new Promise((resolve) => (deliveredResolve = resolve))
  const gate = new Promise((resolve) => (release = resolve))
  const matcher = new RegExp(`/api${path.replaceAll('/', '\\/')}(?:\\?.*)?$`)
  await page.route(matcher, async (route) => {
    if (route.request().method() !== method || ++count > 1) return route.continue()
    const response = await route.fetch()
    receive()
    await gate
    if (force401)
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'UNAUTHENTICATED', message: '旧会话已失效', data: null }),
      })
    else await route.fulfill({ response })
    deliveredResolve()
  })
  return { pending, release, delivered, count: () => count, remove: () => page.unroute(matcher) }
}

async function verifyBehavior(page) {
  await page.setViewportSize({ width: 1440, height: 1000 })
  const writes = []
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname.startsWith('/api/admin/'))
      writes.push(new URL(request.url()).pathname)
  })
  await page.getByRole('link', { name: '用户治理', exact: true }).click()
  await search(page, 'users')
  assert.match(await page.locator('.admin-pagination').innerText(), /共 23 条/)
  assert.equal(await page.locator('.admin-panel > .admin-table-wrap tbody tr').count(), 20)
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  await page.waitForFunction(() =>
    document.querySelector('.admin-pagination')?.textContent.includes('2 / 2'),
  )
  assert.equal(await page.locator('.admin-panel > .admin-table-wrap tbody tr').count(), 3)
  await search(page, 'users', usernames[1])
  assert.match(await page.locator('.admin-pagination').innerText(), /1 \/ 1/)
  await search(page, 'users', '找不到的账号')
  await page.getByText('没有符合条件的用户', { exact: true }).waitFor()
  await capture(page, 'users-empty', [390, 320])
  check('users-real-filter-pagination-and-empty')
  await search(page, 'users', usernames[0])
  assert.equal(
    await row(page, usernames[0]).getByRole('button', { name: '禁用', exact: true }).isDisabled(),
    true,
  )
  check('unique-admin-cannot-disable-self')
  await search(page, 'users', '共同研究')
  const older = await delay(page, `/admin/users/${ownerId}`)
  await page.getByRole('button', { name: `查看用户 ${usernames[1]}`, exact: true }).click()
  await older.pending
  await page.getByRole('button', { name: `查看用户 ${usernames[2]}`, exact: true }).click()
  await page.locator('#user-detail-title').filter({ hasText: '普通成员' }).waitFor()
  older.release()
  await older.delivered
  await older.remove()
  await settle(page)
  assert.match(await page.locator('.admin-detail-header').innerText(), new RegExp(usernames[2]))
  check('late-user-detail-cannot-replace-new-target')
  await search(page, 'users', usernames[1])
  await page.setViewportSize({ width: 320, height: 1000 })
  const button = row(page, usernames[1]).getByRole('button', { name: '禁用', exact: true })
  await button.focus()
  await page.keyboard.press('Enter')
  assert.equal(
    await page.getByRole('alertdialog').evaluate((el) => el === document.activeElement),
    true,
  )
  await page.keyboard.press('Escape')
  assert.equal(await button.evaluate((el) => el === document.activeElement), true)
  assert.equal(writes.length, 0)
  const region = page.getByRole('region', { name: '用户列表，可横向滚动' })
  await region.focus()
  await page.keyboard.press('ArrowRight')
  await page.waitForFunction(() => document.querySelector('.admin-table-wrap').scrollLeft > 0)
  check('keyboard-confirm-cancel-focus-and-local-table-scroll')

  const failPost = async (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'UNAVAILABLE', message: '提交服务暂不可用', data: null }),
    })
  await page.route(`**/api/admin/users/${ownerId}/disable`, failPost)
  await button.click()
  await page.getByRole('alertdialog').getByRole('button', { name: '确认禁用', exact: true }).click()
  await page
    .getByRole('alertdialog')
    .getByRole('alert')
    .filter({ hasText: '提交服务暂不可用' })
    .waitFor()
  assert.equal(sql(`SELECT status FROM app_user WHERE id=${ownerId}`), 'ACTIVE')
  await capture(page, 'user-submit-failure', [320])
  await page.unroute(`**/api/admin/users/${ownerId}/disable`, failPost)
  await page.getByRole('alertdialog').getByRole('button', { name: '取消', exact: true }).click()
  check('user-submit-failure-keeps-confirmation-and-database')

  const failRead = async (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'UNAVAILABLE', message: '列表服务暂不可用', data: null }),
    })
  const saved = await delay(page, `/admin/users/${ownerId}/disable`, 'POST')
  await button.click()
  await page.getByRole('alertdialog').getByRole('button', { name: '确认禁用', exact: true }).click()
  await saved.pending
  assert.equal(
    await page
      .getByRole('alertdialog')
      .getByRole('button', { name: '取消', exact: true })
      .isDisabled(),
    true,
  )
  assert.equal(await page.getByLabel('搜索用户', { exact: true }).isDisabled(), true)
  await page
    .getByRole('alertdialog')
    .getByRole('button', { name: '正在提交…', exact: true })
    .evaluate((el) => {
      el.click()
      el.click()
    })
  await capture(page, 'user-submitting', [390, 320])
  await page.route(/\/api\/admin\/users\?.*$/, failRead)
  saved.release()
  await saved.delivered
  await saved.remove()
  await page.getByText(/操作已完成，但列表刷新失败/).waitFor()
  assert.equal(sql(`SELECT status FROM app_user WHERE id=${ownerId}`), 'DISABLED')
  const oldToken = await fetch(`${backendUrl}/api/auth/me`, {
    headers: { Authorization: `Bearer ${ownerToken}` },
  })
  assert.equal(oldToken.status, 401)
  assert.equal((await oldToken.json()).code, 'ACCOUNT_DISABLED')
  assert.equal(Number(sql(`SELECT COUNT(*) FROM workspace WHERE owner_user_id=${ownerId}`)), 22)
  assert.equal(
    Number(
      sql(
        `SELECT COUNT(*) FROM workspace_member WHERE workspace_id=${spaces[0].workspaceId} AND status='ACTIVE'`,
      ),
    ),
    2,
  )
  await capture(page, 'user-saved-refresh-failure', [320])
  const beforeRetry = writes.length
  await page.unroute(/\/api\/admin\/users\?.*$/, failRead)
  await page.getByRole('button', { name: '重试读取', exact: true }).click()
  await row(page, usernames[1]).getByRole('button', { name: '恢复', exact: true }).waitFor()
  await page.waitForFunction(() => !document.querySelector('.admin-toolbar button')?.disabled)
  assert.equal(writes.length, beforeRetry)
  assert.equal(saved.count(), 1)
  await row(page, usernames[1]).getByRole('button', { name: '恢复', exact: true }).click()
  await confirm(page, '确认恢复')
  assert.equal(sql(`SELECT status FROM app_user WHERE id=${ownerId}`), 'ACTIVE')
  check('real-disable-restore-retains-data-old-token-rejected-one-post-and-get-only-retry')

  await search(page, 'users', usernames[1], 'DISABLED')
  await page.getByText('没有符合条件的用户', { exact: true }).waitFor()
  await search(page, 'users', usernames[2], 'ACTIVE')
  await row(page, usernames[2]).getByRole('button', { name: '禁用', exact: true }).click()
  await confirm(page, '确认禁用')
  assert.equal(await row(page, usernames[2]).count(), 0)
  await search(page, 'users', usernames[2], 'DISABLED')
  await row(page, usernames[2]).getByRole('button', { name: '恢复', exact: true }).click()
  await confirm(page, '确认恢复')
  assert.equal(await row(page, usernames[2]).count(), 0)
  check('status-filter-refresh-removes-changed-row')

  await search(page, 'users', usernames[2])
  const lateWrite = await delay(page, `/admin/users/${memberId}/disable`, 'POST')
  await row(page, usernames[2]).getByRole('button', { name: '禁用', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: '确认禁用', exact: true }).click()
  await lateWrite.pending
  await page.getByRole('link', { name: '空间治理', exact: true }).click()
  await page.getByLabel('搜索空间', { exact: true }).waitFor()
  lateWrite.release()
  await lateWrite.delivered
  await lateWrite.remove()
  await settle(page)
  assert.equal(new URL(page.url()).pathname, '/admin/workspaces')
  assert.equal(await page.getByText(`已禁用 ${usernames[2]}`, { exact: true }).count(), 0)
  await api(`/admin/users/${memberId}/restore`, adminToken, 'POST')
  check('late-user-write-after-leaving-does-not-change-new-page')

  await search(page, 'workspaces')
  assert.match(await page.locator('.admin-pagination').innerText(), /共 22 条/)
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  await page.waitForFunction(() =>
    document.querySelector('.admin-pagination')?.textContent.includes('2 / 2'),
  )
  assert.equal(await page.locator('.admin-panel > .admin-table-wrap tbody tr').count(), 2)
  await search(page, 'workspaces', '不存在的空间')
  await page.getByText('没有符合条件的空间', { exact: true }).waitFor()
  await capture(page, 'workspaces-empty', [320])
  await search(page, 'workspaces', workspacePrefix, 'ARCHIVED_BY_OWNER')
  assert.equal(
    await row(page, spaces[1].name).getByRole('button', { name: '恢复', exact: true }).count(),
    0,
  )
  await page.getByText('由所有者恢复', { exact: true }).waitFor()
  await page.getByRole('button', { name: `查看空间 ${spaces[1].name}`, exact: true }).click()
  await page.locator('#workspace-detail-title').waitFor()
  await capture(page, 'owner-archived-space', [390, 320])
  assert.equal(await page.getByRole('link', { name: /观点|共享自选/ }).count(), 0)
  check('workspace-real-pagination-empty-owner-archive-source-and-no-research-entry')

  await search(page, 'workspaces', workspaceName)
  await row(page, workspaceName).getByRole('button', { name: '治理归档', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: '取消', exact: true }).click()
  assert.equal(sql(`SELECT status FROM workspace WHERE id=${spaces[0].workspaceId}`), 'ACTIVE')
  const archive = await delay(page, `/admin/workspaces/${spaces[0].workspaceId}/archive`, 'POST')
  await row(page, workspaceName).getByRole('button', { name: '治理归档', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: '确认归档', exact: true }).click()
  await archive.pending
  await page.route(/\/api\/admin\/workspaces\?.*$/, failRead)
  archive.release()
  await archive.delivered
  await archive.remove()
  await page.getByText(/操作已完成，但列表刷新失败/).waitFor()
  assert.equal(
    sql(`SELECT status FROM workspace WHERE id=${spaces[0].workspaceId}`),
    'ARCHIVED_BY_SUPER_ADMIN',
  )
  assert.equal(
    Number(
      sql(
        `SELECT COUNT(*) FROM workspace_member WHERE workspace_id=${spaces[0].workspaceId} AND status='ACTIVE'`,
      ),
    ),
    2,
  )
  assert.equal(
    Number(
      sql(
        `SELECT COUNT(*) FROM workspace_invitation WHERE workspace_id=${spaces[0].workspaceId} AND status='ACTIVE'`,
      ),
    ),
    0,
  )
  await capture(page, 'space-saved-refresh-failure', [320])
  const spaceWrites = writes.length
  await page.unroute(/\/api\/admin\/workspaces\?.*$/, failRead)
  await page.getByRole('button', { name: '重试读取', exact: true }).click()
  await row(page, workspaceName).getByRole('button', { name: '恢复', exact: true }).waitFor()
  await page.waitForFunction(() => !document.querySelector('.admin-toolbar button')?.disabled)
  assert.equal(writes.length, spaceWrites)
  await row(page, workspaceName).getByRole('button', { name: '恢复', exact: true }).click()
  await confirm(page, '确认恢复')
  assert.equal(sql(`SELECT status FROM workspace WHERE id=${spaces[0].workspaceId}`), 'ACTIVE')
  assert.equal(
    Number(
      sql(
        `SELECT COUNT(*) FROM workspace_invitation WHERE workspace_id=${spaces[0].workspaceId} AND status='ACTIVE'`,
      ),
    ),
    0,
  )
  check('real-governance-archive-restore-retains-members-revokes-invite-and-get-only-retry')

  // 另一会话在确认期间先由 OWNER 归档：真实业务 403 留在治理页，不能误当权限丢失。
  await row(page, workspaceName).getByRole('button', { name: '治理归档', exact: true }).click()
  await api(`/workspaces/${spaces[0].workspaceId}/archive`, ownerToken, 'POST')
  await page.getByRole('alertdialog').getByRole('button', { name: '确认归档', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('alert').waitFor()
  assert.equal(new URL(page.url()).pathname, '/admin/workspaces')
  assert.equal(
    sql(`SELECT status FROM workspace WHERE id=${spaces[0].workspaceId}`),
    'ARCHIVED_BY_OWNER',
  )
  await capture(page, 'space-concurrent-source-changed', [320])
  await page.getByRole('alertdialog').getByRole('button', { name: '取消', exact: true }).click()
  await api(`/workspaces/${spaces[0].workspaceId}/restore`, ownerToken, 'POST')
  check('concurrent-owner-archive-returns-business-error-without-losing-admin-session')

  for (let i = 0; i < 22; i++)
    await api(`/admin/users/${memberId}/${i % 2 === 0 ? 'disable' : 'restore'}`, adminToken, 'POST')
  await page.getByRole('link', { name: '操作记录', exact: true }).click()
  await page.locator('.admin-table tbody tr').first().waitFor()
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  await page.waitForFunction(() =>
    document.querySelector('.admin-pagination')?.textContent.includes('2 /'),
  )
  await page.getByLabel('操作类型', { exact: true }).selectOption('RESTORE_WORKSPACE')
  await page.getByLabel('操作结果', { exact: true }).selectOption('FAILED')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await page.waitForFunction(() => !document.querySelector('.admin-toolbar button')?.disabled)
  assert.match(await page.locator('.admin-pagination').innerText(), /1 \/ 1/)
  assert.equal(await page.locator('.admin-table tbody tr').count(), 1)
  assert.match(await page.locator('.admin-table tbody').innerText(), /恢复空间/)
  await capture(page, 'logs-failed-filter', [390, 320])
  await page.getByLabel('操作类型', { exact: true }).selectOption('RESTORE_USER')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await page.getByText('没有符合条件的治理操作记录', { exact: true }).waitFor()
  await capture(page, 'logs-empty', [320])
  check('real-log-pagination-action-result-filter-and-empty')
  await page.route(/\/api\/admin\/operation-logs\?.*$/, failRead)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await page.getByRole('alert').waitFor()
  await page.unroute(/\/api\/admin\/operation-logs\?.*$/, failRead)
  await page.getByRole('button', { name: '重试读取', exact: true }).click()
  await page.waitForFunction(() => !document.querySelector('.admin-toolbar button')?.disabled)
  assert.equal(await page.getByRole('alert').count(), 0)
  check('log-network-failure-and-read-retry')

  await page.getByRole('link', { name: '用户治理', exact: true }).click()
  await search(page, 'users', usernames[1])
  const oldSession = await delay(page, '/admin/users', 'GET', true)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await oldSession.pending
  await page.getByRole('button', { name: '退出治理后台', exact: true }).click()
  await page.waitForURL((url) => url.pathname === '/login')
  assert.equal(await page.evaluate(() => localStorage.getItem('stock-system-access-token')), null)
  await login(page, usernames[2])
  const newToken = await page.evaluate(() => localStorage.getItem('stock-system-access-token'))
  oldSession.release()
  await oldSession.delivered
  await oldSession.remove()
  await settle(page)
  assert.equal(
    await page.evaluate(() => localStorage.getItem('stock-system-access-token')),
    newToken,
  )
  assert.equal(new URL(page.url()).pathname, '/workspaces')
  check('logout-clears-session-and-late-old-401-does-not-clear-new-account')
  await page.goto(`${frontendUrl}/admin/users`)
  await page.waitForURL((url) => url.pathname === '/workspaces')
  assert.equal(await page.getByRole('navigation', { name: '治理后台导航' }).count(), 0)
  check('regular-account-cannot-enter-admin-route')

  const accessContext = await browser.newContext({ reducedMotion: 'reduce' })
  const accessPage = await accessContext.newPage()
  currentPage = accessPage
  accessPage.on('pageerror', (error) => errors.push(error.message))
  await login(accessPage, usernames[0])
  await accessPage.getByLabel('搜索用户', { exact: true }).waitFor()
  await accessPage.waitForFunction(() => !document.querySelector('.admin-toolbar button')?.disabled)
  sql(`DELETE FROM super_admin WHERE user_id=${adminId}`)
  await accessPage.getByRole('button', { name: '查询', exact: true }).click()
  await accessPage.waitForURL((url) => url.pathname === '/workspaces')
  assert.equal(
    JSON.parse(await accessPage.evaluate(() => localStorage.getItem('stock-system-current-user')))
      .accountType,
    'REGULAR',
  )
  sql(`INSERT INTO super_admin(id,user_id) VALUES(1,${adminId})`)
  await accessPage.getByRole('button', { name: '退出登录', exact: true }).click()
  await accessPage.waitForURL((url) => url.pathname === '/')
  await login(accessPage, usernames[0])
  await accessPage.waitForFunction(() => !document.querySelector('.admin-toolbar button')?.disabled)
  await accessPage.evaluate(() =>
    localStorage.setItem('stock-system-access-token', 'test-invalid-token'),
  )
  await accessPage.getByRole('button', { name: '查询', exact: true }).click()
  await accessPage.waitForURL((url) => url.pathname === '/login')
  assert.equal(
    await accessPage.evaluate(() => localStorage.getItem('stock-system-access-token')),
    null,
  )
  assert.match(new URL(accessPage.url()).search, /reason=expired/)
  await accessContext.close()
  currentPage = page
  check('real-revoked-admin-403-role-refresh-and-current-invalid-jwt-401-cleanup')
}

try {
  await mkdir(output, { recursive: true })
  await seed()
  browser = await chromium.launch({
    executablePath:
      process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: true,
  })
  const context = await browser.newContext({ reducedMotion: 'reduce' })
  const page = await context.newPage()
  currentPage = page
  page.on('pageerror', (error) => errors.push(error.message))
  await login(page, usernames[0])
  await page.getByLabel('搜索用户').fill(usernames[1])
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const userRow = page
    .locator('.admin-panel > .admin-table-wrap tbody tr')
    .filter({ hasText: usernames[1] })
  await userRow.waitFor()
  await capture(page, 'users')
  await userRow.click()
  await page.locator('#user-detail-title').waitFor()
  await capture(page, 'user-detail')
  await userRow.getByRole('button', { name: '禁用', exact: true }).click()
  await page.getByRole('alertdialog').waitFor()
  await capture(page, 'user-confirm')
  await page.getByRole('alertdialog').getByRole('button', { name: '取消', exact: true }).click()
  await page.getByRole('link', { name: '空间治理', exact: true }).click()
  await page.getByLabel('搜索空间').fill(workspaceName)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const spaceRow = page
    .locator('.admin-panel > .admin-table-wrap tbody tr')
    .filter({ hasText: workspaceName })
  await spaceRow.waitFor()
  await capture(page, 'workspaces')
  await spaceRow.click()
  await page.locator('#workspace-detail-title').waitFor()
  await capture(page, 'workspace-detail')
  await spaceRow.getByRole('button', { name: '治理归档', exact: true }).click()
  await capture(page, 'workspace-confirm')
  await page.getByRole('alertdialog').getByRole('button', { name: '取消', exact: true }).click()
  await page.getByRole('link', { name: '操作记录', exact: true }).click()
  await page.getByRole('heading', { name: '操作记录', exact: true }).waitFor()
  await page.locator('.admin-table tbody tr').first().waitFor()
  await capture(page, 'logs')
  const reference = await context.newPage()
  await reference.route(/https:\/\/fonts\.(googleapis|gstatic)\.com\//, (route) => route.abort())
  for (const name of ['users', 'workspaces', 'logs']) {
    await reference.goto(
      `${pathToFileURL(resolve('../../ui-preview/stock-workspace-demo-v2-content-aligned.html')).href}#/admin/${name}`,
    )
    await reference.evaluate((key) => {
      role = 'admin'
      location.hash = `#/admin/${key}`
      route()
    }, name)
    await reference.locator(`#view-admin-${name}`).waitFor()
    for (const width of [1440, 390]) {
      await reference.setViewportSize({ width, height: 1000 })
      await reference.screenshot({
        path: `${output}/reference-${name}-${width}.png`,
        fullPage: true,
      })
    }
  }
  await reference.close()
  if (phase === 'after') await verifyBehavior(page)

  assert.deepEqual(errors, [])
  success = true
} catch (error) {
  if (currentPage)
    await currentPage.screenshot({ path: `${output}/${phase}-failure.png`, fullPage: true })
  throw error
} finally {
  try {
    if (browser) await browser.close()
  } finally {
    assert.ok(usernames.every((name) => /^u9_[a-z0-9]+_[a-z0-9]+$/.test(name)))
    const users = usernames.map((name) => `'${name}'`).join(',')
    const counts =
      sql(`CREATE TEMPORARY TABLE ui_users AS SELECT id FROM app_user WHERE username IN (${users});
      CREATE TEMPORARY TABLE ui_spaces AS SELECT id FROM workspace WHERE created_by_user_id IN (SELECT id FROM ui_users);
      DELETE FROM super_admin_operation_log WHERE (action IN ('DISABLE_USER','RESTORE_USER') AND target_id IN (SELECT id FROM ui_users)) OR (action IN ('ARCHIVE_WORKSPACE','RESTORE_WORKSPACE') AND target_id IN (SELECT id FROM ui_spaces));
      DELETE FROM workspace_invitation WHERE workspace_id IN (SELECT id FROM ui_spaces);
      DELETE FROM workspace_member WHERE workspace_id IN (SELECT id FROM ui_spaces);
      DELETE FROM workspace WHERE id IN (SELECT id FROM ui_spaces);
      DELETE FROM invitation_join_attempt WHERE user_id IN (SELECT id FROM ui_users);
      DELETE FROM super_admin WHERE user_id IN (SELECT id FROM ui_users);
      DELETE FROM app_user WHERE id IN (SELECT id FROM ui_users);
      SELECT COUNT(*) FROM app_user WHERE username IN (${users});
      SELECT COUNT(*) FROM workspace WHERE id IN (SELECT id FROM ui_spaces);
      SELECT COUNT(*) FROM workspace_member WHERE workspace_id IN (SELECT id FROM ui_spaces);
      SELECT COUNT(*) FROM workspace_invitation WHERE workspace_id IN (SELECT id FROM ui_spaces);
      SELECT COUNT(*) FROM super_admin WHERE user_id IN (SELECT id FROM ui_users);
      SELECT COUNT(*) FROM super_admin_operation_log WHERE (action IN ('DISABLE_USER','RESTORE_USER') AND target_id IN (SELECT id FROM ui_users)) OR (action IN ('ARCHIVE_WORKSPACE','RESTORE_WORKSPACE') AND target_id IN (SELECT id FROM ui_spaces));`)
    counts.split(/\r?\n/).forEach((value) => assert.equal(Number(value), 0, '本轮数据清理为0'))
  }
  await writeFile(
    `${output}/${phase}-verification.json`,
    JSON.stringify({ success, phase, runId, checks, errors, databaseRemaining: 0 }, null, 2),
  )
}
console.log(JSON.stringify({ phase, runId, checks: checks.length, errors, databaseRemaining: 0 }))
