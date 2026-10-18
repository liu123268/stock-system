import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'
import { cases, selectCases, loadCatalog, expectedFor, checkProgramResult, continuationRows, pricing } from './report-cases.mjs'

const catalog = loadCatalog()
const trace = { modelCalls: 2, toolCalls: 1, tools: [{ name: 'search_report_passages', status: 'COMPLETED' }] }
function sample(testCase = cases[0]) {
  const passages = expectedFor(testCase, catalog).passages.slice(0, 1)
  const evidence = passages.map(reportPassage => ({ sourceId: `report-${reportPassage.id}`, type: 'REPORT', reportPassage }))
  return { status: 'SUCCEEDED', answer: { references: [], followUp: { question: testCase.question, evidence,
    reply: { paragraphs: [{ text: '这是程序契约样本，不评价文字的语义。', sourceIds: evidence.map(item => item.sourceId) }], missingInformation: [] } } } }
}
test('目录提供原文位置，检查通过仍保留人工语义审查', () => {
  assert.equal(expectedFor(cases[0], catalog).passages[0].section, 'tx-marketing')
  assert.deepEqual(checkProgramResult(cases[0], sample(), trace, catalog), { programContract: 'PASSED', semanticReview: 'PENDING_HUMAN_REVIEW' })
})
test('错误正文、页码、版本或股票不能沿用真实来源编号', () => {
  for (const [field, value] of [['text', '伪造原文'], ['pdfPage', 1], ['reportVersion', 'f'.repeat(64)], ['symbol', '600519.SH']]) {
    const run = sample(); run.answer.followUp.evidence[0].reportPassage = { ...run.answer.followUp.evidence[0].reportPassage, [field]: value }
    assert.throws(() => checkProgramResult(cases[0], run, trace, catalog))
  }
})
test('正确来源身份不等于引用了所需章节', () => {
  const run = sample(); const wrong = catalog.passages.find(row => row.section === 'tx-fintech')
  run.answer.followUp.evidence[0] = { sourceId: `report-${wrong.id}`, type: 'REPORT', reportPassage: wrong }
  run.answer.followUp.reply.paragraphs[0].sourceIds = [`report-${wrong.id}`]
  assert.throws(() => checkProgramResult(cases[0], run, trace, catalog))
})
test('缺年度或无答案题必须没有报告依据，不能假称匹配', () => {
  const missing = cases.find(item => item.id === 'report-missing-year')
  assert.equal(checkProgramResult(missing, sample(missing), trace, catalog).programContract, 'PASSED')
  const forged = sample(); forged.answer.followUp.question = missing.question
  assert.throws(() => checkProgramResult(missing, forged, trace, catalog))
})
test('未知引用、未使用的来源或没有真正检索都拒绝', () => {
  const unknown = sample(); unknown.answer.followUp.reply.paragraphs[0].sourceIds.push('made-up')
  assert.throws(() => checkProgramResult(cases[0], unknown, trace, catalog))
  const unused = sample(); unused.answer.followUp.reply.paragraphs[0].sourceIds = []
  assert.throws(() => checkProgramResult(cases[0], unused, trace, catalog))
  assert.throws(() => checkProgramResult(cases[0], sample(), { ...trace, tools: [] }, catalog))
})
test('题目选择和续做严格保留同一题库及范围', () => {
  assert.throws(() => selectCases(['report-marketing', 'report-marketing']))
  assert.throws(() => selectCases(['moutai-annual']))
  const selected = selectCases(['report-marketing', 'report-channel-plan'])
  const previous = { mode: 'fixture', plan: { suite: 'reports', maxQuestions: 2, maxModelCallsPerQuestion: 4, automaticRetry: false,
    realBudget: 'ORIGINAL_CUMULATIVE_10_YUAN_LEDGER', pricing, cases: selected }, results: [{ id: selected[0].id, run: sample(), trace }] }
  assert.equal(continuationRows(previous, 'fixture', catalog, selected)[0].reusedWithoutModelCall, true)
  assert.throws(() => continuationRows({ ...previous, plan: { ...previous.plan, suite: 'financial' } }, 'fixture', catalog, selected))
  assert.throws(() => continuationRows(previous, 'real', catalog, selected))
})
const script = fileURLToPath(new URL('../../frontend/e2e/research-agent-financial-probe.mjs', import.meta.url))
function execute(args) {
  return spawnSync(process.execPath, [script, ...args], { encoding: 'utf8', windowsHide: true,
    env: { ...process.env, DB_PASSWORD: '', JWT_SECRET: '', DEEPSEEK_API_KEY: '', AGENT_FINANCIAL_MODEL_PROBE: 'true',
      AGENT_REPORT_MODEL_PROBE: 'false', AGENT_FINANCIAL_PROBE_MODE: 'real' } })
}
test('报告题库可离线打印六题计划，财务授权开关不能代替报告授权', () => {
  const result = execute(['--suite=reports', '--dry-run'])
  assert.equal(result.status, 0, result.stderr)
  const plan = JSON.parse(result.stdout)
  assert.equal(plan.suite, 'reports'); assert.equal(plan.cases.length, 6); assert.equal(plan.automaticRetry, false)
  const denied = execute(['--suite=reports', '--cases=report-marketing'])
  assert.notEqual(denied.status, 0); assert.match(denied.stderr, /真实报告原文检查须另行显式启用/)
  assert.doesNotMatch(denied.stderr, /ECONNREFUSED/)
})
test('未知题库、重复题库或跨题库选择在外部连接前拒绝', () => {
  for (const args of [['--suite=unknown'], ['--suite=reports', '--suite=financial'], ['--suite=reports', '--cases=moutai-annual']]) {
    const result = execute(args)
    assert.notEqual(result.status, 0); assert.doesNotMatch(result.stderr, /ECONNREFUSED|真实报告原文检查须另行显式启用/)
  }
})
