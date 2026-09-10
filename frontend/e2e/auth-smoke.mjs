import { execFileSync } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { chromium } from 'playwright-core'

const chromePath =
  process.env.CHROME_PATH ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const mysqlPath = process.env.MYSQL_CLIENT_PATH ?? 'mysql.exe'
const applicationPropertiesUrl = new URL(
  '../../src/main/resources/application.properties',
  import.meta.url,
)
const testPropertiesUrl = new URL(
  '../../src/main/resources/application-test.properties',
  import.meta.url,
)
const frontendUrl = process.env.FRONTEND_URL ?? 'http://localhost:18083'
const backendUrl = process.env.BACKEND_URL ?? 'http://localhost:18082'
const runId = Date.now().toString(36)
const ownerUsername = `browser_o_${runId}`.slice(0, 20)
const memberUsername = `browser_m_${runId}`.slice(0, 20)
const workspaceName = `浏览器空间${runId}`
const password = 'BrowserPass123'
const ownerNickname = '浏览器 OWNER'
const memberNickname = '浏览器 MEMBER'
const searchedStockCode = '600519.SH'
const marketMode = process.env.E2E_MARKET_MODE ?? 'mock'
const expectedStockSource = marketMode === 'longbridge' ? 'LONGBRIDGE_OPENAPI' : 'LOCAL_MOCK'

async function readDatabaseConfig() {
  const content = `${await readFile(applicationPropertiesUrl, 'utf8')}\n${await readFile(testPropertiesUrl, 'utf8')}`
  const properties = new Map()

  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) continue

    const separatorIndex = line.indexOf('=')
    if (separatorIndex < 0) continue
    properties.set(line.slice(0, separatorIndex), line.slice(separatorIndex + 1))
  }

  const jdbcUrl = properties.get('spring.datasource.url')
  const match = jdbcUrl?.match(/^jdbc:mysql:\/\/([^:/?]+)(?::(\d+))?\/([^?]+)/)
  if (!match) throw new Error('无法从application.properties解析MySQL连接地址')

  return {
    host: match[1],
    port: match[2] ?? '3306',
    database: match[3],
    user: properties.get('spring.datasource.username'),
    password: properties.get('spring.datasource.password'),
  }
}

async function testStockExists() {
  const database = await readDatabaseConfig()
  if (!database.user || database.password === undefined) {
    throw new Error('MySQL账号配置不完整，无法核对浏览器测试股票')
  }
  if (database.database !== 'stock_system_test') {
    throw new Error(`拒绝核对非测试数据库：${database.database}`)
  }

  const output = execFileSync(
    mysqlPath,
    [
      `--host=${database.host}`,
      `--port=${database.port}`,
      `--user=${database.user}`,
      `--database=${database.database}`,
      '--batch',
      '--skip-column-names',
      `--execute=SELECT COUNT(*) FROM stock WHERE code = '${searchedStockCode}'`,
    ],
    {
      encoding: 'utf8',
      env: { ...process.env, MYSQL_PWD: database.password },
    },
  )

  return Number(output.trim()) > 0
}

async function ensureTestStock(stockExistedBefore) {
  if (stockExistedBefore || marketMode !== 'longbridge') return

  const database = await readDatabaseConfig()
  const sql = `INSERT INTO stock (code, name, exchange, currency, listing_status, source, search_aliases, last_synced_at) VALUES ('${searchedStockCode}', '贵州茅台', 'SSE', 'CNY', 'LISTED', 'A_SHARE_DIRECTORY', '贵州茅台 600519.SH', CURRENT_TIMESTAMP(3))`
  execFileSync(
    mysqlPath,
    [
      `--host=${database.host}`,
      `--port=${database.port}`,
      `--user=${database.user}`,
      `--database=${database.database}`,
      '--default-character-set=utf8mb4',
      `--execute=${sql}`,
    ],
    {
      encoding: 'utf8',
      env: { ...process.env, MYSQL_PWD: database.password },
    },
  )
}

