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
const output = resolve(process.env.E2E_UI_OUTPUT ?? '../../ui-preview/ui-03')
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
    await page.waitForTimeout(80)
    if (phase === 'after' && (await page.locator('.market-panel canvas').count())) {
      const layouts = await page.evaluate(async () => {
        const { getInstanceByDom } = await import('/node_modules/.vite/deps/echarts_core.js')
        return [...document.querySelectorAll('.candlestick-chart, .intraday-chart')].map(
          (host) => ({
            width: host.clientWidth,
            left: getInstanceByDom(host).getOption().grid[0].left,
            intraday: host.classList.contains('intraday-chart'),
          }),
        )
      })
      for (const chart of layouts)
        assert.equal(chart.left, chart.width <= 480 ? 54 : chart.intraday ? 68 : 70)
    }
    assert.equal(
      await page.evaluate(() => document.documentElement.scrollWidth > innerWidth),
      false,
      name,
    )
    await page.screenshot({ path: `${output}/${phase}-${name}-${width}.png`, fullPage: true })
    checks.push({ name: `${name}-${width}`, passed: true })
  }
}
function researchCount(workspaceId) {
  assert.ok(Number.isSafeInteger(workspaceId))
  return Number(
    sql(
      `SELECT (SELECT COUNT(*) FROM workspace_stock WHERE workspace_id=${workspaceId}) + (SELECT COUNT(*) FROM research_cycle rc JOIN workspace_stock ws ON ws.id=rc.workspace_stock_id WHERE ws.workspace_id=${workspaceId}) + (SELECT COUNT(*) FROM stock_opinion op JOIN research_cycle rc ON rc.id=op.research_cycle_id JOIN workspace_stock ws ON ws.id=rc.workspace_stock_id WHERE ws.workspace_id=${workspaceId})`,
    ),
  )
}
try {
  await mkdir(output, { recursive: true })
  for (const [index, username] of usernames.entries())
    await api('/auth/register', null, 'POST', {
      username,
      nickname: index ? '行情成员' : '行情所有者',
      password,
      confirmPassword: password,
    })
  const owner = await api('/auth/login', null, 'POST', { username: usernames[0], password })
  const space = await api('/workspaces', owner.accessToken, 'POST', { name: workspaceName })
  const id = space.workspaceId
  const stockBase = `/workspaces/${id}/stocks`
  const marketUrl = (code) => `${frontendUrl}/workspaces/${id}/stocks/market/${code}?q=600519`
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
  let releaseInitial
  const initialGate = new Promise((resolve) => {
    releaseInitial = resolve
  })
  const initialPath = `**/api/workspaces/${id}/market/stocks/600519.SH`
  const initialHandler = async (route) => {
    const response = await route.fetch()
    await initialGate
    await route.fulfill({ response })
  }
  if (phase === 'after') await page.route(initialPath, initialHandler)
  await page.goto(marketUrl('600519.SH'))
  if (phase === 'after') {
    await page.getByText('正在读取股票行情', { exact: true }).waitFor()
    await capture(page, 'loading-market', [1440, 320])
    releaseInitial()
  }
  await page.locator('.candlestick-chart canvas').waitFor()
  if (phase === 'after') await page.unroute(initialPath, initialHandler)
  assert.equal(researchCount(id), 0, '单纯查看行情不能写研究表')
  await capture(page, 'market')
  const reference = await context.newPage()
  await reference.route('https://**', (route) => route.abort())
  await reference.goto(
    `${pathToFileURL(resolve('../../ui-preview/stock-workspace-demo-v2-content-aligned.html')).href}#/market`,
  )
  for (const width of [1440, 390]) {
    await reference.setViewportSize({ width, height: 1000 })
    await reference.screenshot({ path: `${output}/reference-market-${width}.png`, fullPage: true })
  }
  await reference.close()
  await page.getByRole('button', { name: '加入共享自选', exact: true }).click()
  await page.locator('.opinion-form').waitFor()
  assert.equal(
    await page
      .locator('#initial-opinion-title')
      .evaluate((element) => element === document.activeElement),
    true,
  )
  await capture(page, 'initial-form')
  await page.getByRole('button', { name: '取消填写', exact: true }).click()
  assert.equal(await page.locator('.opinion-form').count(), 0)
  assert.equal(
    await page
      .getByRole('button', { name: '加入共享自选', exact: true })
      .evaluate((element) => element === document.activeElement),
    true,
  )
  await page.getByRole('button', { name: '分时', exact: true }).click()
  await page.locator('.intraday-chart canvas').waitFor()
  await capture(page, 'intraday', [1440, 390, 320])
  assert.equal(researchCount(id), 0)
  checks.push({ name: 'read-only-market-form-cancel-focus', passed: true })
  if (phase === 'after') {
    await page.getByLabel('交易日', { exact: true }).fill('2026-09-03')
    await page.getByLabel('交易日', { exact: true }).dispatchEvent('change')
    await page.getByText('2026-09-03 ·', { exact: false }).waitFor()
    const historical = await page.evaluate(
      () =>
        document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('stock')
          .intradaySeries,
    )
    assert.equal(historical.updating, false)
    assert.equal(historical.tradeDate, '2026-09-03')
    assert.ok(historical.points.length > 0)
    await capture(page, 'historical-intraday', [1440, 320])
    await page.getByRole('button', { name: 'K 线', exact: true }).click()
    for (const [period, label] of [
      ['1m', '1 分'],
      ['5m', '5 分'],
      ['15m', '15 分'],
      ['30m', '30 分'],
      ['1h', '1 小时'],
      ['week', '周 K'],
      ['month', '月 K'],
      ['year', '年 K'],
      ['day', '日 K'],
    ]) {
      const response = page.waitForResponse(
        (r) =>
          new URL(r.url()).searchParams.get('period') === period &&
          r.url().includes('/candlesticks'),
      )
      await page.getByRole('button', { name: label, exact: true }).click()
      const result = await (await response).json()
      assert.equal(result.data.period, period)
      await page.locator('.candlestick-chart canvas').waitFor()
      assert.equal(
        await page.getByRole('button', { name: label, exact: true }).getAttribute('aria-pressed'),
        'true',
      )
    }
    checks.push({ name: 'all-nine-periods-today-and-historical-intraday', passed: true })

    // mock 首批仅 240 根；只补 hasMore/cursor 测试条件，更早数据仍来自真实后端。
    let olderCalls = 0
    const historyPath = `**/api/workspaces/${id}/market/stocks/600519.SH/candlesticks**`
    const historyHandler = async (route) => {
      const before = new URL(route.request().url()).searchParams.get('before')
      if (before && ++olderCalls === 1) {
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'UNAVAILABLE',
            message: '验收：更早历史暂不可用',
            data: null,
          }),
        })
        return
      }
      const response = await route.fetch()
      const result = await response.json()
      if (!before) {
        result.data.hasMore = true
        result.data.nextBefore = new Date(result.data.candlesticks[0].time).toISOString()
      }
      await route.fulfill({ response, json: result })
    }
    await page.route(historyPath, historyHandler)
    await page.goto(marketUrl('600519.SH'))
    await page.locator('.candlestick-chart canvas').waitFor()
    const reachStart = () =>
      page.evaluate(async () => {
        const charts = await import('/node_modules/.vite/deps/echarts_core.js')
        charts
          .getInstanceByDom(document.querySelector('.candlestick-chart'))
          .dispatchAction({ type: 'dataZoom', startValue: 0, endValue: 59 })
      })
    await reachStart()
    await page.getByText('验收：更早历史暂不可用', { exact: false }).waitFor()
    const firstSize = await page.evaluate(
      () =>
        document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('stock')
          .candlestickSeries.candlesticks.length,
    )
    assert.equal(firstSize, 240)
    await reachStart()
    await page.waitForFunction(
      () =>
        document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('stock')
          .candlestickSeries.candlesticks.length === 480,
    )
    const range = await page.evaluate(async () => {
      await new Promise(requestAnimationFrame)
      const charts = await import('/node_modules/.vite/deps/echarts_core.js')
      return charts.getInstanceByDom(document.querySelector('.candlestick-chart')).getOption()
        .dataZoom[0]
    })
    assert.ok(range.startValue >= 239 && range.startValue <= 241, '补历史后保留原来可见区间')
    assert.equal(olderCalls, 2)
    await page.unroute(historyPath, historyHandler)
    await page.goto(marketUrl('600519.SH'))
    await page.locator('.candlestick-chart canvas').waitFor()
    checks.push({ name: 'history-zoom-read-failure-retry-merge-and-preserved-range', passed: true })

    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.evaluate(async () => {
      const charts = await import('/node_modules/.vite/deps/echarts_core.js')
      charts
        .getInstanceByDom(document.querySelector('.candlestick-chart'))
        .dispatchAction({ type: 'showTip', seriesIndex: 0, dataIndex: 239 })
    })
    await page.waitForTimeout(80)
    const tooltip = await page.evaluate(() =>
      [...document.querySelectorAll('body > div')]
        .filter((element) => element.id !== 'app')
        .map((element) => ({
          text: element.textContent,
          bg: getComputedStyle(element).backgroundColor,
          visible: getComputedStyle(element).visibility,
          rect: element.getBoundingClientRect().toJSON(),
        }))
        .find((item) => item.text.includes('贵州茅台') && item.visible === 'visible'),
    )
    assert.ok(tooltip, '行情提示框应挂到 body 且可见')
    assert.equal(tooltip.bg, 'rgb(255, 253, 249)')
    assert.ok(tooltip.rect.left >= 0 && tooltip.rect.right <= 1440)
    checks.push({ name: 'body-tooltip-theme-and-visible-bounds', passed: true })

    const errors503 = (message) => ({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'UNAVAILABLE', message, data: null }),
    })
    for (const resource of ['candlesticks', 'intraday']) {
      const path = `**/api/workspaces/${id}/market/stocks/600519.SH/${resource}**`
      const fail = (route) => route.fulfill(errors503(`验收：${resource} 暂不可用`))
      await page.route(path, fail)
      await page.goto(marketUrl('600519.SH'))
      if (resource === 'intraday') {
        await page.locator('.candlestick-chart canvas').waitFor()
        await page.getByRole('button', { name: '分时', exact: true }).click()
      }
      await page.getByText(`验收：${resource} 暂不可用`, { exact: true }).waitFor()
      await capture(page, `${resource}-error`, [1440, 320])
      await page.unroute(path, fail)
      await page
        .getByRole('button', {
          name: resource === 'candlesticks' ? '重新加载 K 线' : '重新加载分时图',
          exact: true,
        })
        .click()
      await page.locator('.market-panel canvas').waitFor()
    }
    checks.push({ name: 'kline-intraday-errors-and-read-only-retry', passed: true })

    let releaseOld, oldStarted
    const oldGate = new Promise((resolve) => {
      releaseOld = resolve
    })
    const oldBegan = new Promise((resolve) => {
      oldStarted = resolve
    })
    const oldHandler = async (route) => {
      oldStarted()
      await oldGate
      await route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'WORKSPACE_ACCESS_DENIED',
          message: '旧股票页面的迟到失败',
          data: null,
        }),
      })
    }
    await page.route(initialPath, oldHandler)
    await page.goto(marketUrl('600519.SH'))
    await oldBegan
    await page.evaluate(
      (url) => document.querySelector('#app').__vue_app__.config.globalProperties.$router.push(url),
      `/workspaces/${id}/stocks/market/00700.HK?q=腾讯`,
    )
    await page.getByRole('heading', { name: '腾讯控股', exact: true }).waitFor()
    const oldResponse = page.waitForResponse(
      (r) => new URL(r.url()).pathname === `/api/workspaces/${id}/market/stocks/600519.SH`,
    )
    releaseOld()
    await oldResponse
    await page.waitForTimeout(80)
    assert.match(new URL(page.url()).pathname, /00700\.HK$/)
    const liveCode = await page.evaluate(
      () =>
        document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('stock')
          .marketDetail.code,
    )
    assert.equal(liveCode, '00700.HK')
    await page.unroute(initialPath, oldHandler)
    checks.push({
      name: 'market-param-reuse-late-old-failure-does-not-navigate-or-refill',
      passed: true,
    })

    await page.goto(marketUrl('600519.SH'))
    await page.getByRole('button', { name: '加入共享自选', exact: true }).click()
    await page.getByRole('button', { name: '加入关注并保存观点', exact: true }).click()
    await page.getByText('请填写判断理由', { exact: true }).waitFor()
    await page
      .getByLabel('判断理由（必填）', { exact: true })
      .fill('初始判断：观察长期现金流与经营质量。')
    await page.getByLabel('目标价（选填）', { exact: false }).fill('0')
    await page.getByRole('button', { name: '加入关注并保存观点', exact: true }).click()
    await page.getByText('目标价和止损参考价必须是大于 0 的数字', { exact: true }).waitFor()
    assert.equal(researchCount(id), 0)
    await page.getByLabel('目标价（选填）', { exact: false }).fill('')
    const failedSubmit = async (route) => {
      if (route.request().method() === 'POST') await route.fulfill(errors503('验收：提交暂时失败'))
      else await route.continue()
    }
    await page.route(`**/api${stockBase}`, failedSubmit)
    await page.getByRole('button', { name: '加入关注并保存观点', exact: true }).click()
    await page.getByText('验收：提交暂时失败', { exact: true }).waitFor()
    assert.equal(
      await page.getByLabel('判断理由（必填）', { exact: true }).inputValue(),
      '初始判断：观察长期现金流与经营质量。',
    )
    assert.equal(researchCount(id), 0)
    await capture(page, 'submit-error', [1440, 390])
    await page.unroute(`**/api${stockBase}`, failedSubmit)
    checks.push({ name: 'failed-submit-preserves-draft-and-writes-nothing', passed: true })
    let writes = 0,
      saved,
      blockReads = true
    const savedHandler = async (route) => {
      const req = route.request(),
        path = new URL(req.url()).pathname
      if (req.method() === 'POST' && path === `/api${stockBase}`) {
        writes += 1
        assert.equal(req.postDataJSON().targetPrice, null)
        assert.equal(req.postDataJSON().stopLossPrice, null)
        const response = await route.fetch()
        assert.ok(response.ok())
        saved = (await response.json()).data
        await route.fulfill({ response })
      } else if (
        blockReads &&
        saved &&
        req.method() === 'GET' &&
        (path === `/api${stockBase}` ||
          path === `/api${stockBase}/${saved.workspaceStockId}/research`)
      ) {
        await route.fulfill(errors503('验收：写入成功后的读取失败'))
      } else await route.continue()
    }
    await page.route('**/api/workspaces/**/stocks**', savedHandler)
    await page.getByRole('button', { name: '加入关注并保存观点', exact: true }).click()
    await page.waitForURL(/\/stocks\/\d+$/)
    await page
      .getByRole('heading', { name: '操作已经保存，页面暂未刷新', exact: true })
      .waitFor({ timeout: 5000 })
    await capture(page, 'saved-read-error', [1440, 390])
    assert.equal(writes, 1)
    assert.equal(researchCount(id), 3)
    blockReads = false
    await page.getByRole('button', { name: '重新加载页面数据', exact: true }).click()
    await page.locator('.research-header h1').filter({ hasText: '贵州茅台' }).waitFor()
    assert.equal(writes, 1)
    await page.unroute('**/api/workspaces/**/stocks**', savedHandler)
    checks.push({
      name: 'validation-empty-price-saved-but-read-failed-get-only-retry',
      passed: true,
    })
    const firstCycle = saved.researchCycleId
    await page.goto(marketUrl('600519.SH'))
    await page.getByRole('link', { name: '查看研究时间线', exact: true }).waitFor()
    assert.equal(await page.locator('.opinion-form').count(), 0)
    await api(`${stockBase}/${saved.workspaceStockId}/end`, owner.accessToken, 'POST', {
      researchCycleId: firstCycle,
    })
    await page.reload()
    await page.getByRole('button', { name: '重新加入关注', exact: true }).click()
    await page.getByLabel('判断理由（必填）', { exact: true }).fill('重新关注：新的观察周期。')
    await capture(page, 'reopen-form', [1440, 390])
    await page.getByRole('button', { name: '重新关注并保存观点', exact: true }).click()
    await page.waitForURL(/\/stocks\/\d+$/)
    await page.locator('.research-header h1').waitFor()
    const detail = await api(`${stockBase}/${saved.workspaceStockId}/research`, owner.accessToken)
    assert.notEqual(detail.currentCycle.id, firstCycle)
    assert.equal(
      Number(
        sql(
          `SELECT COUNT(*) FROM research_cycle WHERE workspace_stock_id=${saved.workspaceStockId}`,
        ),
      ),
      2,
    )
    checks.push({ name: 'active-study-entry-and-reopen-preserves-old-cycle', passed: true })

    await page.goto(marketUrl('300750.SZ'))
    await page.getByRole('button', { name: '加入共享自选', exact: true }).click()
    await page.getByLabel('判断理由（必填）', { exact: true }).fill('验证离页后的迟到提交。')
    let releaseWrite,
      writeStarted,
      delayedWrites = 0
    const writeGate = new Promise((resolve) => {
      releaseWrite = resolve
    })
    const writeBegan = new Promise((resolve) => {
      writeStarted = resolve
    })
    const delayedWrite = async (route) => {
      if (route.request().method() !== 'POST') return route.continue()
      delayedWrites += 1
      const response = await route.fetch()
      assert.ok(response.ok())
      writeStarted()
      await writeGate
      await route.fulfill({ response })
    }
    await page.route(`**/api${stockBase}`, delayedWrite)
    await page.getByRole('button', { name: '加入关注并保存观点', exact: true }).click()
    await writeBegan
    assert.equal(await page.getByLabel('判断理由（必填）', { exact: true }).isDisabled(), true)
    assert.equal(
      await page.getByRole('button', { name: '取消填写', exact: true }).isDisabled(),
      true,
    )
    await page
      .getByRole('button', { name: '正在保存…', exact: true })
      .evaluate((button) => button.click())
    await page.evaluate(
      (url) => document.querySelector('#app').__vue_app__.config.globalProperties.$router.push(url),
      `/workspaces/${id}/stocks/market/00700.HK`,
    )
    await page.getByRole('heading', { name: '腾讯控股', exact: true }).waitFor()
    const delayedResponse = page.waitForResponse(
      (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === `/api${stockBase}`,
    )
    releaseWrite()
    await delayedResponse
    await page.waitForTimeout(80)
    assert.match(new URL(page.url()).pathname, /00700\.HK$/)
    assert.equal(delayedWrites, 1)
    await page.unroute(`**/api${stockBase}`, delayedWrite)
    checks.push({
      name: 'busy-form-blocks-duplicate-and-late-write-does-not-navigate',
      passed: true,
    })

    const invitation = await api(`/workspaces/${id}/invitations`, owner.accessToken, 'POST', {
      expectedInvitationId: null,
    })
    const member = await api('/auth/login', null, 'POST', { username: usernames[1], password })
    await api('/workspaces/join', member.accessToken, 'POST', { inviteCode: invitation.inviteCode })
    await page.goto(marketUrl('00700.HK'))
    await page.getByRole('button', { name: '加入共享自选', exact: true }).click()
    await page.getByLabel('判断理由（必填）', { exact: true }).fill('所有者尚未提交的并发草稿。')
    const other = await api(stockBase, member.accessToken, 'POST', {
      code: '00700.HK',
      stance: 'NEUTRAL',
      reason: '成员先开启本轮。',
      observationNote: null,
      horizon: 'MEDIUM',
      targetPrice: null,
      stopLossPrice: null,
    })
    await page.getByRole('button', { name: '加入关注并保存观点', exact: true }).click()
    await page.waitForURL(`**/stocks/${other.workspaceStockId}`)
    await page.getByText('你的草稿已保留，可确认后作为观点发表', { exact: false }).waitFor()
    assert.equal(
      await page
        .locator('#opinion-compose')
        .getByLabel('判断理由（必填）', { exact: true })
        .inputValue(),
      '所有者尚未提交的并发草稿。',
    )
    const otherResearch = await api(
      `${stockBase}/${other.workspaceStockId}/research`,
      owner.accessToken,
    )
    assert.equal(
      Number(
        sql(
          `SELECT COUNT(*) FROM stock_opinion WHERE research_cycle_id=${otherResearch.currentCycle.id}`,
        ),
      ),
      1,
      '并发草稿不能自动追加为观点',
    )
    await page.locator('.market-panel canvas').waitFor()
    await capture(page, 'shared-research-panel', [1440, 390, 320])
    await page.goto(`${frontendUrl}/workspaces/${id}/stocks`)
    await page
      .locator('.stock-list article')
      .first()
      .getByRole('button', { name: '查看行情图', exact: true })
      .click()
    await page.locator('.market-panel canvas').waitFor()
    await capture(page, 'shared-list-panel', [1440, 390, 320])
    checks.push({ name: 'concurrent-member-cycle-keeps-draft-and-shared-consumers', passed: true })
    await api(`/workspaces/${id}/archive`, owner.accessToken, 'POST')
    await page.goto(marketUrl('600519.SH'))
    await page
      .getByText('这个空间已经归档，只能查看行情和既有研究记录。', { exact: true })
      .waitFor()
    await page.locator('.market-panel canvas').waitFor()
    assert.equal(await page.locator('.opinion-form').count(), 0)
    await capture(page, 'archived-market', [1440, 390, 320])
    checks.push({ name: 'archived-market-chart-readable-and-no-form', passed: true })
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
