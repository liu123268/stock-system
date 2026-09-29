<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type {
  OpinionHorizon,
  OpinionType,
  ResearchCycleDetailResponseVO,
  StockOpinionResponseVO,
  StockStance,
} from '@/api/stock'
import { getWorkspaceStockResearchTimeline } from '@/api/stock'
import { useStockStore } from '@/stores/stock'
import { useWorkspaceStore } from '@/stores/workspace'
import { handleWorkspaceApiError } from '@/utils/workspace-access'

const route = useRoute()
const router = useRouter()
const stockStore = useStockStore()
const workspaceStore = useWorkspaceStore()

const workspaceId = computed(() => Number(route.params.workspaceId))
const workspaceStockId = computed(() => Number(route.params.workspaceStockId))
const isArchived = computed(() => workspaceStore.currentWorkspace?.status !== 'ACTIVE')
const currentCycle = computed(() => stockStore.researchDetail?.currentCycle)
const endConfirming = ref(false)
const composeExpanded = ref(false)
const formError = ref('')
const successMessage = ref('')
const pageRefreshWarning = ref('')
const authorFilter = ref('ALL')
const stanceFilter = ref('ALL')
const typeFilter = ref('ALL')
const timelineCycles = ref<ResearchCycleDetailResponseVO[]>([])
const timelineNextCursor = ref<string | null>(null)
const timelineLoading = ref(false)
const timelineError = ref('')
let timelineRequestVersion = 0
let loadedTimelineTarget = ''
const currentTime = ref(Date.now())
let withdrawClock: number | undefined

const opinion = reactive({
  type: 'UPDATE' as Exclude<OpinionType, 'INITIAL'>,
  stance: 'NEUTRAL' as StockStance,
  reason: '',
  observationNote: '',
  horizon: 'MEDIUM' as OpinionHorizon,
  targetPrice: '',
  stopLossPrice: '',
})

function restorePendingDraft() {
  const draft = stockStore.takeResearchDraft(workspaceId.value, workspaceStockId.value)
  if (!draft) return
  composeExpanded.value = true
  opinion.stance = draft.stance
  opinion.reason = draft.reason
  opinion.observationNote = draft.observationNote ?? ''
  opinion.horizon = draft.horizon
  opinion.targetPrice = draft.targetPrice?.toString() ?? ''
  opinion.stopLossPrice = draft.stopLossPrice?.toString() ?? ''
  successMessage.value = '这只股票已经有活动研究周期。你的草稿已保留，可确认后作为观点发表。'
}

async function scrollToTimeline() {
  await nextTick()
  const target = document.getElementById('research-timeline')
  if (!target) return
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  target.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' })
}

async function toggleCompose() {
  composeExpanded.value = !composeExpanded.value
  if (!composeExpanded.value) return
  await nextTick()
  document.querySelector<HTMLElement>('[data-opinion-first-control]')?.focus()
}

async function loadTimeline(reset: boolean) {
  if (!reset && (!timelineNextCursor.value || timelineLoading.value)) return
  const requestVersion = reset ? ++timelineRequestVersion : timelineRequestVersion
  if (reset) {
    timelineCycles.value = []
    timelineNextCursor.value = null
  }
  timelineLoading.value = true
  timelineError.value = ''
  try {
    const page = (await getWorkspaceStockResearchTimeline(
      workspaceId.value,
      workspaceStockId.value,
      {
        ...(authorFilter.value !== 'ALL' ? { author: Number(authorFilter.value) } : {}),
        ...(stanceFilter.value !== 'ALL' ? { stance: stanceFilter.value as StockStance } : {}),
        ...(typeFilter.value !== 'ALL' ? { type: typeFilter.value as OpinionType } : {}),
      },
      reset ? undefined : timelineNextCursor.value ?? undefined,
    )).data.data
    if (requestVersion !== timelineRequestVersion) return
    const cycles = reset ? [] : [...timelineCycles.value]
    for (const cycle of page.cycles) {
      const existing = cycles.find((item) => item.id === cycle.id)
      if (existing) existing.opinions.push(...cycle.opinions)
      else cycles.push(cycle)
    }
    timelineCycles.value = cycles
    timelineNextCursor.value = page.nextCursor
  } catch (error) {
    if (requestVersion !== timelineRequestVersion) return
    if (await handleWorkspaceApiError(error, router)) return
    timelineError.value = error instanceof Error ? error.message : '研究时间线加载失败'
  } finally {
    if (requestVersion === timelineRequestVersion) timelineLoading.value = false
  }
}

