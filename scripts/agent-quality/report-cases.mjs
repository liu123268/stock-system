import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { pricing, loadCatalog as financialCatalog, expectedFor as expectedFinancial,
  checkProgramResult as checkFinancial } from './financial-cases.mjs'

export { pricing }
export const suite = 'reports'
export const cases = Object.freeze([
  { id: 'report-marketing', symbol: '00700.HK', sections: ['tx-marketing'],
    question: '请根据2025年报告原文，说明营销服务收入增长的解释，并给出对应报告依据。',
    review: ['回答广告单价及曝光量的增长，不用金融科技的原因替代', '引用确实支持所述因素，报告年度不冒充现在的事实'] },
  { id: 'report-channel-plan', symbol: '600519.SH', sections: ['mt-channels'],
    question: '2025年报告原文怎样安排未来的线上与线下渠道？请区分未来计划与已经完成的事实。',
    review: ['说明线上管效率触达、线下管转化服务', '将构建和未来安排保留计划语气，不写成已经全部完成'] },
  { id: 'report-combined', symbol: '600519.SH', sections: ['mt-operations'], period: 'FY 2025', metrics: ['REVENUE'], opinions: true,
    question: '请结合成员对订单的判断、2025年度营业收入与报告原文的采购销售模式，说明能否确认新订单已经增长。',
    review: ['确实读取观点、年度财务数字和报告采购销售模式三类资料', '正文金额及同比逐项核对原快照，保留人民币元，不接受自行换算或改数',
      '区分成员猜测与披露事实，营收和经营模式不能证明新增订单已经增长'] },
  { id: 'report-missing-year', symbol: '00700.HK', sections: [],
    question: '请查询2024年报告原文中的营销服务增长原因；没有该年度原文时明确说明，不用2025年原文替代。',
    review: ['说明当前系统没有该年度受审原文章节', '不能将没有入库说成整份官方报告没有，也不引用2025年替代'] },
  { id: 'report-no-match', symbol: '00700.HK', sections: [],
    question: '报告原文说明了载人飞船的发射时间和轨道高度吗？本次没有相关段落时请说明覆盖范围。',
    review: ['明确已入库章节未匹配，不编造飞船数字或报告引用', '不声称核对过整份年报'] },
  { id: 'report-orders-missing', symbol: '600519.SH', sections: [],
    question: '报告原文披露的新增订单总金额和未交付合同余额分别是多少？没有相关段落时不要用营业收入代替。',
    review: ['不编造订单金额、合同余额或财务引用', '空检索只是当前章节的资料缺口'] },
])

export function selectCases(ids) {
  if (ids === undefined) return cases
  assert.ok(Array.isArray(ids) && ids.length > 0, '至少选择一个题目编号')
  assert.equal(new Set(ids).size, ids.length, '题目编号不能重复')
  return ids.map(id => { const selected = cases.find(item => item.id === id); assert.ok(selected, `未知题目编号：${id}`); return selected })
}
export function loadCatalog() {
  return { ...JSON.parse(readFileSync(new URL('../../src/main/resources/reports/reviewed-passages.json', import.meta.url), 'utf8')),
    financial: financialCatalog() }
}
export function expectedFor(testCase, catalog) {
  const passages = catalog.passages.filter(row => row.symbol === testCase.symbol && testCase.sections.includes(row.section))
  for (const section of testCase.sections) assert.ok(passages.some(row => row.section === section), '问题必须有受审原文位置')
  return { sections: testCase.sections, passages, ...(testCase.period ? { financial: expectedFinancial(testCase, catalog.financial) } : {}) }
}
// Only checks source identity, scope, actual tool execution and persistence, never semantic support.
export function checkProgramResult(testCase, run, trace, catalog) {
  assert.equal(run.status, 'SUCCEEDED', `${testCase.id}: ${run.errorCode ?? '运行失败'}`)
  assert.equal(run.answer?.followUp?.question, testCase.question)
  assert.ok(trace.modelCalls >= 1 && trace.modelCalls <= 4)
  assert.ok(trace.toolCalls >= 1 && trace.toolCalls <= 3)
  assert.ok(trace.tools.some(item => item.name === 'search_report_passages' && item.status === 'COMPLETED'))
  const followUp = run.answer.followUp
  const reports = followUp.evidence.filter(item => item.type === 'REPORT')
  const citations = new Set(followUp.reply.paragraphs.flatMap(item => item.sourceIds))
  const allowed = new Set([...run.answer.references.map(item => item.sourceId), ...followUp.evidence.map(item => item.sourceId)])
  for (const citation of citations) assert.ok(allowed.has(citation), '回答不能引用本次未取得的来源')
  for (const item of reports) {
    const original = catalog.passages.find(row => row.id === item.reportPassage?.id)
    assert.ok(original, '报告引用必须来自受审目录')
    assert.deepEqual(item.reportPassage, original, '正文、报告版本、页码和位置必须完全一致')
    assert.equal(original.symbol, testCase.symbol)
    assert.equal(item.sourceId, `report-${original.id}`)
    assert.ok(citations.has(item.sourceId), '只保存本次实际引用的报告依据')
  }
  if (testCase.sections.length) {
    for (const section of testCase.sections) assert.ok(reports.some(item => item.reportPassage.section === section), '所需章节未实际引用')
  } else assert.equal(reports.length, 0, '缺年度或空候选不能伪造报告引用')
  if (testCase.period) checkFinancial(testCase, run, trace, catalog.financial)
  else assert.ok(followUp.evidence.every(item => item.type === 'REPORT'), '单纯报告题不能用财务或报价替代原文')
  if (testCase.opinions) {
    assert.ok(trace.tools.some(item => item.name === 'read_cycle_opinions' && item.status === 'COMPLETED'))
    assert.ok(run.answer.references.some(item => citations.has(item.sourceId)), '组合题必须引用所读取的成员观点')
  }
  return { programContract: 'PASSED', semanticReview: 'PENDING_HUMAN_REVIEW' }
}
export function continuationRows(previous, mode, catalog, selectedCases = cases) {
  assert.equal(previous.mode, mode); assert.equal(previous.plan.suite, suite)
  assert.equal(previous.plan.maxQuestions, selectedCases.length)
  assert.equal(previous.plan.maxModelCallsPerQuestion, 4); assert.equal(previous.plan.automaticRetry, false)
  assert.equal(previous.plan.realBudget, 'ORIGINAL_CUMULATIVE_10_YUAN_LEDGER')
  assert.deepEqual(previous.plan.pricing, pricing)
  assert.deepEqual(previous.plan.cases.map(item => [item.id, item.question]), selectedCases.map(item => [item.id, item.question]))
  assert.ok(previous.results.length > 0 && previous.results.length < selectedCases.length)
  const seen = new Set()
  return previous.results.map(row => {
    assert.ok(!seen.has(row.id)); seen.add(row.id)
    const selected = selectedCases.find(item => item.id === row.id); assert.ok(selected)
    return { ...row, checks: checkProgramResult(selected, row.run, row.trace, catalog), reusedWithoutModelCall: true }
  })
}
