import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StockMarketPanel from '@/components/stock/StockMarketPanel.vue'

type PanelSeries = { updating: boolean; tradeDate: string; points: never[] }
const mocks = vi.hoisted(() => ({
  loadIntraday:
    vi.fn<
      (
        workspaceId: number,
        stockId: number,
        date?: string,
        silent?: boolean,
      ) => Promise<PanelSeries>
    >(),
  handleError: vi.fn<(error: unknown, router: unknown) => Promise<void>>(),
}))
const store = reactive({
  candlestickSeries: null,
  candlestickLoading: false,
  candlestickError: '',
  intradaySeries: null as null | { updating: boolean; tradeDate: string; points: never[] },
  intradayLoading: false,
  intradayError: '',
  loadCandlesticks: vi.fn<(workspaceId: number, stockId: number, period: string) => void>(),
  loadIntraday: mocks.loadIntraday,
  clearCandlesticks: vi.fn<() => void>(),
  clearIntraday: () => {
    store.intradaySeries = null
  },
})
vi.mock('@/stores/stock', () => ({ useStockStore: () => store }))
vi.mock('vue-router', () => ({ useRouter: () => ({}) }))
vi.mock('@/utils/workspace-access', () => ({ handleWorkspaceApiError: mocks.handleError }))
enableAutoUnmount(afterEach)

function deferred() {
  let resolve!: (value: PanelSeries) => void
  const promise = new Promise<PanelSeries>((r) => {
    resolve = r
  })
  return { promise, resolve }
}
function panel(id = 1) {
  return mount(StockMarketPanel, {
    props: { workspaceId: 1, workspaceStockId: id, code: '600519.SH', name: '测试股票' },
    global: { stubs: { StockIntradayChart: true, StockCandlestickChart: true } },
  })
}

describe('StockMarketPanel delayed refresh lifecycle', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    store.intradaySeries = null
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('does not restart an old panel timer when its response arrives after another panel opens', async () => {
    const old = deferred()
    mocks.loadIntraday.mockReturnValueOnce(old.promise)
    const a = panel()
    await a
      .findAll('button')
      .find((b) => b.text() === '分时')!
      .trigger('click')
    a.unmount()
    const series = { updating: true, tradeDate: '2026-09-30', points: [] }
    mocks.loadIntraday.mockImplementation(async () => {
      store.intradaySeries = series
      return series
    })
    const b = panel(2)
    await b
      .findAll('button')
      .find((b) => b.text() === '分时')!
      .trigger('click')
    await flushPromises()
    old.resolve(series)
    await flushPromises()
    expect(vi.getTimerCount()).toBe(1)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(mocks.loadIntraday).toHaveBeenCalledTimes(3)
    expect(mocks.loadIntraday.mock.calls[mocks.loadIntraday.mock.calls.length - 1]?.[1]).toBe(2)
    b.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('does not restart polling after a delayed response when switching back to K line', async () => {
    const old = deferred()
    mocks.loadIntraday.mockReturnValueOnce(old.promise)
    const a = panel()
    await a
      .findAll('button')
      .find((b) => b.text() === '分时')!
      .trigger('click')
    await a
      .findAll('button')
      .find((b) => b.text() === 'K 线')!
      .trigger('click')
    const series = { updating: true, tradeDate: '2026-09-30', points: [] }
    store.intradaySeries = series
    old.resolve(series)
    await flushPromises()
    expect(vi.getTimerCount()).toBe(0)
    a.unmount()
  })

  it('reloads the new target when Vue reuses the panel with different stock props', async () => {
    const a = panel()
    await flushPromises()
    await a.setProps({ workspaceStockId: 2, code: '00700.HK', name: '另一只股票' })
    await flushPromises()
    expect(store.loadCandlesticks).toHaveBeenLastCalledWith(1, 2, 'day')
    a.unmount()
  })

  it('keeps the new timer when an older in-flight refresh finishes after a mode change', async () => {
    const series = { updating: true, tradeDate: '2026-09-30', points: [] }
    mocks.loadIntraday.mockImplementation(async () => {
      store.intradaySeries = series
      return series
    })
    const a = panel()
    await a
      .findAll('button')
      .find((b) => b.text() === '分时')!
      .trigger('click')
    await flushPromises()
    const old = deferred()
    mocks.loadIntraday.mockReturnValueOnce(old.promise)
    await vi.advanceTimersByTimeAsync(30_000)
    await a
      .findAll('button')
      .find((b) => b.text() === 'K 线')!
      .trigger('click')
    await a
      .findAll('button')
      .find((b) => b.text() === '分时')!
      .trigger('click')
    await flushPromises()
    old.resolve(series)
    await flushPromises()
    expect(vi.getTimerCount()).toBe(1)
    a.unmount()
  })

  it('skips background reads while hidden and stops polling on collapse', async () => {
    const series = { updating: true, tradeDate: '2026-09-30', points: [] }
    mocks.loadIntraday.mockImplementation(async () => {
      store.intradaySeries = series
      return series
    })
    const a = panel()
    await a
      .findAll('button')
      .find((b) => b.text() === '分时')!
      .trigger('click')
    await flushPromises()
    Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    await vi.advanceTimersByTimeAsync(30_000)
    expect(mocks.loadIntraday).toHaveBeenCalledTimes(1)
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    await vi.advanceTimersByTimeAsync(30_000)
    expect(mocks.loadIntraday).toHaveBeenCalledTimes(2)
    a.unmount()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(mocks.loadIntraday).toHaveBeenCalledTimes(2)
  })
})
