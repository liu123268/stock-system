import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StockCandlestickChart from '@/components/StockCandlestickChart.vue'

const chartMocks = vi.hoisted(() => ({
  dispose: vi.fn<() => void>(),
  getOption: vi.fn<() => { dataZoom: unknown[] }>(() => ({ dataZoom: [] })),
  on: vi.fn<() => void>(),
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

describe('StockCandlestickChart display contract', () => {
  beforeEach(() => {
    chartMocks.setOption.mockClear()
    vi.stubGlobal('ResizeObserver', ResizeObserverStub)
    vi.stubGlobal('matchMedia', () => ({ matches: false }))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders neutral volume bars on the same zoomed time range as the candlesticks', async () => {
    const wrapper = mount(StockCandlestickChart, {
      props: {
        name: '测试股票',
        period: 'day',
        periodLabel: '日 K',
        candlesticks: [
          {
            time: '2026-09-14T15:00:00',
            open: 10,
            high: 11,
            low: 9.8,
            close: 10.8,
            volume: 1200,
            turnover: 12500,
          },
          {
            time: '2026-09-15T15:00:00',
            open: 10.8,
            high: 11.2,
            low: 10.5,
            close: 10.6,
            volume: 1800,
            turnover: 19400,
          },
        ],
      },
    })
    await nextTick()

    const option = chartMocks.setOption.mock.calls[0]?.[0] as {
      dataZoom: Array<{ xAxisIndex?: number[] }>
      grid: unknown[]
      yAxis: Array<{ name?: string }>
      series: Array<{
        name: string
        data: unknown[]
        itemStyle?: { color?: string; color0?: string; borderColor?: string; borderColor0?: string }
        xAxisIndex?: number
        yAxisIndex?: number
      }>
    }

    expect(option.grid).toHaveLength(2)
    expect(option.dataZoom[0]?.xAxisIndex).toEqual([0, 1])
    expect(option.yAxis[1]?.name).toBe('成交量')
    expect(option.series[0]?.itemStyle).toEqual({
      color: '#B42318',
      color0: '#166D67',
      borderColor: '#B42318',
      borderColor0: '#166D67',
    })
    expect(option.series[1]).toMatchObject({
      name: '成交量',
      data: [1200, 1800],
      xAxisIndex: 1,
      yAxisIndex: 1,
      itemStyle: { color: '#a69e8d' },
    })
    expect(wrapper.get('.candlestick-chart').attributes('aria-label')).toContain('K 线及成交量图')
  })
})
