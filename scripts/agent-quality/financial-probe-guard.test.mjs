import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'

const script = fileURLToPath(new URL('../../frontend/e2e/research-agent-financial-probe.mjs', import.meta.url))
function execute(args, variables = {}) {
  return spawnSync(process.execPath, [script, ...args], { encoding: 'utf8', windowsHide: true,
    env: { ...process.env, DB_PASSWORD: '', JWT_SECRET: '', DEEPSEEK_API_KEY: '',
      MYSQL_CLIENT_PATH: 'not-a-real-mysql-client', AGENT_FINANCIAL_PROBE_MODE: 'real',
      AGENT_FINANCIAL_MODEL_PROBE: 'false', ...variables } })
}
test('没有数据库、Key或服务也可只读打印完整计划', () => {
  const result = execute(['--dry-run'])
  assert.equal(result.status, 0, result.stderr)
  const plan = JSON.parse(result.stdout)
  assert.equal(plan.cases.length, 6)
  assert.equal(plan.maxModelCallsPerQuestion, 4)
  assert.equal(plan.automaticRetry, false)
  assert.equal(plan.realBudget, 'ORIGINAL_CUMULATIVE_10_YUAN_LEDGER')
  assert.equal(plan.semanticReview, 'REQUIRED')
})
test('未显式启用真实财务检查时，在连接服务或创建数据之前拒绝', () => {
  const result = execute([])
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /真实财务资料检查须另行显式启用/)
  assert.doesNotMatch(result.stderr, /ECONNREFUSED|not-a-real-mysql-client/)
})
test('不接受未知模式或默默切换到其他模型入口', () => {
  const result = execute([], { AGENT_FINANCIAL_PROBE_MODE: 'production' })
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /只能显式选择真实模型或本地替身/)
})
