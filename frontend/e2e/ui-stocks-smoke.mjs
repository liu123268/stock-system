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
const output = resolve(process.env.E2E_UI_OUTPUT ?? '../../ui-preview/ui-02')
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
    await page.waitForTimeout(100)
    assert.equal(
      await page.evaluate(() => document.documentElement.scrollWidth > innerWidth),
      false,
      name,
    )
    await page.screenshot({ path: `${output}/${phase}-${name}-${width}.png`, fullPage: true })
    checks.push({ name: `${name}-${width}`, passed: true })
  }
}
async function pushQuery(page, q) {
  await page.evaluate((q) => {
    const router = document.querySelector('#app').__vue_app__.config.globalProperties.$router
    return router.push({ query: { ...router.currentRoute.value.query, q } })
  }, q)
}
try {
  await mkdir(output, { recursive: true })
  for (const [index, username] of usernames.entries()) {
    await api('/auth/register', null, 'POST', {
      username,
      nickname: index ? '观点成员·长期现金流研究小组' : '验收所有者',
      password,
      confirmPassword: password,
    })
  }
  const owner = await api('/auth/login', null, 'POST', { username: usernames[0], password })
  const member = await api('/auth/login', null, 'POST', { username: usernames[1], password })
  const space = await api('/workspaces', owner.accessToken, 'POST', { name: workspaceName })
  const workspaceId = space.workspaceId
  const base = `/workspaces/${workspaceId}/stocks`
  const invitation = await api(
    `/workspaces/${workspaceId}/invitations`,
    owner.accessToken,
    'POST',
    { expectedInvitationId: null },
  )
  await api('/workspaces/join', member.accessToken, 'POST', { inviteCode: invitation.inviteCode })
  const initial = {
    stance: 'NEUTRAL',
    reason: 'UI-02 验收初始观点。',
    observationNote: null,
    horizon: 'LONG',
    targetPrice: null,
    stopLossPrice: null,
  }
  const tracked = []
  for (const code of codes)
    tracked.push(await api(base, owner.accessToken, 'POST', { ...initial, code }))
  const a = tracked[0],
    b = tracked[1],
    ended = tracked[2]
  await api(`${base}/${ended.workspaceStockId}/end`, owner.accessToken, 'POST', {
    researchCycleId: ended.researchCycleId,
  })
  await api(`${base}/${a.workspaceStockId}/opinions`, member.accessToken, 'POST', {
    ...initial,
    researchCycleId: a.researchCycleId,
    type: 'UPDATE',
    stance: 'BULLISH',
  })
  const withdrawn = await api(`${base}/${a.workspaceStockId}/opinions`, owner.accessToken, 'POST', {
    ...initial,
    researchCycleId: a.researchCycleId,
    type: 'UPDATE',
    stance: 'BEARISH',
  })
  await api(`${base}/${a.workspaceStockId}/opinions/${withdrawn.id}`, owner.accessToken, 'DELETE')
  const listUrl = `${frontendUrl}/workspaces/${workspaceId}/stocks`
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
  await page.goto(listUrl)
  const card = (id) =>
    page
      .locator('.stock-list article')
      .filter({ has: page.locator(`a[href="/workspaces/${workspaceId}/stocks/${id}"]`) })
  await card(a.workspaceStockId).waitFor()
  assert.match(await card(a.workspaceStockId).innerText(), /看多/)
  assert.match(await card(a.workspaceStockId).innerText(), /观点成员/)
  // 周期统计包含撤回占位；最近观点单独排除已撤回记录。
  assert.match(await card(a.workspaceStockId).innerText(), /本期 3 条观点 · 2 位成员参与/)
  await capture(page, 'stocks')
  const reference = await context.newPage()
  await reference.route('https://**', (route) => route.abort())
  await reference.goto(
    `${pathToFileURL(resolve('../../ui-preview/stock-workspace-demo-v2-content-aligned.html')).href}#/stocks`,
  )
  for (const width of [1440, 390]) {
    await reference.setViewportSize({ width, height: 1000 })
    await reference.screenshot({ path: `${output}/reference-stocks-${width}.png`, fullPage: true })
  }
  await reference.close()
  const submitSearch = async (q) => {
    await page.getByLabel('股票代码、名称或拼音首字母', { exact: true }).fill(q)
    await page.getByRole('button', { name: '搜索股票', exact: true }).click()
  }
  await submitSearch('腾讯')
  await page.locator('.search-results a').filter({ hasText: '00700.HK' }).waitFor()
  await capture(page, 'search', [1440, 390])
  if (phase === 'before') {
    await pushQuery(page, '600519')
    await page.waitForTimeout(300)
    checks.push({
      name: 'query-reuse-observation',
      keyword: await page.locator('#stock-keyword').inputValue(),
      query: new URL(page.url()).searchParams.get('q'),
    })
  } else {
    await pushQuery(page, '600519')
    await page.locator('.search-results a').filter({ hasText: '600519.SH' }).waitFor()
    assert.equal(await page.locator('#stock-keyword').inputValue(), '600519')
    await page.goBack()
    await page.locator('.search-results a').filter({ hasText: '00700.HK' }).waitFor()
    assert.equal(await page.locator('#stock-keyword').inputValue(), '腾讯')
    await page.goForward()
    await page.locator('.search-results a').filter({ hasText: '600519.SH' }).waitFor()
    await page.reload()
    await page.locator('.search-results a').filter({ hasText: '600519.SH' }).waitFor()
    await page.locator('.search-results a').filter({ hasText: '600519.SH' }).click()
    await page.locator('canvas').first().waitFor()
    await page.getByRole('link', { name: '返回搜索结果', exact: false }).click()
    await page.locator('.search-results a').filter({ hasText: '600519.SH' }).waitFor()
    checks.push({ name: 'query-reuse-back-forward-reload-market-return', passed: true })
    await submitSearch('没有这只股票xyz')
    await page.getByText('没有找到匹配的股票', { exact: false }).waitFor()
    await capture(page, 'search-empty', [1440, 320])
    await page.goto(listUrl)
    await card(a.workspaceStockId).getByRole('link', { name: '贵州茅台', exact: true }).click()
    await page.waitForURL(`**/stocks/${a.workspaceStockId}`)
    await page.goto(listUrl)
    await card(a.workspaceStockId).getByRole('link', { name: '查看全部观点', exact: false }).click()
    await page.waitForURL(`**/stocks/${a.workspaceStockId}#research-timeline`)
    await page.locator('#research-timeline').waitFor()
    await page.goto(listUrl)
    await page.locator('.ended-list a').filter({ hasText: '宁德时代' }).click()
    await page.waitForURL(`**/stocks/${ended.workspaceStockId}`)
    await page.goto(listUrl)
    checks.push({ name: 'research-timeline-and-ended-entry', passed: true })
    let writes = 0,
      failRead = false
    const attentionHandler = async (route) => {
      const req = route.request(),
        path = new URL(req.url()).pathname
      if (req.method() === 'PATCH' && path.endsWith('/attention')) {
        writes += 1
        const response = await route.fetch()
        assert.ok(response.ok())
        failRead = true
        await route.fulfill({ response })
      } else if (req.method() === 'GET' && path === `/api${base}` && failRead) {
        failRead = false
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({ code: 'UNAVAILABLE', message: '验收：列表刷新失败', data: null }),
        })
      } else await route.continue()
    }
    await page.route('**/api/workspaces/**/stocks**', attentionHandler)
    await card(a.workspaceStockId)
      .getByRole('button', { name: '设为重点关注', exact: true })
      .click()
    await page.getByText('关注级别已经保存，但共享自选刷新失败', { exact: false }).waitFor()
    await page.getByRole('button', { name: '只重新加载页面数据', exact: true }).click()
    await card(a.workspaceStockId)
      .getByRole('button', { name: '改为普通关注', exact: true })
      .waitFor()
    assert.equal(writes, 1)
    await page.unroute('**/api/workspaces/**/stocks**', attentionHandler)
    checks.push({ name: 'attention-saved-refresh-failure-read-only-retry', passed: true })
    await card(a.workspaceStockId)
      .getByRole('button', { name: '改为普通关注', exact: true })
      .click()
    await card(a.workspaceStockId)
      .getByRole('button', { name: '设为重点关注', exact: true })
      .waitFor()
    for (const id of [a.workspaceStockId, b.workspaceStockId, a.workspaceStockId]) {
      await card(id).getByRole('button', { name: '查看行情图', exact: true }).click()
      await page.locator('.market-panel canvas').waitFor()
      assert.equal(await page.locator('.market-panel').count(), 1)
      assert.equal(await card(id).locator('.market-panel').count(), 1)
      const seriesCode = await page.evaluate(() => document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('stock').candlestickSeries.code)
      assert.equal(seriesCode, id === a.workspaceStockId ? '600519.SH' : '00700.HK', '图表数据身份必须与展开股票一致')
    }
    await capture(page, 'inline-chart')
    await card(a.workspaceStockId).getByRole('button', { name: '收起行情图', exact: true }).click()
    assert.equal(await page.locator('.market-panel').count(), 0)
    checks.push({ name: 'single-inline-chart-both-switch-directions-collapse', passed: true })

    // 当前接口仍读真实 test 后端；只延迟返回，模拟响应顺序颠倒。
    let releaseSearch, searchStarted
    const searchGate = new Promise((resolve) => {
      releaseSearch = resolve
    })
    const started = new Promise((resolve) => {
      searchStarted = resolve
    })
    const searchHandler = async (route) => {
      if (new URL(route.request().url()).searchParams.get('keyword') === '腾讯') {
        const response = await route.fetch()
        searchStarted()
        await searchGate
        await route.fulfill({ response })
      } else await route.continue()
    }
    await page.route('**/api/stocks/search**', searchHandler)
    await pushQuery(page, '腾讯')
    await started
    await pushQuery(page, '600519')
    await page.locator('.search-results a').filter({ hasText: '600519.SH' }).waitFor()
    releaseSearch()
    await page.waitForTimeout(150)
    assert.equal(await page.locator('.search-results a').filter({ hasText: '00700.HK' }).count(), 0)
    await page.unroute('**/api/stocks/search**', searchHandler)
    checks.push({ name: 'delayed-old-search-cannot-refill-new-query', passed: true })

    for (const suffix of ['', '/ended']) {
      const path = `**/api${base}${suffix}`
      const fail = (route) =>
        route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'UNAVAILABLE',
            message: suffix ? '验收：历史读取失败' : '验收：列表读取失败',
            data: null,
          }),
        })
      await page.route(path, fail)
      await page.goto(listUrl)
      await page
        .getByText(suffix ? '验收：历史读取失败' : '验收：列表读取失败', { exact: true })
        .waitFor()
      await capture(page, suffix ? 'history-error' : 'list-error', [1440, 320])
      if (suffix) assert.equal(await page.locator('.stock-list article').count(), 2)
      else assert.equal(await page.getByText('共享自选还是空的', { exact: true }).count(), 0)
      await page.unroute(path, fail)
      await page
        .getByRole('button', { name: suffix ? '重新加载历史研究' : '重新加载列表', exact: true })
        .click()
      if (suffix) await page.locator('.ended-list a').waitFor()
      else await card(a.workspaceStockId).waitFor()
    }
    checks.push({ name: 'independent-list-history-errors-and-read-retries', passed: true })

    let releaseChart, chartStarted
    const chartGate = new Promise((resolve) => {
      releaseChart = resolve
    })
    const chartBegan = new Promise((resolve) => {
      chartStarted = resolve
    })
    const chartPath = `**/api${base}/${a.workspaceStockId}/candlesticks**`
    const chartHandler = async (route) => {
      const response = await route.fetch()
      chartStarted()
      await chartGate
      await route.fulfill({ response })
    }
    await page.route(chartPath, chartHandler)
    await card(a.workspaceStockId).getByRole('button', { name: '查看行情图', exact: true }).click()
    await chartBegan
    await card(b.workspaceStockId).getByRole('button', { name: '查看行情图', exact: true }).click()
    await page.locator('.market-panel canvas').waitFor()
    releaseChart()
    await page.waitForTimeout(150)
    assert.equal(await page.locator('.market-panel').count(), 1)
    assert.match(await page.locator('.market-title strong').innerText(), /腾讯控股/)
    const chartState = await page.evaluate(() => {
      const series = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('stock').candlestickSeries
      return { code: series.code, name: series.name, points: series.candlesticks.length }
    })
    assert.equal(chartState.code, '00700.HK', '标题属于 B 时，共享图表数据也必须属于 B')
    assert.equal(chartState.name, '腾讯控股')
    assert.ok(chartState.points > 0)
    await page.unroute(chartPath, chartHandler)
    await page.getByRole('button', { name: '分时', exact: true }).click()
    await page.locator('.market-panel canvas').waitFor()
    await capture(page, 'inline-intraday', [1440, 390, 320])
    await page.getByRole('button', { name: 'K 线', exact: true }).click()
    await page.locator('.market-panel canvas').waitFor()
    await card(b.workspaceStockId).getByRole('button', { name: '收起行情图', exact: true }).click()
    checks.push({ name: 'delayed-A-chart-cannot-overwrite-B', passed: true })

    const empty = await api('/workspaces', owner.accessToken, 'POST', { name: `空空间${runId}` })
    await page.evaluate(
      (id) =>
        document
          .querySelector('#app')
          .__vue_app__.config.globalProperties.$router.push(`/workspaces/${id}/stocks`),
      empty.workspaceId,
    )
    await page.getByText('共享自选还是空的', { exact: true }).waitFor()
    assert.equal(await page.locator('.search-results a').count(), 0)
    await capture(page, 'empty-list', [1440, 390, 320])
    await page.goto(listUrl)
    await card(a.workspaceStockId).waitFor()
    checks.push({ name: 'workspace-param-reuse-empty-and-old-search-cleanup', passed: true })

    await page.locator('#stock-keyword').focus()
    await page.keyboard.press('Tab')
    assert.equal(await page.getByRole('button', { name: '搜索股票', exact: true }).evaluate((element) => element === document.activeElement && getComputedStyle(element).outlineStyle !== 'none'), true)
    await page.keyboard.press('Shift+Tab')
    await page.locator('#stock-keyword').fill('腾讯')
    await page.keyboard.press('Enter')
    await page.locator('.search-results a').filter({ hasText: '00700.HK' }).waitFor()
    checks.push({ name: 'keyboard-search-submit-and-visible-focus', passed: true })

    await api(`/workspaces/${workspaceId}/archive`, owner.accessToken, 'POST')
    await page.reload()
    await page.locator('.archive-notice').waitFor()
    assert.equal(await page.locator('form[role="search"]').count(), 0)
    assert.equal(await page.locator('.attention-toggle').count(), 0)
    await card(a.workspaceStockId).getByRole('button', { name: '查看行情图', exact: true }).click()
    await page.locator('.market-panel canvas').waitFor()
    await capture(page, 'archived', [1440, 390, 320])
    checks.push({ name: 'archived-no-writes-history-and-chart-readable', passed: true })
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
