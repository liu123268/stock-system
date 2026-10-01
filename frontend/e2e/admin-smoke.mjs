import { execFileSync } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { chromium } from 'playwright-core'

const chromePath =
  process.env.CHROME_PATH ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const mysqlPath = process.env.MYSQL_CLIENT_PATH ?? 'mysql.exe'
const frontendUrl = process.env.FRONTEND_URL ?? 'http://localhost:18083'
const backendUrl = process.env.BACKEND_URL ?? 'http://localhost:18082'
const runId = Date.now().toString(36)
const adminUsername = `adm_${runId}`.slice(0, 20)
const userUsername = `usr_${runId}`.slice(0, 20)
const workspaceName = `治理浏览器${runId}`
const password = 'BrowserPass123'
const propertiesUrls = [
  new URL('../../src/main/resources/application.properties', import.meta.url),
  new URL('../../src/main/resources/application-test.properties', import.meta.url),
]

async function databaseConfig() {
  const properties = new Map()
  for (const url of propertiesUrls) {
    const content = await readFile(url, 'utf8')
    for (const raw of content.split(/\r?\n/)) {
      const line = raw.trim()
      if (!line || line.startsWith('#')) continue
      const separator = line.indexOf('=')
      if (separator > 0) properties.set(line.slice(0, separator), line.slice(separator + 1))
    }
  }
  const jdbcUrl = properties.get('spring.datasource.url')
  const match = jdbcUrl?.match(/^jdbc:mysql:\/\/([^:/?]+)(?::(\d+))?\/([^?]+)/)
  if (!match) throw new Error('无法解析测试数据库连接')

  const resolve = (value) => {
    const placeholder = value?.match(/^\$\{([A-Z0-9_]+)(?::([^}]*))?\}$/)
    return placeholder ? (process.env[placeholder[1]] ?? placeholder[2]) : value
  }
  return {
    host: match[1],
    port: match[2] ?? '3306',
    database: match[3],
    user: resolve(properties.get('spring.datasource.username')),
    password: resolve(properties.get('spring.datasource.password')),
  }
}

async function sql(statement) {
  const config = await databaseConfig()
  if (config.database !== 'stock_system_test') {
    throw new Error(`拒绝操作非测试数据库：${config.database}`)
  }
  return execFileSync(
    mysqlPath,
    [
      `--host=${config.host}`,
      `--port=${config.port}`,
      `--user=${config.user}`,
      `--database=${config.database}`,
      '--default-character-set=utf8mb4',
      '--batch',
      '--skip-column-names',
      `--execute=${statement}`,
    ],
    { encoding: 'utf8', env: { ...process.env, MYSQL_PWD: config.password } },
  ).trim()
}

async function verifyEnvironment() {
  for (const baseUrl of [backendUrl, frontendUrl]) {
    const response = await fetch(`${baseUrl}/api/test/environment`)
    if (!response.ok) throw new Error(`${baseUrl}不是可验证的test环境`)
    const result = await response.json()
    if (result?.data?.profile !== 'test' || result?.data?.database !== 'stock_system_test') {
      throw new Error(`拒绝浏览器写入：${baseUrl}实际环境为${JSON.stringify(result?.data)}`)
    }
  }
  if ((await sql('SELECT DATABASE()')) !== 'stock_system_test') {
    throw new Error('拒绝操作：SQL 客户端实际连接不是 stock_system_test')
  }
  if (Number(await sql('SELECT COUNT(*) FROM super_admin')) !== 0) {
    throw new Error('测试库已有超级管理员，拒绝覆盖非本轮数据')
  }
}

async function api(path, options = {}) {
  const response = await fetch(`${frontendUrl}/api${path}`, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...options.headers },
  })
  const body = await response.json()
  if (!response.ok) throw new Error(`${path}失败：${response.status} ${JSON.stringify(body)}`)
  return body.data
}

async function seed() {
  await api('/auth/register', {
    method: 'POST',
    body: JSON.stringify({ username: adminUsername, nickname: '浏览器治理员', password }),
  })
  const user = await api('/auth/register', {
    method: 'POST',
    body: JSON.stringify({ username: userUsername, nickname: '浏览器普通用户', password }),
  })
  const adminId = Number(await sql(`SELECT id FROM app_user WHERE username='${adminUsername}'`))
  await sql(`INSERT INTO super_admin(id,user_id) VALUES (1,${adminId})`)

  const login = await api('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username: userUsername, password }),
  })
  const workspace = await api('/workspaces', {
    method: 'POST',
    headers: { Authorization: `Bearer ${login.accessToken}` },
    body: JSON.stringify({ name: workspaceName }),
  })
  return { adminId, userId: user.userId, userToken: login.accessToken, workspaceId: workspace.workspaceId }
}

