<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useAdminPage } from '@/composables/useAdminPage'
const { store, active, run } = useAdminPage()
const filters = reactive({ action: '', result: '' })
const page = ref(1)
const actionLabels: Record<string, string> = {
  DISABLE_USER: '禁用用户',
  RESTORE_USER: '恢复用户',
  ARCHIVE_WORKSPACE: '归档空间',
  RESTORE_WORKSPACE: '恢复空间',
}
function formatTime(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}
async function load() {
  return run(() => store.loadOperationLogs({ page: page.value, size: 20, ...filters }))
}
async function search() {
  if (store.loading) return
  page.value = 1
  await load()
}
async function changePage(next: number) {
  if (store.loading) return
  const previous = page.value
  page.value = next
  if (!(await load()) && active()) page.value = previous
}
onMounted(() => void load())
</script>

<template>
  <section class="admin-page">
    <header class="admin-page-header">
      <div>
        <p class="admin-kicker">( AUDIT TRAIL )</p>
        <h2>操作记录<span aria-hidden="true">。</span></h2>
        <p>按时间查看唯一超级管理员的治理结果；不记录普通用户越权请求和无效 Token。</p>
      </div>
    </header>
    <div v-if="store.error" class="admin-error" role="alert">
      <p>{{ store.error }}</p>
      <button class="admin-button" type="button" :disabled="store.loading" @click="load">
        重试读取
      </button>
    </div>
    <div class="admin-panel" :aria-busy="store.loading">
      <form class="admin-toolbar" @submit.prevent="search">
        <label
          >操作类型<select aria-label="操作类型" v-model="filters.action" :disabled="store.loading">
            <option value="">全部操作</option>
            <option v-for="(label, value) in actionLabels" :key="value" :value="value">
              {{ label }}
            </option>
          </select></label
        >
        <label
          >操作结果<select aria-label="操作结果" v-model="filters.result" :disabled="store.loading">
            <option value="">全部结果</option>
            <option value="SUCCESS">成功</option>
            <option value="FAILED">失败</option>
          </select></label
        >
        <button class="admin-button primary" type="submit" :disabled="store.loading">查询</button>
      </form>
      <p v-if="store.loading && !store.operationLogs.items.length" class="admin-state">
        正在读取操作记录…
      </p>
      <p v-else-if="!store.operationLogs.items.length" class="admin-state">
        没有符合条件的治理操作记录
      </p>
      <div
        v-else
        class="admin-table-wrap"
        tabindex="0"
        role="region"
        aria-label="操作记录，可横向滚动"
      >
        <table class="admin-table">
          <caption>
            治理操作结果
          </caption>
          <thead>
            <tr>
              <th>时间</th>
              <th>操作</th>
              <th>目标</th>
              <th>状态变化</th>
              <th>结果</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in store.operationLogs.items" :key="log.operationLogId">
              <td>{{ formatTime(log.createdAt) }}</td>
              <td>{{ actionLabels[log.action] }}</td>
              <td>{{ log.targetLabel ?? '目标不存在' }} · #{{ log.targetId }}</td>
              <td>{{ log.beforeStatus ?? '未知' }} → {{ log.afterStatus ?? '未知' }}</td>
              <td>
                <span class="admin-badge" :class="{ failed: log.result === 'FAILED' }">{{
                  log.result === 'SUCCESS' ? '成功' : '失败'
                }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="admin-pagination">
        <span class="admin-muted">共 {{ store.operationLogs.total }} 条</span
        ><button
          class="admin-button"
          type="button"
          :disabled="page <= 1 || store.loading"
          @click="changePage(page - 1)"
        >
          上一页</button
        ><span
          >{{ store.operationLogs.page }} / {{ Math.max(store.operationLogs.totalPages, 1) }}</span
        ><button
          class="admin-button"
          type="button"
          :disabled="page >= store.operationLogs.totalPages || store.loading"
          @click="changePage(page + 1)"
        >
          下一页
        </button>
      </div>
    </div>
  </section>
</template>
