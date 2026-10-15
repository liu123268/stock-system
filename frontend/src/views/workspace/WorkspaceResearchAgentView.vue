<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getWorkspaceStockResearch, type WorkspaceStockResearchResponseVO } from '@/api/stock'
import { ApiError } from '@/api/http'
import ResearchAgentPanel from '@/components/stock/ResearchAgentPanel.vue'
import { useAuthStore } from '@/stores/auth'
import { useAgentDraftStore } from '@/stores/agent-draft'
import { useWorkspaceStore } from '@/stores/workspace'
import { handleWorkspaceApiError } from '@/utils/workspace-access'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const drafts = useAgentDraftStore()
const workspace = useWorkspaceStore()
const target = computed(() => ({
  workspaceId: Number(route.params.workspaceId),
  stockId: Number(route.params.workspaceStockId),
  cycleId: Number(route.params.cycleId),
}))
const validTarget = computed(() => Object.values(target.value).every(value => Number.isSafeInteger(value) && value > 0))
const loading = ref(true)
const error = ref('')
const detail = ref<WorkspaceStockResearchResponseVO | null>(null)
let version = 0
let disposed = false

const backToResearch = computed(() => {
  const query: Record<string, string> = { cycleId: String(target.value.cycleId) }
  for (const key of ['timelineCursor', 'author', 'stance', 'type']) {
    const value = route.query[key]
    if (typeof value === 'string' && value.length <= 160) query[key] = value
  }
  return {
    name: 'workspace-stock-research',
    params: { workspaceId: target.value.workspaceId, workspaceStockId: target.value.stockId },
    query,
    hash: '#research-timeline',
  }
})
const currentCycle = computed(() => detail.value?.currentCycle?.id === target.value.cycleId
  ? detail.value.currentCycle : null)

async function load() {
  const request = { version: ++version, session: auth.sessionVersion, target: { ...target.value } }
  const current = () => !disposed && auth.isAuthenticated && request.version === version && request.session === auth.sessionVersion
  detail.value = null
  error.value = ''
  loading.value = true
  if (!validTarget.value) {
    error.value = '研究对象地址无效，请从股票研究周期重新进入。'
    loading.value = false
    return
  }
  try {
    await workspace.ensureCurrent(request.target.workspaceId)
    if (!current()) return
    const result = (await getWorkspaceStockResearch(request.target.workspaceId, request.target.stockId)).data.data
    if (!current()) return
    if (result.workspaceStockId !== request.target.stockId) throw new Error('读取的股票与当前研究对象不一致，请重新进入。')
    detail.value = result
  } catch (cause) {
    if (!current()) return
    detail.value = null
    if (cause instanceof ApiError && ['WORKSPACE_NOT_FOUND', 'WORKSPACE_MEMBERSHIP_INACTIVE', 'WORKSPACE_ACCESS_DENIED', 'UNAUTHORIZED'].includes(cause.code))
      drafts.remove(request.target)
    if (await handleWorkspaceApiError(cause, router)) return
    if (!current()) return
    error.value = cause instanceof Error ? cause.message : '暂时无法读取研究对象，请重试。'
  } finally {
    if (current()) loading.value = false
  }
}

watch([() => target.value.workspaceId, () => target.value.stockId, () => target.value.cycleId, () => auth.sessionVersion, () => auth.isAuthenticated], () => {
  version++
  detail.value = null
  if (!auth.isAuthenticated) {
    error.value = '请重新登录后查看个人研究记录。'
    loading.value = false
    return
  }
  void load()
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { disposed = true; version++ })
</script>

<template>
  <section class="research-agent-page" aria-label="个人研究助手">
    <header class="research-agent-header">
      <RouterLink v-if="validTarget" class="ui-button return-research" :to="backToResearch">← 返回研究观点</RouterLink>
      <RouterLink v-else class="ui-button return-research" :to="{ name: 'workspaces' }">返回空间选择</RouterLink>
      <div v-if="detail" class="research-agent-object">
        <p>{{ detail.code }} · 研究周期 #{{ target.cycleId }}<template v-if="currentCycle"> · 开始于 {{ currentCycle.startedAt.replace('T', ' ').slice(0, 16) }}</template></p>
        <h1>{{ detail.name }} · 研究助手</h1>
      </div>
      <h1 v-else>研究助手</h1>
    </header>
    <div v-if="loading" class="research-agent-state" role="status">正在读取当前研究对象…</div>
    <div v-else-if="error" class="research-agent-state" role="alert">
      <h2>暂时无法打开研究助手</h2>
      <p>{{ error }}</p>
      <button v-if="validTarget && auth.isAuthenticated" class="ui-button" type="button" @click="load">重新读取</button>
    </div>
    <ResearchAgentPanel v-else-if="detail" :key="`${auth.sessionVersion}:${target.workspaceId}:${target.stockId}:${target.cycleId}`" :target="target" standalone />
  </section>
</template>

<style scoped>
.research-agent-page { display: flex; flex: 1; flex-direction: column; min-width: 0; min-height: 0; width: 100%; }
.research-agent-header { display: flex; flex-shrink: 0; align-items: center; gap: 20px; padding: 18px 0; }
.return-research { display: inline-flex; flex-shrink: 0; align-items: center; min-height: 44px; padding: 8px 14px; border: 1px solid var(--color-border); border-radius: var(--radius-action); background: var(--color-surface); color: var(--color-ink); font-size: 13px; text-decoration: none; }
.research-agent-object { min-width: 0; }
.research-agent-header p { margin: 0 0 5px; color: var(--color-muted); font-family: var(--font-mono); font-size: 11px; overflow-wrap: anywhere; }
.research-agent-header h1 { margin: 0; font-family: var(--font-display); font-size: 22px; overflow-wrap: anywhere; }
.research-agent-state { padding: 28px 20px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-muted); line-height: 1.8; }
.research-agent-state h2 { color: var(--color-ink); font-size: 18px; }
.research-agent-state button { min-height: 44px; padding: 8px 16px; border: 1px solid var(--color-border); border-radius: var(--radius-action); background: var(--color-surface); color: var(--color-ink); cursor: pointer; }
@media (max-width: 560px) {
  .research-agent-header { flex-wrap: wrap; gap: 10px; padding: 12px 0; }
  .research-agent-header h1 { font-size: 19px; }
  .research-agent-object { width: 100%; }
}
</style>
