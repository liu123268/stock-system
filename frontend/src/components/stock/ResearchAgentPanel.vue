<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/api/http'
import { handleWorkspaceApiError } from '@/utils/workspace-access'
import { askAgent, clearAgent, generateAgent, readAgentOriginal, readAgentRequest, readAgentRun, restoreAgent } from '@/api/agent'
import type { AgentAnswer, AgentEvidence, AgentItem, AgentRun, AgentTarget, OpinionOriginal } from '@/api/agent'
import { readPendingAgent, writePendingAgent, removePendingAgent } from '@/utils/agent-pending'
import type { PendingAgentRequest } from '@/utils/agent-pending'

const props = defineProps<{ target: AgentTarget }>()
const router = useRouter()
const auth = useAuthStore()
const accessErrors = ['WORKSPACE_NOT_FOUND', 'WORKSPACE_MEMBERSHIP_INACTIVE', 'WORKSPACE_ACCESS_DENIED', 'UNAUTHORIZED']
const loading = ref(true)
const busy = ref(false)
const clearing = ref(false)
const enabled = ref(false)
const accessLost = ref(false)
const error = ref('')
const notices = ref('')
const messages = ref<AgentRun[]>([])
const hasEarlier = ref(false)
const dateFrom = ref('')
const dateTo = ref('')
const question = ref('')
const evidence = ref<AgentEvidence | null>(null)
const clearConfirming = ref(false)
const originalDialog = ref<HTMLDialogElement>()
const original = ref<OpinionOriginal | null>(null)
const originalLoading = ref(false)
const originalError = ref('')
const pending = ref<PendingAgentRequest | null>(null)
let version = 0
let originalVersion = 0
let disposed = false
let pollTimer: ReturnType<typeof setTimeout> | undefined
let sourceButton: HTMLElement | null = null
const latest = computed(() => messages.value.find((item) => item.answer?.summary)?.answer)
function capture() { return { version, session: auth.sessionVersion, target: { ...props.target } } }
function current(request: ReturnType<typeof capture>) { return !disposed && version === request.version && auth.sessionVersion === request.session }
function time(value: string | null) { return value ? value.replace('T', ' ').slice(0, 19) : '暂无时间' }
function sections(answer: AgentAnswer) {
  if (!answer.summary) return []
  return [
    { title: '观点变化', items: answer.summary.changes, empty: '本次资料未形成可对照的观点变化。' },
    { title: '成员观点', items: answer.summary.members, empty: '本次范围内没有有效成员观点。' },
    { title: '待核对事项', items: answer.summary.pendingItems, empty: '本次没有列出待核对事项。' },
  ]
}
function marketRefs(answer: AgentAnswer, item: AgentItem) {
  return answer.followUp?.evidence.filter(source => item.sourceIds.includes(source.sourceId)) ?? []
}
function refs(answer: AgentAnswer, item: AgentItem) { return answer.references.filter((source) => item.sourceIds.includes(source.sourceId)) }
function rememberRequest() {
  const requestId = pending.value?.requestId ?? crypto.randomUUID()
  pending.value = { requestId, target: { ...props.target }, userId: auth.currentUser?.userId ?? null }
  writePendingAgent(pending.value)
  return requestId
}
function forgetRequest(requestId: string) {
  removePendingAgent(requestId)
  if (pending.value?.requestId === requestId) pending.value = null
}
async function fail(cause: unknown, request: ReturnType<typeof capture>) {
  if (!current(request)) return
  if (cause instanceof ApiError && accessErrors.includes(cause.code)) hidePrivateContent()
  const failedVersion = version
  if (await handleWorkspaceApiError(cause, router)) return
  if (disposed || version !== failedVersion || auth.sessionVersion !== request.session) return
  error.value = cause instanceof ApiError ? cause.message : '暂时无法连接。可以恢复运行状态；重试不会自动再次调用模型。'
}
function hidePrivateContent() {
  version++; originalVersion++; stopPoll()
  busy.value = false; loading.value = false; notices.value = ''
  messages.value = []; original.value = null; evidence.value = null
  originalDialog.value?.close(); enabled.value = false; accessLost.value = true
  if (pending.value) forgetRequest(pending.value.requestId)
}
function stopPoll() { clearTimeout(pollTimer); pollTimer = undefined }
function schedulePoll(runId: number, request: ReturnType<typeof capture>) {
  stopPoll()
  pollTimer = setTimeout(async () => {
    if (!current(request)) return
    try {
      const run = (await readAgentRun(request.target, runId)).data.data
      if (!current(request)) return
      if (run.status === 'RUNNING') schedulePoll(runId, request)
      else {
        busy.value = false; accept(run); forgetRequest(run.requestId)
        if (accessLost.value) return
        if (run.answer?.followUp?.question === question.value) question.value = ''
        await load(false)
        if (run.message && current(request)) error.value = run.message
      }
    } catch (cause) {
      if (!current(request)) return
      busy.value = false; await fail(cause, request)
    }
  }, 1500)
}
function accept(run: AgentRun) {
  // 进行中失权可能以 FAILED 运行回包抵达；即使后续 GET 断线也先清正文。
  if (run.errorCode && accessErrors.includes(run.errorCode)) hidePrivateContent()
  if (run.answer) {
    messages.value = [run, ...messages.value.filter((message) => message.runId !== run.runId)]
    notices.value = '本次结果已保存到你的个人记录。'
  } else if (run.status !== 'RUNNING') {
    if (run.status === 'INVALIDATED' || run.status === 'CLEARED')
      messages.value = messages.value.map(item => item.runId === run.runId ? run : item)
    notices.value = ''
    error.value = run.message || '本次未完成梳理，已有有效结果仍然保留。'
  }
}
async function load(reset = true, older = false) {
  const request = capture()
  pending.value ??= readPendingAgent(request.target, auth.currentUser?.userId ?? null)
  loading.value = true
  error.value = ''
  try {
    const before = older ? messages.value[messages.value.length - 1]?.runId : undefined
    const session = (await restoreAgent(request.target, before)).data.data
    if (!current(request)) return
    enabled.value = session.enabled
    accessLost.value = false
    messages.value = older ? [...messages.value, ...session.messages] : session.messages
    hasEarlier.value = session.hasEarlierMessages
    busy.value = !!session.activeRun
    if (session.invalidated) notices.value = '部分结果依赖的观点已经撤回或变化，需要重新梳理。'
    else if (reset) notices.value = ''
    if (!older && pending.value) {
      const requestId = pending.value.requestId
      try {
        const run = (await readAgentRequest(request.target, requestId)).data.data
        if (!current(request)) return
        if (run.status === 'RUNNING') {
          busy.value = true
          notices.value = '本次任务仍在进行，正在恢复结果。'
          schedulePoll(run.runId, request)
        } else {
          accept(run); forgetRequest(requestId)
          if (run.answer?.followUp?.question === question.value) question.value = ''
        }
      } catch (cause) {
        if (!current(request)) return
        if (cause instanceof ApiError && cause.code === 'RESEARCH_AGENT_RUN_NOT_FOUND') {
          notices.value = ''
          error.value = '暂未找到本次任务，请稍后恢复，或再次发送同一问题。'
        } else await fail(cause, request)
      }
    }
    if (session.activeRun && current(request)) schedulePoll(session.activeRun.runId, request)
  } catch (cause) { await fail(cause, request) }
  finally { if (current(request)) loading.value = false }
}
async function generate() {
  if (busy.value || clearing.value || loading.value || !enabled.value) return
  if (dateFrom.value && dateTo.value && dateFrom.value > dateTo.value) {
    error.value = '开始日期不能晚于结束日期。'; return
  }
  const request = capture()
  const requestId = rememberRequest()
  busy.value = true
  error.value = ''
  notices.value = '正在核对本周期资料并整理观点，请稍候。'
  try {
    const run = (await generateAgent(request.target, requestId, {
      ...(dateFrom.value ? { dateFrom: dateFrom.value } : {}),
      ...(dateTo.value ? { dateTo: dateTo.value } : {}),
    })).data.data
    if (!current(request)) return
    accept(run)
    if (accessLost.value) return
    if (run.status === 'RUNNING') { schedulePoll(run.runId, request); return }
    forgetRequest(requestId)
    busy.value = false
    await load(false)
    if (run.message && current(request)) error.value = run.message
  } catch (cause) {
    if (!current(request)) return
    busy.value = false
    await fail(cause, request)
    // POST 未返回运行结果时先查原编号，绝不偷偷重发付费请求。
    if (pending.value && current(request)) {
      await load(false)
      if (current(request) && pending.value && !busy.value && cause instanceof ApiError) error.value = cause.message
    }
  }
}
async function ask() {
  if (busy.value || clearing.value || loading.value || !enabled.value || !question.value.trim()) return
  if (dateFrom.value && dateTo.value && dateFrom.value > dateTo.value) { error.value = '开始日期不能晚于结束日期。'; return }
  const request = capture()
  const requestId = rememberRequest()
  busy.value = true
  error.value = ''
  notices.value = '正在根据问题核对所需资料，请稍候。'
  try {
    const run = (await askAgent(request.target, requestId, question.value, {
      ...(dateFrom.value ? { dateFrom: dateFrom.value } : {}), ...(dateTo.value ? { dateTo: dateTo.value } : {}),
    })).data.data
    if (!current(request)) return
    accept(run)
    if (accessLost.value) return
    if (run.status === 'RUNNING') { schedulePoll(run.runId, request); return }
    forgetRequest(requestId)
    busy.value = false
    if (run.answer?.followUp?.question === question.value) question.value = ''
    await load(false)
    if (run.message && current(request)) error.value = run.message
  } catch (cause) {
    if (!current(request)) return
    busy.value = false
    await fail(cause, request)
    if (pending.value && current(request)) {
      await load(false)
      if (current(request) && pending.value && !busy.value && cause instanceof ApiError) error.value = cause.message
    }
  }
}
async function clear() {
  if (clearing.value) return
  const request = capture()
  clearing.value = true
  try {
    await clearAgent(request.target)
    if (!current(request)) return
    version++
    originalVersion++
    stopPoll()
    messages.value = []
    busy.value = false
    hasEarlier.value = false
    original.value = null
    evidence.value = null
    question.value = ''
    if (pending.value) forgetRequest(pending.value.requestId)
    originalDialog.value?.close()
    clearConfirming.value = false
    notices.value = '已清空本周期的个人记录。'
    error.value = ''
  } catch (cause) { await fail(cause, request) }
  finally { if (!disposed) clearing.value = false }
}
async function openOriginal(opinionId: number, event: MouseEvent) {
  const request = capture()
  const sequence = ++originalVersion
  sourceButton = event.currentTarget as HTMLElement
  original.value = null
  evidence.value = null
  originalError.value = ''
  originalLoading.value = true
  await nextTick()
  originalDialog.value?.showModal()
  try {
    const content = (await readAgentOriginal(request.target, opinionId)).data.data
    if (current(request) && sequence === originalVersion) original.value = content
  } catch (cause) {
    if (!current(request) || sequence !== originalVersion) return
    originalError.value = cause instanceof ApiError ? cause.message : '原文暂时无法读取，请关闭后重试。'
    if (cause instanceof ApiError && cause.code === 'STOCK_OPINION_NOT_FOUND') await load(false)
    else await fail(cause, request)
  } finally { if (current(request) && sequence === originalVersion) originalLoading.value = false }
}
async function openEvidence(runId: number, sourceId: string, event: MouseEvent) {
  const request = capture()
  const sequence = ++originalVersion
  sourceButton = event.currentTarget as HTMLElement
  original.value = null
  evidence.value = null
  originalError.value = ''
  originalLoading.value = true
  await nextTick()
  originalDialog.value?.showModal()
  try {
    const run = (await readAgentRun(request.target, runId)).data.data
    if (!current(request) || sequence !== originalVersion) return
    const source = run.answer?.followUp?.evidence.find(item => item.sourceId === sourceId)
    if (!source) { originalError.value = run.message || '这条依据已经失效，请重新查询。'; await load(false); return }
    evidence.value = source
  } catch (cause) {
    if (current(request) && sequence === originalVersion) { originalError.value = cause instanceof ApiError ? cause.message : '依据暂时无法读取。'; await fail(cause, request) }
  } finally { if (current(request) && sequence === originalVersion) originalLoading.value = false }
}
function closeOriginal() {
  originalVersion++
  original.value = null
  evidence.value = null
  sourceButton?.focus({ preventScroll: true })
}
watch([() => props.target.workspaceId, () => props.target.stockId, () => props.target.cycleId, () => auth.sessionVersion], (next, previous) => {
  if (next[3] !== previous[3] && pending.value) forgetRequest(pending.value.requestId)
  pending.value = null
  accessLost.value = false
  version++; originalVersion++; stopPoll(); messages.value = []; original.value = null
  originalDialog.value?.close(); busy.value = false; dateFrom.value = ''; dateTo.value = ''; question.value = ''; evidence.value = null
  void load()
})
onMounted(() => void load())
onBeforeUnmount(() => { disposed = true; version++; originalVersion++; stopPoll(); originalDialog.value?.close() })
</script>

