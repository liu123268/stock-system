import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

export const dimensions = Object.freeze(['sourceSupport', 'factBoundary', 'relevance', 'taskCompletion'])
export const artifactHash = artifact => createHash('sha256').update(JSON.stringify(artifact, null, 2)).digest('hex')
const ratio = (passed, total) => ({ passed, total, rate: total ? passed / total : null })
const sha = value => createHash('sha256').update(JSON.stringify(value)).digest('hex')
export function resultHash(row) { const copy = { ...row }; delete copy.manualReview; return artifactHash(copy) }

export function checkDecision(row, decision) {
  assert.equal(decision.id, row.id, '审查决定不属于本次执行')
  assert.equal(decision.resultSha256, resultHash(row), '正文或依据已变化，须重新审查')
  assert.ok(typeof decision.reviewer === 'string' && decision.reviewer.trim())
  assert.ok(Number.isFinite(Date.parse(decision.reviewedAt)))
  assert.deepEqual(Object.keys(decision.dimensions).sort(), [...dimensions].sort())
  for (const rating of Object.values(decision.dimensions)) {
    assert.ok(['PASSED', 'FAILED'].includes(rating.verdict), '逐题放行必须有完整结论')
    assert.ok(typeof rating.note === 'string' && rating.note.trim(), '逐题审查须写具体依据')
  }
  return decision
}

function embeddedReview(artifact) {
  const reviewed = artifact.results.filter(row => row.manualReview)
  if (!reviewed.length) return undefined
  for (const row of reviewed) checkDecision(row, row.manualReview)
  return { artifactSha256: artifactHash(artifact), reviewer: 'PER_ROW_SOURCE_REVIEW',
    reviewedAt: reviewed.at(-1).manualReview.reviewedAt,
    rows: reviewed.map(row => ({ id: row.id, dimensions: row.manualReview.dimensions })) }
}

function planned(artifact) {
  assert.ok(Array.isArray(artifact.plan?.cases) && artifact.plan.cases.length > 0, '缺少冻结题集')
  assert.equal(new Set(artifact.plan.cases.map(item => item.id)).size, artifact.plan.cases.length, '计划题号重复')
  const executions = artifact.plan.executions ?? artifact.plan.cases.map(item => ({ id: item.id, caseId: item.id, sample: 1 }))
  assert.equal(executions.length, artifact.plan.maxQuestions, '执行分母必须与完整计划一致')
  assert.equal(new Set(executions.map(item => item.id)).size, executions.length, '计划执行编号重复')
  for (const item of artifact.plan.cases) {
    const samples = executions.filter(row => row.caseId === item.id).map(row => row.sample).sort((a, b) => a - b)
    assert.deepEqual(samples, Array.from({ length: item.repeats ?? 1 }, (_, i) => i + 1), '执行样本不能少于或超出预先计划')
  }
  return executions.map(item => {
    const testCase = artifact.plan.cases.find(row => row.id === item.caseId)
    assert.ok(testCase, '执行编号必须对应题集')
    return { ...item, testCase }
  })
}

function validateReview(artifact, review, ids) {
  if (!review) return new Map()
  assert.equal(review.artifactSha256, artifactHash(artifact), '人工审查不属于这份结果；禁止挪用旧评分')
  assert.ok(Array.isArray(review.rows))
  const rows = new Map()
  for (const row of review.rows) {
    assert.ok(ids.has(row.id) && !rows.has(row.id), '审查编号未知或重复')
    assert.deepEqual(Object.keys(row.dimensions).sort(), [...dimensions].sort(), '评分维度必须完整')
    for (const value of Object.values(row.dimensions)) {
      assert.ok(['PASSED', 'FAILED', 'PENDING'].includes(value.verdict), '未知评分结论')
      if (value.verdict !== 'PENDING') {
        assert.ok(typeof value.note === 'string' && value.note.trim(), '通过或失败必须写具体依据')
        assert.ok(typeof review.reviewer === 'string' && review.reviewer.trim(), '审查须注明审查者')
        assert.ok(Number.isFinite(Date.parse(review.reviewedAt)), '审查须注明有效时间')
      }
      if (value.verdict === 'PASSED') {
        const result = artifact.results.find(item => item.id === row.id)
        assert.ok(result.run?.status === 'SUCCEEDED' && result.run.answer, '没有完整回答不能标为人工语义通过')
      }
    }
    rows.set(row.id, row)
  }
  return rows
}

function toolVerdict(testCase, trace) {
  if (!Array.isArray(testCase.requiredTools) || !Array.isArray(testCase.allowedTools)) return 'UNSCORED'
  const tools = trace?.tools ?? []
  const completed = tools.filter(item => item.status === 'COMPLETED').map(item => item.name)
  return testCase.requiredTools.every(name => completed.includes(name)) && tools.every(item => testCase.allowedTools.includes(item.name))
    ? 'PASSED' : 'FAILED'
}

