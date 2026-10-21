import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { writeReviewArtifacts } from './quality-report.mjs'

const values = new Map()
for (const argument of process.argv.slice(2)) {
  const match = /^--(results|review|baseline|baseline-review)=(.+)$/.exec(argument)
  assert.ok(match && !values.has(match[1]), '仅接受唯一的 --results=目录、--review=文件、--baseline=结果文件、--baseline-review=文件')
  values.set(match[1], match[2])
}
assert.ok(values.has('results'), '必须指定已有结果目录；本命令不连接服务或模型')
assert.ok(!values.has('baseline-review') || values.has('baseline'), '基线评分必须与基线结果一起提供')
const score = await writeReviewArtifacts(resolve(values.get('results')), values.get('review'), values.get('baseline'), values.get('baseline-review'))
process.stdout.write(JSON.stringify({ mode: score.mode, planned: score.denominator, attempted: score.attempted,
  programPassed: score.program.passed, semanticPassed: score.semantic.passed, realQualityPassed: score.realQualityPassed }) + '\n')
