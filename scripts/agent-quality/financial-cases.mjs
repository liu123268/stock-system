import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

export const pricing = Object.freeze({
  checkedOn: '2026-10-17', model: 'deepseek-flash', providerRevision: 'DeepSeek-V4.1-Flash-1017',
  inputPerMillionYuan: '2', outputPerMillionYuan: '8',
  source: 'https://api-docs.deepseek.com/zh-cn/quick_start/pricing/',
})

export const cases = Object.freeze([
  { id: 'moutai-annual', symbol: '600519.SH', period: 'FY 2025', comparisonPeriods: ['FY 2024'], metrics: ['REVENUE', 'NET_PROFIT'],
    question: '请查询这只股票2025年营业收入和归母净利润，说明原报币种、金额单位和与2024年同口径相比的变化。',
    review: ['两项金额与原报一致，单位为人民币元', '同比使用2024比较列，不用成员推测或股价替代财务数字'] },
  { id: 'tencent-original-currency', symbol: '00700.HK', period: 'FY 2025', comparisonPeriods: ['FY 2024'], metrics: ['REVENUE', 'NET_PROFIT'],
    question: '请查询这只股票2025年收入及本公司权益持有人应占盈利，并比较2024年；请使用报告原币种和单位，不要按股票交易币种改写。',
    review: ['收入751766、权益持有人应占盈利224842，原报单位为人民币百万元', '港股交易币种不能把财务事实改写为港币'] },
  { id: 'missing-comparable-year', symbol: '00700.HK', period: 'FY 2024', metrics: ['REVENUE'],
    question: '请查2024年收入，并说明能否利用本次已有报告库计算相对2023年的同比。缺少可比期时请说明，不要推算。',
    review: ['2024收入660257人民币百万元', '缺2023比较列，不编造同比，不把2025与2024比较当成所问同比'] },
  { id: 'quarter-not-covered', symbol: '00700.HK', noFinancialEvidence: true,
    question: '请给出2025年第三季度单季度营业收入。系统资料没有这个季度时请直说，不要用全年收入替代。',
    review: ['明确单季度资料未覆盖', '不拿2025全年或2024比较列充当2025第三季度金额'] },
  { id: 'revenue-is-not-orders', symbol: '600519.SH', period: 'FY 2025', metrics: ['REVENUE'],
    question: '请读取2025年营业收入，再判断这个数字能否证实成员甲猜测的“新订单已经增长”。请区分营收事实与订单推测。',
    review: ['确实查询2025营业收入', '收入指标不能证明订单已经增长或增长原因，不将自造观点当权威披露'] },
  { id: 'quote-focused-gaps', symbol: '00700.HK', quote: true,
    question: '现在最新可用价是多少？请只说明本次报价、币种、数据时间、来源和是否模拟，以及影响回答这个报价问题的必要缺口。',
    review: ['查询本轮报价并说明模拟标记', '不额外列出与本次报价问题无关的日K、财务或公告缺口'] },
])

// 显式选择时只接受题库中的唯一编号；输入有误不能退回执行整套题。
export function selectCases(ids) {
  if (ids === undefined) return cases
  assert.ok(Array.isArray(ids) && ids.length > 0, '至少选择一个题目编号')
  assert.equal(new Set(ids).size, ids.length, '题目编号不能重复')
  return ids.map(id => {
    const selected = cases.find(item => item.id === id)
    assert.ok(selected, `未知题目编号：${id}`)
    return selected
  })
}

export function loadCatalog() {
  return JSON.parse(readFileSync(new URL('../../src/main/resources/financial/official-annual-reports.json', import.meta.url), 'utf8'))
}

function symbolKey(symbol) { return symbol.replace(/^0+(?=\d)/, '') }
export function reportFor(catalog, symbol) {
  const reports = catalog.reports.filter(report => symbolKey(report.symbol) === symbolKey(symbol))
  assert.equal(reports.length, 1, '报告对象必须唯一匹配')
  return reports[0]
}

export function expectedFor(testCase, catalog) {
  if (!testCase.period) return { financialEvidence: false, quote: !!testCase.quote }
  const report = reportFor(catalog, testCase.symbol)
  const year = Number(testCase.period.slice(3))
  return { symbol: report.symbol, period: testCase.period, currency: report.currency, unit: report.unit,
    report: { title: report.title, url: report.url, sha256: report.sha256 },
    facts: testCase.metrics.map(metric => {
      const current = report.values.find(value => value.metric === metric && value.year === year)
      assert.ok(current, '题目必须有已核实目标期')
      const comparable = report.values.find(value => value.metric === metric && value.year === year - 1)
      return { metric, current: current.amount, comparable: comparable?.amount ?? null, pdfPages: current.pdfPages }
    }) }
}