export function buildScorecard(artifact, review) {
  assert.ok(['fixture', 'real'].includes(artifact.mode), '必须区分替身与真实模型')
  const executions = planned(artifact)
  const ids = new Set(executions.map(item => item.id))
  const actual = new Map()
  const requestIds = new Set(), runIds = new Set()
  for (const row of artifact.results) {
    assert.ok(ids.has(row.id) && !actual.has(row.id), '结果编号未知或重复，不能改写分母')
    if (row.caseId !== undefined) assert.equal(row.caseId, executions.find(item => item.id === row.id).caseId, '题目归属不符')
    if (row.run?.requestId) {
      assert.ok(!requestIds.has(row.run.requestId), '重复采样不能复用同一次请求')
      requestIds.add(row.run.requestId)
    }
    if (row.run?.runId != null) {
      assert.ok(!runIds.has(row.run.runId), '重复采样不能复用同一次运行')
      runIds.add(row.run.runId)
    }
    actual.set(row.id, row)
  }
  const reviewed = validateReview(artifact, review ?? embeddedReview(artifact), new Set(actual.keys()))
  const rows = executions.map(item => {
    const result = actual.get(item.id)
    const ratings = reviewed.get(item.id)?.dimensions
    const program = result ? result.run?.status === 'SUCCEEDED' && result.checks?.programContract === 'PASSED' ? 'PASSED' : 'FAILED' : 'NOT_RUN'
    const semantic = !result ? 'NOT_RUN' : ratings && dimensions.some(key => ratings[key].verdict === 'FAILED')
      ? 'FAILED' : !ratings || dimensions.some(key => ratings[key].verdict === 'PENDING') ? 'PENDING' : 'PASSED'
    return { id: item.id, caseId: item.caseId, sample: item.sample, category: item.testCase.category ?? artifact.plan.suite ?? 'FINANCIAL',
      program, toolSelection: result ? toolVerdict(item.testCase, result.trace) : 'NOT_RUN',
      semantic, taskCompleted: program === 'PASSED' && semantic === 'PASSED',
      runId: result?.run?.runId ?? null, requestId: result?.run?.requestId ?? null,
      status: result?.run?.status ?? 'NOT_RUN', errorCode: result?.run?.errorCode ?? null,
      failure: result?.trace?.failure ?? null, model: result?.trace?.model ?? null,
      promptVersion: result?.trace?.promptVersion ?? null, elapsedMillis: result?.elapsedMillis ?? null,
      modelCalls: result?.trace?.modelCalls ?? 0, toolCalls: result?.trace?.toolCalls ?? 0,
      inputTokens: result?.trace?.inputTokens ?? null, outputTokens: result?.trace?.outputTokens ?? null,
      costUpperYuan: result?.trace?.costUpperYuan ?? null, dimensions: ratings ?? null }
  })
  const groups = artifact.plan.cases.filter(item => (item.repeats ?? 1) > 1).map(item => {
    const samples = rows.filter(row => row.caseId === item.id)
    return { caseId: item.id, planned: samples.length, attempted: samples.filter(row => row.program !== 'NOT_RUN').length,
      completed: samples.filter(row => row.taskCompleted).length, allSamplesPassed: samples.every(row => row.taskCompleted),
      fixtureVariants: new Set(artifact.results.filter(row => row.caseId === item.id).map(row => row.fixtureFingerprint ?? 'UNRECORDED')).size,
      interpretation: artifact.results.some(row => row.caseId === item.id && row.reusedWithoutModelCall)
        ? 'REVIEWED_CROSS_RUN_SAMPLES_NOT_CONTROLLED_REPETITION' : 'SAME_RUN_SAMPLES' }
  })
  const elapsed = rows.map(row => row.elapsedMillis).filter(value => Number.isFinite(value) && value >= 0).sort((a, b) => a - b)
  const percentile = p => elapsed.length ? elapsed[Math.max(0, Math.ceil(elapsed.length * p) - 1)] : null
  return { artifactSha256: artifactHash(artifact), mode: artifact.mode,
    interpretation: artifact.mode === 'fixture' ? 'CONTROLLED_PROGRAM_CHECK_ONLY' : 'REAL_MODEL_WITH_EXPLICIT_SEMANTIC_REVIEW',
    denominator: executions.length, attempted: actual.size, notRun: executions.length - actual.size,
    program: ratio(rows.filter(row => row.program === 'PASSED').length, executions.length),
    toolSelection: { ...ratio(rows.filter(row => row.toolSelection === 'PASSED').length, executions.length),
      unscored: rows.filter(row => row.toolSelection === 'UNSCORED').length },
    semantic: { ...ratio(rows.filter(row => row.semantic === 'PASSED').length, executions.length),
      reviewed: rows.filter(row => ['PASSED', 'FAILED'].includes(row.semantic)).length,
      failed: rows.filter(row => row.semantic === 'FAILED').length,
      pending: rows.filter(row => row.semantic === 'PENDING').length },
    taskCompletion: ratio(rows.filter(row => row.taskCompleted).length, executions.length),
    modelCalls: rows.reduce((sum, row) => sum + row.modelCalls, 0), toolCalls: rows.reduce((sum, row) => sum + row.toolCalls, 0),
    latencyMillis: { count: elapsed.length, p50: percentile(0.5), p95: percentile(0.95) },
    realQualityPassed: artifact.mode === 'real' && rows.every(row => row.taskCompleted && row.toolSelection === 'PASSED'), repetitions: groups, rows }
}

