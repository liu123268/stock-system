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
const output = resolve(process.env.E2E_UI_OUTPUT ?? '../../ui-preview/ui-01')
const runId = Date.now().toString(36)
const usernames = [`browser_ui_o_${runId}`.slice(0, 20), `browser_ui_m_${runId}`.slice(0, 20)]
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
async function login(page, username) {
  await page.goto(`${frontendUrl}/login`)
  await page.getByLabel('登录账号').fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '进入空间', exact: true }).click()
  await page.waitForURL(/\/workspaces/)
}

async function saveWithFailedRefresh(page, mutationPath, action) {
  let saved = false
  let savedName
  let writes = 0
  let failedReads = 0
  const handler = async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    if (method === 'POST' && path === mutationPath) {
      const response = await route.fetch()
      assert.ok(response.ok())
      const { data } = await response.json()
      savedName = data.workspace?.name ?? data.name
      writes += 1
      saved = true
      await route.fulfill({ response })
    } else if (method === 'GET' && path === '/api/workspaces' && saved) {
      saved = false
      failedReads += 1
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'UNAVAILABLE',
          message: '保存后的列表刷新暂时失败',
          data: null,
        }),
      })
    } else await route.continue()
  }
  await page.route('**/api/workspaces**', handler)
  try {
    await action()
    await page.waitForURL(/\/overview$/)
    // 路由变更早于子页接口结束，等目标页读取完成再撤下网络替身。
    await page.getByRole('heading', { name: savedName, exact: true }).waitFor()
    assert.equal(writes, 1, '保存成功后不能重复 POST')
    assert.equal(failedReads, 1)
    checks.push({ name: `${mutationPath}-saved-with-refresh-failure`, passed: true })
  } finally {
    await page.unroute('**/api/workspaces**', handler)
  }
}
async function capture(page, name, widths = [1440, 390]) {
  for (const width of widths) {
    await page.setViewportSize({ width, height: 1000 })
    await page.waitForTimeout(180)
    const state = await page.evaluate(() => ({
      overflow: document.documentElement.scrollWidth > innerWidth,
      heading: document.querySelector('h1')?.textContent,
      escapedControls: [
        ...document.querySelectorAll(
          '.research-theme button, .research-theme input, .research-theme select, .research-theme textarea, .research-theme a:not(.ui-skip-link)',
        ),
      ]
        .filter(
          (element) =>
            element.getClientRects().length && getComputedStyle(element).visibility !== 'hidden',
        )
        .filter((element) => {
          const rect = element.getBoundingClientRect()
          return rect.left < -1 || rect.right > innerWidth + 1
        })
        .map((element) => element.textContent.trim() || element.id),
    }))
    // before 是基线记录，旧页可能有缺陷，不能把它误算为新界面通过。
    if (phase === 'after') {
      assert.equal(state.overflow, false, `${name} ${width}px 横向溢出`)
      assert.deepEqual(state.escapedControls, [], `${name} ${width}px 控件越出视口`)
    }
    checks.push({ name, width, ...state })
    await page.screenshot({ path: `${output}/${phase}-${name}-${width}.png`, fullPage: true })
  }
}
try {
  await mkdir(output, { recursive: true })
  for (const [index, username] of usernames.entries()) {
    await api('/auth/register', null, 'POST', {
      username,
      nickname: index ? '验收成员' : '验收所有者',
      password,
      confirmPassword: password,
    })
  }
  const owner = await api('/auth/login', null, 'POST', { username: usernames[0], password })
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
  await page.getByLabel('空间名称', { exact: true }).waitFor()
  await capture(page, 'empty-picker', [1440, 390, 320])
  await page.getByLabel('空间名称', { exact: true }).fill(workspaceName)
  const submitCreate = () => page.getByRole('button', { name: '创建空间', exact: true }).click()
  if (phase === 'after') await saveWithFailedRefresh(page, '/api/workspaces', submitCreate)
  else await submitCreate()
  await page.waitForURL(/\/overview$/)
  await page.getByRole('heading', { name: workspaceName, exact: true }).waitFor()
  const workspaceId = Number(new URL(page.url()).pathname.split('/')[2])
  await capture(page, 'overview', [1440, 960, 390, 320])
  const invitation = await api(
    `/workspaces/${workspaceId}/invitations`,
    owner.accessToken,
    'POST',
    { expectedInvitationId: null },
  )
  const second = await api('/workspaces', owner.accessToken, 'POST', { name: `第二空间${runId}` })
  await page.goto(`${frontendUrl}/workspaces`)
  await page.getByRole('button').filter({ hasText: workspaceName }).waitFor()
  await capture(page, 'picker', [1440, 960, 390, 320])
  const reference = await context.newPage()
  await reference.route('https://**', (route) => route.abort())
  for (const route of ['pick', 'overview']) {
    await reference.goto(
      `${pathToFileURL(resolve('../../ui-preview/stock-workspace-demo-v2-content-aligned.html')).href}#/${route}`,
    )
    for (const width of [1440, 390]) {
      await reference.setViewportSize({ width, height: 1000 })
      await reference.screenshot({
        path: `${output}/reference-${route}-${width}.png`,
        fullPage: true,
      })
    }
  }
  await reference.close()
  if (phase === 'after') {
    const memberContext = await browser.newContext()
    const memberPage = await memberContext.newPage()
    memberPage.on('pageerror', (error) => errors.push(error.message))
    await login(memberPage, usernames[1])
    await memberPage.getByLabel('邀请码', { exact: true }).fill(invitation.inviteCode.toLowerCase())
    await saveWithFailedRefresh(memberPage, '/api/workspaces/join', () =>
      memberPage.getByRole('button', { name: '使用邀请码加入', exact: true }).click(),
    )
    await memberPage.waitForURL(new RegExp(`/workspaces/${workspaceId}/overview$`))
    await memberPage.getByRole('button', { name: '返回空间选择', exact: true }).click()
    await memberPage.waitForURL(/\/workspaces\?reason=switch$/)
    await memberPage.getByRole('button').filter({ hasText: workspaceName }).waitFor()
    assert.ok(await memberPage.getByLabel('邀请码').isVisible(), '单空间主动切换后加入入口必须可见')
    checks.push({ name: 'join-and-explicit-single-workspace-switch', passed: true })

    const stock = await api(`/workspaces/${workspaceId}/stocks`, owner.accessToken, 'POST', {
      code: '600519.SH',
      stance: 'NEUTRAL',
      reason: 'UI 外壳验收，观察长期现金流。',
      observationNote: null,
      horizon: 'LONG',
      targetPrice: null,
      stopLossPrice: null,
    })
    const routes = [
      'overview',
      'stocks',
      'stocks/market/600519.SH',
      `stocks/${stock.workspaceStockId}`,
      'members',
      'settings',
    ]
    for (const route of routes) {
      await page.goto(`${frontendUrl}/workspaces/${workspaceId}/${route}`)
      await page.locator('h1').waitFor()
      if (route.includes('market')) await page.locator('canvas').first().waitFor()
      await capture(
        page,
        route === `stocks/${stock.workspaceStockId}` ? 'research' : route.replaceAll('/', '-'),
        [1440, 960, 390, 320],
      )
      const controls = await page.locator('.workspace-nav nav a').count()
      assert.equal(controls, 4, '只保留四项空间导航')
      if (route.startsWith('stocks'))
        assert.equal(
          await page.locator('.workspace-nav nav a[aria-current="page"]').innerText(),
          '共享自选',
        )
    }
    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.getByRole('button', { name: '返回空间选择', exact: true }).click()
    await page
      .getByRole('button')
      .filter({ hasText: `第二空间${runId}` })
      .click()
    await page.getByRole('heading', { name: `第二空间${runId}`, exact: true }).waitFor()
    await page.reload()
    await page.getByRole('heading', { name: `第二空间${runId}`, exact: true }).waitFor()
    await page.goBack()
    await page.waitForURL(/\/workspaces\?reason=switch$/)
    checks.push({ name: 'switch-second-space-refresh-and-back', passed: true })

    // 直接更换同一组件的空间参数，验证 key 卸载旧页，避免只有 onMounted 的旧子页复用数据。
    await page.goto(`${frontendUrl}/workspaces/${workspaceId}/members`)
    await page.getByRole('heading', { name: workspaceName, exact: true }).waitFor()
    await page.evaluate(
      (id) =>
        document
          .querySelector('#app')
          .__vue_app__.config.globalProperties.$router.push(`/workspaces/${id}/members`),
      second.workspaceId,
    )
    await page.getByRole('heading', { name: `第二空间${runId}`, exact: true }).waitFor()
    assert.equal(await page.locator('.member-row').count(), 1)
    await page.goBack()
    await page.getByRole('heading', { name: workspaceName, exact: true }).waitFor()
    await page.locator('.member-row').filter({ hasText: '验收成员' }).waitFor()
    checks.push({ name: 'same-component-space-param-and-browser-back', passed: true })
    await page.goto(`${frontendUrl}/workspaces/${workspaceId}/overview`)
    await page.getByRole('heading', { name: workspaceName, exact: true }).waitFor()
    await page.keyboard.press('Tab')
    assert.equal(await page.locator(':focus').innerText(), '跳到页面内容')
    await page.keyboard.press('Enter')
    assert.equal(await page.locator(':focus').getAttribute('id'), 'workspace-content')
    await page.setViewportSize({ width: 390, height: 1000 })
    await page.getByRole('link', { name: '共享自选', exact: true }).click()
    await page.getByRole('heading', { name: '共享自选', exact: true }).waitFor()
    await page.getByRole('link', { name: '成员', exact: true }).click()
    await page.locator('.member-row').filter({ hasText: '验收成员' }).waitFor()
    checks.push({ name: 'keyboard-skip-link-and-mobile-navigation', passed: true })

    await api(`/workspaces/${workspaceId}`, owner.accessToken, 'PATCH', {
      name: '用于验证很长空间名称能够换行并保持导航和控件正常使用的研究组',
    })
    await page.goto(`${frontendUrl}/workspaces/${workspaceId}/overview`)
    await page.locator('.switcher').filter({ hasText: '用于验证' }).waitFor()
    await capture(page, 'long-name', [1440, 390, 320])
    await api(`/workspaces/${workspaceId}/archive`, owner.accessToken, 'POST')
    await page.getByRole('button', { name: '返回空间选择', exact: true }).click()
    await page
      .locator('.archived-section')
      .getByRole('button')
      .filter({ hasText: '用于验证' })
      .click()
    await page.getByText('这个空间已归档，当前为只读状态。', { exact: true }).waitFor()
    await capture(page, 'archived', [1440, 390])
    await page.getByRole('link', { name: '个人资料', exact: true }).click()
    await page.waitForURL(/\/profile$/)
    checks.push({ name: 'archived-readonly-and-profile', passed: true })

    let releaseOldResponse
    let oldRequestStarted
    const pendingResponse = new Promise((resolve) => {
      releaseOldResponse = resolve
    })
    const requestStarted = new Promise((resolve) => {
      oldRequestStarted = resolve
    })
    const oldPath = `**/api/workspaces/${second.workspaceId}`
    await page.route(oldPath, async (route) => {
      oldRequestStarted()
      await pendingResponse
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'UNAUTHENTICATED',
          message: '上一会话的延迟响应',
          data: null,
        }),
      })
    })
    await page.goto(`${frontendUrl}/workspaces/${second.workspaceId}/overview`)
    await requestStarted
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await page.waitForURL((url) => url.pathname === '/')
    assert.equal(await page.evaluate(() => localStorage.getItem('stock-system-access-token')), null)
    assert.equal(await page.evaluate(() => localStorage.getItem('stock-system-current-user')), null)
    await login(page, usernames[1])
    releaseOldResponse()
    await page.unrouteAll({ behavior: 'wait' })
    await page.waitForTimeout(100)
    assert.ok(await page.evaluate(() => localStorage.getItem('stock-system-access-token')))
    await page.locator('.archived-section').getByRole('button').waitFor()
    assert.equal(await page.getByText(`第二空间${runId}`, { exact: true }).count(), 0)
    checks.push({ name: 'logout-and-another-account-with-delayed-old-401', passed: true })

    await page.route('**/api/workspaces', (route) =>
      route.request().method() === 'GET'
        ? route.fulfill({
            status: 503,
            contentType: 'application/json',
            body: JSON.stringify({ code: 'UNAVAILABLE', message: '验收临时故障', data: null }),
          })
        : route.continue(),
    )
    await page.reload()
    await page.getByRole('button', { name: '重新加载' }).waitFor()
    await capture(page, 'load-error', [390])
    await page.unroute('**/api/workspaces')
    await page.getByRole('button', { name: '重新加载' }).click()
    await page.locator('.archived-section').getByRole('button').waitFor()
    checks.push({ name: 'load-failure-read-only-retry', passed: true })
  }
  assert.deepEqual(errors, [])
  success = true
} catch (error) {
  if (currentPage && !currentPage.isClosed()) {
    await currentPage.screenshot({ path: `${output}/${phase}-failure.png`, fullPage: true })
    console.error(
      JSON.stringify({
        path: new URL(currentPage.url()).pathname,
        text: await currentPage.locator('body').innerText(),
      }),
    )
  }
  throw error
} finally {
  try {
    if (browser) await browser.close()
  } finally {
    assert.ok(usernames.every((name) => /^browser_ui_[om]_[a-z0-9]+$/.test(name)))
    const users = usernames.map((name) => `'${name}'`).join(',')
    sql(`SET @ui_o=(SELECT id FROM app_user WHERE username='${usernames[0]}');
      CREATE TEMPORARY TABLE ui_spaces AS SELECT id FROM workspace WHERE created_by_user_id=@ui_o;
      CREATE TEMPORARY TABLE ui_stocks AS SELECT id FROM workspace_stock WHERE workspace_id IN (SELECT id FROM ui_spaces);
      CREATE TEMPORARY TABLE ui_cycles AS SELECT id FROM research_cycle WHERE workspace_stock_id IN (SELECT id FROM ui_stocks);
      DELETE FROM research_attention_change WHERE research_cycle_id IN (SELECT id FROM ui_cycles);
      DELETE FROM stock_opinion WHERE research_cycle_id IN (SELECT id FROM ui_cycles);
      DELETE FROM research_cycle WHERE id IN (SELECT id FROM ui_cycles);
      DELETE FROM workspace_stock WHERE id IN (SELECT id FROM ui_stocks);
      DELETE FROM workspace_invitation WHERE workspace_id IN (SELECT id FROM ui_spaces);
      DELETE FROM workspace_member WHERE workspace_id IN (SELECT id FROM ui_spaces);
      DELETE FROM workspace WHERE id IN (SELECT id FROM ui_spaces);
      DELETE FROM invitation_join_attempt WHERE user_id IN (SELECT id FROM app_user WHERE username IN (${users}));
      DELETE FROM app_user WHERE username IN (${users});
      ${stockExistedBefore ? '' : "DELETE FROM stock WHERE code='600519.SH' AND source='LOCAL_MOCK' AND NOT EXISTS(SELECT 1 FROM workspace_stock WHERE stock_id=stock.id);"}
      SELECT COUNT(*) FROM app_user WHERE username IN (${users});
      SELECT COUNT(*) FROM workspace WHERE created_by_user_id=@ui_o;
      SELECT COUNT(*) FROM workspace_member WHERE workspace_id IN (SELECT id FROM ui_spaces);
      SELECT COUNT(*) FROM workspace_invitation WHERE workspace_id IN (SELECT id FROM ui_spaces);
      SELECT COUNT(*) FROM workspace_stock WHERE id IN (SELECT id FROM ui_stocks);
      SELECT COUNT(*) FROM research_cycle WHERE id IN (SELECT id FROM ui_cycles);
      SELECT COUNT(*) FROM stock_opinion WHERE research_cycle_id IN (SELECT id FROM ui_cycles);
      SELECT COUNT(*) FROM research_attention_change WHERE research_cycle_id IN (SELECT id FROM ui_cycles);
    `)
      .split(/\r?\n/)
      .forEach((value) => assert.equal(Number(value), 0, '本轮数据必须清理为 0'))
  }
  await writeFile(
    `${output}/${phase}-verification.json`,
    JSON.stringify({ success, phase, runId, checks, errors, databaseRemaining: 0 }, null, 2),
  )
}
console.log(JSON.stringify({ phase, runId, checks: checks.length, errors, databaseRemaining: 0 }))