async function cleanupTestUser(stockExistedBefore) {
  if (![ownerUsername, memberUsername].every((item) => /^browser_[a-z0-9_]+$/.test(item))) {
    throw new Error(`拒绝清理非测试账号：${ownerUsername}、${memberUsername}`)
  }
  if (!/^浏览器空间[a-z0-9]+$/.test(workspaceName)) {
    throw new Error(`拒绝清理非测试空间：${workspaceName}`)
  }

  const database = await readDatabaseConfig()
  if (!database.user || database.password === undefined) {
    throw new Error('MySQL账号配置不完整，无法清理浏览器测试数据')
  }

  if (database.database !== 'stock_system_test') {
    throw new Error(`拒绝清理非测试数据库：${database.database}`)
  }

  const sql = [
    `SET @test_workspace_id = (SELECT id FROM workspace WHERE name = '${workspaceName}' LIMIT 1)`,
    `DELETE FROM stock_opinion WHERE research_cycle_id IN (SELECT id FROM research_cycle WHERE workspace_stock_id IN (SELECT id FROM workspace_stock WHERE workspace_id = @test_workspace_id))`,
    `DELETE FROM research_cycle WHERE workspace_stock_id IN (SELECT id FROM workspace_stock WHERE workspace_id = @test_workspace_id)`,
    `DELETE FROM workspace_stock WHERE workspace_id = @test_workspace_id`,
    ...(stockExistedBefore
      ? []
      : [
          `DELETE FROM stock WHERE code = '${searchedStockCode}' AND source IN ('${expectedStockSource}', 'A_SHARE_DIRECTORY') AND NOT EXISTS (SELECT 1 FROM workspace_stock WHERE stock_id = stock.id)`,
        ]),
    `DELETE FROM workspace_invitation WHERE workspace_id = @test_workspace_id`,
    `DELETE FROM workspace_member WHERE workspace_id = @test_workspace_id`,
    `DELETE FROM workspace WHERE id = @test_workspace_id AND name = '${workspaceName}'`,
    `DELETE FROM app_user WHERE username IN ('${ownerUsername}', '${memberUsername}')`,
    `SELECT (SELECT COUNT(*) FROM app_user WHERE username IN ('${ownerUsername}', '${memberUsername}')) + (SELECT COUNT(*) FROM workspace WHERE id = @test_workspace_id) + (SELECT COUNT(*) FROM workspace_stock WHERE workspace_id = @test_workspace_id) + (SELECT COUNT(*) FROM research_cycle WHERE workspace_stock_id IN (SELECT id FROM workspace_stock WHERE workspace_id = @test_workspace_id)) + (SELECT COUNT(*) FROM stock_opinion WHERE research_cycle_id IN (SELECT id FROM research_cycle WHERE workspace_stock_id IN (SELECT id FROM workspace_stock WHERE workspace_id = @test_workspace_id)))${stockExistedBefore ? '' : ` + (SELECT COUNT(*) FROM stock WHERE code = '${searchedStockCode}')`}`,
  ].join('; ')

  const output = execFileSync(
    mysqlPath,
    [
      `--host=${database.host}`,
      `--port=${database.port}`,
      `--user=${database.user}`,
      `--database=${database.database}`,
      '--default-character-set=utf8mb4',
      '--batch',
      '--raw',
      `--execute=${sql}`,
    ],
    {
      encoding: 'utf8',
      env: { ...process.env, MYSQL_PWD: database.password },
    },
  )

  const remaining = Number(output.trim().split(/\r?\n/).at(-1))
  if (remaining !== 0) {
    throw new Error(`浏览器测试数据清理失败：${runId}`)
  }
}

async function checkServices() {
  const environmentResponse = await fetch(`${backendUrl}/api/test/environment`)
  if (!environmentResponse.ok) {
    throw new Error(
      `拒绝开始浏览器写入：${backendUrl} 不是可验证的 test 后端（HTTP ${environmentResponse.status}）`,
    )
  }
  const environmentResult = await environmentResponse.json()
  if (
    environmentResult?.data?.profile !== 'test' ||
    environmentResult?.data?.database !== 'stock_system_test'
  ) {
    throw new Error(`拒绝开始浏览器写入：后端实际环境为 ${JSON.stringify(environmentResult?.data)}`)
  }

  const frontendResponse = await fetch(`${frontendUrl}/register`)
  if (!frontendResponse.ok) {
    throw new Error(`前端不可用：HTTP ${frontendResponse.status}`)
  }

  const backendResponse = await fetch(`${backendUrl}/api/auth/me`)
  if (backendResponse.status !== 401) {
    throw new Error(`后端认证检查异常：预期401，实际${backendResponse.status}`)
  }
}

await checkServices()
const stockExistedBefore = await testStockExists()
let browser
let testResult

