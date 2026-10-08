import type { StockSearchResponseVO } from '@/api/stock'

type Status = Pick<StockSearchResponseVO, 'listingStatus' | 'simulated' | 'statusInfo'>

export function stockStatusLabel(stock: Status): string {
  if (stock.simulated) return '模拟行情'
  if (stock.listingStatus === 'LISTED') return '正常'
  if (stock.listingStatus === 'SUSPENDED') return '停牌'
  if (stock.listingStatus === 'DELISTED') return '已退市'
  const labels: Record<string, string> = {
    FUSE: '熔断暂停交易', PREPARE_LIST: '待上市', CODE_MOVED: '代码已变更',
    TO_BE_OPENED: '待开盘', SPLIT_STOCK_HALTS: '拆股暂停交易',
    EXPIRED: '已到期', WARRANT_PREPARE_LIST: '权证待上市',
  }
  return stock.statusInfo?.current
    ? (labels[stock.statusInfo.tradeStatus ?? ''] ?? '交易状态待核实')
    : '交易状态待核实'
}