// 这里只验工具快照和程序契约；回答是否支持结论必须对照 review 项人工检查。
export function checkProgramResult(testCase, run, trace, catalog) {
  assert.equal(run.status, 'SUCCEEDED', `${testCase.id}: ${run.errorCode ?? '运行失败'}`)
  assert.equal(run.answer?.followUp?.question, testCase.question)
  assert.ok(trace.modelCalls >= 1 && trace.modelCalls <= 4)
  assert.ok(trace.toolCalls <= 3)
  const evidence = run.answer.followUp.evidence
  const financial = evidence.filter(item => item.type === 'FINANCIAL')
  if (testCase.quote) {
    assert.ok(evidence.some(item => item.type === 'QUOTE' && item.quote?.simulated === true))
    assert.equal(financial.length, 0, '报价问题不应额外获取财务数字')
  }
  if (testCase.noFinancialEvidence) assert.equal(financial.length, 0, '缺季度时不能用全年引用冒充')
  if (testCase.period) {
    const expected = expectedFor(testCase, catalog)
    const report = reportFor(catalog, testCase.symbol)
    const matching = financial.filter(item => item.financialFacts.selectedPeriod === testCase.period)
    assert.ok(matching.length, '必须实际取得所问年度的财务依据')
    const allowedPeriods = new Set([testCase.period, ...(testCase.comparisonPeriods ?? [])])
    for (const item of financial) {
      const facts = item.financialFacts
      assert.equal(symbolKey(facts.symbol), symbolKey(testCase.symbol))
      assert.ok(allowedPeriods.has(facts.selectedPeriod), '只允许题目要求的本期和比较年度')
      for (const fact of facts.facts) {
        assert.equal(fact.current.period, facts.selectedPeriod)
        if (fact.comparable) assert.equal(fact.comparable.period, `FY ${Number(facts.selectedPeriod.slice(3)) - 1}`)
        for (const value of [fact.current, fact.comparable].filter(Boolean)) {
          const source = report.values.find(row => row.metric === fact.metric && `FY ${row.year}` === value.period)
          assert.ok(source, '引用数字必须来自当前对象已核实目录')
          assert.equal(typeof value.amount, 'string', '金额不能经过浮点数转换')
          assert.equal(value.amount, source.amount)
          assert.equal(value.currency, expected.currency)
          assert.equal(value.unit, expected.unit)
          assert.equal(value.currencyBasis, 'ISSUER_ORIGINAL')
          assert.equal(value.profitBasis, fact.metric === 'NET_PROFIT' ? 'PARENT_SHAREHOLDERS' : 'NOT_APPLICABLE')
        }
        const reference = facts.reports.find(source => source.metric === fact.metric)
        assert.ok(reference)
        assert.equal(reference.sha256, expected.report.sha256)
        assert.equal(reference.url, expected.report.url)
        assert.equal(reference.reportPeriod, 'FY 2025')
        const source = report.values.find(row => row.metric === fact.metric && `FY ${row.year}` === fact.current.period)
        assert.deepEqual(reference.pdfPages, source.pdfPages)
      }
    }
    for (const wanted of expected.facts) {
      const fact = matching.flatMap(item => item.financialFacts.facts).find(item => item.metric === wanted.metric)
      assert.ok(fact, '回答所需科目缺失')
      assert.equal(fact.current.amount, wanted.current)
      if (wanted.comparable === null) {
        assert.equal(fact.comparable, null)
        assert.equal(fact.change.percent, null)
        assert.ok(fact.change.unavailableReason)
      } else assert.equal(fact.comparable?.amount, wanted.comparable)
    }
  }
  return { programContract: 'PASSED', semanticReview: 'PENDING_HUMAN_REVIEW' }
}

// 仅续做尚未发问的题；保存结果必须能离线重新通过，不重发已执行问题。
export function continuationRows(previous, mode, catalog, selectedCases = cases) {
  assert.equal(previous.mode, mode, '不能混用真实模型与替身结果')
  assert.equal(previous.plan.maxQuestions, selectedCases.length)
  assert.equal(previous.plan.maxModelCallsPerQuestion, 4)
  assert.equal(previous.plan.automaticRetry, false)
  assert.equal(previous.plan.realBudget, 'ORIGINAL_CUMULATIVE_10_YUAN_LEDGER')
  assert.deepEqual(previous.plan.pricing, pricing, '续做须使用同一模型版本及费用条件')
  assert.deepEqual(previous.plan.cases.map(item => [item.id, item.question]), selectedCases.map(item => [item.id, item.question]))
  assert.ok(previous.results.length > 0 && previous.results.length < selectedCases.length, '仅接受已执行部分题目的记录')
  const seen = new Set()
  return previous.results.map(row => {
    assert.ok(!seen.has(row.id), '不能重复计算或执行同一题')
    seen.add(row.id)
    const testCase = selectedCases.find(item => item.id === row.id)
    assert.ok(testCase, '不能续做其他问题集')
    return { ...row, checks: checkProgramResult(testCase, row.run, row.trace, catalog), reusedWithoutModelCall: true }
  })
}
