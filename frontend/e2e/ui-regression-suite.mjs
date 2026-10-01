import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// 各脚本独立验证测试库并在 finally 清理。串行运行，避免管理员与随机 fixture 相互干扰。
const frontend = fileURLToPath(new URL('../', import.meta.url))
const output = resolve(process.env.E2E_UI_OUTPUT ?? '../../ui-preview/ui-10')
const results = []
await mkdir(output, { recursive: true })
for (const name of [
  'shell',
  'stocks',
  'market',
  'research',
  'overview',
  'management',
  'landing',
  'account',
  'governance',
  'auth',
  'admin',
]) {
  const folder = `${output}/regression-${name}`
  await mkdir(folder, { recursive: true })
  const script = ['auth', 'admin'].includes(name) ? `${name}-smoke.mjs` : `ui-${name}-smoke.mjs`
  let passed = false
  let checks = null
  console.log(`开始回归 ${name}`)
  try {
    const stdout = execFileSync(process.execPath, [`e2e/${script}`], {
      cwd: frontend,
      encoding: 'utf8',
      maxBuffer: 8 * 1024 * 1024,
      env: { ...process.env, E2E_UI_PHASE: 'after', E2E_UI_OUTPUT: folder },
    })
    await writeFile(`${folder}/command.log`, stdout)
    if (!['auth', 'admin'].includes(name)) {
      const report = JSON.parse(await readFile(`${folder}/after-verification.json`, 'utf8'))
      assert.equal(report.success, true)
      assert.equal(report.databaseRemaining, 0)
      assert.deepEqual(report.errors, [])
      checks = report.checks.length
    } else if (name === 'auth') {
      const report = JSON.parse(stdout)
      assert.equal(report.success, true)
      assert.equal(report.databaseCleaned, true)
    } else {
      const reports = stdout
        .trim()
        .split(/\r?\n/)
        .map((line) => JSON.parse(line))
      assert.equal(reports[0].desktop, true)
      assert.equal(reports[0].mobile, true)
      assert.equal(reports[0].oldTokenInvalidated, true)
      assert.equal(reports[1].cleanupRemaining, 0)
    }
    passed = true
    console.log(`通过 ${name}${checks === null ? '' : `：${checks} 项`}；数据清理完成`)
  } catch (error) {
    // 保留本轮结果，失败即停，不把后续未运行脚本写成通过。
    const log = String(error.stdout ?? '') + String(error.stderr ?? '')
    await writeFile(`${folder}/failure.log`, log)
    throw error
  } finally {
    results.push({ name, passed, checks })
    await writeFile(
      `${output}/regression-verification.json`,
      JSON.stringify(
        { success: results.length === 11 && results.every((r) => r.passed), results },
        null,
        2,
      ),
    )
  }
}