watch([authorFilter, stanceFilter, typeFilter], () => {
  if (loadedTimelineTarget === `${workspaceId.value}:${workspaceStockId.value}`) {
    void loadTimeline(true)
  }
})

async function load() {
  const requestedWorkspaceId = workspaceId.value
  const requestedStockId = workspaceStockId.value
  if (
    !Number.isSafeInteger(requestedWorkspaceId) ||
    requestedWorkspaceId <= 0 ||
    !Number.isSafeInteger(requestedStockId) ||
    requestedStockId <= 0
  ) {
    await router.replace('/workspaces')
    return
  }
  stockStore.clearRefreshWarning()
  pageRefreshWarning.value = ''
  try {
    await workspaceStore.ensureCurrent(requestedWorkspaceId)
    if (workspaceId.value !== requestedWorkspaceId || workspaceStockId.value !== requestedStockId) return
    await stockStore.loadResearch(requestedWorkspaceId, requestedStockId)
    if (workspaceId.value !== requestedWorkspaceId || workspaceStockId.value !== requestedStockId) return
    loadedTimelineTarget = `${requestedWorkspaceId}:${requestedStockId}`
    await loadTimeline(true)
    restorePendingDraft()
    if (route.hash === '#research-timeline') await scrollToTimeline()
  } catch (error) {
    await handleWorkspaceApiError(error, router)
  }
}

function optionalPrice(value: string) {
  if (!value) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : Number.NaN
}

function resetForm() {
  opinion.type = 'UPDATE'
  opinion.stance = 'NEUTRAL'
  opinion.reason = ''
  opinion.observationNote = ''
  opinion.horizon = 'MEDIUM'
  opinion.targetPrice = ''
  opinion.stopLossPrice = ''
}

async function submitOpinion(reopen: boolean) {
  const targetPrice = optionalPrice(opinion.targetPrice)
  const stopLossPrice = optionalPrice(opinion.stopLossPrice)
  if (!opinion.reason.trim()) {
    formError.value = '请填写判断理由'
    return
  }
  if (Number.isNaN(targetPrice) || Number.isNaN(stopLossPrice)) {
    formError.value = '目标价和止损参考价必须是大于 0 的数字'
    return
  }
  formError.value = ''
  successMessage.value = ''
  try {
    if (reopen) {
      const detail = stockStore.researchDetail
      if (!detail) return
      const result = await stockStore.add(workspaceId.value, {
        code: detail.code,
        stance: opinion.stance,
        reason: opinion.reason.trim(),
        observationNote: opinion.observationNote.trim() || null,
        horizon: opinion.horizon,
        targetPrice,
        stopLossPrice,
      })
      try {
        await stockStore.loadResearch(workspaceId.value, workspaceStockId.value)
        await loadTimeline(true)
      } catch {
        pageRefreshWarning.value = result.alreadyActive
          ? '其他成员已经开启研究周期，但详情刷新失败；你的草稿仍然保留，请重新加载。'
          : '新的研究周期已经开启，但详情刷新失败，请重新加载。'
        formError.value = pageRefreshWarning.value
      }
      if (result.alreadyActive) {
        successMessage.value = '其他成员已经先开启研究周期，你的草稿仍然保留，可作为观点发表。'
        return
      }
      successMessage.value = '新的研究周期已经开启，旧周期仍保留在下方。'
    } else {
      const cycle = currentCycle.value
      if (!cycle) {
        formError.value = '研究周期已经变化，请刷新后重试'
        return
      }
      await stockStore.createOpinion(workspaceId.value, workspaceStockId.value, {
        researchCycleId: cycle.id,
        type: opinion.type,
        stance: opinion.stance,
        reason: opinion.reason.trim(),
        observationNote: opinion.observationNote.trim() || null,
        horizon: opinion.horizon,
        targetPrice,
        stopLossPrice,
      })
      await loadTimeline(true)
      successMessage.value = '观点已经加入当前研究时间线。'
    }
    resetForm()
    composeExpanded.value = false
    await scrollToTimeline()
  } catch (error) {
    if (await handleWorkspaceApiError(error, router)) return
    formError.value = error instanceof Error ? error.message : '提交失败'
  }
}

