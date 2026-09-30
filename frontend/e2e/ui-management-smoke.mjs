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
const output = resolve(process.env.E2E_UI_OUTPUT ?? '../../ui-preview/ui-06')
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
  const second = await api('/workspaces', owner.accessToken, 'POST', { name: `第二空间${runId}` })
  const id = space.workspaceId
  const invitation = await api(`/workspaces/${id}/invitations`, owner.accessToken, 'POST', {
    currentInvitationId: null,
  })
  await api('/workspaces/join', member.accessToken, 'POST', { inviteCode: invitation.inviteCode })
  const secondInvite = await api(
    `/workspaces/${second.workspaceId}/invitations`,
    owner.accessToken,
    'POST',
    { currentInvitationId: null },
  )
  await api('/workspaces/join', member.accessToken, 'POST', { inviteCode: secondInvite.inviteCode })
  browser = await chromium.launch({
    executablePath:
      process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: true,
  })
  const context = await browser.newContext({ reducedMotion: 'reduce' })
  const page = await context.newPage()
  currentPage = page
  page.on('pageerror', (error) => errors.push(error.message))
  const memberContext = await browser.newContext({ reducedMotion: 'reduce' })
  const memberPage = await memberContext.newPage()
  memberPage.on('pageerror', (error) => errors.push(error.message))
  const url = (section, workspace = id) => `${frontendUrl}/workspaces/${workspace}/${section}`
  const routeTo = (p, section, workspace = id) =>
    p.evaluate(
      (path) =>
        document.querySelector('#app').__vue_app__.config.globalProperties.$router.push(path),
      `/workspaces/${workspace}/${section}`,
    )
  const waitMembers = (p) => p.locator('.member-row').first().waitFor()
  const waitSettings = (p) => p.locator('.status-section').waitFor()
  const row = (p, username) => p.locator('.member-row').filter({ hasText: `@${username}` })
  const check = (name) => checks.push({ name, passed: true })
  await login(page, usernames[0])
  await page.goto(url('members'))
  await waitMembers(page)
  await capture(page, 'owner-members')
  await row(page, usernames[1]).getByRole('button', { name: '转让所有权' }).click()
  await capture(page, 'transfer-confirmation', [1440, 390, 320])
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await login(memberPage, usernames[1])
  await memberPage.goto(url('members'))
  await waitMembers(memberPage)
  assert.equal(await memberPage.getByRole('button', { name: '移除成员', exact: true }).count(), 0)
  await capture(memberPage, 'member-members', [1440, 390])
  await page.goto(url('settings'))
  await waitSettings(page)
  await capture(page, 'owner-settings')
  await page.getByRole('button', { name: '归档这个空间', exact: true }).click()
  await capture(page, 'archive-confirmation', [1440, 390, 320])
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await memberPage.goto(url('settings'))
  await waitSettings(memberPage)
  assert.equal(await memberPage.getByRole('button', { name: '保存名称', exact: true }).count(), 0)
  await capture(memberPage, 'member-settings', [1440, 390])
  if (phase === 'after') {
    const failRead = async (route) => {
      if (route.request().method() !== 'GET') return route.continue()
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'SERVICE_UNAVAILABLE', message: '验收读取失败', data: null }),
      })
    }
    const memberPath = `**/api/workspaces/${id}/members`
    const detailPath = `**/api/workspaces/${id}`
    await routeTo(page, 'members')
    await waitMembers(page)
    assert.equal(
      await page.getByRole('button', { name: '退出这个空间', exact: true }).isDisabled(),
      true,
    )
    const removeButton = row(page, usernames[1]).getByRole('button', {
      name: '移除成员',
      exact: true,
    })
    await removeButton.focus()
    await page.keyboard.press('Enter')
    assert.equal(
      await page.locator('.confirmation').evaluate((el) => el === document.activeElement),
      true,
    )
    await page.getByRole('button', { name: '取消', exact: true }).click()
    assert.equal(await removeButton.evaluate((el) => el === document.activeElement), true)
    assert.equal(
      sql(
        `SELECT status FROM workspace_member WHERE workspace_id=${id} AND user_id=${member.userId}`,
      ),
      'ACTIVE',
    )
    check('owner-cannot-leave-and-keyboard-confirmation-cancel-restores-focus-without-write')

    await page.route(memberPath, failRead)
    await page.reload()
    await page.getByRole('heading', { name: '成员列表暂时没有加载出来' }).waitFor()
    await capture(page, 'members-read-failure', [390])
    await page.unroute(memberPath, failRead)
    await page.getByRole('button', { name: '重新加载', exact: true }).click()
    await waitMembers(page)
    check('initial-member-read-failure-only-read-retry')

    await row(page, usernames[1]).getByRole('button', { name: '转让所有权', exact: true }).click()
    await api(`/workspaces/${id}/leave`, member.accessToken, 'POST')
    await page.getByRole('button', { name: '确认转让所有权', exact: true }).click()
    await page.locator('.feedback.error').waitFor()
    assert.equal(await page.locator('.confirmation').count(), 1)
    assert.equal(sql(`SELECT owner_user_id FROM workspace WHERE id=${id}`), String(owner.userId))
    await page.getByRole('button', { name: '取消', exact: true }).click()
    await api('/workspaces/join', member.accessToken, 'POST', { inviteCode: invitation.inviteCode })
    await page.reload()
    await waitMembers(page)
    check('concurrent-target-left-transfer-rejected-keeps-confirmation-and-owner')

    let deletes = 0
    page.on('request', (r) => {
      if (r.method() === 'DELETE' && r.url().endsWith(`/members/${member.userId}`)) deletes++
    })
    await page.route(memberPath, failRead)
    await page.route(detailPath, failRead)
    await row(page, usernames[1]).getByRole('button', { name: '移除成员', exact: true }).click()
    await page.getByRole('button', { name: '确认移除成员', exact: true }).click()
    await page.locator('.refresh-warning').waitFor()
    assert.equal(
      sql(
        `SELECT status FROM workspace_member WHERE workspace_id=${id} AND user_id=${member.userId}`,
      ),
      'REMOVED',
    )
    assert.equal(await row(page, usernames[1]).count(), 0)
    await capture(page, 'removed-refresh-failure', [1440, 390])
    await page.getByRole('button', { name: '重新读取', exact: true }).click()
    await page.getByRole('heading', { name: '成员列表暂时没有加载出来' }).waitFor()
    assert.equal(deletes, 1)
    await page.unroute(memberPath, failRead)
    await page.unroute(detailPath, failRead)
    await page.getByRole('button', { name: '重新加载', exact: true }).click()
    await waitMembers(page)
    assert.equal(deletes, 1)
    const oldInviteResponse = await fetch(`${backendUrl}/api/workspaces/join`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${member.accessToken}`,
      },
      body: JSON.stringify({ inviteCode: invitation.inviteCode }),
    })
    assert.equal(oldInviteResponse.status, 403)
    assert.equal((await oldInviteResponse.json()).code, 'REMOVED_MEMBER_REQUIRES_NEW_INVITATION')
    const fresh = await api(`/workspaces/${id}/invitations`, owner.accessToken, 'POST', {
      currentInvitationId: invitation.invitationId,
    })
    await api('/workspaces/join', member.accessToken, 'POST', { inviteCode: fresh.inviteCode })
    await page.reload()
    await waitMembers(page)
    check(
      'real-remove-saved-read-failure-retry-deletes-once-and-old-invite-rejected-new-invite-joins',
    )

    let transferPosts = 0
    page.on('request', (r) => {
      if (r.method() === 'POST' && r.url().endsWith('/ownership-transfer')) transferPosts++
    })
    const listPath = '**/api/workspaces'
    await page.route(memberPath, failRead)
    await page.route(listPath, failRead)
    await row(page, usernames[1]).getByRole('button', { name: '转让所有权', exact: true }).click()
    await page.getByRole('button', { name: '确认转让所有权', exact: true }).click()
    await page.locator('.refresh-warning').waitFor()
    assert.equal(sql(`SELECT owner_user_id FROM workspace WHERE id=${id}`), String(member.userId))
    assert.equal(await page.getByRole('button', { name: '移除成员', exact: true }).count(), 0)
    assert.ok((await row(page, usernames[1]).innerText()).includes('OWNER'))
    await capture(page, 'transferred-refresh-failure', [390])
    await page.unroute(memberPath, failRead)
    await page.unroute(listPath, failRead)
    await page.getByRole('button', { name: '重新读取', exact: true }).click()
    await waitMembers(page)
    assert.equal(transferPosts, 1)
    await memberPage.goto(url('members'))
    await waitMembers(memberPage)
    assert.equal(
      await row(memberPage, usernames[0])
        .getByRole('button', { name: '转让所有权', exact: true })
        .count(),
      1,
    )
    await api(`/workspaces/${id}/ownership-transfer`, member.accessToken, 'POST', {
      targetUserId: owner.userId,
    })
    await page.reload()
    await waitMembers(page)
    check('real-transfer-saved-read-failure-only-get-retry-and-fresh-owner-permissions')

    let began, release
    const started = new Promise((resolve) => {
      began = resolve
    })
    const held = new Promise((resolve) => {
      release = resolve
    })
    const delayOldRead = async (route) => {
      began()
      await held
      await route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'WORKSPACE_MEMBERSHIP_INACTIVE',
          message: '旧成员页响应',
          data: null,
        }),
      })
    }
    await page.route(memberPath, delayOldRead)
    await routeTo(page, 'settings')
    await waitSettings(page)
    await routeTo(page, 'members')
    await started
    await routeTo(page, 'settings')
    await waitSettings(page)
    const received = page.waitForResponse((r) => r.url().endsWith(`/workspaces/${id}/members`))
    release()
    await received
    await page.waitForTimeout(80)
    assert.ok(page.url().endsWith('/settings'))
    await page.unroute(memberPath, delayOldRead)
    check('same-workspace-leave-members-ignores-old-access-error')

    await memberPage.goto(url('members'))
    await waitMembers(memberPage)
    await memberPage.route(listPath, failRead)
    await memberPage.getByRole('button', { name: '退出这个空间', exact: true }).click()
    await memberPage.getByRole('button', { name: '确认退出空间', exact: true }).click()
    await memberPage.waitForURL(
      (u) => u.pathname === '/workspaces' && u.searchParams.get('reason') === 'left',
    )
    assert.equal(
      sql(
        `SELECT status FROM workspace_member WHERE workspace_id=${id} AND user_id=${member.userId}`,
      ),
      'LEFT',
    )
    await memberPage.unroute(listPath, failRead)
    await api('/workspaces/join', member.accessToken, 'POST', { inviteCode: fresh.inviteCode })
    check('real-leave-success-list-failure-still-navigates-to-selection')

    await page.goto(url('settings'))
    await waitSettings(page)
    let renamePosts = 0
    page.on('request', (r) => {
      if (r.method() === 'PATCH' && new URL(r.url()).pathname === `/api/workspaces/${id}`)
        renamePosts++
    })
    const input = page.getByLabel('新名称', { exact: true })
    for (const [value, message] of [
      [' \u3000\t', '请输入空间名称'],
      ['长'.repeat(31), '移除空白后的名称最多 30 个字符'],
    ]) {
      await input.fill(value)
      await page.getByRole('button', { name: '保存名称', exact: true }).click()
      await page.getByText(message, { exact: true }).waitFor()
    }
    assert.equal(renamePosts, 0)
    await input.fill(second.name)
    await page.getByRole('button', { name: '保存名称', exact: true }).click()
    await page.locator('.rename-form .error').waitFor()
    assert.equal(await input.inputValue(), second.name)
    assert.equal(sql(`SELECT name FROM workspace WHERE id=${id}`), workspaceName)
    check('rename-empty-unicode-space-length-and-duplicate-preserve-draft')
    const renamed = `新名称${runId}·共同研究`
    await input.fill(` 新 名称${runId}\u3000·共同研究 `)
    await input.press('Enter')
    await page.getByRole('heading', { name: renamed, exact: true }).waitFor()
    assert.equal(await input.inputValue(), renamed)
    assert.equal(await page.locator('.switcher > span').innerText(), renamed)
    assert.equal(sql(`SELECT name FROM workspace WHERE id=${id}`), renamed)
    await routeTo(page, 'members')
    await waitMembers(page)
    await page.goBack()
    await waitSettings(page)
    await page.reload()
    await waitSettings(page)
    assert.equal(await input.inputValue(), renamed)
    check('real-rename-normalization-synchronizes-heading-sidebar-and-back-reload')

    await page.route(detailPath, async (route) => {
      if (route.request().method() !== 'PATCH') return route.continue()
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'SERVICE_UNAVAILABLE', message: '验收提交失败', data: null }),
      })
    })
    await input.fill(`保留草稿${runId}`)
    await page.getByRole('button', { name: '保存名称', exact: true }).click()
    await page.getByText('验收提交失败', { exact: true }).waitFor()
    assert.equal(await input.inputValue(), `保留草稿${runId}`)
    assert.equal(sql(`SELECT name FROM workspace WHERE id=${id}`), renamed)
    await page.unroute(detailPath)
    await input.fill(renamed)
    check('rename-write-failure-keeps-draft-and-original-database-name')

    await page.getByRole('button', { name: '归档这个空间', exact: true }).click()
    assert.equal(
      await page.locator('.confirmation').evaluate((el) => el === document.activeElement),
      true,
    )
    assert.equal(
      await page.getByRole('button', { name: '保存名称', exact: true }).isDisabled(),
      true,
    )
    await page.getByRole('button', { name: '取消', exact: true }).click()
    assert.equal(sql(`SELECT status FROM workspace WHERE id=${id}`), 'ACTIVE')
    check('archive-cancel-no-submit-and-confirmation-excludes-rename')

    let archivePosts = 0
    page.on('request', (r) => {
      if (r.method() === 'POST' && r.url().endsWith(`/workspaces/${id}/archive`)) archivePosts++
    })
    await page.route(listPath, failRead)
    await page.getByRole('button', { name: '归档这个空间', exact: true }).click()
    await page.getByRole('button', { name: '确认归档空间', exact: true }).click()
    await page.getByText('空间已归档，当前邀请码已停用。', { exact: true }).waitFor()
    await page.locator('.refresh-warning').waitFor()
    assert.equal(sql(`SELECT status FROM workspace WHERE id=${id}`), 'ARCHIVED_BY_OWNER')
    assert.equal(
      sql(`SELECT COUNT(*) FROM workspace_invitation WHERE workspace_id=${id} AND status='ACTIVE'`),
      '0',
    )
    assert.equal(await page.getByRole('button', { name: '保存名称', exact: true }).count(), 0)
    await capture(page, 'archived-list-refresh-failure', [1440, 390])
    await page.getByRole('button', { name: '重新读取', exact: true }).click()
    await page.getByRole('heading', { name: '空间设置暂时没有加载出来' }).waitFor()
    assert.equal(archivePosts, 1)
    await page.unroute(listPath, failRead)
    await page.getByRole('button', { name: '重新加载', exact: true }).click()
    await waitSettings(page)
    assert.equal(archivePosts, 1)
    await capture(page, 'owner-archived-settings', [1440, 390, 320])
    await memberPage.goto(url('members'))
    await waitMembers(memberPage)
    assert.equal(
      await memberPage.getByRole('button', { name: '退出这个空间', exact: true }).isDisabled(),
      false,
    )
    assert.equal(
      await memberPage.getByRole('button', { name: '转让所有权', exact: true }).count(),
      0,
    )
    await capture(memberPage, 'member-archived-members', [390])
    await page.getByRole('link', { name: '成员', exact: true }).click()
    await waitMembers(page)
    assert.equal(await page.getByRole('button', { name: '移除成员', exact: true }).count(), 0)
    assert.equal(
      await page.getByRole('button', { name: '退出这个空间', exact: true }).isDisabled(),
      true,
    )
    await routeTo(page, 'settings')
    await waitSettings(page)
    check('real-archive-revokes-code-read-retry-post-once-and-archived-member-owner-controls')
    await page.getByRole('button', { name: '恢复这个空间', exact: true }).click()
    await page.getByRole('button', { name: '确认恢复空间', exact: true }).click()
    await page.getByText('空间已恢复，可以继续邀请成员和写入研究数据。', { exact: true }).waitFor()
    assert.equal(sql(`SELECT status FROM workspace WHERE id=${id}`), 'ACTIVE')
    assert.equal(
      sql(`SELECT COUNT(*) FROM workspace_invitation WHERE workspace_id=${id} AND status='ACTIVE'`),
      '0',
    )
    check('real-owner-restore-does-not-reactivate-old-invitation')

    // 仅在本轮 test fixture 里设置治理来源；真实 API 验证 OWNER 无恢复权限。
    sql(
      `UPDATE workspace SET status='ARCHIVED_BY_SUPER_ADMIN',archived_at=NOW() WHERE id=${id} AND created_by_user_id=${owner.userId}`,
    )
    await page.reload()
    await waitSettings(page)
    assert.equal(await page.getByRole('button', { name: '恢复这个空间', exact: true }).count(), 0)
    assert.equal(await page.getByRole('button', { name: '保存名称', exact: true }).count(), 0)
    const deniedRestore = await fetch(`${backendUrl}/api/workspaces/${id}/restore`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${owner.accessToken}` },
    })
    assert.equal(deniedRestore.status, 403)
    await capture(page, 'governance-archived-settings', [1440, 390, 320])
    await memberPage.goto(url('settings'))
    await waitSettings(memberPage)
    assert.equal(
      await memberPage.getByRole('button', { name: '恢复这个空间', exact: true }).count(),
      0,
    )
    sql(
      `UPDATE workspace SET status='ACTIVE',archived_at=NULL WHERE id=${id} AND created_by_user_id=${owner.userId}`,
    )
    await page.reload()
    await waitSettings(page)
    check('governance-source-fixture-owner-and-member-readonly-real-restore-api-denied')

    await page.getByRole('button', { name: '归档这个空间', exact: true }).click()
    await api(`/workspaces/${id}/ownership-transfer`, owner.accessToken, 'POST', {
      targetUserId: member.userId,
    })
    await page.getByRole('button', { name: '确认归档空间', exact: true }).click()
    await page.locator('.feedback.error').waitFor()
    assert.equal(sql(`SELECT status FROM workspace WHERE id=${id}`), 'ACTIVE')
    await page.getByRole('button', { name: '取消', exact: true }).click()
    await page.reload()
    await waitSettings(page)
    assert.equal(await page.getByRole('button', { name: '归档这个空间', exact: true }).count(), 0)
    assert.equal(await page.getByRole('button', { name: '保存名称', exact: true }).count(), 0)
    await api(`/workspaces/${id}/ownership-transfer`, member.accessToken, 'POST', {
      targetUserId: owner.userId,
    })
    await page.reload()
    await waitSettings(page)
    check(
      'concurrent-ownership-change-rejects-old-archive-confirmation-and-reentry-updates-permissions',
    )

    const delayMutation = async (p, pattern, action, nextSection, expectedName) => {
      let ready, unblock
      const started = new Promise((resolve) => {
        ready = resolve
      })
      const gate = new Promise((resolve) => {
        unblock = resolve
      })
      let posts = 0
      const hold = async (route) => {
        if (!['POST', 'PATCH', 'DELETE'].includes(route.request().method())) return route.continue()
        posts++
        const response = await route.fetch()
        assert.equal(response.status(), 200)
        ready()
        await gate
        await route.fulfill({ response })
      }
      await p.route(pattern, hold)
      await action()
      await started
      assert.equal(
        (await p.getByRole('button', { name: '正在处理…', exact: true }).count()) +
          (await p.getByRole('button', { name: '正在保存…', exact: true }).count()),
        1,
      )
      // 原生点击 disabled 按钮也不能发第二次请求。
      await p
        .locator('button:disabled')
        .evaluateAll((buttons) => buttons.forEach((button) => button.click()))
      assert.equal(posts, 1)
      await routeTo(p, nextSection, second.workspaceId)
      await p.getByRole('heading', { name: expectedName, exact: true }).waitFor()
      const received = p.waitForResponse(
        (r) =>
          r.url().includes(pattern.replace('**', '')) &&
          ['POST', 'PATCH', 'DELETE'].includes(r.request().method()),
      )
      unblock()
      await received
      await p.waitForTimeout(100)
      assert.ok(p.url().includes(`/workspaces/${second.workspaceId}/`))
      assert.equal(await p.locator('.switcher > span').innerText(), expectedName)
      assert.equal(await p.locator('.feedback,.refresh-warning,.confirmation').count(), 0)
      await p.unroute(pattern, hold)
    }
    await delayMutation(
      page,
      `**/api/workspaces/${id}`,
      async () => {
        await input.fill(`迟到改名${runId}`)
        await page.getByRole('button', { name: '保存名称', exact: true }).click()
      },
      'settings',
      second.name,
    )
    assert.equal(await page.getByLabel('新名称', { exact: true }).inputValue(), second.name)
    check('delayed-real-rename-deduplicates-and-cannot-overwrite-space-b-form-or-feedback')
    await page.goto(url('settings'))
    await waitSettings(page)
    await delayMutation(
      page,
      `**/api/workspaces/${id}/archive`,
      async () => {
        await page.getByRole('button', { name: '归档这个空间', exact: true }).click()
        await page.getByRole('button', { name: '确认归档空间', exact: true }).click()
      },
      'settings',
      second.name,
    )
    assert.equal(await page.getByRole('button', { name: '归档这个空间', exact: true }).count(), 1)
    check('delayed-real-archive-cannot-archive-or-clear-space-b-state')
    await page.goto(url('settings'))
    await waitSettings(page)
    await delayMutation(
      page,
      `**/api/workspaces/${id}/restore`,
      async () => {
        await page.getByRole('button', { name: '恢复这个空间', exact: true }).click()
        await page.getByRole('button', { name: '确认恢复空间', exact: true }).click()
      },
      'settings',
      second.name,
    )
    check('delayed-real-restore-cannot-refill-space-b-with-space-a')
    await page.goto(url('members'))
    await waitMembers(page)
    await delayMutation(
      page,
      `**/api/workspaces/${id}/ownership-transfer`,
      async () => {
        await row(page, usernames[1])
          .getByRole('button', { name: '转让所有权', exact: true })
          .click()
        await page.getByRole('button', { name: '确认转让所有权', exact: true }).click()
      },
      'members',
      second.name,
    )
    assert.equal(
      await row(page, usernames[1]).getByRole('button', { name: '移除成员', exact: true }).count(),
      1,
    )
    check('delayed-real-transfer-cannot-change-space-b-owner-or-start-old-refresh')
    await api(`/workspaces/${id}/ownership-transfer`, member.accessToken, 'POST', {
      targetUserId: owner.userId,
    })
    await page.goto(url('members'))
    await waitMembers(page)
    await delayMutation(
      page,
      `**/api/workspaces/${id}/members/${member.userId}`,
      async () => {
        await row(page, usernames[1]).getByRole('button', { name: '移除成员', exact: true }).click()
        await page.getByRole('button', { name: '确认移除成员', exact: true }).click()
      },
      'members',
      second.name,
    )
    assert.equal(await page.locator('.member-row').count(), 2)
    check('delayed-real-removal-cannot-remove-space-b-members')
    const lastInvite = await api(`/workspaces/${id}/invitations`, owner.accessToken, 'POST', {
      currentInvitationId: null,
    })
    await api('/workspaces/join', member.accessToken, 'POST', { inviteCode: lastInvite.inviteCode })
    await memberPage.goto(url('members'))
    await waitMembers(memberPage)
    await delayMutation(
      memberPage,
      `**/api/workspaces/${id}/leave`,
      async () => {
        await memberPage.getByRole('button', { name: '退出这个空间', exact: true }).click()
        await memberPage.getByRole('button', { name: '确认退出空间', exact: true }).click()
      },
      'members',
      second.name,
    )
    assert.equal(await memberPage.locator('.member-row').count(), 2)
    check('delayed-real-leave-cannot-clear-space-b-or-navigate-away')
  }
  const reference = await context.newPage()
  for (const section of ['members', 'settings']) {
    await reference.goto(
      `${pathToFileURL(resolve('../../ui-preview/stock-workspace-demo-v2.html')).href}#/${section}`,
    )
    await reference.locator(`#view-${section}`).waitFor()
    for (const width of [1440, 390]) {
      await reference.setViewportSize({ width, height: 1000 })
      await reference.screenshot({
        path: `${output}/reference-${section}-${width}.png`,
        fullPage: true,
      })
    }
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