try {
  await ensureTestStock(stockExistedBefore)
  browser = await chromium.launch({ executablePath: chromePath, headless: true })
  const page = await browser.newPage()
  const apiResponses = []
  const browserErrors = []
  page.on('pageerror', (error) => browserErrors.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error') browserErrors.push(message.text())
  })

  const captureApiResponses = (targetPage) =>
    targetPage.on('response', (response) => {
      if (new URL(response.url()).pathname.startsWith('/api/')) {
        apiResponses.push({
          method: response.request().method(),
          path: new URL(response.url()).pathname,
          status: response.status(),
        })
      }
    })
  captureApiResponses(page)

  await page.goto(`${frontendUrl}/register`)

  const registerInputs = page.locator('input')
  await registerInputs.nth(0).fill(ownerUsername)
  await registerInputs.nth(1).fill(ownerNickname)
  await registerInputs.nth(2).fill(password)
  await registerInputs.nth(3).fill(password)
  await page.locator('.auth-submit-button').click()

  await page.waitForURL((url) => url.pathname === '/login')

  const loginInputs = page.locator('input')
  const prefilledUsername = await loginInputs.nth(0).inputValue()
  if (prefilledUsername !== ownerUsername) {
    throw new Error('注册后的登录账号没有自动带入登录页')
  }

  await loginInputs.nth(1).fill(password)
  await page.locator('.auth-submit-button').click()
  await page.waitForURL((url) => url.pathname === '/workspaces')

  await page.locator('#workspace-name').fill(workspaceName)
  const createWorkspaceResponse = page.waitForResponse((response) => {
    const path = new URL(response.url()).pathname
    return response.request().method() === 'POST' && path === '/api/workspaces'
  })
  const workspaceDetailResponse = page.waitForResponse((response) => {
    const path = new URL(response.url()).pathname
    return response.request().method() === 'GET' && /^\/api\/workspaces\/\d+$/.test(path)
  })
  await page.getByRole('button', { name: '创建空间' }).click()
  await createWorkspaceResponse
  await page.waitForURL((url) => /^\/workspaces\/\d+\/overview$/.test(url.pathname))
  await workspaceDetailResponse

  const pageText = await page.locator('body').innerText()
  if (!pageText.includes(ownerNickname)) {
    throw new Error('首页没有显示当前用户昵称')
  }

  const tokenSaved = await page.evaluate(() =>
    Boolean(localStorage.getItem('stock-system-access-token')),
  )
  if (!tokenSaved) {
    throw new Error('登录成功后JWT没有保存到localStorage')
  }

  const initialStockListResponse = page.waitForResponse((response) => {
    const path = new URL(response.url()).pathname
    return response.request().method() === 'GET' && /^\/api\/workspaces\/\d+\/stocks$/.test(path)
  })
  await page.getByRole('link', { name: '共享自选', exact: true }).click()
  if ((await initialStockListResponse).status() !== 200) {
    throw new Error('共享自选列表没有成功加载')
  }
  await page.locator('#stock-keyword').fill('600519')
  const searchStockResponse = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return response.request().method() === 'GET' && url.pathname === '/api/stocks/search'
  })
  await page.getByRole('button', { name: '搜索股票', exact: true }).click()
  if ((await searchStockResponse).status() !== 200) {
    throw new Error('股票搜索没有成功返回')
  }
  await page.getByRole('button', { name: /贵州茅台.*600519\.SH/ }).click()
  await page.getByLabel('观点倾向').selectOption('BULLISH')
  await page.getByLabel('关注周期').selectOption('MEDIUM')
  await page.getByLabel('观点理由').fill('品牌优势和现金流仍需持续验证')
  await page.getByLabel('风险点').fill('估值波动和消费需求变化')
  await page.getByLabel('目标价（选填）').fill('1680')
  await page.getByLabel('止损参考价（选填）').fill('1350')
  const addStockResponsePromise = page.waitForResponse(
    (response) => {
      const path = new URL(response.url()).pathname
      return response.request().method() === 'POST' && /^\/api\/workspaces\/\d+\/stocks$/.test(path)
    },
    { timeout: 5000 },
  )
  await page.getByRole('button', { name: '添加并保存观点', exact: true }).click()
  const addStockResponse = await addStockResponsePromise.catch(async () => {
    const messages = await page.locator('.inline-error').allInnerTexts()
    const formState = await page.locator('.opinion-form').evaluate((form) => ({
      valid: form.checkValidity(),
      values: Object.fromEntries(new FormData(form).entries()),
      buttonDisabled: form.querySelector('button[type="submit"]')?.disabled,
    }))
    throw new Error(
      `点击添加后没有发出请求，页面提示：${messages.join('；') || '无'}；表单：${JSON.stringify(formState)}；浏览器错误：${browserErrors.join('；') || '无'}`,
    )
  })
  if (addStockResponse.status() !== 200) {
    throw new Error('股票与初始观点没有成功原子添加')
  }
  await page.getByText(/已加入共享自选，初始观点已经保存。/).waitFor()
  await page.getByText('贵州茅台', { exact: true }).last().waitFor()
  const stockPageText = await page.locator('body').innerText()
  const expectedSourceText = marketMode === 'longbridge' ? 'LONGBRIDGE_OPENAPI' : '本地模拟行情'
  if (!stockPageText.includes(expectedSourceText) || !stockPageText.includes('看多')) {
    throw new Error(`共享自选列表没有显示预期行情来源和初始观点摘要：${stockPageText}`)
  }

  let realMarketChartsCompleted = false
  let realMarketScreenshotCaptured = false
  if (marketMode === 'longbridge') {
    const chartApiPath = (suffix) =>
      new RegExp(`^/api/workspaces/\\d+/stocks/\\d+/${suffix}$`)
    const waitForChartResponse = (suffix, expectedStatus = 200) =>
      page.waitForResponse((response) => {
        const path = new URL(response.url()).pathname
        return (
          response.request().method() === 'GET' &&
          chartApiPath(suffix).test(path) &&
          response.status() === expectedStatus
        )
      })

    const dailyKlineResponse = waitForChartResponse('candlesticks')
    await page.getByRole('button', { name: '查看行情图', exact: true }).click()
    await dailyKlineResponse
    await page.locator('.candlestick-chart canvas').waitFor()

    const minuteKlineResponse = waitForChartResponse('candlesticks')
    await page.getByRole('button', { name: '1 分', exact: true }).click()
    await minuteKlineResponse
    await page.getByText(/已加载 \d+ 根 · 数据源：LONGBRIDGE_OPENAPI/).waitFor()

    const todayIntradayResponse = waitForChartResponse('intraday')
    await page.getByRole('button', { name: '分时', exact: true }).click()
    await todayIntradayResponse
    await page.locator('.intraday-chart canvas').waitFor()

    const selectIntradayDate = async (date) => {
      await page.locator('.intraday-date input').evaluate((input, value) => {
        input.value = value
        input.dispatchEvent(new Event('input', { bubbles: true }))
        input.dispatchEvent(new Event('change', { bubbles: true }))
      }, date)
    }

    const historicalIntradayResponse = waitForChartResponse('intraday')
    await selectIntradayDate('2026-09-09')
    await historicalIntradayResponse
    await page.getByText(/2026-09-09 · 已收盘历史快照/).waitFor()
    if (process.env.E2E_SCREENSHOT_PATH) {
      await page.setViewportSize({ width: 1440, height: 1000 })
      await page.screenshot({ path: process.env.E2E_SCREENSHOT_PATH, fullPage: true })
      await page.setViewportSize({ width: 390, height: 844 })
      await page.screenshot({
        path: process.env.E2E_SCREENSHOT_PATH.replace(/\.png$/i, '-mobile.png'),
        fullPage: true,
      })
      await page.setViewportSize({ width: 1280, height: 720 })
      realMarketScreenshotCaptured = true
    }

    const rangeResponsePromise = waitForChartResponse('intraday', 422)
    await selectIntradayDate('2023-05-09')
    const rangeResponse = await rangeResponsePromise
    const rangeResult = await rangeResponse.json()
    if (
      rangeResult.code !== 'MARKET_INTRADAY_HISTORY_RANGE_EXCEEDED' ||
      !rangeResult.message.includes('2023-09-01')
    ) {
      throw new Error(`历史分钟范围提示不明确：${JSON.stringify(rangeResult)}`)
    }
    await page.getByText(/最早可查看至 2023-09-01/).waitFor()

    const holidayResponsePromise = waitForChartResponse('intraday', 400)
    await selectIntradayDate('2026-09-05')
    const holidayResult = await (await holidayResponsePromise).json()
    if (holidayResult.code !== 'MARKET_INTRADAY_NON_TRADING_DAY') {
      throw new Error(`非交易日没有返回专用错误：${JSON.stringify(holidayResult)}`)
    }
    await page.getByText('所选日期不是该市场的交易日', { exact: true }).waitFor()
    realMarketChartsCompleted = true
  }
  if (process.env.E2E_SCREENSHOT_PATH && !realMarketScreenshotCaptured) {
    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.screenshot({ path: process.env.E2E_SCREENSHOT_PATH, fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 })
    await page.screenshot({
      path: process.env.E2E_SCREENSHOT_PATH.replace(/\.png$/i, '-mobile.png'),
      fullPage: true,
    })
    await page.setViewportSize({ width: 1280, height: 720 })
  }
  await page.getByRole('link', { name: '概览', exact: true }).click()

  const createInvitationResponse = page.waitForResponse((response) => {
    const path = new URL(response.url()).pathname
    return (
      response.request().method() === 'POST' && /^\/api\/workspaces\/\d+\/invitations$/.test(path)
    )
  })
  await page.getByRole('button', { name: '生成邀请码', exact: true }).click()
  if ((await createInvitationResponse).status() !== 201) {
    throw new Error('OWNER没有成功生成邀请码')
  }
  const inviteCode = await page.locator('.current-invitation strong').innerText()
  if (!/^[A-Z0-9]{6}$/.test(inviteCode)) {
    throw new Error('页面没有显示合法的6位邀请码')
  }

  const memberContext = await browser.newContext()
  const memberPage = await memberContext.newPage()
  captureApiResponses(memberPage)
  await memberPage.goto(`${frontendUrl}/register`)
  const memberRegisterInputs = memberPage.locator('input')
  await memberRegisterInputs.nth(0).fill(memberUsername)
  await memberRegisterInputs.nth(1).fill(memberNickname)
  await memberRegisterInputs.nth(2).fill(password)
  await memberRegisterInputs.nth(3).fill(password)
  await memberPage.locator('.auth-submit-button').click()
  await memberPage.waitForURL((url) => url.pathname === '/login')
  await memberPage.locator('input').nth(1).fill(password)
  await memberPage.locator('.auth-submit-button').click()
  await memberPage.waitForURL((url) => url.pathname === '/workspaces')

  await memberPage.locator('#invite-code').fill(inviteCode)
  const joinResponse = memberPage.waitForResponse((response) => {
    const path = new URL(response.url()).pathname
    return response.request().method() === 'POST' && path === '/api/workspaces/join'
  })
  const memberDetailResponse = memberPage.waitForResponse((response) => {
    const path = new URL(response.url()).pathname
    return response.request().method() === 'GET' && /^\/api\/workspaces\/\d+$/.test(path)
  })
  await memberPage.getByRole('button', { name: '使用邀请码加入' }).click()
  if ((await joinResponse).status() !== 200) {
    throw new Error('新成员没有成功使用邀请码加入')
  }
  await memberPage.waitForURL((url) => /^\/workspaces\/\d+\/overview$/.test(url.pathname))
  await memberDetailResponse
  const memberPageText = await memberPage.locator('body').innerText()
  if (!memberPageText.includes('MEMBER') || !memberPageText.includes('2 人')) {
    throw new Error('加入后页面没有显示MEMBER身份和2名有效成员')
  }

  const memberStockListResponse = memberPage.waitForResponse((response) => {
    const path = new URL(response.url()).pathname
    return response.request().method() === 'GET' && /^\/api\/workspaces\/\d+\/stocks$/.test(path)
  })
  await memberPage.getByRole('link', { name: '共享自选', exact: true }).click()
  if ((await memberStockListResponse).status() !== 200) {
    throw new Error('MEMBER没有成功读取共享自选列表')
  }
  if (!(await memberPage.locator('body').innerText()).includes('贵州茅台')) {
    throw new Error('MEMBER没有看到OWNER添加的共享股票')
  }

  const memberListResponse = memberPage.waitForResponse((response) => {
    const path = new URL(response.url()).pathname
    return response.request().method() === 'GET' && /^\/api\/workspaces\/\d+\/members$/.test(path)
  })
  await memberPage.getByRole('link', { name: '成员', exact: true }).click()
  if ((await memberListResponse).status() !== 200) {
    throw new Error('MEMBER没有成功读取成员列表')
  }
  if (!(await memberPage.locator('body').innerText()).includes(ownerNickname)) {
    throw new Error('成员列表没有显示空间OWNER')
  }

  const leaveResponse = memberPage.waitForResponse((response) => {
    const path = new URL(response.url()).pathname
    return response.request().method() === 'POST' && /^\/api\/workspaces\/\d+\/leave$/.test(path)
  })
  await memberPage.getByRole('button', { name: '退出这个空间' }).click()
  await memberPage.getByRole('button', { name: '确认退出空间' }).click()
  if ((await leaveResponse).status() !== 200) {
    throw new Error('MEMBER没有成功主动退出空间')
  }
  await memberPage.waitForURL((url) => url.pathname === '/workspaces')

  await memberPage.locator('#invite-code').fill(inviteCode)
  await memberPage.getByRole('button', { name: '使用邀请码加入' }).click()
  await memberPage.waitForURL((url) => /^\/workspaces\/\d+\/overview$/.test(url.pathname))

  await page.getByRole('link', { name: '成员', exact: true }).click()
  const memberRow = page.locator('.member-row').filter({ hasText: memberNickname })
  const removeResponse = page.waitForResponse((response) => {
    const path = new URL(response.url()).pathname
    return (
      response.request().method() === 'DELETE' &&
      /^\/api\/workspaces\/\d+\/members\/\d+$/.test(path)
    )
  })
  await memberRow.getByRole('button', { name: '移除成员' }).click()
  await page.getByRole('button', { name: '确认移除成员' }).click()
  if ((await removeResponse).status() !== 200) {
    throw new Error('OWNER没有成功移除普通成员')
  }

  await memberPage.goto(`${frontendUrl}/workspaces`)
  await memberPage.locator('#invite-code').fill(inviteCode)
  const rejectedOldInvitation = memberPage.waitForResponse((response) => {
    const path = new URL(response.url()).pathname
    return response.request().method() === 'POST' && path === '/api/workspaces/join'
  })
  await memberPage.getByRole('button', { name: '使用邀请码加入' }).click()
  if ((await rejectedOldInvitation).status() !== 403) {
    throw new Error('被移除成员仍然可以使用移除前的邀请码')
  }

  await page.getByRole('link', { name: '概览', exact: true }).click()
  const regenerateResponse = page.waitForResponse((response) => {
    const path = new URL(response.url()).pathname
    return (
      response.request().method() === 'POST' && /^\/api\/workspaces\/\d+\/invitations$/.test(path)
    )
  })
  await page.getByRole('button', { name: '重新生成邀请码' }).click()
  if ((await regenerateResponse).status() !== 201) {
    throw new Error('OWNER没有成功重新生成邀请码')
  }
  await page.waitForFunction(
    (oldCode) =>
      document.querySelector('.current-invitation strong')?.textContent?.trim() !== oldCode,
    inviteCode,
  )
  const newInviteCode = await page.locator('.current-invitation strong').innerText()
  if (!/^[A-Z0-9]{6}$/.test(newInviteCode) || newInviteCode === inviteCode) {
    throw new Error('重新生成后没有显示新的合法邀请码')
  }

  await memberPage.locator('#invite-code').fill(newInviteCode)
  await memberPage.getByRole('button', { name: '使用邀请码加入' }).click()
  await memberPage.waitForURL((url) => /^\/workspaces\/\d+\/overview$/.test(url.pathname))

  await page.getByRole('link', { name: '成员', exact: true }).click()
  const transferRow = page.locator('.member-row').filter({ hasText: memberNickname })
  const transferResponse = page.waitForResponse((response) => {
    const path = new URL(response.url()).pathname
    return (
      response.request().method() === 'POST' &&
      /^\/api\/workspaces\/\d+\/ownership-transfer$/.test(path)
    )
  })
  await transferRow.getByRole('button', { name: '转让所有权' }).click()
  await page.getByRole('button', { name: '确认转让所有权' }).click()
  if ((await transferResponse).status() !== 200) {
    throw new Error('OWNER没有成功转让所有权')
  }

  await memberPage.getByRole('link', { name: '成员', exact: true }).click()
  const finalOwnerRow = memberPage.locator('.member-row').filter({ hasText: memberNickname })
  if (!(await finalOwnerRow.innerText()).includes('OWNER')) {
    throw new Error('转让后新OWNER的成员页面没有显示最新角色')
  }

  await memberPage.getByRole('link', { name: '设置', exact: true }).click()
  const archiveResponse = memberPage.waitForResponse((response) => {
    const path = new URL(response.url()).pathname
    return response.request().method() === 'POST' && /^\/api\/workspaces\/\d+\/archive$/.test(path)
  })
  await memberPage.getByRole('button', { name: '归档这个空间' }).click()
  await memberPage.getByRole('button', { name: '确认归档空间' }).click()
  if ((await archiveResponse).status() !== 200) {
    throw new Error('新OWNER没有成功归档空间')
  }
  await memberPage.getByText('空间已归档，当前邀请码已停用。', { exact: true }).waitFor()
  if (!(await memberPage.locator('body').innerText()).includes('已归档')) {
    throw new Error('归档后设置页没有显示只读状态')
  }

  const workspacePath = new URL(memberPage.url()).pathname
  const workspaceId = workspacePath.match(/^\/workspaces\/(\d+)\//)?.[1]
  if (!workspaceId) throw new Error('无法从设置页取得当前空间ID')
  await page.goto(`${frontendUrl}/workspaces/${workspaceId}/overview`)
  await page.getByText('这个空间已归档，当前为只读状态。', { exact: true }).waitFor()
  if (!(await page.locator('body').innerText()).includes('当前为只读状态')) {
    throw new Error('普通成员重新进入归档空间后没有看到只读提示')
  }

  const restoreResponse = memberPage.waitForResponse((response) => {
    const path = new URL(response.url()).pathname
    return response.request().method() === 'POST' && /^\/api\/workspaces\/\d+\/restore$/.test(path)
  })
  await memberPage.getByRole('button', { name: '恢复这个空间' }).click()
  await memberPage.getByRole('button', { name: '确认恢复空间' }).click()
  if ((await restoreResponse).status() !== 200) {
    throw new Error('新OWNER没有成功恢复自己归档的空间')
  }

  await memberPage.getByRole('button', { name: '退出登录', exact: true }).click()
  await memberPage.waitForURL((url) => url.pathname === '/')
  const logoutClearedSession = await memberPage.evaluate(
    () =>
      !localStorage.getItem('stock-system-access-token') &&
      !localStorage.getItem('stock-system-current-user'),
  )
  if (!logoutClearedSession) {
    throw new Error('退出登录后本地认证状态没有完全清除')
  }

  const expectedResponses = [
    { method: 'POST', path: '/api/auth/register', status: 201 },
    { method: 'POST', path: '/api/auth/login', status: 200 },
    { method: 'GET', path: '/api/auth/me', status: 200 },
    { method: 'GET', path: '/api/workspaces', status: 200 },
    { method: 'POST', path: '/api/workspaces', status: 201 },
    { method: 'POST', path: '/api/workspaces/join', status: 200 },
  ]
  for (const expected of expectedResponses) {
    const response = apiResponses.find(
      (item) => item.method === expected.method && item.path === expected.path,
    )
    if (!response || response.status !== expected.status) {
      throw new Error(`${expected.method} ${expected.path} 没有在真实浏览器中成功完成`)
    }
  }

  const detailResponse = apiResponses.find(
    (item) => item.method === 'GET' && /^\/api\/workspaces\/\d+$/.test(item.path),
  )
  if (!detailResponse || detailResponse.status !== 200) {
    throw new Error('GET /api/workspaces/{workspaceId} 没有在真实浏览器中成功完成')
  }
  const invitationResponse = apiResponses.find(
    (item) => item.method === 'POST' && /^\/api\/workspaces\/\d+\/invitations$/.test(item.path),
  )
  if (!invitationResponse || invitationResponse.status !== 201) {
    throw new Error('POST /api/workspaces/{workspaceId}/invitations 没有成功完成')
  }

  testResult = {
    success: true,
    ownerUsername,
    memberUsername,
    ownerFinalPath: new URL(page.url()).pathname,
    memberFinalPath: new URL(memberPage.url()).pathname,
    inviteCodeCreated: true,
    memberLifecycleCompleted: true,
    ownershipTransferred: true,
    sharedStockAndInitialOpinionCompleted: true,
    realMarketChartsCompleted,
    workspaceArchivedAndRestored: true,
    logoutClearedSession,
    jwtSaved: tokenSaved,
    apiResponses,
  }
} finally {
  try {
    if (browser) await browser.close()
  } finally {
    await cleanupTestUser(stockExistedBefore)
  }
}

console.log(JSON.stringify({ ...testResult, databaseCleaned: true }, null, 2))