async function retryResearchData() {
  try {
    await Promise.all([
      stockStore.loadResearch(workspaceId.value, workspaceStockId.value),
      loadTimeline(true),
      stockStore.loadList(workspaceId.value),
      stockStore.loadEnded(workspaceId.value),
    ])
    restorePendingDraft()
    stockStore.clearRefreshWarning()
    pageRefreshWarning.value = ''
    formError.value = ''
  } catch (error) {
    await handleWorkspaceApiError(error, router)
  }
}

async function endResearch() {
  const cycle = currentCycle.value
  if (!cycle) return
  try {
    await stockStore.endResearch(workspaceId.value, workspaceStockId.value, cycle.id)
    await loadTimeline(true)
    endConfirming.value = false
    composeExpanded.value = false
    resetForm()
    successMessage.value = '本轮关注已经结束，研究记录已转为只读历史。'
  } catch (error) {
    if (await handleWorkspaceApiError(error, router)) return
  }
}

async function withdrawOpinion(item: StockOpinionResponseVO) {
  const initial = item.type === 'INITIAL'
  if (
    !window.confirm(
      initial
        ? '撤销初始观点会取消本期研究，并将股票移出正在关注；内容会隐藏，但取消记录永久保留。确定继续吗？'
        : '撤销后，这条误发内容会隐藏，但作者、发表时间和撤销记录永久保留。观点发生变化时应发表新观点，不应撤销历史。确定继续吗？',
    )
  )
    return
  successMessage.value = ''
  try {
    await stockStore.withdrawOpinion(workspaceId.value, workspaceStockId.value, item.id)
    await loadTimeline(true)
    successMessage.value = initial
      ? '初始观点已撤销，本期研究已取消并移出正在关注，历史记录仍然保留。'
      : '误发内容已撤销：正文已经隐藏，时间线仍保留发表和撤销记录。'
  } catch (error) {
    await handleWorkspaceApiError(error, router)
  }
}

function formatTime(value: string | null) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function formatPrice(value: number | null, currency: string) {
  if (value === null) return '未填写'
  return `${currency === 'HKD' ? 'HK$' : '¥'}${value.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  })}`
}

function canWithdrawNow(item: StockOpinionResponseVO) {
  return item.canWithdraw && new Date(item.createdAt).getTime() + 5 * 60 * 1000 >= currentTime.value
}

const stanceLabels = { BULLISH: '看多', NEUTRAL: '中性', BEARISH: '看空' }
const typeLabels = { INITIAL: '初始观点', UPDATE: '观点更新', REVIEW: '复盘总结' }
const horizonLabels = { SHORT: '短期', MEDIUM: '中期', LONG: '长期' }

watch([workspaceId, workspaceStockId], () => {
  timelineRequestVersion += 1
  loadedTimelineTarget = ''
  timelineCycles.value = []
  timelineNextCursor.value = null
  timelineLoading.value = false
  timelineError.value = ''
  authorFilter.value = 'ALL'
  stanceFilter.value = 'ALL'
  typeFilter.value = 'ALL'
  void load()
}, { immediate: true })

