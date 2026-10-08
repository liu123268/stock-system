import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import StockStatusNotice from '../StockStatusNotice.vue'
import { stockStatusLabel } from '@/utils/stock-status'

describe('stock status presentation', () => {
  it('shows the last confirmed state as history when the current request failed', () => {
    const wrapper = mount(StockStatusNotice, { props: { stock: {
      simulated: false, listingStatus: 'UNKNOWN', statusInfo: {
        tradeStatus: 'NORMAL', lastKnownListingStatus: 'LISTED', checkedAt: '2026-10-08T06:35:00',
        attemptedAt: '2026-10-08T09:00:00', current: false,
      },
    } } })
    expect(wrapper.text()).toContain('交易状态待核实')
    expect(wrapper.text()).toContain('上次确认：正常')
    expect(wrapper.text()).not.toContain('状态检查于')
  })
  it('displays verified delisting independently of any quote price', () => {
    const wrapper = mount(StockStatusNotice, { props: { stock: {
      simulated: false, listingStatus: 'DELISTED', statusInfo: {
        tradeStatus: 'DELISTED', lastKnownListingStatus: 'DELISTED', checkedAt: '2026-10-08T06:35:00',
        attemptedAt: '2026-10-08T06:35:00', current: true,
      },
    } } })
    expect(wrapper.text()).toContain('已退市')
    expect(wrapper.text()).toContain('状态检查于')
  })
  it('preserves a provider code change without calling it delisting', () => {
    expect(stockStatusLabel({ simulated: false, listingStatus: 'UNKNOWN', statusInfo: {
      tradeStatus: 'CODE_MOVED', lastKnownListingStatus: 'UNKNOWN', checkedAt: null,
      attemptedAt: null, current: true,
    } })).toBe('代码已变更')
  })
})