<template>
  <section class="agent-panel" aria-label="个人研究助手" :aria-busy="busy || loading">
    <header class="agent-heading">
      <div><h3>研究梳理</h3><p>整理本周期的成员观点，结果仅你可见。</p></div>
      <button class="ui-button" type="button" :disabled="loading || clearing" @click="clearConfirming = !clearConfirming">清空个人记录</button>
    </header>
    <form class="agent-controls" @submit.prevent="generate">
      <label>观点开始日期<input v-model="dateFrom" type="date" :disabled="busy || clearing" /></label>
      <label>观点结束日期<input v-model="dateTo" type="date" :disabled="busy || clearing" /></label>
      <button class="ui-button" type="submit" :disabled="busy || loading || clearing || !enabled">{{ busy ? '正在梳理…' : latest ? '重新梳理' : '开始梳理' }}</button>
    </form>
    <p class="agent-note">日期留空时读取完整周期。成员的推测仍需权威资料核对。</p>
    <div v-if="clearConfirming" class="agent-confirm">
      <p>清空这个周期的个人结果？正在生成的迟到结果也不会重新保存。</p>
      <button class="ui-button" type="button" :disabled="clearing" @click="clearConfirming = false">保留记录</button>
      <button class="ui-button" type="button" :disabled="clearing" @click="clear">确认清空</button>
    </div>
    <p v-if="loading" role="status" class="agent-note">正在读取个人记录…</p>
    <p v-else-if="!enabled && !accessLost" class="agent-note">研究助手尚未启用。启用服务端模型和预算配置后可生成；已有记录仍可查看。</p>
    <p v-if="notices" role="status" class="agent-note">{{ notices }}</p>
    <div v-if="error" class="agent-error" role="alert"><p>{{ error }}</p><button class="ui-button" type="button" :disabled="loading" @click="load(false)">恢复运行状态</button></div>
    <form class="agent-question" @submit.prevent="ask">
      <label>继续提问<textarea v-model="question" maxlength="1000" rows="3" :disabled="busy || clearing || !enabled" placeholder="例如：成员甲的最新判断是什么？现在最新可用价是多少？" /></label>
      <div><span class="agent-note">可查询成员观点、最新可用报价和短期日 K；财报与公告尚未接入。</span><button class="ui-button" type="submit" :disabled="busy || loading || clearing || !enabled || !question.trim()">{{ busy ? '正在回答…' : '发送问题' }}</button></div>
    </form>
    <p v-if="!loading && !messages.length && !busy && !accessLost" class="agent-empty">生成后会在这里保留概况、成员观点和待核对事项，返回页面可继续查看。</p>
    <article v-for="run in messages" :key="run.runId" class="agent-answer" :data-run-id="run.runId">
      <template v-if="run.answer?.summary">
        <p class="agent-note">资料读取于 {{ time(run.answer.scope.readAt) }} · {{ run.answer.scope.readOpinionCount }} 条有效观点</p>
        <p v-if="run.answer.scope.filter.dateFrom || run.answer.scope.filter.dateTo" class="agent-note">观点日期：{{ run.answer.scope.filter.dateFrom || '周期开始' }} 至 {{ run.answer.scope.filter.dateTo || '周期结束' }}</p>
        <p class="agent-quote" v-if="run.answer.quote?.status === 'AVAILABLE'">生成时报价：<strong>{{ run.answer.quote.price }} {{ run.answer.quote.currency }}</strong> · {{ time(run.answer.quote.quoteTime) }} · {{ run.answer.quote.source }}{{ run.answer.quote.simulated ? '（模拟）' : '' }}</p>
        <p v-else class="agent-note">本次未取得可用报价，以下继续整理已有观点。</p>
        <p v-if="run.answer.newOpinionsAvailable" class="agent-note">本周期已有新观点，可点击重新梳理。</p>
        <h4>概况</h4><p class="agent-prose">{{ run.answer.summary.overview.text }}</p>
        <div class="agent-references"><button v-for="source in refs(run.answer, run.answer.summary.overview)" :key="source.sourceId" class="ui-button source-link" type="button" @click="openOriginal(source.opinionId, $event)">查看 {{ source.authorName }} 的原文</button></div>
        <section v-for="section in sections(run.answer)" :key="section.title">
          <h4>{{ section.title }}</h4>
          <p v-if="!section.items.length" class="agent-note">{{ section.empty }}</p>
          <div v-for="(item, index) in section.items" :key="index" class="agent-item">
            <p class="agent-prose">{{ item.text }}</p>
            <div class="agent-references"><button v-for="source in refs(run.answer, item)" :key="source.sourceId" class="ui-button source-link" type="button" @click="openOriginal(source.opinionId, $event)">查看 {{ source.authorName }} 的原文 · {{ time(source.createdAt) }}</button></div>
          </div>
        </section>
      </template>
      <template v-else-if="run.answer?.followUp">
        <p class="agent-your-question">你的问题：{{ run.answer.followUp.question }}</p>
        <p class="agent-note">回答生成于 {{ time(run.answer.generatedAt) }}</p>
        <p v-if="run.answer.followUp.historyOmitted" class="agent-note">本轮只承接当前范围内部分有效历史，未携带全部记录。</p>
        <p v-if="run.answer.newOpinionsAvailable" class="agent-note">回答读取之后已有新观点，可重新提问核对。</p>
        <div v-for="(paragraph, index) in run.answer.followUp.reply.paragraphs" :key="index" class="agent-item">
          <p class="agent-prose">{{ paragraph.text }}</p>
          <div class="agent-references">
            <button v-for="source in refs(run.answer, paragraph)" :key="source.sourceId" class="ui-button source-link" type="button" @click="openOriginal(source.opinionId, $event)">查看 {{ source.authorName }} 的原文</button>
            <button v-for="source in marketRefs(run.answer, paragraph)" :key="source.sourceId" class="ui-button source-link" type="button" @click="openEvidence(run.runId, source.sourceId, $event)">查看{{ source.label }}依据</button>
          </div>
        </div>
        <div v-if="run.answer.followUp.reply.missingInformation.length" class="agent-missing"><h4>还缺什么资料</h4><p v-for="(item, index) in run.answer.followUp.reply.missingInformation" :key="index" class="agent-note">{{ item }}</p></div>
      </template>
      <p v-else class="agent-note">{{ run.message || '该结果已失效，请重新梳理。' }}</p>
    </article>
    <button v-if="hasEarlier" class="ui-button" type="button" :disabled="loading" @click="load(false, true)">查看更早的梳理</button>
    <dialog ref="originalDialog" class="agent-original" aria-labelledby="agent-original-title" @close="closeOriginal" @click="event => { if (event.target === originalDialog) originalDialog?.close() }">
      <header><h3 id="agent-original-title">{{ evidence ? '行情依据' : '观点原文' }}</h3><button class="ui-button" type="button" autofocus @click="originalDialog?.close()">关闭原文</button></header>
      <p v-if="originalLoading" role="status">正在读取当前可见原文…</p>
      <p v-else-if="originalError" role="alert">{{ originalError }}</p>
      <template v-else-if="original">
        <p class="agent-note">{{ original.authorNickname }} · {{ time(original.createdAt) }} · {{ { BULLISH: '看多', NEUTRAL: '中性', BEARISH: '看空' }[original.stance] }}</p>
        <h4>判断理由</h4><p class="agent-prose">{{ original.reason }}</p>
        <template v-if="original.observationNote"><h4>观察条件</h4><p class="agent-prose">{{ original.observationNote }}</p></template>
      </template>
      <template v-else-if="evidence?.quote">
        <h4>{{ evidence.label }}</h4><p class="agent-prose">{{ evidence.quote.price }} {{ evidence.quote.currency }} · {{ time(evidence.quote.quoteTime) }}</p>
        <p class="agent-note">来源：{{ evidence.quote.source }}{{ evidence.quote.simulated ? '（模拟）' : '' }}。这是生成时取得的快照。</p>
      </template>
      <template v-else-if="evidence?.dailyPrices">
        <h4>{{ evidence.label }}</h4>
        <p class="agent-note">请求区间 {{ evidence.dailyPrices.requestedFrom }} 至 {{ evidence.dailyPrices.requestedTo }}；实际取得 {{ evidence.dailyPrices.actualFrom }} 至 {{ evidence.dailyPrices.actualTo }}。</p>
        <p class="agent-prose">名义收盘价差：{{ evidence.dailyPrices.nominalPriceDifference ?? '无法计算' }} {{ evidence.dailyPrices.currency }}；名义变化：{{ evidence.dailyPrices.nominalPriceChangePercent === null ? '无法计算' : `${evidence.dailyPrices.nominalPriceChangePercent}%` }}。</p>
        <p class="agent-note">{{ evidence.dailyPrices.coverageNote }} 来源：{{ evidence.dailyPrices.source }}{{ evidence.dailyPrices.simulated ? '（模拟）' : '' }}。</p>
        <div class="agent-price-table"><table><thead><tr><th scope="col">日期</th><th scope="col">收盘价</th></tr></thead><tbody><tr v-for="point in evidence.dailyPrices.prices" :key="point.time"><td>{{ time(point.time).slice(0, 10) }}</td><td>{{ point.close }} {{ evidence.dailyPrices.currency }}</td></tr></tbody></table></div>
      </template>
    </dialog>
  </section>
