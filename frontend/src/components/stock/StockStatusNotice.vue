<script setup lang="ts">
import type { StockSearchResponseVO } from '@/api/stock'
import { stockStatusLabel } from '@/utils/stock-status'

defineProps<{ stock: Pick<StockSearchResponseVO, 'listingStatus' | 'simulated' | 'statusInfo'> }>()
function time(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value))
}
function previous(status: string | null) {
  return ({ LISTED: '正常', SUSPENDED: '停牌', DELISTED: '已退市' } as Record<string, string>)[status ?? '']
    ?? '其他交易状态'
}
</script>

<template>
  <p class="stock-status-notice">
    <strong>{{ stockStatusLabel(stock) }}</strong>
    <template v-if="!stock.simulated && stock.statusInfo?.checkedAt">
      <span v-if="stock.statusInfo.current"> · 状态检查于 {{ time(stock.statusInfo.checkedAt) }}</span>
      <span v-else> · 上次确认：{{ previous(stock.statusInfo.lastKnownListingStatus) }}，{{ time(stock.statusInfo.checkedAt) }}</span>
    </template>
  </p>
</template>

<style scoped>
.stock-status-notice { color: var(--color-muted); font-size: 12px; overflow-wrap: anywhere; }
.stock-status-notice strong { color: var(--color-ink); font-weight: 600; }
</style>
