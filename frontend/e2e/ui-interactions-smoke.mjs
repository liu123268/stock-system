import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium } from 'playwright-core'

const frontendUrl = process.env.FRONTEND_URL ?? 'http://localhost:18083'
const backendUrl = process.env.BACKEND_URL ?? 'http://localhost:18082'
const phase = process.env.E2E_UI_PHASE ?? 'after'
assert.ok(['before', 'after'].includes(phase))
const output = resolve(process.env.E2E_UI_OUTPUT ?? '../../ui-preview/ui-interactions')
const runId = Date.now().toString(36)
const usernames = ['o', 'm', 'a'].map((key) => `fx_${key}_${runId}`)
const password = 'BrowserPass123'
const workspaceName = `迁移验收${runId}`
const checks = []
const errors = []
const routes = []
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
let adminId, ownerToken, workspaceId, stockId
const check = (name, detail = {}) => checks.push({ name, passed: true, ...detail })
async function login(page, username, admin = false) {
  await page.goto(`${frontendUrl}/login`)
  await page.getByLabel('登录账号', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '进入空间', exact: true }).click()
  await page.waitForURL(admin ? '**/admin/users' : /\/workspaces/)
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
  await api('/workspaces', ownerToken, 'POST', { name: `切换核对${runId}` })
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
  const context = await browser.newContext({
    reducedMotion: 'no-preference',
    viewport: { width: 1440, height: 1000 },
  })
  const page = await context.newPage()
  currentPage = page
  page.on('pageerror', (e) => errors.push(e.message))

  let writes = 0
  page.on('request', (request) => {
    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method())) writes++
  })
  const snapshots = []
  const state = (locator) =>
    locator.evaluate((element) => {
      const style = getComputedStyle(element)
      const first = element.firstElementChild
      return Object.fromEntries([
        ...[
          'backgroundColor',
          'color',
          'borderColor',
          'translate',
          'scale',
          'transform',
          'gap',
          'outlineStyle',
          'outlineWidth',
          'transitionDuration',
        ].map((key) => [key, style[key]]),
        ['childTranslate', first ? getComputedStyle(first).translate : 'none'],
        ['focusVisible', element.matches(':focus-visible')],
      ])
    })
  async function sample(locator, name, kind = 'button') {
    await locator.scrollIntoViewIfNeeded()
    await page.mouse.move(1, 1)
    await page.waitForTimeout(650)
    const normal = await state(locator)
    await locator.screenshot({ path: `${output}/${phase}-${name}-normal.png` })
    const url = page.url(),
      initialWrites = writes
    await locator.hover()
    await page.waitForTimeout(650)
    const hover = await state(locator)
    await locator.screenshot({ path: `${output}/${phase}-${name}-hover.png` })
    const bounds = await locator.boundingBox()
    await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
    await page.mouse.down()
    await page.waitForTimeout(150)
    const pressed = await state(locator)
    await locator.screenshot({ path: `${output}/${phase}-${name}-pressed.png` })
    await page.mouse.move(1, 1)
    await page.mouse.up()
    await page.waitForTimeout(650)
    assert.equal(page.url(), url, `${name} 截取状态不应触发导航`)
    assert.equal(writes, initialWrites, `${name} 截取状态不应触发写入`)
    if (phase === 'after') {
      if (kind === 'button') {
        assert.notEqual(hover.color, hover.backgroundColor, `${name} 悬浮时文字与背景同色`)
        assert.notEqual(hover.translate, normal.translate, `${name} 悬浮反馈遗漏`)
        assert.notEqual(pressed.translate, hover.translate, `${name} 按下反馈遗漏`)
        assert.ok(Number(pressed.scale) < 1, `${name} 应有按压反馈`)
      }
      if (kind === 'row')
        assert.notEqual(hover.childTranslate, normal.childTranslate, `${name} 行内容位移遗漏`)
      if (kind === 'link') assert.notEqual(hover.gap, normal.gap, `${name} 箭头展开遗漏`)
      if (kind === 'footer') assert.notEqual(hover.color, normal.color, `${name} 描边填色遗漏`)
      if (kind === 'disabled') {
        for (const key of ['backgroundColor', 'color', 'borderColor', 'translate', 'scale']) {
          assert.equal(hover[key], normal[key], `${name} 禁用悬浮仍改变 ${key}`)
          assert.equal(pressed[key], normal[key], `${name} 禁用按下仍改变 ${key}`)
        }
      }
      if (kind !== 'footer' && kind !== 'disabled' && name !== 'home-feature') {
        await locator.focus()
        await page.keyboard.press('Shift+Tab')
        await page.keyboard.press('Tab')
        const focus = await state(locator)
        assert.equal(focus.focusVisible, true, `${name} 键盘焦点不可见`)
        assert.notEqual(focus.outlineStyle, 'none')
      }
    }
    snapshots.push({ name, kind, normal, hover, pressed })
    check(`${name}-states`, { kind, normal, hover, pressed })
  }
  async function visit(path, ready) {
    await page.goto(`${frontendUrl}${path}`)
    await page.locator(ready).first().waitFor()
    await page.waitForLoadState('networkidle')
    routes.push({ path })
    if (phase === 'after') {
      // 专门在正常动态下悬浮，旧全站截图的 reduce 偏好不能覆盖位移越界。
      for (const width of [1440, 960, 390, 320]) {
        await page.setViewportSize({ width, height: 1000 })
        for (const family of [
          '.ui-button',
          '.period-switch button',
          '.workspace-row',
          '.workspace-nav nav a',
          '.feature-row',
          '.line-link',
        ]) {
          const target = page.locator(family).first()
          if (!(await target.isVisible()) || (await target.isDisabled())) continue
          await target.hover()
          await page.waitForTimeout(300)
          const bounds = await target.evaluate((element) => {
            const r = element.getBoundingClientRect()
            return {
              overflow: document.documentElement.scrollWidth > innerWidth,
              outside:
                !element.closest('.admin-table-wrap') && (r.left < -1 || r.right > innerWidth + 1),
            }
          })
          assert.equal(bounds.overflow, false, `${path} ${width}px 悬浮时页面溢出`)
          assert.equal(bounds.outside, false, `${path} ${width}px 悬浮时控件越界`)
        }
        check(`hover-layout-${path}-${width}`)
      }
      await page.setViewportSize({ width: 1440, height: 1000 })
      await page.mouse.move(1, 1)
    }
  }
  await visit('/', 'h1')
  await sample(page.locator('.nav-cta'), 'home-register')
  await sample(page.locator('.hero-bottom .line-link'), 'home-arrow', 'link')
  await sample(page.locator('.feature-row').first(), 'home-feature', 'row')
  await sample(page.locator('.foot-mark'), 'home-footer', 'footer')
  await visit('/login', 'form')
  await sample(page.locator('.auth-submit-button'), 'login-submit')
  await visit('/register', 'form')
  await sample(page.locator('.auth-submit-button'), 'register-submit')
  await login(page, usernames[0])
  await visit('/workspaces?reason=switch', '.space-picker')
  await sample(page.locator('.workspace-row').first(), 'picker-row', 'row')
  await sample(page.locator('.join-form button'), 'picker-join')
  const base = `/workspaces/${workspaceId}`
  for (const [path, ready, selector, name] of [
    [`${base}/overview`, '.overview-heading', '.invitation-section button', 'overview-invite'],
    [`${base}/stocks`, '.page-heading', '.search-form button', 'stocks-search'],
    [`${base}/stocks/market/600519.SH`, 'canvas', '.chart-mode-switch button', 'market-mode'],
    [`${base}/stocks/${stockId}`, '.research-header', '.compose-toggle', 'research-compose'],
    [`${base}/members`, '.management-heading', '.member-actions button', 'members-action'],
    [`${base}/settings`, '.management-heading', '.danger-action', 'settings-danger'],
    ['/profile', '.profile-page', '.profile-submit', 'profile-save'],
  ]) {
    await visit(path, ready)
    await sample(page.locator(selector).first(), name)
    if (name === 'stocks-search') {
      await page.getByLabel('股票代码、名称或拼音首字母').fill('600519')
      await page.locator('.search-form button').click()
      await page.locator('.search-results a').first().waitFor()
      await sample(page.locator('.search-results a').first(), 'stocks-result', 'row')
      await sample(page.locator('.workspace-nav nav a').nth(1), 'sidebar-link', 'row')
    }
    if (name === 'research-compose') {
      await page.locator('.compose-toggle').click()
      await sample(page.locator('.opinion-compose button[type="submit"]'), 'opinion-submit')
    }
  }
  await page.goto(`${frontendUrl}/`)
  await page.evaluate(() => {
    localStorage.clear()
    location.reload()
  })
  await page.waitForLoadState('networkidle')
  await login(page, usernames[2], true)
  for (const section of ['users', 'workspaces', 'operation-logs']) {
    await visit(`/admin/${section}`, '.admin-page')
    await sample(page.locator('.admin-toolbar button[type="submit"]'), `admin-${section}-query`)
    await sample(
      page.locator('.admin-pagination button').first(),
      `admin-${section}-disabled`,
      'disabled',
    )
  }
  if (phase === 'after') {
    // 核对真实请求锁定期间的按钮。响应已由后端处理，但界面仍必须禁用。
    await page.getByRole('button', { name: '退出治理后台', exact: true }).click()
    let release
    const held = new Promise((resolve) => {
      release = resolve
    })
    await page.route('**/api/auth/login', async (route) => {
      const response = await route.fetch()
      await held
      await route.fulfill({ response })
    })
    await page.getByLabel('登录账号', { exact: true }).fill(usernames[0])
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page.locator('.auth-submit-button').click()
    try {
      await page.locator('.auth-submit-button:disabled').waitFor()
      await sample(page.locator('.auth-submit-button'), 'login-loading', 'disabled')
    } finally {
      release()
    }
    await page.waitForURL(/\/workspaces/)
    await page.unroute('**/api/auth/login')
    await visit(`${base}/overview`, '.overview-heading')
    const button = page.locator('.invitation-section button').first()
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.mouse.move(1, 1)
    const reducedNormal = await state(button)
    await button.hover()
    const reducedHover = await state(button)
    assert.equal(reducedHover.translate, 'none')
    assert.equal(reducedHover.scale, 'none')
    assert.notEqual(reducedHover.backgroundColor, reducedNormal.backgroundColor)
    check('reduced-motion-keeps-immediate-color-without-displacement')
    await page.emulateMedia({ reducedMotion: 'no-preference' })
    await button.hover()
    await page.waitForTimeout(650)
    assert.notEqual((await state(button)).translate, 'none')
    check('motion-preference-updates-without-reload')
    await page.mouse.move(1, 1)
    await button.focus()
    const count = writes
    await page.keyboard.down('Space')
    await page.waitForTimeout(150)
    assert.equal((await state(button)).scale, '0.98')
    await page.keyboard.press('Tab')
    await page.keyboard.up('Space')
    assert.equal(writes, count, '取消键盘按下状态不应生成邀请码')
    check('keyboard-space-press-feedback')
    const touch = await browser.newContext({
      hasTouch: true,
      isMobile: true,
      viewport: { width: 390, height: 844 },
    })
    try {
      const mobile = await touch.newPage()
      await mobile.goto(`${frontendUrl}/login`)
      const submit = mobile.locator('.auth-submit-button')
      await submit.scrollIntoViewIfNeeded()
      const mobileNormal = await state(submit)
      const mobileBounds = await submit.boundingBox()
      const cdp = await touch.newCDPSession(mobile)
      await cdp.send('Input.dispatchTouchEvent', {
        type: 'touchStart',
        touchPoints: [
          {
            x: mobileBounds.x + mobileBounds.width / 2,
            y: mobileBounds.y + mobileBounds.height / 2,
          },
        ],
      })
      await mobile.waitForTimeout(150)
      assert.equal((await state(submit)).scale, '0.98', '触摸按住应有按压反馈')
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
      await mobile.locator('.el-form-item__error').first().waitFor()
      await mobile.waitForTimeout(300)
      const mobileReleased = await state(submit)
      assert.equal(mobileReleased.translate, mobileNormal.translate)
      assert.equal(mobileReleased.backgroundColor, mobileNormal.backgroundColor)
      await submit.scrollIntoViewIfNeeded()
      const cancelBounds = await submit.boundingBox()
      await cdp.send('Input.dispatchTouchEvent', {
        type: 'touchStart',
        touchPoints: [
          {
            x: cancelBounds.x + cancelBounds.width / 2,
            y: cancelBounds.y + cancelBounds.height / 2,
          },
        ],
      })
      await mobile.waitForTimeout(150)
      assert.equal((await state(submit)).scale, '0.98')
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] })
      await mobile.waitForTimeout(300)
      assert.equal(await submit.getAttribute('data-touch-pressed'), null)
      assert.equal((await state(submit)).scale, 'none')
      check('touch-cancel-clears-press-without-submit')
      const overflow = await mobile.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      )
      assert.equal(overflow, false)
      check('coarse-pointer-without-sticky-hover-at-390')
      await mobile.emulateMedia({ reducedMotion: 'reduce' })
      await mobile.setViewportSize({ width: 320, height: 844 })
      await mobile.reload()
      await submit.scrollIntoViewIfNeeded()
      const reducedBounds = await submit.boundingBox()
      const reducedTouchNormal = await state(submit)
      await cdp.send('Input.dispatchTouchEvent', {
        type: 'touchStart',
        touchPoints: [
          {
            x: reducedBounds.x + reducedBounds.width / 2,
            y: reducedBounds.y + reducedBounds.height / 2,
          },
        ],
      })
      await mobile.waitForTimeout(150)
      const reducedTouchPressed = await state(submit)
      assert.equal(reducedTouchPressed.scale, 'none')
      assert.equal(reducedTouchPressed.translate, 'none')
      assert.notEqual(reducedTouchPressed.backgroundColor, reducedTouchNormal.backgroundColor)
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] })
      assert.equal(
        await mobile.evaluate(() => document.documentElement.scrollWidth > innerWidth),
        false,
      )
      check('touch-and-reduced-motion-at-320')
    } finally {
      await touch.close()
    }
    const anonymous = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
    try {
      const edgePage = await anonymous.newPage()
      await edgePage.goto(`${frontendUrl}/`)
      const entry = edgePage.locator('.nav-cta')
      await entry.hover()
      await edgePage.waitForTimeout(300)
      const r = await entry.boundingBox()
      await edgePage.mouse.move(r.x + 0.5, r.y + r.height / 2)
      await edgePage.mouse.down()
      await edgePage.waitForTimeout(160)
      await edgePage.mouse.up()
      await edgePage.waitForURL('**/register')
      check('edge-click-still-navigates-after-press-scale')
    } finally {
      await anonymous.close()
    }
    assert.deepEqual(errors, [])
  }
  await writeFile(`${output}/${phase}-states.json`, JSON.stringify(snapshots, null, 2))
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
    assert.ok(usernames.every((name) => /^fx_[oma]_[a-z0-9]+$/.test(name)))
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