</template>

<style scoped>
.agent-panel { padding: 22px; border-bottom: 1px solid var(--color-border); min-width: 0; scroll-margin-top: 100px; }
.agent-panel button { min-height: 44px; padding: 8px 18px; border: 1px solid var(--color-border); border-radius: var(--radius-action); background: var(--color-surface); color: var(--color-ink); font-size: 12px; cursor: pointer; }
.agent-panel button:disabled { opacity: 0.55; cursor: default; }
.agent-controls button[type='submit'] { background: var(--color-ink); color: var(--color-surface); border-color: var(--color-ink); }
.agent-question { margin-top: 18px; padding: 18px 0; border-top: 1px solid var(--color-border); }
.agent-question label { display: grid; gap: 8px; font-size: 14px; }
.agent-question textarea { width: 100%; resize: vertical; padding: 12px; border: 1px solid var(--color-border); border-radius: var(--radius-control); background: var(--color-surface); color: var(--color-ink); font: inherit; line-height: 1.8; min-height: 100px; }
.agent-question textarea::placeholder { color: var(--color-muted); }
.agent-question > div { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-top: 10px; }
.agent-your-question { font-size: 15px; font-weight: 600; line-height: 1.8; white-space: pre-wrap; }
.agent-price-table { margin-top: 16px; overflow-x: auto; }
.agent-price-table table { width: 100%; border-collapse: collapse; font-size: 14px; }
.agent-price-table th, .agent-price-table td { padding: 8px; border-bottom: 1px solid var(--color-border); text-align: left; }
.agent-heading, .agent-original header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.agent-heading h3, .agent-original h3 { margin: 0 0 6px; font-size: 18px; }
.agent-heading p, .agent-note { color: var(--color-muted); font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; }
.agent-controls { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 12px; margin: 18px 0 8px; }
.agent-controls label { display: grid; gap: 6px; font-size: 13px; min-width: 0; }
.agent-controls input { padding: 9px; border: 1px solid var(--color-border); border-radius: var(--radius-control); background: var(--color-surface); color: var(--color-ink); min-height: 40px; max-width: 100%; }
.agent-confirm { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin: 16px 0; padding: 14px; background: var(--color-surface-secondary); }
.agent-confirm p { flex-basis: 100%; font-size: 14px; }
.agent-error { color: var(--color-danger); display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-top: 12px; }
.agent-empty { padding: 22px 0; font-size: 14px; line-height: 1.8; color: var(--color-muted); }
.agent-answer { padding: 22px 0; border-bottom: 1px solid var(--color-border); overflow-wrap: anywhere; }
.agent-answer h4, .agent-original h4 { margin: 22px 0 10px; font-size: 15px; font-weight: 600; }
.agent-quote { margin: 8px 0; font-size: 14px; line-height: 1.8; }
.agent-prose { max-width: 72ch; white-space: pre-wrap; line-height: 1.9; font-size: 14px; }
.agent-item + .agent-item { margin-top: 14px; }
.agent-references { display: flex; flex-wrap: wrap; gap: 6px 12px; margin-top: 5px; }
.agent-panel .source-link { text-align: left; border-color: transparent; padding: 6px 0; color: var(--color-focus); border-radius: var(--radius-control); overflow-wrap: anywhere; }
.agent-original { width: min(640px, calc(100% - 32px)); max-height: 80dvh; padding: 24px; color: var(--color-text); background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 8px; overflow-y: auto; }
.agent-original::backdrop { background: rgb(25 21 16 / 42%); }
@media (max-width: 480px) {
  .agent-heading { flex-wrap: wrap; }
  .agent-controls label { width: 100%; }
  .agent-controls input { width: 100%; }
  .agent-question > div { align-items: flex-start; flex-direction: column; }
  .agent-original { padding: 18px; }
}
</style>
