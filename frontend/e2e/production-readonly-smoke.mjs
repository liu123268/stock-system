import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium } from 'playwright-core'

// Anonymous, read-only production checks. Never register users or write business data.
const base = new URL(process.env.PRODUCTION_URL ?? 'https://134.175.137.81')
assert.equal(base.protocol, 'https:')
assert.equal(base.username, '')
assert.equal(base.password, '')
const output = resolve(process.env.E2E_PRODUCTION_OUTPUT ?? '../target/v1-production-readonly')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({
  executablePath: process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: true,
})
const errors = []
const insecureRequests = []
const checks = []
try {
  // Default certificate validation stays enabled.
  const context = await browser.newContext()
  const page = await context.newPage()
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    if (request.url().startsWith('http:')) insecureRequests.push(request.url())
  })
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 1000 })
    for (const path of ['/', '/login']) {
      const response = await page.goto(new URL(path, base).href)
      assert.equal(response.status(), 200)
      await page.waitForLoadState('networkidle')
      assert.ok((await page.title()).includes('基点'))
      assert.ok(await page.locator('body').innerText())
      await page.screenshot({ path: resolve(output, `${path === '/' ? 'home' : 'login'}-${width}.png`), fullPage: true })
      checks.push({ path, width, status: response.status() })
    }
  }
  const me = await context.request.get(new URL('/api/auth/me', base).href)
  assert.equal(me.status(), 401)
  const missing = await context.request.get(new URL('/assets/v1-missing-asset.js', base).href)
  assert.equal(missing.status(), 404)
  const redirect = await context.request.get(`http://${base.host}/`, { maxRedirects: 0 })
  assert.ok([301, 302, 307, 308].includes(redirect.status()))
  assert.equal(new URL(redirect.headers().location).protocol, 'https:')
  assert.deepEqual(errors, [])
  assert.deepEqual(insecureRequests, [])
  const result = { success: true, url: base.origin, checks, authStatus: me.status(), missingStatus: missing.status(), httpsRedirect: true, errors, businessWrites: 0 }
  await writeFile(resolve(output, 'verification.json'), JSON.stringify(result, null, 2))
  console.log(JSON.stringify(result))
} finally {
  await browser.close()
}