async function cleanup(ids = {}) {
  if (!/^adm_[a-z0-9]+$/.test(adminUsername) || !/^usr_[a-z0-9]+$/.test(userUsername)) {
    throw new Error('拒绝清理不符合本轮标识的账号')
  }
  if (!/^治理浏览器[a-z0-9]+$/.test(workspaceName)) {
    throw new Error('拒绝清理不符合本轮标识的空间')
  }
  const statement = [
    `SET @admin_id=(SELECT id FROM app_user WHERE username='${adminUsername}' LIMIT 1)`,
    `SET @user_id=(SELECT id FROM app_user WHERE username='${userUsername}' LIMIT 1)`,
    `SET @workspace_id=(SELECT id FROM workspace WHERE name='${workspaceName}' LIMIT 1)`,
    `DELETE FROM super_admin_operation_log WHERE (target_id IN (@admin_id,@user_id) AND action IN ('DISABLE_USER','RESTORE_USER')) OR (target_id=@workspace_id AND action IN ('ARCHIVE_WORKSPACE','RESTORE_WORKSPACE'))`,
    `DELETE FROM workspace_invitation WHERE workspace_id=@workspace_id`,
    `DELETE FROM workspace_member WHERE workspace_id=@workspace_id`,
    `DELETE FROM workspace WHERE id=@workspace_id AND name='${workspaceName}'`,
    `DELETE FROM super_admin WHERE id=1 AND user_id=@admin_id`,
    `DELETE FROM app_user WHERE id IN (@admin_id,@user_id) AND username IN ('${adminUsername}','${userUsername}')`,
    `SELECT (SELECT COUNT(*) FROM app_user WHERE username IN ('${adminUsername}','${userUsername}')) + (SELECT COUNT(*) FROM workspace WHERE name='${workspaceName}') + (SELECT COUNT(*) FROM super_admin WHERE user_id=@admin_id) + (SELECT COUNT(*) FROM super_admin_operation_log WHERE target_id IN (@admin_id,@user_id,@workspace_id))`,
  ].join('; ')
  const remaining = Number((await sql(statement)).split(/\r?\n/).at(-1))
  if (remaining !== 0) throw new Error(`治理浏览器测试清理后仍剩余${remaining}条数据`)
  return { ...ids, remaining }
}

let browser
let ids
try {
  await verifyEnvironment()
  ids = await seed()
  browser = await chromium.launch({ executablePath: chromePath, headless: true })
  const page = await browser.newPage({ viewport: { width: 1440, height: 960 } })

  await page.goto(`${frontendUrl}/login`)
  await page.getByLabel('登录账号', { exact: true }).fill(adminUsername)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '进入空间' }).click()
  await page.waitForURL('**/admin/users')
  await page.getByRole('heading', { name: '用户治理' }).waitFor()

  await page.getByLabel('搜索用户').fill(userUsername)
  await page.getByRole('button', { name: '查询' }).click()
  const userRow = page.locator('tbody tr').filter({ hasText: userUsername })
  await userRow.getByRole('button', { name: '禁用' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: '确认禁用' }).click()
  await page.getByText(`已禁用 ${userUsername}`).waitFor()

  const disabledResponse = await fetch(`${frontendUrl}/api/auth/me`, {
    headers: { Authorization: `Bearer ${ids.userToken}` },
  })
  const disabledBody = await disabledResponse.json()
  if (disabledResponse.status !== 401 || disabledBody.code !== 'ACCOUNT_DISABLED') {
    throw new Error(`旧Token未立即失效：${disabledResponse.status} ${JSON.stringify(disabledBody)}`)
  }

  await userRow.getByRole('button', { name: '恢复' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: '确认恢复' }).click()
  await page.getByText(`已恢复 ${userUsername}`).waitFor()

  await page.getByRole('link', { name: '空间治理' }).click()
  await page.getByLabel('搜索空间').fill(workspaceName)
  await page.getByRole('button', { name: '查询' }).click()
  const workspaceRow = page.locator('tbody tr').filter({ hasText: workspaceName })
  await workspaceRow.getByRole('button', { name: '治理归档' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: '确认归档' }).click()
  await page.getByText(`已治理归档 ${workspaceName}`).waitFor()
  await workspaceRow.getByRole('button', { name: '恢复' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: '确认恢复' }).click()
  await page.getByText(`已恢复 ${workspaceName}`).waitFor()

  await page.getByRole('link', { name: '操作记录' }).click()
  await page.getByRole('heading', { name: '操作记录' }).waitFor()
  const logRows = page.locator('tbody tr')
  if ((await logRows.count()) < 4) throw new Error('治理操作记录少于4条')

  await page.setViewportSize({ width: 390, height: 844 })
  await page.reload()
  await page.getByRole('heading', { name: '操作记录' }).waitFor()
  await page.getByRole('navigation', { name: '治理后台导航' }).waitFor()

  console.log(JSON.stringify({
    environment: 'stock_system_test',
    desktop: true,
    mobile: true,
    oldTokenInvalidated: true,
    governanceLogs: await logRows.count(),
  }))
} finally {
  if (browser) await browser.close()
  const cleanupResult = await cleanup(ids)
  console.log(JSON.stringify({ cleanupRemaining: cleanupResult.remaining }))
}
