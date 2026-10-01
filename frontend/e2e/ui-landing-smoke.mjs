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
const output = resolve(process.env.E2E_UI_OUTPUT ?? '../../ui-preview/ui-07')
const runId = Date.now().toString(36)
const usernames = [`browser_ui_o_${runId}`.slice(0, 20), `browser_ui_m_${runId}`.slice(0, 20)]
const password = 'BrowserPass123'
const workspaceName = `迁移验收${runId}·共同研究与长期观察`
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
async function login(page, username) {
  await page.goto(`${frontendUrl}/login`)
  await page.getByLabel('登录账号').fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '进入空间', exact: true }).click()
  await page.waitForURL(/\/workspaces/)
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
    checks.push({ name: `${name}-${width}`, passed: true })
  }
}

try {
  await mkdir(output, { recursive: true })
  browser = await chromium.launch({
    executablePath:
      process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: true,
  })
  const context = await browser.newContext({ reducedMotion: 'reduce' })
  await context.route(/https:\/\/fonts\.(googleapis|gstatic)\.com\//, (route) => route.abort())
  const page = await context.newPage()
  currentPage = page
  page.on('pageerror', (error) => errors.push(error.message))
  const requests = []
  page.on('request', (request) => {
    if (new URL(request.url()).pathname.startsWith('/api/'))
      requests.push({ method: request.method(), path: new URL(request.url()).pathname })
  })
  await page.goto(frontendUrl)
  await page.locator('h1').waitFor()
  await capture(page, 'anonymous-home')
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.screenshot({ path: `${output}/${phase}-home-top-1440.png` })
  assert.equal(requests.length, 0, '公开首页不能读取私有业务或请求行情')
  checks.push({ name: 'anonymous-home-zero-api-reads-or-writes', passed: true })
  if (phase === 'after') {
    const check = (name) => checks.push({ name, passed: true })
    assert.ok((await page.locator('.ui-brand').innerText()).includes('基点'))
    assert.equal(await page.locator('.room-demo .demo-opinion').count(), 3)
    assert.equal(await page.locator('.workflow li').count(), 4)
    assert.ok((await page.locator('.desk').innerText()).includes('非真实行情 / 非收益率'))
    assert.ok((await page.locator('.research-rail').innerText()).includes('人物与内容均为演示'))
    assert.equal(
      await page.getByRole('link', { name: /下载客户端|TWITTER|写信|更新日志/ }).count(),
      0,
    )
    assert.equal(await page.getByText('行情数据订阅费', { exact: true }).count(), 0)
    check('brand-local-demo-labels-actual-workflow-and-no-placeholder-or-platform-statistics')
    for (const id of [2, 3, 1]) {
      const note = page.locator(`[data-note="${id}"]`)
      await note.focus()
      await page.keyboard.press('Enter')
      assert.equal(await note.getAttribute('aria-pressed'), 'true')
      assert.equal(await page.locator('.research-note[aria-pressed="true"]').count(), 1)
      assert.equal(
        await page.locator('.research-marker.is-active').getAttribute('data-marker'),
        String(id),
      )
    }
    assert.equal(requests.length, 0)
    check('keyboard-demo-notes-update-single-marker-with-zero-api-requests')
    assert.equal(
      await page.locator('.marquee-track').evaluate((el) => getComputedStyle(el).animationName),
      'none',
    )
    assert.equal(await page.locator('.ticker-group[aria-hidden="true"]').isVisible(), false)
    assert.equal(await page.locator('.ticker-group:not([aria-hidden]) > span').count(), 5)
    const staticBounds = await page
      .locator('.ticker-group:not([aria-hidden]) > span')
      .evaluateAll((els) =>
        els.every(
          (el) =>
            el.getBoundingClientRect().left >= 0 &&
            el.getBoundingClientRect().right <= innerWidth + 1,
        ),
      )
    assert.equal(staticBounds, true)
    check('reduced-motion-static-wrapped-ticker-all-items-readable-without-duplicate')

    await page.goto(frontendUrl)
    await page.locator('h1').waitFor()
    await page.keyboard.press('Tab')
    assert.equal(
      await page
        .getByRole('link', { name: '跳到页面内容', exact: true })
        .evaluate((el) => el === document.activeElement),
      true,
    )
    await page.keyboard.press('Enter')
    assert.equal(
      await page.locator('#landing-content').evaluate((el) => el === document.activeElement),
      true,
    )
    check('keyboard-skip-link-focuses-main-content')
    await page.setViewportSize({ width: 1440, height: 1000 })
    for (const [name, id] of [
      ['产品', 'desk'],
      ['功能', 'features'],
      ['空间', 'room'],
      ['开始', 'workflow'],
    ]) {
      await page
        .getByRole('navigation', { name: '产品介绍', exact: true })
        .getByRole('link', { name, exact: true })
        .click()
      await page.waitForFunction(
        (anchor) => {
          const top = document.getElementById(anchor).getBoundingClientRect().top
          const max = document.documentElement.scrollHeight - innerHeight
          const expected = Math.min(Math.max(0, scrollY + top - 24), max)
          return Math.abs(scrollY - expected) < 4 && top >= 0 && top < innerHeight
        },
        id,
      )
      assert.equal(new URL(page.url()).hash, `#${id}`)
    }
    await page.locator('#workflow').screenshot({ path: `${output}/${phase}-workflow-1440.png` })
    // 先离开当前文档，覆盖真正的直达加载；同文档 goto(hash) 会由浏览器原生锚点滚动。
    await page.goto('about:blank')
    await page.goto(`${frontendUrl}/#features`)
    await page.waitForFunction(
      () => Math.abs(document.getElementById('features').getBoundingClientRect().top - 24) < 4,
    )
    await page.reload()
    await page.waitForFunction(
      () => Math.abs(document.getElementById('features').getBoundingClientRect().top - 24) < 4,
    )
    const roomLink = page
      .getByRole('navigation', { name: '产品介绍', exact: true })
      .getByRole('link', { name: '空间', exact: true })
    await roomLink.scrollIntoViewIfNeeded()
    const historyPosition = await page.evaluate(() => scrollY)
    await roomLink.click()
    await page.waitForFunction(
      () => Math.abs(document.getElementById('room').getBoundingClientRect().top - 24) < 4,
    )
    const roomPosition = await page.evaluate(() => scrollY)
    await page.goBack()
    assert.equal(new URL(page.url()).hash, '#features')
    // 点击顶端导航前浏览器已滚到导航处，历史返回恢复实际位置，不强制再次定位 hash。
    await page.waitForFunction((position) => Math.abs(scrollY - position) < 4, historyPosition)
    await page.goForward()
    assert.equal(new URL(page.url()).hash, '#room')
    await page.waitForFunction((position) => Math.abs(scrollY - position) < 4, roomPosition)
    check('all-section-links-direct-hash-refresh-and-browser-back-forward')
    await page.goto(frontendUrl)
    await page
      .getByRole('navigation', { name: '账号入口', exact: true })
      .getByRole('link', { name: '登录', exact: true })
      .click()
    await page.waitForURL('**/login')
    await page.getByLabel('登录账号').waitFor()
    await page.goBack()
    await page
      .getByRole('navigation', { name: '账号入口', exact: true })
      .getByRole('link', { name: '注册', exact: true })
      .click()
    await page.waitForURL('**/register')
    await page.getByRole('button', { name: '创建账号', exact: true }).waitFor()
    await page.goto(frontendUrl)
    await page
      .locator('.rail-bottom')
      .getByRole('link', { name: '进入研究空间', exact: true })
      .click()
    await page.waitForURL('**/login')
    check('anonymous-real-login-register-and-research-entry-routes')

    const motionContext = await browser.newContext({ reducedMotion: 'no-preference' })
    const motionPage = await motionContext.newPage()
    motionPage.on('pageerror', (error) => errors.push(error.message))
    await motionPage.goto(frontendUrl)
    await motionPage.locator('h1').waitFor()
    await capture(motionPage, 'motion-home')
    const track = motionPage.locator('.marquee-track')
    const initial = await track.evaluate((el) => getComputedStyle(el).transform)
    await motionPage.locator('.marquee').hover()
    await motionPage.waitForTimeout(150)
    assert.equal(await track.evaluate((el) => getComputedStyle(el).animationPlayState), 'running')
    assert.notEqual(await track.evaluate((el) => getComputedStyle(el).transform), initial)
    await motionPage.locator('.marquee').focus()
    assert.equal(await track.evaluate((el) => getComputedStyle(el).animationPlayState), 'running')
    assert.equal(await motionPage.getByRole('button', { name: /暂停/ }).count(), 0)
    check('normal-marquee-moves-and-keeps-running-on-hover-focus')
    for (const [width, height] of [
      [1440, 720],
      [1920, 1080],
      [1440, 1440],
    ]) {
      await motionPage.setViewportSize({ width, height })
      await motionPage.evaluate(() => window.scrollTo(0, 0))
      assert.equal(
        await motionPage.evaluate(() => document.documentElement.scrollWidth > innerWidth),
        false,
      )
      assert.equal(await motionPage.locator('.desk-head').isVisible(), true)
      await motionPage.screenshot({ path: `${output}/${phase}-home-${width}x${height}.png` })
      check(`viewport-home-${width}x${height}`)
    }
    await motionContext.close()

    // 角色 fixture 限于本轮 test 账号；不覆盖既有管理员，不调用治理写入接口。
    assert.equal(Number(sql('SELECT COUNT(*) FROM super_admin')), 0, '已有管理员时拒绝建立 fixture')
    for (const [index, username] of usernames.entries())
      await api('/auth/register', null, 'POST', {
        username,
        nickname: index ? '首页验收管理员' : '首页验收成员',
        password,
        confirmPassword: password,
      })
    const adminId = Number(sql(`SELECT id FROM app_user WHERE username='${usernames[1]}'`))
    assert.ok(Number.isSafeInteger(adminId) && adminId > 0)
    sql(`INSERT INTO super_admin(id,user_id) VALUES (1,${adminId})`)
    await login(page, usernames[0])
    await page.getByRole('heading', { name: '建立你们的第一个空间。', exact: true }).waitFor()
    const workspace = await api(
      '/workspaces',
      (await api('/auth/login', null, 'POST', { username: usernames[0], password })).accessToken,
      'POST',
      { name: workspaceName },
    )
    await page.goto(frontendUrl)
    requests.length = 0
    await page.locator('h1').waitFor()
    await capture(page, 'regular-home', [1440, 390])
    assert.equal(requests.length, 0)
    assert.equal(
      await page
        .getByRole('navigation', { name: '账号入口', exact: true })
        .getByRole('link', { name: '注册', exact: true })
        .count(),
      0,
    )
    await page
      .getByRole('navigation', { name: '账号入口', exact: true })
      .getByRole('link', { name: '进入研究空间', exact: true })
      .click()
    await page.waitForURL(`**/workspaces/${workspace.workspaceId}/overview`)
    await page.getByRole('heading', { name: workspaceName, exact: true }).waitFor()
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await page.waitForURL(frontendUrl + '/')
    await page
      .getByRole('navigation', { name: '账号入口', exact: true })
      .getByRole('link', { name: '注册', exact: true })
      .waitFor()
    assert.equal(await page.evaluate(() => localStorage.getItem('stock-system-access-token')), null)
    check('real-regular-session-home-entry-and-logout-restores-anonymous-links')

    const adminContext = await browser.newContext({ reducedMotion: 'reduce' })
    const adminPage = await adminContext.newPage()
    adminPage.on('pageerror', (error) => errors.push(error.message))
    await adminPage.goto(`${frontendUrl}/login`)
    await adminPage.getByLabel('登录账号').fill(usernames[1])
    await adminPage.getByLabel('密码', { exact: true }).fill(password)
    await adminPage.getByRole('button', { name: '进入空间', exact: true }).click()
    await adminPage.waitForURL('**/admin/users')
    await adminPage.goto(frontendUrl)
    await adminPage.locator('h1').waitFor()
    await capture(adminPage, 'admin-home', [1440, 390, 320])
    assert.equal(
      await adminPage
        .getByRole('navigation', { name: '账号入口', exact: true })
        .getByRole('link', { name: '进入治理后台', exact: true })
        .count(),
      1,
    )
    await adminPage
      .locator('.rail-bottom')
      .getByRole('link', { name: '进入治理后台', exact: true })
      .click()
    await adminPage.waitForURL('**/admin/users')
    check('real-admin-session-home-entry-routes-to-governance')

    await adminPage.evaluate(() => {
      const user = JSON.parse(localStorage.getItem('stock-system-current-user'))
      user.accountType = 'REGULAR'
      localStorage.setItem('stock-system-current-user', JSON.stringify(user))
    })
    await adminPage.goto(frontendUrl)
    await adminPage
      .getByRole('navigation', { name: '账号入口', exact: true })
      .getByRole('link', { name: '进入研究空间', exact: true })
      .click()
    await adminPage.waitForURL('**/admin/users')
    check('stale-stored-role-revalidated-by-existing-auth-guard-before-entry')
    await adminContext.close()

    await login(page, usernames[0])
    await page.evaluate(() =>
      localStorage.setItem('stock-system-access-token', 'invalid-ui-landing-token'),
    )
    await page.goto(frontendUrl)
    await page
      .getByRole('navigation', { name: '账号入口', exact: true })
      .getByRole('link', { name: '进入研究空间', exact: true })
      .click()
    await page.waitForURL(
      (u) => u.pathname === '/login' && u.searchParams.get('reason') === 'expired',
    )
    assert.equal(await page.evaluate(() => localStorage.getItem('stock-system-access-token')), null)
    check('expired-stored-session-public-home-entry-uses-existing-401-login-recovery')
  }
  const reference = await context.newPage()
  await reference.goto(
    `${pathToFileURL(resolve('../../ui-preview/stock-workspace-demo-v2-content-aligned.html')).href}#/home`,
  )
  await reference.locator('#page-home').waitFor()
  for (const width of [1440, 390]) {
    await reference.setViewportSize({ width, height: 1000 })
    await reference.screenshot({ path: `${output}/reference-home-${width}.png`, fullPage: true })
  }
  await reference.close()
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
    assert.ok(usernames.every((name) => /^browser_ui_[om]_[a-z0-9]+$/.test(name)))
    const users = usernames.map((name) => `'${name}'`).join(',')
    sql(`SET @ui_o=(SELECT id FROM app_user WHERE username='${usernames[0]}'); SET @ui_m=(SELECT id FROM app_user WHERE username='${usernames[1]}');
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
      DELETE FROM super_admin WHERE user_id IN (SELECT id FROM app_user WHERE username IN (${users}));
      DELETE FROM app_user WHERE username IN (${users});
      SELECT COUNT(*) FROM super_admin WHERE user_id IN (@ui_o,@ui_m);
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
