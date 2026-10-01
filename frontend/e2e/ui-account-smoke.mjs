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
const output = resolve(process.env.E2E_UI_OUTPUT ?? '../../ui-preview/ui-08')
const runId = Date.now().toString(36)
const usernames = [`ui08_o_${runId}`.slice(0, 20), `ui08_m_${runId}`.slice(0, 20)]
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
  await page.waitForURL(/\/(workspaces|admin)/)
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
  const check = (name) => checks.push({ name, passed: true })
  const posts = []
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname.startsWith('/api/auth/'))
      posts.push(new URL(request.url()).pathname)
  })
  for (const name of ['login', 'register']) {
    await page.goto(`${frontendUrl}/${name}`)
    await page.getByLabel('登录账号', { exact: true }).waitFor()
    await capture(page, name)
  }
  assert.equal(Number(sql('SELECT COUNT(*) FROM super_admin')), 0)
  if (phase === 'after') {
    await page.goto(`${frontendUrl}/login`)
    await page.getByLabel('登录账号', { exact: true }).waitFor()
    await page.keyboard.press('Tab')
    assert.equal(
      await page
        .getByRole('link', { name: '跳到账号表单', exact: true })
        .evaluate((el) => el === document.activeElement),
      true,
    )
    await page.keyboard.press('Enter')
    assert.equal(
      await page.locator('#account-form').evaluate((el) => el === document.activeElement),
      true,
    )
    await page.keyboard.press('Tab')
    assert.equal(
      await page
        .getByLabel('登录账号', { exact: true })
        .evaluate((el) => el === document.activeElement),
      true,
    )
    check('keyboard-skip-link-and-visible-form-input-focus')
    await page.getByRole('button', { name: '进入空间', exact: true }).click()
    await page.getByText('请输入登录账号', { exact: true }).waitFor()
    assert.equal(posts.length, 0)
    await page.getByLabel('登录账号', { exact: true }).fill(usernames[0])
    await page.getByLabel('密码', { exact: true }).fill('123')
    await page.getByRole('button', { name: '进入空间', exact: true }).click()
    await page.getByText('密码长度为4到64个字符', { exact: true }).waitFor()
    assert.equal(posts.length, 0)
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page.getByRole('checkbox', { name: '显示密码' }).check()
    assert.equal(await page.getByLabel('密码', { exact: true }).getAttribute('type'), 'text')
    assert.equal(posts.length, 0)
    await page.getByRole('checkbox', { name: '显示密码' }).uncheck()
    assert.equal(
      await page.getByLabel('登录账号', { exact: true }).getAttribute('autocomplete'),
      'username',
    )
    assert.equal(
      await page.getByLabel('密码', { exact: true }).getAttribute('autocomplete'),
      'current-password',
    )
    await page.getByLabel('密码', { exact: true }).press('Enter')
    await page
      .getByRole('alert')
      .filter({ hasText: /账号|密码|凭据/ })
      .waitFor()
    assert.equal(await page.getByLabel('登录账号', { exact: true }).inputValue(), usernames[0])
    await page.setViewportSize({ width: 390, height: 1000 })
    await page.screenshot({ path: `${output}/after-login-invalid-390.png`, fullPage: true })
    check('login-empty-short-password-visibility-autofill-and-real-invalid-credentials')
    await page.route('**/api/auth/login', (route) =>
      route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SERVICE_UNAVAILABLE',
          message: '登录服务暂时不可用',
          data: null,
        }),
      }),
    )
    await page.getByRole('button', { name: '进入空间', exact: true }).click()
    await page.getByRole('alert').filter({ hasText: '登录服务暂时不可用' }).waitFor()
    assert.equal(await page.getByLabel('密码', { exact: true }).inputValue(), password)
    await page.unroute('**/api/auth/login')
    check('login-network-failure-keeps-input-and-retry')
    const redirect = '/profile?source=account-test#nickname-title'
    await page.goto(`${frontendUrl}/login?redirect=${encodeURIComponent(redirect)}`)
    await page
      .getByRole('navigation', { name: '账号入口', exact: true })
      .getByRole('link', { name: '注册', exact: true })
      .click()
    assert.equal(new URL(page.url()).searchParams.get('redirect'), redirect)
    await page
      .getByRole('navigation', { name: '账号入口', exact: true })
      .getByRole('link', { name: '登录', exact: true })
      .click()
    assert.equal(new URL(page.url()).searchParams.get('redirect'), redirect)
    await page.locator('.auth-switch-note').getByRole('link', { name: '创建账号' }).click()
    assert.equal(new URL(page.url()).searchParams.get('redirect'), redirect)
    const submit = page.getByRole('button', { name: '创建账号', exact: true })
    const beforePosts = posts.length
    await submit.click()
    await page.getByText('请输入昵称', { exact: true }).waitFor()
    await page.getByLabel('登录账号', { exact: true }).fill('错误账号')
    await page.getByLabel('昵称', { exact: true }).fill('注册验收成员')
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page.getByLabel('确认密码', { exact: true }).fill('DifferentPass123')
    await submit.click()
    await page.getByText('两次输入的密码不一致', { exact: true }).waitFor()
    assert.equal(posts.length, beforePosts)
    await page.getByLabel('登录账号', { exact: true }).fill(usernames[0])
    await page.getByLabel('确认密码', { exact: true }).fill(password)
    let release
    const gate = new Promise((resolve) => {
      release = resolve
    })
    let fetched
    const received = new Promise((resolve) => {
      fetched = resolve
    })
    await page.route('**/api/auth/register', async (route) => {
      const response = await route.fetch()
      fetched()
      await gate
      await route.fulfill({ response })
    })
    await page.getByLabel('确认密码', { exact: true }).press('Enter')
    await received
    await capture(page, 'register-submitting', [390, 320])
    assert.equal(await page.getByLabel('登录账号', { exact: true }).isDisabled(), true)
    await page.getByRole('button', { name: '正在创建…', exact: true }).evaluate((el) => {
      el.click()
      el.click()
    })
    assert.equal(posts.filter((path) => path === '/api/auth/register').length, 1)
    release()
    await page.waitForURL(
      (u) => u.pathname === '/login' && u.searchParams.get('reason') === 'registered',
    )
    await page.unroute('**/api/auth/register')
    assert.equal(new URL(page.url()).searchParams.get('redirect'), redirect)
    assert.equal(await page.getByLabel('登录账号', { exact: true }).inputValue(), usernames[0])
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page.getByRole('button', { name: '进入空间', exact: true }).click()
    await page.waitForURL(
      (u) =>
        u.pathname === '/profile' &&
        u.searchParams.get('source') === 'account-test' &&
        u.hash === '#nickname-title',
    )
    const message = page.locator('.account-message').first()
    await message.waitFor()
    assert.equal(
      await message.evaluate((el) => {
        const r = el.getBoundingClientRect()
        return (
          getComputedStyle(el).position === 'fixed' &&
          r.left >= 0 &&
          r.right <= innerWidth &&
          r.top >= 0
        )
      }),
      true,
    )
    check('body-message-theme-and-narrow-viewport-bounds')
    check('register-validation-one-enter-post-busy-lock-prefill-and-login-full-redirect')
  }
  for (const [index, username] of usernames.entries()) {
    if (phase === 'after' && index === 0) continue
    await api('/auth/register', null, 'POST', {
      username,
      nickname: index ? '账号迁移管理员' : '账号迁移成员',
      password,
    })
  }
  const regularSession = await api('/auth/login', null, 'POST', {
    username: usernames[0],
    password,
  })
  const workspace = await api('/workspaces', regularSession.accessToken, 'POST', {
    name: workspaceName,
  })
  const adminId = Number(sql(`SELECT id FROM app_user WHERE username='${usernames[1]}'`))
  assert.ok(Number.isSafeInteger(adminId) && adminId > 0)
  sql(`INSERT INTO super_admin(id,user_id) VALUES (1,${adminId})`)
  if (phase === 'before') await login(page, usernames[0])
  await page.goto(`${frontendUrl}/profile`)
  await page.getByLabel('昵称', { exact: true }).waitFor()
  await capture(page, 'regular-profile')
  const adminContext = await browser.newContext({ reducedMotion: 'reduce' })
  const adminPage = await adminContext.newPage()
  adminPage.on('pageerror', (error) => errors.push(error.message))
  await login(adminPage, usernames[1])
  await adminPage.goto(`${frontendUrl}/profile`)
  await adminPage.getByLabel('昵称', { exact: true }).waitFor()
  await capture(adminPage, 'admin-profile', [1440, 390, 320])
  if (phase === 'after') {
    async function delayed(target, pattern, method) {
      let release, received, deliveredResolve
      const gate = new Promise((resolve) => {
        release = resolve
      })
      const pending = new Promise((resolve) => {
        received = resolve
      })
      const delivered = new Promise((resolve) => {
        deliveredResolve = resolve
      })
      let count = 0
      await target.route(pattern, async (route) => {
        if (route.request().method() !== method) return route.continue()
        count++
        const response = await route.fetch()
        received(response.status())
        await gate
        await route.fulfill({ response })
        deliveredResolve()
      })
      return { pending, delivered, release, count: () => count }
    }
    async function settle(target) {
      await target.evaluate(
        () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
      )
    }
    // 资料页的返回入口按真实账号角色，返回不改变成员关系。
    await adminPage.getByRole('link', { name: '← 返回治理后台', exact: true }).click()
    await adminPage.waitForURL('**/admin/users')
    await page.getByRole('link', { name: '← 返回空间选择', exact: true }).click()
    await page.waitForURL(`**/workspaces/${workspace.workspaceId}/overview`)
    await page.getByRole('heading', { name: workspaceName, exact: true }).waitFor()
    check('regular-and-admin-profile-role-return-routes')
    await page.goto(`${frontendUrl}/profile`)
    const nicknameInput = page.getByLabel('昵称', { exact: true })
    await nicknameInput.fill('   ')
    await page.getByRole('button', { name: '保存昵称', exact: true }).click()
    await page.getByText('昵称需要 1 到 20 个字符', { exact: true }).waitFor()
    const longNickname = '研'.repeat(20)
    await nicknameInput.fill(longNickname)
    await page.route('**/api/auth/me', (route) =>
      route.request().method() === 'PATCH'
        ? route.fulfill({
            status: 503,
            contentType: 'application/json',
            body: JSON.stringify({
              code: 'SERVICE_UNAVAILABLE',
              message: '昵称保存暂时不可用',
              data: null,
            }),
          })
        : route.continue(),
    )
    await page.getByRole('button', { name: '保存昵称', exact: true }).click()
    await page.getByText('昵称保存暂时不可用', { exact: true }).waitFor()
    assert.equal(await nicknameInput.inputValue(), longNickname)
    await page.screenshot({ path: `${output}/after-nickname-failure-320.png`, fullPage: true })
    await page.unroute('**/api/auth/me')
    await nicknameInput.fill(' 去掉两侧空白 ')
    await page.getByRole('button', { name: '保存昵称', exact: true }).click()
    await page.getByText('昵称已更新。', { exact: true }).waitFor()
    assert.equal((await api('/auth/me', regularSession.accessToken)).nickname, '去掉两侧空白')
    await nicknameInput.fill(longNickname)
    await page.getByRole('button', { name: '保存昵称', exact: true }).click()
    await page.getByText('昵称已更新。', { exact: true }).waitFor()
    assert.equal((await api('/auth/me', regularSession.accessToken)).nickname, longNickname.trim())
    await page.reload()
    assert.equal(await page.getByLabel('昵称', { exact: true }).inputValue(), longNickname)
    await page.getByRole('link', { name: '← 返回空间选择', exact: true }).click()
    await page.getByText(longNickname, { exact: true }).first().waitFor()
    check('nickname-empty-failure-draft-trim-shared-user-and-refresh-persistence')
    await page.goto(`${frontendUrl}/profile`)
    await page.getByLabel('原密码', { exact: true }).fill('WrongPassword123')
    await page.getByLabel('新密码', { exact: true }).fill('NewBrowserPass123')
    await page.getByLabel('确认新密码', { exact: true }).fill('Mismatch123')
    await page.getByRole('button', { name: '修改密码并重新登录', exact: true }).click()
    await page.getByText('两次输入的新密码不一致', { exact: true }).waitFor()
    await page.getByLabel('确认新密码', { exact: true }).fill('NewBrowserPass123')
    await page.getByRole('checkbox', { name: '显示密码' }).check()
    assert.equal(await page.getByLabel('原密码', { exact: true }).getAttribute('type'), 'text')
    await page.getByRole('checkbox', { name: '显示密码' }).uncheck()
    await page.getByRole('button', { name: '修改密码并重新登录', exact: true }).click()
    await page
      .getByRole('alert')
      .filter({ hasText: /原密码|旧密码/ })
      .waitFor()
    assert.equal(await page.getByLabel('新密码', { exact: true }).inputValue(), 'NewBrowserPass123')
    await page.screenshot({ path: `${output}/after-password-invalid-320.png`, fullPage: true })
    check('password-confirmation-visibility-and-real-wrong-current-password-preserves-draft')
    const nickDelay = await delayed(page, '**/api/auth/me', 'PATCH')
    await page.getByLabel('昵称', { exact: true }).fill('离页后仍可读取的昵称')
    await page.getByRole('button', { name: '保存昵称', exact: true }).click()
    assert.equal(await nickDelay.pending, 200)
    assert.equal(await page.getByLabel('昵称', { exact: true }).isDisabled(), true)
    assert.equal(
      await page.getByRole('button', { name: '修改密码并重新登录', exact: true }).isDisabled(),
      true,
    )
    await page.getByRole('button', { name: '正在保存…', exact: true }).evaluate((el) => {
      el.click()
      el.click()
    })
    assert.equal(nickDelay.count(), 1)
    await page.getByRole('link', { name: '← 返回空间选择', exact: true }).click()
    await page.waitForURL(`**/workspaces/${workspace.workspaceId}/overview`)
    nickDelay.release()
    await nickDelay.delivered
    await page.getByText('离页后仍可读取的昵称', { exact: true }).first().waitFor()
    await page.unroute('**/api/auth/me')
    assert.equal(await page.getByText('昵称已更新。', { exact: true }).count(), 0)
    check('nickname-one-patch-busy-lock-and-leaving-page-does-not-show-old-feedback')

    // 同一 SPA 中从 A 退出再登录 B，释放 A 的成功响应，不能覆盖 B 的会话。
    await page.goto(`${frontendUrl}/profile`)
    const crossNickname = await delayed(page, '**/api/auth/me', 'PATCH')
    await page.getByLabel('昵称', { exact: true }).fill('账号A迟到昵称')
    await page.getByRole('button', { name: '保存昵称', exact: true }).click()
    await crossNickname.pending
    await page.getByRole('link', { name: '← 返回空间选择', exact: true }).click()
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await page
      .getByRole('navigation', { name: '账号入口', exact: true })
      .getByRole('link', { name: '登录', exact: true })
      .click()
    await page.getByLabel('登录账号', { exact: true }).fill(usernames[1])
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page.getByRole('button', { name: '进入空间', exact: true }).click()
    await page.waitForURL('**/admin/users')
    crossNickname.release()
    await crossNickname.delivered
    await settle(page)
    await page.unroute('**/api/auth/me')
    assert.equal(
      await page.evaluate(
        () => JSON.parse(localStorage.getItem('stock-system-current-user')).username,
      ),
      usernames[1],
    )
    assert.equal(new URL(page.url()).pathname, '/admin/users')
    check('delayed-real-nickname-write-cannot-overwrite-new-admin-account')

    // 匿名登录请求离页后返回，不重新保存会话或从首页跳走。
    const anonymous = await browser.newContext({ reducedMotion: 'reduce' })
    const guest = await anonymous.newPage()
    guest.on('pageerror', (error) => errors.push(error.message))
    await guest.goto(`${frontendUrl}/login`)
    const loginDelay = await delayed(guest, '**/api/auth/login', 'POST')
    await guest.getByLabel('登录账号', { exact: true }).fill(usernames[0])
    await guest.getByLabel('密码', { exact: true }).fill(password)
    await guest.getByLabel('密码', { exact: true }).press('Enter')
    await loginDelay.pending
    await guest.getByRole('link', { name: '返回首页', exact: true }).click()
    await guest.waitForURL(frontendUrl + '/')
    loginDelay.release()
    await loginDelay.delivered
    await settle(guest)
    assert.equal(
      await guest.evaluate(() => localStorage.getItem('stock-system-access-token')),
      null,
    )
    assert.equal(new URL(guest.url()).pathname, '/')
    await guest.unroute('**/api/auth/login')
    check('delayed-login-after-leaving-form-does-not-save-session-or-navigate')
    await guest.goto(`${frontendUrl}/register`)
    await guest.getByLabel('登录账号', { exact: true }).fill(usernames[0])
    await guest.getByLabel('昵称', { exact: true }).fill('重复账号草稿')
    await guest.getByLabel('密码', { exact: true }).fill(password)
    await guest.getByLabel('确认密码', { exact: true }).fill(password)
    await guest.getByRole('button', { name: '创建账号', exact: true }).click()
    await guest
      .getByRole('alert')
      .filter({ hasText: /已存在|占用/ })
      .waitFor()
    assert.equal(await guest.getByLabel('昵称', { exact: true }).inputValue(), '重复账号草稿')
    await guest.setViewportSize({ width: 390, height: 1000 })
    await guest.screenshot({ path: `${output}/after-register-duplicate-390.png`, fullPage: true })
    check('real-duplicate-register-error-keeps-all-input')
    const duplicateDelay = await delayed(guest, '**/api/auth/register', 'POST')
    await guest.getByRole('button', { name: '创建账号', exact: true }).click()
    assert.equal(await duplicateDelay.pending, 409)
    await guest.getByRole('link', { name: '返回首页', exact: true }).click()
    await guest.waitForURL(frontendUrl + '/')
    duplicateDelay.release()
    await duplicateDelay.delivered
    await settle(guest)
    assert.equal(new URL(guest.url()).pathname, '/')
    assert.equal(await guest.getByRole('alert').count(), 0)
    check('delayed-registration-error-after-leaving-has-no-old-message-or-navigation')
    await guest.unroute('**/api/auth/register')
    await anonymous.close()

    // A 的改密已在后端成功，B 登录后才送达 A 的响应，不能清掉 B。
    await adminPage.getByRole('link', { name: '个人资料', exact: true }).click()
    // 以上页面仍是后台，单独 regular 浏览器通过真实登录进入资料。
    const passwordContext = await browser.newContext({ reducedMotion: 'reduce' })
    const passwordPage = await passwordContext.newPage()
    currentPage = passwordPage
    passwordPage.on('pageerror', (error) => errors.push(error.message))
    await login(passwordPage, usernames[0])
    await passwordPage.getByRole('link', { name: '个人资料', exact: true }).click()
    const passwordDelay = await delayed(passwordPage, '**/api/auth/password', 'POST')
    await passwordPage.getByLabel('原密码', { exact: true }).fill(password)
    await passwordPage.getByLabel('新密码', { exact: true }).fill('NewBrowserPass123')
    await passwordPage.getByLabel('确认新密码', { exact: true }).fill('NewBrowserPass123')
    await passwordPage.getByRole('button', { name: '修改密码并重新登录', exact: true }).click()
    assert.equal(await passwordDelay.pending, 200)
    const tokenA = await passwordPage.evaluate(() =>
      localStorage.getItem('stock-system-access-token'),
    )
    await passwordPage.getByRole('link', { name: '基点首页', exact: true }).click()
    // A 的旧凭证由后端拒绝，空间选择页清理会话；随后在同一 SPA 登录 B。
    await passwordPage
      .getByRole('navigation', { name: '账号入口', exact: true })
      .getByRole('link', { name: '进入研究空间', exact: true })
      .click()
    await passwordPage.waitForURL(
      (u) => u.pathname === '/login' && u.searchParams.get('reason') === 'expired',
    )
    await passwordPage.getByLabel('登录账号', { exact: true }).fill(usernames[1])
    await passwordPage.getByLabel('密码', { exact: true }).fill(password)
    await passwordPage.getByRole('button', { name: '进入空间', exact: true }).click()
    await passwordPage.waitForURL('**/admin/users')
    passwordDelay.release()
    await passwordDelay.delivered
    await settle(passwordPage)
    assert.equal(
      await passwordPage.evaluate(
        () => JSON.parse(localStorage.getItem('stock-system-current-user')).username,
      ),
      usernames[1],
    )
    assert.equal(new URL(passwordPage.url()).pathname, '/admin/users')
    assert.equal(
      (await fetch(`${backendUrl}/api/auth/me`, { headers: { Authorization: `Bearer ${tokenA}` } }))
        .status,
      401,
    )
    check('delayed-real-password-change-invalidates-old-token-without-clearing-new-account')
    await passwordContext.close()
    currentPage = adminPage

    // 成功改密的普通路径：清会话、提示、旧密码拒绝、新密码可登录。
    await adminPage.goto(`${frontendUrl}/profile`)
    await adminPage.getByLabel('原密码', { exact: true }).fill(password)
    await adminPage.getByLabel('新密码', { exact: true }).fill('NewAdminPass123')
    await adminPage.getByLabel('确认新密码', { exact: true }).fill('NewAdminPass123')
    await adminPage.getByRole('button', { name: '修改密码并重新登录', exact: true }).click()
    await adminPage.waitForURL(
      (u) => u.pathname === '/login' && u.searchParams.get('reason') === 'password-changed',
    )
    await adminPage.getByText('密码已更新，请使用新密码重新登录。', { exact: true }).waitFor()
    assert.equal(
      await adminPage.evaluate(() => localStorage.getItem('stock-system-access-token')),
      null,
    )
    assert.equal(await adminPage.getByLabel('登录账号', { exact: true }).inputValue(), usernames[1])
    await adminPage.getByLabel('密码', { exact: true }).fill(password)
    await adminPage.getByRole('button', { name: '进入空间', exact: true }).click()
    await adminPage
      .getByRole('alert')
      .filter({ hasText: /密码|凭据/ })
      .waitFor()
    await adminPage.getByLabel('密码', { exact: true }).fill('NewAdminPass123')
    await adminPage.getByRole('button', { name: '进入空间', exact: true }).click()
    await adminPage.waitForURL('**/admin/users')
    await adminPage.getByRole('link', { name: '个人资料', exact: true }).click()
    await adminPage.getByLabel('原密码', { exact: true }).waitFor()
    assert.equal(await adminPage.getByLabel('原密码', { exact: true }).inputValue(), '')
    check('password-success-clears-session-notice-old-password-rejected-and-new-login')
    await page.goto(`${frontendUrl}/profile`)
    // page 仍是另一管理员会话，此时改密后的旧凭证在刷新确认时应被清理。
    await page.waitForURL(
      (u) => u.pathname === '/login' && u.searchParams.get('reason') === 'expired',
    )
    check('other-admin-browser-old-session-rejected-after-password-change')
    const motionContext = await browser.newContext({ reducedMotion: 'no-preference' })
    const motionPage = await motionContext.newPage()
    motionPage.on('pageerror', (error) => errors.push(error.message))
    for (const [name, width, height] of [
      ['login', 1440, 720],
      ['register', 1440, 1440],
      ['register', 320, 640],
    ]) {
      await motionPage.setViewportSize({ width, height })
      await motionPage.goto(`${frontendUrl}/${name}`)
      await motionPage.getByLabel('登录账号', { exact: true }).waitFor()
      assert.equal(
        await motionPage.evaluate(() => document.documentElement.scrollWidth > innerWidth),
        false,
      )
      assert.equal(
        await motionPage
          .getByRole('button', { name: name === 'login' ? '进入空间' : '创建账号', exact: true })
          .isVisible(),
        true,
      )
      await motionPage.screenshot({
        path: `${output}/after-${name}-${width}x${height}.png`,
        fullPage: true,
      })
      check(`motion-${name}-${width}x${height}`)
    }
    await motionContext.close()
  }
  const reference = await context.newPage()
  for (const name of ['login', 'register', 'profile']) {
    await reference.goto(
      `${pathToFileURL(resolve('../../ui-preview/stock-workspace-demo-v2-content-aligned.html')).href}#/${name}`,
    )
    await reference.locator(`#page-${name}`).waitFor()
    for (const width of [1440, 390]) {
      await reference.setViewportSize({ width, height: 1000 })
      await reference.screenshot({
        path: `${output}/reference-${name}-${width}.png`,
        fullPage: true,
      })
    }
  }
  await reference.close()
  await adminContext.close()
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
    assert.ok(usernames.every((name) => /^ui08_[om]_[a-z0-9]+$/.test(name)))
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