onMounted(() => {
  withdrawClock = window.setInterval(() => {
    currentTime.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  timelineRequestVersion += 1
  if (withdrawClock !== undefined) window.clearInterval(withdrawClock)
  stockStore.clearResearchView()
})
</script>

<template>
  <section v-if="stockStore.researchLoading" class="page-state" aria-live="polite">
    正在读取研究记录…
  </section>
  <section v-else-if="stockStore.researchError && !stockStore.researchDetail" class="page-state">
    <h1>{{ stockStore.refreshWarning || pageRefreshWarning ? '操作已经保存，页面暂未刷新' : '研究记录暂时无法加载' }}</h1>
    <p>{{ pageRefreshWarning || stockStore.refreshWarning || stockStore.researchError }}</p>
    <button type="button" @click="retryResearchData">重新加载页面数据</button>
  </section>
  <template v-else-if="stockStore.researchDetail">
    <header class="research-header">
      <RouterLink :to="{ name: 'workspace-stocks', params: { workspaceId } }">← 返回共享自选</RouterLink>
      <p class="eyebrow">{{ stockStore.researchDetail.code }} · {{ stockStore.researchDetail.exchange }}</p>
      <h1>{{ stockStore.researchDetail.name }}</h1>
      <p>每次关注形成一段独立周期；观点按发表时间追加，旧判断不会被新内容覆盖。</p>
    </header>

    <p v-if="successMessage" class="success-message" aria-live="polite">{{ successMessage }}</p>
    <p v-if="stockStore.researchError" class="error-message" role="alert">
      {{ stockStore.researchError }}
    </p>
    <p v-if="stockStore.refreshWarning" class="error-message" role="alert">
      {{ stockStore.refreshWarning }}
      <button type="button" @click="retryResearchData">只重新加载页面数据</button>
    </p>

    <section v-if="!isArchived" class="current-actions">
      <div v-if="currentCycle">
        <p class="eyebrow">当前周期</p>
        <h2>{{ currentCycle.attentionLevel === 'FOCUS' ? '重点关注' : '普通关注' }}</h2>
        <p>开始于 {{ formatTime(currentCycle.startedAt) }}，由 {{ currentCycle.startedByNickname }} 发起。</p>
      </div>
      <div v-else>
        <p class="eyebrow">当前状态</p>
        <h2>没有正在进行的研究周期</h2>
        <p>历史观点保持只读，需要继续跟踪时可以开启新一期。</p>
      </div>
      <div v-if="!endConfirming" class="current-action-controls">
        <button
          class="compose-toggle"
          type="button"
          :aria-expanded="composeExpanded"
          aria-controls="opinion-compose"
          @click="toggleCompose"
        >
          {{
            composeExpanded
              ? '收起观点表单'
              : currentCycle
                ? '发表观点更新'
                : '开启新一期研究'
          }}
        </button>
        <button
          v-if="currentCycle"
          class="danger-link"
          type="button"
          @click="endConfirming = true"
        >
          结束本轮关注
        </button>
      </div>
      <div v-else class="confirm-end">
        <span>结束后本周期永久只读。</span>
        <button type="button" @click="endConfirming = false">取消</button>
        <button type="button" :disabled="stockStore.researchSubmitting" @click="endResearch">
          确认结束
        </button>
      </div>
    </section>

    <section v-if="!isArchived && composeExpanded" id="opinion-compose" class="opinion-compose">
      <p class="eyebrow">{{ currentCycle ? '追加研究记录' : '重新开启研究' }}</p>
      <h2>{{ currentCycle ? '发表观点更新或复盘' : '用新的初始观点开启下一段周期' }}</h2>
      <p class="form-guidance">
        判断理由说明“为什么形成当前倾向”；关键观察条件为选填，用于记录哪些变化可能支持、削弱或推翻当前判断。
      </p>
      <form novalidate @submit.prevent="submitOpinion(!currentCycle)">
        <div class="form-grid">
          <label v-if="currentCycle">
            <span>记录类型（必填）</span>
            <select v-model="opinion.type" data-opinion-first-control required>
              <option value="UPDATE">观点更新</option>
              <option value="REVIEW">复盘总结</option>
            </select>
          </label>
          <label>
            <span>观点倾向（必填）</span>
            <select
              v-model="opinion.stance"
              :data-opinion-first-control="currentCycle ? undefined : ''"
              required
            >
              <option value="BULLISH">看多</option>
              <option value="NEUTRAL">中性</option>
              <option value="BEARISH">看空</option>
            </select>
          </label>
          <label>
            <span>关注周期（必填）</span>
            <select v-model="opinion.horizon" required>
              <option value="SHORT">短期</option>
              <option value="MEDIUM">中期</option>
              <option value="LONG">长期</option>
            </select>
          </label>
        </div>
        <div class="text-grid">
          <label>
            <span>判断理由（必填）</span>
            <textarea
              v-model="opinion.reason"
              maxlength="1000"
              required
              placeholder="为什么形成当前的看多、中性或看空判断？"
            ></textarea>
          </label>
          <label>
            <span>关键观察条件（选填）</span>
            <textarea
              v-model="opinion.observationNote"
              maxlength="1000"
              placeholder="哪些变化可能支持、削弱或推翻当前判断？"
            ></textarea>
          </label>
        </div>
        <div class="form-grid">
          <label><span>目标价（选填）</span><input v-model="opinion.targetPrice" inputmode="decimal" /></label>
          <label><span>止损参考价（选填）</span><input v-model="opinion.stopLossPrice" inputmode="decimal" /></label>
        </div>
        <p v-if="formError" class="error-message" role="alert">{{ formError }}</p>
        <button class="primary" type="submit" :disabled="stockStore.researchSubmitting">
          {{ stockStore.researchSubmitting ? '正在提交…' : currentCycle ? '发表到时间线' : '重新开启关注' }}
        </button>
      </form>
    </section>

    <section id="research-timeline" class="timeline-section">
      <div class="timeline-heading">
        <div><p class="eyebrow">完整历史</p><h2>研究周期与观点时间线</h2></div>
        <div class="filters" aria-label="观点筛选">
          <select v-model="authorFilter" aria-label="按成员筛选">
            <option value="ALL">全部成员</option>
            <option v-for="author in stockStore.researchDetail.authors" :key="author.userId" :value="String(author.userId)">{{ author.nickname }}</option>
          </select>
          <select v-model="stanceFilter" aria-label="按倾向筛选">
            <option value="ALL">全部倾向</option><option value="BULLISH">看多</option><option value="NEUTRAL">中性</option><option value="BEARISH">看空</option>
          </select>
          <select v-model="typeFilter" aria-label="按类型筛选">
            <option value="ALL">全部类型</option><option value="INITIAL">初始观点</option><option value="UPDATE">观点更新</option><option value="REVIEW">复盘总结</option>
          </select>
        </div>
      </div>

      <p v-if="timelineLoading && !timelineCycles.length" class="empty-filter">正在读取研究时间线…</p>
      <p v-else-if="timelineError && !timelineCycles.length" class="empty-filter" role="alert">{{ timelineError }} <button type="button" @click="loadTimeline(true)">重试</button></p>
      <p v-else-if="!timelineCycles.length" class="empty-filter">当前筛选条件下没有观点。</p>
      <article v-for="cycle in timelineCycles" :key="cycle.id" class="cycle-card">
        <header>
          <div>
            <span class="cycle-number">开始于 {{ formatTime(cycle.startedAt) }} · {{ cycle.startedByNickname }}</span>
            <h3>{{ cycle.status === 'ACTIVE' ? '正在关注' : cycle.status === 'CANCELLED' ? '已取消' : '已结束' }} · {{ cycle.attentionLevel === 'FOCUS' ? '重点关注' : '普通关注' }}</h3>
          </div>
          <dl>
            <div><dt>开始</dt><dd>{{ formatTime(cycle.startedAt) }}</dd></div>
            <div><dt>结束</dt><dd>{{ formatTime(cycle.endedAt) }}</dd></div>
            <div><dt>关注价</dt><dd>{{ formatPrice(cycle.startPrice, stockStore.researchDetail.currency) }}</dd></div>
          </dl>
        </header>

        <div class="timeline">
          <section v-for="item in cycle.opinions" :key="item.id" class="opinion-item">
            <div class="opinion-meta">
              <strong>{{ item.authorNickname }}</strong>
              <span>{{ typeLabels[item.type] }} · {{ formatTime(item.createdAt) }}</span>
            </div>
            <p v-if="item.withdrawn" class="withdrawn">这条观点已由作者撤销，记录仍保留。</p>
            <template v-else>
              <p class="opinion-tags">{{ stanceLabels[item.stance] }} · {{ horizonLabels[item.horizon] }}</p>
              <div class="opinion-copy">
                <p>{{ item.reason }}</p>
                <p v-if="item.observationNote"><strong>关键观察条件：</strong>{{ item.observationNote }}</p>
              </div>
              <p class="opinion-prices">目标价 {{ formatPrice(item.targetPrice, stockStore.researchDetail.currency) }} · 止损参考 {{ formatPrice(item.stopLossPrice, stockStore.researchDetail.currency) }}</p>
              <button v-if="canWithdrawNow(item)" type="button" class="withdraw-link" @click="withdrawOpinion(item)">
                {{ item.type === 'INITIAL' ? '撤销本次误添加' : '撤销误发内容' }}
              </button>
            </template>
          </section>
        </div>
      </article>
      <p v-if="timelineError && timelineCycles.length" class="empty-filter" role="alert">{{ timelineError }} <button type="button" @click="loadTimeline(false)">重试</button></p>
      <button v-if="timelineNextCursor" type="button" class="load-more" :disabled="timelineLoading" @click="loadTimeline(false)">
        {{ timelineLoading ? '正在读取…' : '加载更早的观点' }}
      </button>
    </section>
  </template>
</template>

<style scoped>
.page-state { padding: 100px 0; color: var(--color-muted); }
.research-header { padding: 54px 0 36px; border-bottom: 1px solid var(--color-ink); }
.research-header > a { color: var(--color-focus); font-size: 13px; }
.eyebrow, label span, dt { color: var(--color-muted); font-size: 12px; font-weight: 650; }
.research-header .eyebrow { margin-top: 42px; }
.research-header h1 { margin-top: 8px; font-size: 46px; letter-spacing: -0.04em; }
.research-header > p:last-child, .current-actions p { margin-top: 10px; color: var(--color-muted); }
.success-message, .error-message { margin-top: 20px; padding: 14px 16px; }
.success-message { background: color-mix(in oklab, var(--color-focus) 9%, var(--color-surface)); color: var(--color-focus); }
.error-message { color: var(--color-danger); }
.current-actions { display: flex; align-items: center; justify-content: space-between; gap: 30px; padding: 30px 0; border-bottom: 1px solid var(--color-border); }
.current-actions h2, .opinion-compose h2, .timeline-heading h2 { margin-top: 7px; font-size: 25px; }
.current-action-controls { display: flex; align-items: center; flex-wrap: wrap; gap: 14px; }
.compose-toggle { min-height: 40px; padding: 0 16px; border: 1px solid var(--color-ink); border-radius: var(--radius-action); background: var(--color-ink); color: white; cursor: pointer; font-weight: 700; }
.danger-link, .withdraw-link { border: 0; background: transparent; color: var(--color-danger); cursor: pointer; text-decoration: underline; text-underline-offset: 4px; }
.confirm-end { display: flex; align-items: center; gap: 10px; color: var(--color-muted); font-size: 13px; }
.confirm-end button, .primary { min-height: 40px; padding: 0 14px; border: 1px solid var(--color-border); border-radius: var(--radius-action); cursor: pointer; }
.confirm-end button:last-child, .primary { border-color: var(--color-ink); background: var(--color-ink); color: white; font-weight: 700; }
.opinion-compose { margin-top: 38px; padding: 30px; background: var(--color-surface); }
.form-guidance { max-width: 760px; margin-top: 10px; color: var(--color-muted); font-size: 13px; line-height: 1.7; }
.opinion-compose form { margin-top: 24px; }
.form-grid, .text-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; margin-bottom: 18px; }
.text-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
label span { display: block; margin-bottom: 7px; }
input, select, textarea { width: 100%; border: 1px solid var(--color-border); border-radius: var(--radius-control); background: var(--color-canvas); color: inherit; }
input, select { min-height: 44px; padding: 0 12px; }
textarea { min-height: 120px; padding: 12px; resize: vertical; }
.primary { margin-top: 8px; }
.timeline-section { padding: 60px 0 100px; }
.timeline-heading { display: flex; align-items: end; justify-content: space-between; gap: 24px; padding-bottom: 20px; border-bottom: 1px solid var(--color-ink); }
.filters { display: flex; gap: 8px; }
.filters select { width: auto; min-width: 120px; }
.cycle-card { padding: 32px 0; border-bottom: 1px solid var(--color-border); }
.cycle-card > header { display: flex; justify-content: space-between; gap: 30px; }
.cycle-number { color: var(--color-focus); font-size: 12px; font-weight: 700; }
.cycle-card h3 { margin-top: 6px; font-size: 20px; }
.cycle-card dl { display: flex; gap: 28px; margin: 0; }
dd { margin: 4px 0 0; font-size: 13px; }
.timeline { margin: 28px 0 0 12px; border-left: 1px solid var(--color-border); }
.opinion-item { position: relative; padding: 0 0 32px 28px; }
.opinion-item::before { position: absolute; top: 5px; left: -5px; width: 9px; height: 9px; border-radius: 50%; background: var(--color-focus); content: ''; }
.opinion-meta { display: flex; justify-content: space-between; gap: 20px; }
.opinion-meta span, .opinion-prices, .empty-filter, .withdrawn { color: var(--color-muted); font-size: 12px; }
.opinion-tags { margin-top: 8px; color: var(--color-focus); font-weight: 700; }
.opinion-copy { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; margin-top: 12px; line-height: 1.7; }
.opinion-prices { margin-top: 10px; }
.withdraw-link { margin-top: 10px; padding: 0; font-size: 12px; }
.empty-filter { padding: 24px 0; }
@media (max-width: 760px) {
  .current-actions, .timeline-heading, .cycle-card > header { align-items: flex-start; flex-direction: column; }
  .current-action-controls { width: 100%; }
  .form-grid, .text-grid, .opinion-copy { grid-template-columns: 1fr; }
  .filters { width: 100%; flex-direction: column; }
  .filters select { width: 100%; }
  .cycle-card dl { flex-wrap: wrap; }
  .opinion-meta { flex-direction: column; gap: 4px; }
}
</style>
