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
const output = resolve(process.env.E2E_UI_OUTPUT ?? '../../ui-preview/ui-05')
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
const codes = ['600519.SH', '00700.HK', '300750.SZ']
const stockExistedBefore = new Set(
  codes.filter((code) => Number(sql(`SELECT COUNT(*) FROM stock WHERE code='${code}'`)) > 0),
)

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
  for (const [index, username] of usernames.entries())
    await api('/auth/register', null, 'POST', {
      username,
      nickname: index ? '参与研究的成员昵称很长' : '空间所有者',
      password,
      confirmPassword: password,
    })
  const owner = await api('/auth/login', null, 'POST', { username: usernames[0], password })
  const member = await api('/auth/login', null, 'POST', { username: usernames[1], password })
  const space = await api('/workspaces', owner.accessToken, 'POST', { name: workspaceName })
  const emptySpace = await api('/workspaces', owner.accessToken, 'POST', { name: `空空间${runId}` })
  const id = space.workspaceId
  const invitation = await api(`/workspaces/${id}/invitations`, owner.accessToken, 'POST', {
    currentInvitationId: null,
  })
  await api('/workspaces/join', member.accessToken, 'POST', { inviteCode: invitation.inviteCode })
  const stockBase = `/workspaces/${id}/stocks`
  const initial = {
    stance: 'NEUTRAL',
    reason: '初始判断：关注经营质量。',
    observationNote: null,
    horizon: 'MEDIUM',
    targetPrice: null,
    stopLossPrice: null,
  }
  const first = await api(stockBase, owner.accessToken, 'POST', { code: codes[0], ...initial })
  for (let index = 0; index < 24; index++)
    await api(`${stockBase}/${first.workspaceStockId}/opinions`, member.accessToken, 'POST', {
      ...initial,
      researchCycleId: first.researchCycleId,
      type: index % 2 ? 'UPDATE' : 'REVIEW',
      reason: `较早记录${index}`,
    })
  const withdrawnReason = '撤销内容不得出现在概览正文'
  const withdrawn = await api(
    `${stockBase}/${first.workspaceStockId}/opinions`,
    member.accessToken,
    'POST',
    {
      ...initial,
      reason: withdrawnReason,
      stance: 'BULLISH',
      researchCycleId: first.researchCycleId,
      type: 'UPDATE',
    },
  )
  await api(
    `${stockBase}/${first.workspaceStockId}/opinions/${withdrawn.id}`,
    member.accessToken,
    'DELETE',
  )
  for (const attentionLevel of ['FOCUS', 'NORMAL'])
    await api(`${stockBase}/${first.workspaceStockId}/attention`, owner.accessToken, 'PATCH', {
      researchCycleId: first.researchCycleId,
      attentionLevel,
    })
  await api(`${stockBase}/${first.workspaceStockId}/end`, member.accessToken, 'POST', {
    researchCycleId: first.researchCycleId,
  })
  await api(stockBase, member.accessToken, 'POST', { code: codes[0], ...initial })
  const second = await api(stockBase, owner.accessToken, 'POST', { code: codes[1], ...initial })
  const secondTimeline = await api(
    `${stockBase}/${second.workspaceStockId}/research/timeline`,
    owner.accessToken,
  )
  await api(
    `${stockBase}/${second.workspaceStockId}/opinions/${secondTimeline.cycles[0].opinions[0].id}`,
    owner.accessToken,
    'DELETE',
  )
  await api(stockBase, owner.accessToken, 'POST', { code: codes[2], ...initial })
  const overview = await api(`/workspaces/${id}/overview`, owner.accessToken)
  assert.equal(overview.activeStockCount, 2)
  assert.equal(overview.recentActivities.length, 20)
  for (const event of [
    'STOCK_ADDED',
    'FOLLOW_RESTARTED',
    'OPINION_POSTED',
    'OPINION_WITHDRAWN',
    'ATTENTION_CHANGED',
    'FOLLOW_ENDED',
    'FOLLOW_CANCELLED',
  ])
    assert.ok(
      overview.recentActivities.some((item) => item.eventType === event),
      event,
    )
  assert.equal(JSON.stringify(overview).includes(withdrawnReason), false)
  assert.ok(
    overview.recentActivities
      .filter((item) => item.opinionWithdrawn)
      .every((item) => item.stance === null),
  )
  checks.push({
    name: 'real-count-twenty-events-all-types-and-withdrawn-content-hidden',
    passed: true,
  })
  browser = await chromium.launch({
    executablePath:
      process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: true,
  })
  const context = await browser.newContext({
    reducedMotion: 'reduce',
    permissions: ['clipboard-read', 'clipboard-write'],
  })
  const page = await context.newPage()
  currentPage = page
  page.on('pageerror', (error) => errors.push(error.message))
  const overviewUrl = (workspace = id) => `${frontendUrl}/workspaces/${workspace}/overview`
  await login(page, usernames[0])
  await page.goto(overviewUrl())
  await page.getByRole('heading', { name: workspaceName, exact: true }).waitFor()
  await page.locator('.current-invitation strong').waitFor()
  await page.locator('.recent-list li').first().waitFor()
  await capture(page, 'owner-overview')
  const memberContext = await browser.newContext({ reducedMotion: 'reduce' })
  const memberPage = await memberContext.newPage()
  memberPage.on('pageerror', (error) => errors.push(error.message))
  let memberInviteReads = 0
  memberPage.on('request', (request) => {
    if (request.url().includes(`/workspaces/${id}/invitations`)) memberInviteReads++
  })
  await login(memberPage, usernames[1])
  await memberPage.goto(overviewUrl())
  await memberPage
    .getByRole('heading', { name: '邀请码由空间 OWNER 管理。', exact: true })
    .waitFor()
  await capture(memberPage, 'member-overview', [1440, 390])
  assert.equal(memberInviteReads, 0)
  assert.equal(await memberPage.locator('.invitation-section').count(), 0)
  checks.push({ name: 'member-overview-does-not-request-or-reveal-owner-invitation', passed: true })
  await page.goto(overviewUrl(emptySpace.workspaceId))
  await page.getByText('还没有研究动态。前往共享自选添加第一只股票。', { exact: true }).waitFor()
  await capture(page, 'empty-overview', [1440, 390])
  if (phase === 'before') {
    const reference = await context.newPage()
    await reference.goto(
      pathToFileURL(resolve('../../ui-preview/stock-workspace-demo-v2-content-aligned.html')).href +
        '#/overview',
    )
    for (const width of [1440, 390]) {
      await reference.setViewportSize({ width, height: 1000 })
      await reference.screenshot({
        path: `${output}/reference-overview-${width}.png`,
        fullPage: true,
      })
    }
    await reference.close()
    await api(`/workspaces/${id}/archive`, owner.accessToken, 'POST')
    await page.goto(overviewUrl())
    await page.getByRole('heading', { name: '邀请和研究写入已经暂停。', exact: true }).waitFor()
    await capture(page, 'archived-overview', [1440, 390])
  }
  if (phase === 'after') {
    const overviewPath = `**/api/workspaces/${id}/overview`
    const invitationPath = `**/api/workspaces/${id}/invitations`
    const currentPath = `**/api/workspaces/${id}/invitations/current`
    const error503 = (message) => ({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'TEMPORARY_UNAVAILABLE', message, data: null }),
    })
    const waitOverview = async () => {
      await page.getByRole('heading', { name: workspaceName, exact: true }).waitFor()
      await page.locator('.recent-list li').first().waitFor()
    }
    const routeTo = (path) =>
      page.evaluate(
        (url) =>
          document.querySelector('#app').__vue_app__.config.globalProperties.$router.push(url),
        path,
      )
    const invitationCount = () =>
      Number(sql(`SELECT COUNT(*) FROM workspace_invitation WHERE workspace_id=${id}`))
    await page.goto(overviewUrl())
    await waitOverview()
    assert.equal(await page.locator('.recent-list li').count(), 20)
    assert.equal(
      await page
        .locator('body')
        .innerText()
        .then((text) => text.includes(withdrawnReason)),
      false,
    )
    assert.equal(await page.locator('[data-overview-count] dd').innerText(), '2 只')
    for (const event of [
      'STOCK_ADDED',
      'FOLLOW_RESTARTED',
      'OPINION_POSTED',
      'OPINION_WITHDRAWN',
      'ATTENTION_CHANGED',
      'FOLLOW_ENDED',
      'FOLLOW_CANCELLED',
    ]) {
      const link = page.locator(`[data-activity-type="${event}"] a`).first()
      const href = await link.getAttribute('href')
      await link.click()
      await page.locator('.research-header h1').waitFor()
      assert.ok(page.url().endsWith(href))
      await page.goBack()
      await waitOverview()
    }
    checks.push({
      name: 'all-event-types-link-to-real-research-and-browser-back-restores-overview',
      passed: true,
    })
    await page.setViewportSize({ width: 1440, height: 1100 })
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({ path: `${output}/after-overview-top-1440.png` })

    let releaseLoading
    const loadingGate = new Promise((resolve) => {
      releaseLoading = resolve
    })
    const holdOverview = async (route) => {
      const response = await route.fetch()
      await loadingGate
      await route.fulfill({ response })
    }
    await page.route(overviewPath, holdOverview)
    await page.reload()
    await page.getByText('正在读取近期动态', { exact: true }).waitFor()
    await page.locator('.current-invitation strong').waitFor()
    assert.equal(await page.locator('[data-overview-count] dd').innerText(), '读取中')
    await capture(page, 'loading-activity', [1440, 320])
    releaseLoading()
    await page.locator('.recent-list li').first().waitFor()
    await page.unroute(overviewPath, holdOverview)

    const failOverview = (route) => route.fulfill(error503('验收：近期动态读取失败'))
    await page.route(overviewPath, failOverview)
    await page.reload()
    await page.getByRole('button', { name: '重试近期动态', exact: true }).waitFor()
    await page.locator('.current-invitation strong').waitFor()
    assert.equal(await page.locator('[data-overview-count] dd').innerText(), '暂不可用')
    await capture(page, 'activity-error', [1440, 390])
    await page.unroute(overviewPath, failOverview)
    await page.getByRole('button', { name: '重试近期动态', exact: true }).click()
    await page.locator('.recent-list li').first().waitFor()
    checks.push({
      name: 'activity-loading-and-error-do-not-block-invitations-or-show-false-zero',
      passed: true,
    })

    const failHistory = (route) =>
      route.request().method() === 'GET'
        ? route.fulfill(error503('验收：邀请历史读取失败'))
        : route.continue()
    await page.route(invitationPath, failHistory)
    await page.reload()
    await page.getByRole('button', { name: '只重新读取邀请信息', exact: true }).waitFor()
    await page.locator('.current-invitation strong').waitFor()
    await capture(page, 'invitation-history-error', [1440, 390])
    await page.unroute(invitationPath, failHistory)
    await page.getByRole('button', { name: '只重新读取邀请信息', exact: true }).click()
    await page.waitForFunction(() => !document.querySelector('.invitation-error'))
    const failCurrent = (route) => route.fulfill(error503('验收：当前邀请码读取失败'))
    await page.route(currentPath, failCurrent)
    await page.reload()
    await page.getByRole('button', { name: '只重新读取邀请信息', exact: true }).waitFor()
    assert.equal(
      await page
        .getByText('当前没有邀请码。生成后可供最多 5 名成员加入。', { exact: true })
        .count(),
      0,
    )
    assert.ok(await page.locator('.history-row').count())
    await capture(page, 'invitation-current-error', [390])
    await page.unroute(currentPath, failCurrent)
    await page.getByRole('button', { name: '只重新读取邀请信息', exact: true }).click()
    await page.locator('.current-invitation strong').waitFor()
    checks.push({
      name: 'invitation-current-and-history-partial-failures-preserve-successful-data',
      passed: true,
    })

    await page.getByRole('button', { name: '复制邀请码', exact: true }).click()
    await page.getByText('邀请码已复制', { exact: true }).waitFor()
    const displayedCode = await page.locator('.current-invitation strong').innerText()
    assert.equal(await page.evaluate(() => navigator.clipboard.readText()), displayedCode)
    await page.evaluate(() =>
      Object.defineProperty(navigator.clipboard, 'writeText', {
        configurable: true,
        value: async () => {
          throw new Error('clipboard denied')
        },
      }),
    )
    await page.getByRole('button', { name: '复制邀请码', exact: true }).click()
    await page.getByText('浏览器未允许复制，请选中上方邀请码手动复制。', { exact: true }).waitFor()
    checks.push({ name: 'clipboard-success-and-denied-manual-copy-feedback', passed: true })

    let invitationPosts = 0
    page.on('request', (request) => {
      if (request.url().endsWith(`/workspaces/${id}/invitations`) && request.method() === 'POST')
        invitationPosts++
    })
    const countBeforeCreate = invitationCount()
    await page.route(invitationPath, failHistory)
    await page.getByRole('button', { name: '重新生成邀请码', exact: true }).click()
    await page.getByText('新的邀请码已经生成', { exact: true }).waitFor()
    const savedCode = await page.locator('.current-invitation strong').innerText()
    assert.notEqual(savedCode, displayedCode)
    assert.equal(invitationCount(), countBeforeCreate + 1)
    await capture(page, 'saved-invitation-refresh-error', [1440, 390])
    await page.getByRole('button', { name: '只重新读取邀请信息', exact: true }).click()
    await page.getByRole('button', { name: '只重新读取邀请信息', exact: true }).waitFor()
    assert.equal(invitationPosts, 1)
    await page.unroute(invitationPath, failHistory)
    await page.getByRole('button', { name: '只重新读取邀请信息', exact: true }).click()
    await page.waitForFunction(() => !document.querySelector('.invitation-error'))
    assert.equal(invitationPosts, 1)
    assert.equal(await page.locator('.current-invitation strong').innerText(), savedCode)
    checks.push({ name: 'saved-invitation-refresh-failure-retries-get-only', passed: true })

    await page.route(currentPath, failCurrent)
    await page.route(invitationPath, failHistory)
    await page.getByRole('button', { name: '停用当前邀请码', exact: true }).click()
    await page.getByText('邀请码已经停用', { exact: true }).waitFor()
    assert.equal(await page.locator('.current-invitation').count(), 0)
    assert.equal(
      Number(
        sql(
          `SELECT COUNT(*) FROM workspace_invitation WHERE workspace_id=${id} AND status='ACTIVE'`,
        ),
      ),
      0,
    )
    await capture(page, 'revoked-invitation-refresh-error', [1440, 390])
    await page.unroute(currentPath, failCurrent)
    await page.unroute(invitationPath, failHistory)
    await page.getByRole('button', { name: '只重新读取邀请信息', exact: true }).click()
    await page.getByText('当前没有邀请码。生成后可供最多 5 名成员加入。', { exact: true }).waitFor()
    checks.push({ name: 'successful-revocation-stays-cleared-when-get-fails', passed: true })

    const failPost = (route) =>
      route.request().method() === 'POST'
        ? route.fulfill(error503('验收：邀请码生成失败'))
        : route.continue()
    await page.route(invitationPath, failPost)
    const countBeforeFailure = invitationCount()
    await page.getByRole('button', { name: '生成邀请码', exact: true }).click()
    await page.getByRole('button', { name: '重新核对空间与邀请信息', exact: true }).waitFor()
    assert.equal(invitationCount(), countBeforeFailure)
    await capture(page, 'invitation-submit-error', [390])
    await page.unroute(invitationPath, failPost)
    await page.getByRole('button', { name: '重新核对空间与邀请信息', exact: true }).click()
    await page.getByRole('button', { name: '生成邀请码', exact: true }).click()
    await page.getByText('新的邀请码已经生成', { exact: true }).waitFor()
    const staleInvitation = await api(`/workspaces/${id}/invitations/current`, owner.accessToken)
    const concurrentInvitation = await api(
      `/workspaces/${id}/invitations`,
      owner.accessToken,
      'POST',
      { currentInvitationId: staleInvitation.invitationId },
    )
    await page.getByRole('button', { name: '重新生成邀请码', exact: true }).click()
    await page.getByText('邀请码已经变化，请刷新后重试', { exact: true }).waitFor()
    const conflictCount = invitationCount()
    await page.getByRole('button', { name: '重新核对空间与邀请信息', exact: true }).click()
    await page.locator('.current-invitation strong').waitFor()
    assert.equal(
      await page.locator('.current-invitation strong').innerText(),
      concurrentInvitation.inviteCode,
    )
    assert.equal(invitationCount(), conflictCount)
    checks.push({
      name: 'failed-generation-and-concurrent-invitation-change-do-not-repeat-post',
      passed: true,
    })

    const secondInvitation = await api(
      `/workspaces/${emptySpace.workspaceId}/invitations`,
      owner.accessToken,
      'POST',
      { currentInvitationId: null },
    )
    let releasePost, startedPost
    const postGate = new Promise((resolve) => {
      releasePost = resolve
    })
    const postBegan = new Promise((resolve) => {
      startedPost = resolve
    })
    const delayedPost = async (route) => {
      if (route.request().method() !== 'POST') return route.continue()
      const response = await route.fetch()
      assert.ok(response.ok())
      startedPost()
      await postGate
      await route.fulfill({ response })
    }
    const beforeDelayedPosts = invitationPosts
    await page.route(invitationPath, delayedPost)
    await page.getByRole('button', { name: '重新生成邀请码', exact: true }).click()
    await postBegan
    assert.equal(
      await page.getByRole('button', { name: '正在处理…', exact: true }).isDisabled(),
      true,
    )
    assert.equal(
      await page.getByRole('button', { name: '复制邀请码', exact: true }).isDisabled(),
      true,
    )
    await page
      .getByRole('button', { name: '正在处理…', exact: true })
      .evaluate((button) => button.click())
    await routeTo(`/workspaces/${emptySpace.workspaceId}/overview`)
    await page.locator('.current-invitation strong').waitFor()
    assert.equal(
      await page.locator('.current-invitation strong').innerText(),
      secondInvitation.inviteCode,
    )
    const latePost = page.waitForResponse(
      (r) => r.request().method() === 'POST' && r.url().endsWith(`/workspaces/${id}/invitations`),
    )
    releasePost()
    await latePost
    await page.waitForTimeout(80)
    assert.equal(invitationPosts, beforeDelayedPosts + 1)
    assert.equal(
      await page.locator('.current-invitation strong').innerText(),
      secondInvitation.inviteCode,
    )
    assert.equal(await page.locator('.invitation-message, .invitation-error').count(), 0)
    await page.unroute(invitationPath, delayedPost)
    checks.push({
      name: 'busy-controls-and-late-generation-preserve-new-workspace-invitation',
      passed: true,
    })

    let releaseOld, startedOld
    const oldGate = new Promise((resolve) => {
      releaseOld = resolve
    })
    const oldBegan = new Promise((resolve) => {
      startedOld = resolve
    })
    const delayedOverview = async (route) => {
      startedOld()
      await oldGate
      await route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'WORKSPACE_MEMBERSHIP_INACTIVE',
          message: '旧概览响应',
          data: null,
        }),
      })
    }
    await page.route(overviewPath, delayedOverview)
    await routeTo(`/workspaces/${id}/overview`)
    await oldBegan
    await routeTo(`/workspaces/${id}/stocks`)
    await page.getByRole('heading', { name: '共享自选', exact: true }).waitFor()
    const oldResponse = page.waitForResponse((r) => r.url().endsWith(`/workspaces/${id}/overview`))
    releaseOld()
    await oldResponse
    await page.waitForTimeout(80)
    assert.ok(page.url().endsWith(`/workspaces/${id}/stocks`))
    await page.unroute(overviewPath, delayedOverview)
    checks.push({ name: 'same-workspace-leave-ignores-late-overview-access-error', passed: true })

    await api(`/workspaces/${id}/ownership-transfer`, owner.accessToken, 'POST', {
      targetUserId: member.userId,
    })
    await page.goto(overviewUrl())
    await page.getByRole('heading', { name: '邀请码由空间 OWNER 管理。', exact: true }).waitFor()
    assert.equal(await page.locator('.current-invitation').count(), 0)
    await memberPage.reload()
    await memberPage.locator('.current-invitation strong').waitFor()
    checks.push({
      name: 'reentry-revalidates-transferred-owner-and-hides-old-owner-invitation',
      passed: true,
    })
    await api(`/workspaces/${id}/archive`, member.accessToken, 'POST')
    await page.reload()
    await page.getByRole('heading', { name: '邀请和研究写入已经暂停。', exact: true }).waitFor()
    await page.locator('.recent-list li').first().waitFor()
    assert.equal(await page.locator('.invitation-section').count(), 0)
    await capture(page, 'archived-overview', [1440, 390, 320])
    await memberPage.reload()
    await memberPage
      .getByRole('heading', { name: '邀请和研究写入已经暂停。', exact: true })
      .waitFor()
    assert.equal(await memberPage.locator('.current-invitation').count(), 0)
    checks.push({
      name: 'archived-owner-and-member-read-counts-and-history-without-invitation-writes',
      passed: true,
    })
  }

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
      ${codes
        .filter((code) => !stockExistedBefore.has(code))
        .map(
          (code) =>
            `DELETE FROM stock WHERE code='${code}' AND source='LOCAL_MOCK' AND NOT EXISTS(SELECT 1 FROM workspace_stock WHERE stock_id=stock.id);`,
        )
        .join('\n')}
      SELECT COUNT(*) FROM app_user WHERE username IN (${users});
      SELECT COUNT(*) FROM workspace WHERE created_by_user_id=@ui_o;
      SELECT COUNT(*) FROM workspace_member WHERE workspace_id IN (SELECT id FROM ui_spaces);
      SELECT COUNT(*) FROM workspace_invitation WHERE workspace_id IN (SELECT id FROM ui_spaces);
      SELECT COUNT(*) FROM workspace_stock WHERE id IN (SELECT id FROM ui_stocks);
      SELECT COUNT(*) FROM research_cycle WHERE id IN (SELECT id FROM ui_cycles);
      SELECT COUNT(*) FROM stock_opinion WHERE research_cycle_id IN (SELECT id FROM ui_cycles);
      SELECT COUNT(*) FROM research_attention_change WHERE research_cycle_id IN (SELECT id FROM ui_cycles);
      ${codes
        .filter((code) => !stockExistedBefore.has(code))
        .map((code) => `SELECT COUNT(*) FROM stock WHERE code='${code}' AND source='LOCAL_MOCK';`)
        .join('\n')}
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
