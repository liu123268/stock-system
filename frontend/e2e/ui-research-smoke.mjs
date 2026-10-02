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
const output = resolve(process.env.E2E_UI_OUTPUT ?? '../../ui-preview/ui-04')
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
try {
  await mkdir(output, { recursive: true })
  for (const [index, username] of usernames.entries())
    await api('/auth/register', null, 'POST', {
      username,
      nickname: index ? '参与研究的成员昵称很长' : '研究所有者',
      password,
      confirmPassword: password,
    })
  const owner = await api('/auth/login', null, 'POST', { username: usernames[0], password })
  const member = await api('/auth/login', null, 'POST', { username: usernames[1], password })
  const space = await api('/workspaces', owner.accessToken, 'POST', { name: workspaceName })
  const id = space.workspaceId
  const invitation = await api(`/workspaces/${id}/invitations`, owner.accessToken, 'POST', {
    expectedInvitationId: null,
  })
  await api('/workspaces/join', member.accessToken, 'POST', { inviteCode: invitation.inviteCode })
  const stockBase = `/workspaces/${id}/stocks`
  const initial = {
    stance: 'NEUTRAL',
    reason: '初始判断：观察经营质量。',
    observationNote: '关注现金流与库存变化。',
    horizon: 'MEDIUM',
    targetPrice: 1600.5,
    stopLossPrice: null,
  }
  const first = await api(stockBase, owner.accessToken, 'POST', { code: '600519.SH', ...initial })
  const append = (cycle, index, token = owner.accessToken) =>
    api(`${stockBase}/${first.workspaceStockId}/opinions`, token, 'POST', {
      researchCycleId: cycle,
      type: index % 2 ? 'REVIEW' : 'UPDATE',
      stance: ['BULLISH', 'NEUTRAL', 'BEARISH'][index % 3],
      reason: `第 ${index} 条研究记录：现金流、渠道和经营质量。`,
      observationNote:
        index === 23
          ? '长观察条件：' + '逐项观察事实变化，保留判断形成时的依据。'.repeat(18)
          : null,
      horizon: 'LONG',
      targetPrice: 1650,
      stopLossPrice: 1200,
    })
  for (let index = 1; index <= 22; index++)
    await append(first.researchCycleId, index, index % 2 ? member.accessToken : owner.accessToken)
  await api(`${stockBase}/${first.workspaceStockId}/end`, owner.accessToken, 'POST', {
    researchCycleId: first.researchCycleId,
  })
  const active = await api(stockBase, owner.accessToken, 'POST', {
    code: '600519.SH',
    ...initial,
    reason: '第二期：重新观察，保留上一期判断。',
  })
  for (let index = 23; index <= 45; index++)
    await append(active.researchCycleId, index, index % 2 ? member.accessToken : owner.accessToken)
  const second = await api(stockBase, owner.accessToken, 'POST', { code: '00700.HK', ...initial })
  const researchUrl = (stock = first.workspaceStockId, hash = '') =>
    `${frontendUrl}/workspaces/${id}/stocks/${stock}${hash}`
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
  const initialDetailPath = `**/api${stockBase}/${first.workspaceStockId}/research`
  const holdInitial = async (route) => {
    const response = await route.fetch()
    await initialGate
    await route.fulfill({ response })
  }
  if (phase === 'after') await page.route(initialDetailPath, holdInitial)
  await page.goto(researchUrl())
  if (phase === 'after') {
    await page.getByText('正在读取研究记录…', { exact: true }).waitFor()
    await capture(page, 'loading-research', [1440, 320])
    releaseInitial()
  }
  await page.locator('.research-header h1').waitFor()
  if (phase === 'after') await page.unroute(initialDetailPath, holdInitial)
  await page.locator('.market-panel canvas').waitFor()
  await page.locator('.opinion-item').first().waitFor()
  await capture(page, 'research')
  await page.getByRole('button', { name: '发表观点更新', exact: true }).click()
  await page.getByLabel('判断理由（必填）', { exact: true }).fill('待确认草稿：继续观察经营质量。')
  await capture(page, 'compose', [1440, 390])
  await page.getByRole('button', { name: '收起观点表单', exact: true }).click()
  while (await page.getByRole('button', { name: '加载更早的观点', exact: true }).count()) {
    const response = page.waitForResponse(
      (r) => r.url().includes('/research/timeline') && r.request().method() === 'GET',
    )
    await page.getByRole('button', { name: '加载更早的观点', exact: true }).click()
    await response
    await page.waitForTimeout(80)
  }
  assert.equal(await page.locator('.opinion-item').count(), 47)
  assert.equal(await page.locator('.cycle-card').count(), 2)
  checks.push({ name: 'three-pages-47-opinions-two-cycles-merged', passed: true })
  if (phase === 'before') {
    const reference = await context.newPage()
    await reference.goto(
      pathToFileURL(resolve('../../ui-preview/stock-workspace-demo-v2-content-aligned.html')).href +
        '#/research?stock=1',
    )
    for (const width of [1440, 390]) {
      await reference.setViewportSize({ width, height: 1000 })
      await reference.screenshot({
        path: `${output}/reference-research-${width}.png`,
        fullPage: true,
      })
    }
    await reference.close()
  }
  if (phase === 'after') {
    const timelinePath = `**/api${stockBase}/${first.workspaceStockId}/research/timeline*`
    const detailPath = `**/api${stockBase}/${first.workspaceStockId}/research`
    const marketPath = `**/api/workspaces/${id}/market/stocks/600519.SH`
    const error503 = (message) => ({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'TEMPORARY_UNAVAILABLE', message, data: null }),
    })
    // 首个空间 GET 失败时也必须有恢复入口；成功重试只读取，不再发表／关注。
    const workspacePath = `**/api/workspaces/${id}`
    let workspaceReads = 0
    let recoveryWrites = 0
    const recordWrite = (request) => {
      if (
        new URL(request.url()).pathname.startsWith('/api/workspaces/') &&
        request.method() !== 'GET'
      )
        recoveryWrites += 1
    }
    const failWorkspace = async (route) => {
      workspaceReads += 1
      await route.fulfill(error503('验收：空间详情暂时不可用'))
    }
    page.on('request', recordWrite)
    await page.route(workspacePath, failWorkspace)
    try {
      await page.reload()
      await page.getByRole('heading', { name: '暂时无法进入这个空间', exact: true }).waitFor()
      assert.ok(await page.evaluate(() => localStorage.getItem('stock-system-access-token')))
      assert.ok(await page.getByRole('link', { name: '选择其他空间', exact: true }).isVisible())
      await capture(page, 'initial-workspace-error', [1440, 960, 390, 320])
      await page.getByRole('button', { name: '重新加载空间与研究数据', exact: true }).click()
      await page.getByRole('heading', { name: '暂时无法进入这个空间', exact: true }).waitFor()
      assert.equal(workspaceReads, 2)
      await page.unroute(workspacePath, failWorkspace)
      await page.getByRole('button', { name: '重新加载空间与研究数据', exact: true }).click()
      await page.locator('.research-header h1').waitFor()
      await page.locator('.opinion-item').first().waitFor()
      await page.locator('.market-panel canvas').waitFor()
      assert.equal(recoveryWrites, 0)
      checks.push({
        name: 'initial-workspace-error-repeated-get-only-retry-recovers-research',
        passed: true,
      })
    } finally {
      page.off('request', recordWrite)
      await page.unroute(workspacePath, failWorkspace)
    }
    const countOpinions = () =>
      Number(
        sql(
          `SELECT COUNT(*) FROM stock_opinion op JOIN research_cycle rc ON rc.id=op.research_cycle_id WHERE rc.workspace_stock_id=${first.workspaceStockId}`,
        ),
      )
    const routeTo = (stock, hash = '') =>
      page.evaluate(
        (url) =>
          document.querySelector('#app').__vue_app__.config.globalProperties.$router.push(url),
        `/workspaces/${id}/stocks/${stock}${hash}`,
      )
    const filter = async (label, value) => {
      const waiting = page.waitForResponse(
        (r) => r.url().includes('/research/timeline') && r.request().method() === 'GET',
      )
      await page.getByLabel(label, { exact: true }).selectOption(value)
      const response = await waiting
      assert.ok(response.ok())
      const data = (await response.json()).data
      await page.waitForTimeout(80)
      assert.equal(
        await page.locator('.opinion-item').count(),
        data.cycles.reduce((total, cycle) => total + cycle.opinions.length, 0),
      )
      return data
    }
    const onlyInitial = await filter('按类型筛选', 'INITIAL')
    assert.equal(onlyInitial.cycles.length, 2)
    assert.ok(
      onlyInitial.cycles
        .flatMap((cycle) => cycle.opinions)
        .every((opinion) => opinion.type === 'INITIAL'),
    )
    await capture(page, 'filtered-history', [1440, 390])
    await page.setViewportSize({ width: 1440, height: 1100 })
    await page.getByRole('button', { name: '收起行情图', exact: true }).click()
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({ path: `${output}/after-research-overview-1440.png` })
    await page.getByRole('button', { name: '发表观点更新', exact: true }).click()
    await page
      .locator('#opinion-compose')
      .screenshot({ path: `${output}/after-compose-panel-1440.png` })
    await page.getByRole('button', { name: '收起观点表单', exact: true }).click()
    await page.getByRole('button', { name: '查看行情图', exact: true }).click()
    await page.locator('.market-panel canvas').waitFor()
    await filter('按倾向筛选', 'BEARISH')
    await page.getByText('当前筛选条件下没有观点。', { exact: true }).waitFor()
    await capture(page, 'empty-filter', [1440, 320])
    await filter('按类型筛选', 'ALL')
    await filter('按倾向筛选', 'ALL')
    const memberRecords = await filter('按成员筛选', String(member.userId))
    assert.ok(
      memberRecords.cycles
        .flatMap((cycle) => cycle.opinions)
        .every((opinion) => opinion.authorUserId === member.userId),
    )
    await filter('按成员筛选', 'ALL')
    checks.push({ name: 'server-filters-reset-cursor-and-empty-state', passed: true })

    const failInitial = async (route) => route.fulfill(error503('验收：初始详情读取失败'))
    await page.route(detailPath, failInitial)
    await page.reload()
    await page.getByRole('heading', { name: '研究记录暂时无法加载', exact: true }).waitFor()
    await capture(page, 'initial-detail-error', [1440, 390])
    await page.unroute(detailPath, failInitial)
    await page.getByRole('button', { name: '重新加载页面数据', exact: true }).click()
    await page.locator('.opinion-item').first().waitFor()
    await filter('按类型筛选', 'INITIAL')
    await filter('按类型筛选', 'ALL')
    checks.push({ name: 'initial-detail-retry-restores-timeline-filter-target', passed: true })

    const failTimeline = async (route) => route.fulfill(error503('验收：时间线读取失败'))
    await page.route(timelinePath, failTimeline)
    await page.getByLabel('按类型筛选', { exact: true }).selectOption('REVIEW')
    await page.getByText('验收：时间线读取失败', { exact: false }).waitFor()
    assert.equal(await page.getByText('当前筛选条件下没有观点。', { exact: true }).count(), 0)
    await capture(page, 'timeline-error', [1440, 390])
    await page.unroute(timelinePath, failTimeline)
    await page.getByRole('button', { name: '重试', exact: true }).click()
    await page.locator('.opinion-item').first().waitFor()
    await filter('按类型筛选', 'ALL')
    await page.route(timelinePath, failTimeline)
    await page.getByRole('button', { name: '加载更早的观点', exact: true }).click()
    await page.getByText('验收：时间线读取失败', { exact: false }).waitFor()
    assert.equal(await page.locator('.opinion-item').count(), 20)
    await page.unroute(timelinePath, failTimeline)
    await page.getByRole('button', { name: '重试', exact: true }).click()
    await page.waitForFunction(() => document.querySelectorAll('.opinion-item').length === 40)
    await page.getByRole('button', { name: '加载更早的观点', exact: true }).click()
    await page.waitForFunction(() => document.querySelectorAll('.opinion-item').length === 47)
    const opinionIds = await page
      .locator('.opinion-item')
      .evaluateAll((rows) => rows.map((row) => row.dataset.opinionId))
    assert.equal(new Set(opinionIds).size, 47)
    checks.push({
      name: 'initial-and-next-page-errors-preserve-records-and-retry-without-duplicates',
      passed: true,
    })

    await page.getByRole('button', { name: '收起行情图', exact: true }).click()
    assert.equal(await page.locator('.market-panel').count(), 0)
    await page.getByRole('button', { name: '查看行情图', exact: true }).click()
    await page.locator('.market-panel canvas').waitFor()
    assert.equal(await page.locator('.market-panel').count(), 1)
    checks.push({ name: 'chart-collapse-unmount-and-single-remount', passed: true })
    for (const [mode, expected] of [
      ['null', '暂无可用报价'],
      ['older', '暂不计算价格变化'],
      ['different-source', '暂不计算价格变化'],
      ['up', '+5.00%'],
      ['error', '验收：报价读取失败'],
    ]) {
      const quoteHandler = async (route) => {
        if (mode === 'error') return route.fulfill(error503(expected))
        const response = await route.fetch(),
          body = await response.json()
        if (mode === 'null') body.data.price = null
        if (mode === 'older') body.data.quoteTime = '1990-01-01T00:00:00'
        if (mode === 'different-source') body.data.simulated = false
        if (mode === 'up') body.data.price *= 1.05
        await route.fulfill({ response, json: body })
      }
      await page.route(marketPath, quoteHandler)
      await page.getByRole('button', { name: '刷新行情', exact: true }).click()
      await page.getByText(expected, { exact: false }).first().waitFor()
      if (['null', 'error'].includes(mode)) await capture(page, `quote-${mode}`, [1440, 390])
      await page.unroute(marketPath, quoteHandler)
    }
    await page.getByRole('button', { name: '重试行情', exact: true }).click()
    await page.locator('.latest-price').waitFor()
    checks.push({ name: 'independent-quote-retry-and-price-change-boundaries', passed: true })

    await page.goto(researchUrl(second.workspaceStockId))
    await page.goto(researchUrl(first.workspaceStockId, '#research-timeline'))
    await page.locator('.opinion-item').first().waitFor()
    await page.locator('.latest-price').waitFor()
    await page.waitForFunction(() => {
      const top = document.getElementById('research-timeline').getBoundingClientRect().top
      return top >= 60 && top < 150
    })
    await page.waitForTimeout(80)
    assert.equal(await page.locator('.market-panel').count(), 0)
    assert.ok(
      await page
        .locator('#research-timeline')
        .evaluate(
          (element) =>
            element.getBoundingClientRect().top >= 60 && element.getBoundingClientRect().top < 150,
        ),
    )
    await page.reload()
    await page.locator('.opinion-item').first().waitFor()
    await page.locator('.latest-price').waitFor()
    await page.waitForFunction(() => {
      const top = document.getElementById('research-timeline').getBoundingClientRect().top
      return top >= 60 && top < 150
    })
    await page.waitForTimeout(80)
    assert.ok(
      await page
        .locator('#research-timeline')
        .evaluate(
          (element) =>
            element.getBoundingClientRect().top >= 60 && element.getBoundingClientRect().top < 150,
        ),
    )
    checks.push({ name: 'direct-url-refresh-and-timeline-anchor-below-header', passed: true })

    await page.getByRole('button', { name: '发表观点更新', exact: true }).click()
    await page.getByRole('button', { name: '发表到时间线', exact: true }).click()
    await page.getByText('请填写判断理由', { exact: true }).waitFor()
    await page.getByLabel('判断理由（必填）', { exact: true }).fill('UI-04 保存结果验证。')
    await page.getByLabel('目标价（选填）', { exact: true }).fill('-2')
    await page.getByRole('button', { name: '发表到时间线', exact: true }).click()
    await page.getByText('目标价和止损参考价必须是大于 0 的数字', { exact: true }).waitFor()
    await page.getByLabel('目标价（选填）', { exact: true }).fill('1700.25')
    const postPath = `**/api${stockBase}/${first.workspaceStockId}/opinions`
    const failSubmit = async (route) => route.fulfill(error503('验收：观点提交失败'))
    await page.route(postPath, failSubmit)
    await page.getByRole('button', { name: '发表到时间线', exact: true }).click()
    await page.getByText('验收：观点提交失败', { exact: true }).first().waitFor()
    assert.equal(
      await page.getByLabel('判断理由（必填）', { exact: true }).inputValue(),
      'UI-04 保存结果验证。',
    )
    assert.equal(countOpinions(), 47)
    await page.unroute(postPath, failSubmit)
    let posts = 0
    const save = async (route) => {
      posts += 1
      assert.equal(route.request().postDataJSON().targetPrice, 1700.25)
      assert.equal(route.request().postDataJSON().stopLossPrice, null)
      await route.continue()
    }
    await page.route(postPath, save)
    await page.route(timelinePath, failTimeline)
    await page.getByRole('button', { name: '发表到时间线', exact: true }).click()
    await page.getByText('观点已经保存，但时间线刷新失败，请重新加载。', { exact: false }).waitFor()
    await capture(page, 'saved-timeline-error', [1440, 390])
    await page.getByRole('button', { name: '只重新加载页面数据', exact: true }).click()
    await page.getByText('观点已经保存，但时间线刷新失败，请重新加载。', { exact: false }).waitFor()
    assert.equal(posts, 1)
    await page.unroute(timelinePath, failTimeline)
    await page.getByRole('button', { name: '只重新加载页面数据', exact: true }).click()
    await page.locator('.opinion-copy').filter({ hasText: 'UI-04 保存结果验证。' }).waitFor()
    assert.equal(posts, 1)
    assert.equal(countOpinions(), 48)
    await page.unroute(postPath, save)
    checks.push({
      name: 'validation-failed-submit-draft-saved-timeline-failure-get-only-retry',
      passed: true,
    })

    await page.getByRole('button', { name: '发表观点更新', exact: true }).click()
    await page.getByLabel('判断理由（必填）', { exact: true }).fill('详情读取失败的已保存观点。')
    const failDetail = async (route) => route.fulfill(error503('验收：详情读取失败'))
    await page.route(detailPath, failDetail)
    await page.getByRole('button', { name: '发表到时间线', exact: true }).click()
    await page.getByRole('heading', { name: '操作已经保存，页面暂未刷新', exact: true }).waitFor()
    await capture(page, 'saved-detail-error', [1440, 390])
    assert.equal(countOpinions(), 49)
    await page.unroute(detailPath, failDetail)
    await page.getByRole('button', { name: '重新加载页面数据', exact: true }).click()
    await page.locator('.research-header h1').waitFor()
    assert.equal(countOpinions(), 49)
    checks.push({ name: 'saved-detail-failure-and-read-only-recovery', passed: true })

    await page.getByRole('button', { name: '发表观点更新', exact: true }).click()
    await page.getByLabel('判断理由（必填）', { exact: true }).fill('迟到的已保存观点。')
    let releaseWrite,
      startedWrite,
      latePosts = 0
    const writeGate = new Promise((resolve) => {
      releaseWrite = resolve
    })
    const writeBegan = new Promise((resolve) => {
      startedWrite = resolve
    })
    const delayedWrite = async (route) => {
      latePosts += 1
      const response = await route.fetch()
      assert.ok(response.ok())
      startedWrite()
      await writeGate
      await route.fulfill({ response })
    }
    await page.route(postPath, delayedWrite)
    await page.getByRole('button', { name: '发表到时间线', exact: true }).click()
    await writeBegan
    assert.equal(await page.getByLabel('判断理由（必填）', { exact: true }).isDisabled(), true)
    await page
      .getByRole('button', { name: '正在提交…', exact: true })
      .evaluate((button) => button.click())
    await routeTo(second.workspaceStockId)
    await page.getByRole('heading', { name: '腾讯控股 · 研究', exact: true }).waitFor()
    assert.equal(await page.locator('#opinion-compose').count(), 0)
    const lateResponse = page.waitForResponse(
      (r) => r.request().method() === 'POST' && r.url().includes('/opinions'),
    )
    releaseWrite()
    await lateResponse
    await page.waitForTimeout(80)
    assert.equal(latePosts, 1)
    assert.equal(
      await page.evaluate(
        () =>
          document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('stock')
            .researchDetail.code,
      ),
      '00700.HK',
    )
    await page.unroute(postPath, delayedWrite)
    checks.push({
      name: 'busy-form-and-late-write-do-not-refill-or-navigate-new-target',
      passed: true,
    })

    let releaseRead, readStarted
    const readGate = new Promise((resolve) => {
      releaseRead = resolve
    })
    const readBegan = new Promise((resolve) => {
      readStarted = resolve
    })
    const delayedRead = async (route) => {
      readStarted()
      await readGate
      await route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'WORKSPACE_ACCESS_DENIED',
          message: '旧详情迟到失败',
          data: null,
        }),
      })
    }
    await page.route(detailPath, delayedRead)
    await routeTo(first.workspaceStockId)
    await readBegan
    await routeTo(second.workspaceStockId)
    await page.getByRole('heading', { name: '腾讯控股 · 研究', exact: true }).waitFor()
    const oldRead = page.waitForResponse((r) =>
      r.url().endsWith(`/stocks/${first.workspaceStockId}/research`),
    )
    releaseRead()
    await oldRead
    await page.waitForTimeout(80)
    assert.ok(page.url().endsWith(`/stocks/${second.workspaceStockId}`))
    await page.unroute(detailPath, delayedRead)
    checks.push({
      name: 'late-old-detail-error-does-not-change-access-or-current-target',
      passed: true,
    })

    await page.goto(researchUrl())
    const lateRecord = page.locator('.opinion-item').filter({ hasText: '迟到的已保存观点。' })
    await lateRecord.waitFor()
    let releaseTimeline, timelineStarted
    const timelineGate = new Promise((resolve) => {
      releaseTimeline = resolve
    })
    const timelineBegan = new Promise((resolve) => {
      timelineStarted = resolve
    })
    const delayedTimeline = async (route) => {
      const response = await route.fetch()
      assert.ok(response.ok())
      timelineStarted()
      await timelineGate
      await route.fulfill({ response })
    }
    await page.route(timelinePath, delayedTimeline)
    page.once('dialog', (dialog) => dialog.accept())
    await lateRecord.getByRole('button', { name: '撤销误发内容', exact: true }).click()
    await timelineBegan
    await routeTo(second.workspaceStockId)
    await page.getByRole('heading', { name: '腾讯控股 · 研究', exact: true }).waitFor()
    const lateTimeline = page.waitForResponse((r) =>
      r.url().includes(`/stocks/${first.workspaceStockId}/research/timeline`),
    )
    releaseTimeline()
    await lateTimeline
    await page.waitForTimeout(80)
    assert.equal(await page.locator('.success-message, .error-message').count(), 0)
    assert.equal(
      await page.locator('.opinion-item').filter({ hasText: '迟到的已保存观点。' }).count(),
      0,
    )
    assert.ok(page.url().endsWith(`/stocks/${second.workspaceStockId}`))
    await page.unroute(timelinePath, delayedTimeline)
    checks.push({
      name: 'late-post-write-timeline-does-not-refill-new-target-feedback',
      passed: true,
    })

    await page.goto(researchUrl())
    await page.getByRole('button', { name: '发表观点更新', exact: true }).click()
    await page.getByLabel('判断理由（必填）', { exact: true }).fill('周期变化后仍需确认的草稿。')
    await api(`${stockBase}/${first.workspaceStockId}/end`, member.accessToken, 'POST', {
      researchCycleId: active.researchCycleId,
    })
    const changed = await api(stockBase, member.accessToken, 'POST', {
      code: '600519.SH',
      ...initial,
    })
    const countBeforeConflict = countOpinions()
    await page.getByRole('button', { name: '发表到时间线', exact: true }).click()
    await page.locator('#research-form-error').waitFor()
    assert.equal(countOpinions(), countBeforeConflict)
    assert.equal(
      await page.getByLabel('判断理由（必填）', { exact: true }).inputValue(),
      '周期变化后仍需确认的草稿。',
    )
    await page.getByRole('button', { name: '重新加载研究数据', exact: true }).click()
    await page.getByRole('button', { name: '确认当前周期，保留草稿', exact: true }).waitFor()
    await page.getByRole('button', { name: '发表到时间线', exact: true }).click()
    await page.getByText('研究周期已经变化，请确认当前周期后再提交草稿', { exact: true }).waitFor()
    assert.equal(countOpinions(), countBeforeConflict)
    await capture(page, 'changed-cycle-draft', [1440, 390])
    await page.getByRole('button', { name: '确认当前周期，保留草稿', exact: true }).click()
    await page.getByRole('button', { name: '发表到时间线', exact: true }).click()
    await page.locator('.opinion-copy').filter({ hasText: '周期变化后仍需确认的草稿。' }).waitFor()
    assert.equal(countOpinions(), countBeforeConflict + 1)
    checks.push({
      name: 'other-member-reopens-old-cycle-draft-requires-explicit-current-cycle-confirmation',
      passed: true,
    })

    const ownRecord = page
      .locator('.opinion-item')
      .filter({ hasText: '周期变化后仍需确认的草稿。' })
    page.once('dialog', (dialog) => dialog.accept())
    await ownRecord.getByRole('button', { name: '撤销误发内容', exact: true }).click()
    await page
      .getByText('误发内容已撤销：正文已经隐藏，时间线仍保留发表和撤销记录。', { exact: true })
      .waitFor()
    assert.equal(
      await page.locator('.opinion-copy').filter({ hasText: '周期变化后仍需确认的草稿。' }).count(),
      0,
    )
    await page.locator('.withdrawn').first().waitFor()
    await capture(page, 'withdrawn-placeholder', [1440, 390])
    assert.equal(countOpinions(), countBeforeConflict + 1)
    checks.push({ name: 'withdraw-hides-content-preserves-record-and-author-time', passed: true })

    await page.getByRole('button', { name: '结束本轮关注', exact: true }).click()
    await api(`${stockBase}/${first.workspaceStockId}/end`, member.accessToken, 'POST', {
      researchCycleId: changed.researchCycleId,
    })
    const newer = await api(stockBase, member.accessToken, 'POST', {
      code: '600519.SH',
      ...initial,
    })
    await page.getByRole('button', { name: '确认结束', exact: true }).click()
    await page.getByRole('button', { name: '重新加载研究数据', exact: true }).waitFor()
    await page.getByRole('button', { name: '重新加载研究数据', exact: true }).click()
    await page.getByRole('button', { name: '确认结束', exact: true }).click()
    await page.getByText('研究周期已经变化，请重新确认要结束的周期。', { exact: false }).waitFor()
    const stillActive = await api(
      `${stockBase}/${first.workspaceStockId}/research`,
      owner.accessToken,
    )
    assert.equal(stillActive.currentCycle.id, newer.researchCycleId)
    await page.getByRole('button', { name: '结束本轮关注', exact: true }).click()
    await page.getByRole('button', { name: '取消', exact: true }).click()
    await page.getByRole('button', { name: '结束本轮关注', exact: true }).click()
    await page.getByRole('button', { name: '确认结束', exact: true }).click()
    await page.getByRole('heading', { name: '没有正在进行的研究周期', exact: true }).waitFor()
    await page
      .getByText('当前没有进行中的周期；历史周期不使用今天的报价计算结束时的价格变化。', {
        exact: true,
      })
      .waitFor()
    await capture(page, 'ended-cycle', [1440, 390])
    await page.getByRole('button', { name: '开启新一期研究', exact: true }).click()
    await page.getByLabel('判断理由（必填）', { exact: true }).fill('其他成员先重开时保留的草稿。')
    const concurrent = await api(stockBase, member.accessToken, 'POST', {
      code: '600519.SH',
      ...initial,
    })
    await page.getByRole('button', { name: '重新开启关注', exact: true }).click()
    await page
      .getByText('其他成员已经先开启研究周期，你的草稿仍然保留，可作为观点发表。', { exact: true })
      .waitFor()
    assert.equal(
      await page.getByLabel('判断理由（必填）', { exact: true }).inputValue(),
      '其他成员先重开时保留的草稿。',
    )
    assert.equal(
      Number(
        sql(
          `SELECT COUNT(*) FROM stock_opinion WHERE research_cycle_id=${concurrent.researchCycleId}`,
        ),
      ),
      1,
    )
    checks.push({
      name: 'end-confirmation-target-cancel-end-and-concurrent-reopen-draft',
      passed: true,
    })

    await page.goto(researchUrl(second.workspaceStockId))
    await page.getByRole('button', { name: '撤销本次误添加', exact: true }).waitFor()
    page.once('dialog', (dialog) => dialog.accept())
    await page.getByRole('button', { name: '撤销本次误添加', exact: true }).click()
    await page.getByRole('heading', { name: '没有正在进行的研究周期', exact: true }).waitFor()
    await page.getByRole('heading', { name: '已取消 · 普通关注', exact: true }).waitFor()
    await capture(page, 'cancelled-cycle', [1440, 390])
    checks.push({
      name: 'initial-withdraw-cancels-cycle-and-retains-readonly-placeholder',
      passed: true,
    })

    await page.goto(researchUrl())
    const fresh = await api(
      `${stockBase}/${first.workspaceStockId}/opinions`,
      owner.accessToken,
      'POST',
      {
        researchCycleId: concurrent.researchCycleId,
        type: 'UPDATE',
        ...initial,
        reason: '撤销窗口检查。',
      },
    )
    await page.clock.install({ time: new Date() })
    await page.reload()
    await page.locator(`[data-opinion-id="${fresh.id}"] button`).waitFor()
    await page.clock.fastForward(6 * 60 * 1000)
    await page.waitForFunction(
      (opinionId) => !document.querySelector(`[data-opinion-id="${opinionId}"] button`),
      fresh.id,
    )
    assert.equal(await page.locator(`[data-opinion-id="${fresh.id}"] button`).count(), 0)
    checks.push({ name: 'client-withdraw-window-closes-after-five-minutes', passed: true })
    await api(`/workspaces/${id}/archive`, owner.accessToken, 'POST')
    await page.reload()
    await page.locator('.opinion-item').first().waitFor()
    assert.equal(
      await page.locator('.current-actions, #opinion-compose, .withdraw-link').count(),
      0,
    )
    await capture(page, 'archived-research', [1440, 390, 320])
    checks.push({ name: 'archived-research-filters-history-and-chart-readonly', passed: true })
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
