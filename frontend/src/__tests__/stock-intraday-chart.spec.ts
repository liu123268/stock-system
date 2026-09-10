import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StockIntradayChart from '@/components/StockIntradayChart.vue'

const chartMocks = vi.hoisted(() => ({
  dispose: vi.fn<() => void>(),
  resize: vi.fn<() => void>(),
  setOption: vi.fn<(option: unknown, replace?: boolean) => void>(),
}))

vi.mock('echarts/core', () => ({
  init: () => chartMocks,
  use: vi.fn<(components: unknown[]) => void>(),
}))

class ResizeObserverStub {
  observe() {}
  disconnect() {}
}

describe('StockIntradayChart display contract', () => {
  beforeEach(() => {
    chartMocks.setOption.mockClear()
    vi.stubGlobal('ResizeObserver', ResizeObserverStub)
    vi.stubGlobal('matchMedia', () => ({ matches: false }))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows two-decimal prices and a complete percentage in the tooltip and axis pointer', async () => {
    mount(StockIntradayChart, {
      props: {
        name: '测试股票',
        referencePrice: 100,
        tradingSessions: [
          { start: '09:30:00', end: '11:30:00' },
          { start: '13:00:00', end: '15:00:00' },
        ],
        points: [{
          time: '2026-09-10T09:30:00',
          price: 101.03,
          averagePrice: 100.025097,
          volume: 100,
          turnover: 10103,
        }],
      },
    })
    await nextTick()

    const option = chartMocks.setOption.mock.calls[0]?.[0] as {
      yAxis: Array<{ axisPointer?: { label?: { formatter?: (params: { value: number }) => string } } }>
      series: Array<{
        name: string
        tooltip?: { valueFormatter?: (value: number) => string }
      }>
    }
    expect(option.series[0]?.tooltip?.valueFormatter?.(101.03)).toBe('101.03')
    expect(option.series[1]?.tooltip?.valueFormatter?.(100.025097)).toBe('100.03')
    expect(option.series[2]?.name).toBe('涨跌幅')
    expect(option.series[2]?.tooltip?.valueFormatter?.(1.03)).toBe('+1.03%')
    expect(option.yAxis[1]?.axisPointer?.label?.formatter?.({ value: 1.03 })).toBe('+1.03%')
  })
})