// 旧证据可描述比较；只有问题、预期、数据和模式均一致才能称同条件对照。
export function compareArtifacts(baseline, current, baselineReview, currentReview) {
  const before = buildScorecard(baseline, baselineReview), after = buildScorecard(current, currentReview)
  const common = current.plan.cases.flatMap(item => {
    const old = baseline.plan.cases.find(row => row.id === item.id && row.question === item.question)
    if (!old) return []
    const sameInput = old.expected !== undefined && item.expected !== undefined
      && Array.isArray(baseline.sourceFixtures) && baseline.sourceFixtures.length > 0
      && sha(baseline.sourceFixtures) === sha(current.sourceFixtures) && sha(old.expected) === sha(item.expected)
    return [{ caseId: item.id, controlled: baseline.mode === current.mode && sameInput,
      before: before.rows.filter(row => row.caseId === item.id), after: after.rows.filter(row => row.caseId === item.id) }]
  })
  assert.ok(common.length > 0, '两轮没有同题，不生成整体提升率')
  return { baselineSha256: before.artifactSha256, currentSha256: after.artifactSha256,
    interpretation: common.every(item => item.controlled) ? 'SAME_INPUT_COMPARISON' : 'DESCRIPTIVE_HISTORY_NOT_CONTROLLED', common }
}

export async function writeReviewArtifacts(directory, reviewPath, baselinePath, baselineReviewPath) {
  const artifact = JSON.parse(await readFile(resolve(directory, 'results.json'), 'utf8'))
  const review = reviewPath ? JSON.parse(await readFile(reviewPath, 'utf8')) : undefined
  const score = buildScorecard(artifact, review)
  const template = { artifactSha256: score.artifactSha256, reviewer: '', reviewedAt: '', rows: artifact.results.map(row => ({ id: row.id,
    dimensions: Object.fromEntries(dimensions.map(key => [key, { verdict: 'PENDING', note: '' }])) })) }
  try { await writeFile(resolve(directory, 'review-template.json'), JSON.stringify(template, null, 2), { flag: 'wx' }) }
  catch (error) { if (error.code !== 'EEXIST') throw error }
  await writeFile(resolve(directory, 'scorecard.json'), JSON.stringify(score, null, 2))
  let comparison
  if (baselinePath) {
    const baseline = JSON.parse(await readFile(baselinePath, 'utf8'))
    const oldReview = baselineReviewPath ? JSON.parse(await readFile(baselineReviewPath, 'utf8')) : undefined
    comparison = compareArtifacts(baseline, artifact, oldReview, review)
    await writeFile(resolve(directory, 'comparison.json'), JSON.stringify(comparison, null, 2))
  }
  const lines = ['# 研究助手质量记录', '', `模式：${score.mode}；计划 ${score.denominator} 次，实际 ${score.attempted} 次，未执行 ${score.notRun} 次。`,
    `程序通过 ${score.program.passed}/${score.denominator}；工具选择通过 ${score.toolSelection.passed}/${score.denominator}（未评分 ${score.toolSelection.unscored} 次）；人工语义通过 ${score.semantic.passed}/${score.denominator}（已审 ${score.semantic.reviewed} 次）。`,
    `真实质量验收：${score.realQualityPassed ? '通过' : '未通过或待审'}。替身与待审结果均不能算真实质量通过。`, '',
    '| 执行 | 程序 | 工具选择 | 人工语义 | 状态／诊断 |', '| --- | --- | --- | --- | --- |',
    ...score.rows.map(row => `| ${row.id} | ${row.program} | ${row.toolSelection} | ${row.semantic} | ${row.errorCode ?? row.status}${row.failure ? ` / ${row.failure.stage}:${row.failure.category}` : ''} |`), '',
    '逐题原文、依据、数值、调用轨迹及模型版本见 results.json；人工核对须同时查看正文和依据。review-template.json 按 artifactSha256 绑定结果，填写四个维度及具体依据后重新生成。', '',
    ...(comparison ? [`历史对照：${comparison.interpretation}，不同输入不计算整体提升率。`, ''] : [])]
  await writeFile(resolve(directory, 'quality-review.md'), lines.join('\n'))
  return score
}
