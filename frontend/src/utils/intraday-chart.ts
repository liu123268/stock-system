import type {
  StockIntradayPointVO,
  StockTradingSessionVO,
} from '@/api/stock'

export interface IntradayChartData {
  times: string[]
  prices: Array<number | null>
  averagePrices: Array<number | null>
  volumes: Array<number | null>
}

export interface IntradayPercentScale {
  percentLimit: number
  priceMin: number
  priceMax: number
}

export const INTRADAY_LINE_CONNECT_NULLS = true
export const INTRADAY_PERCENTAGE_LABEL = '涨跌幅'
const PERCENT_SCALE_STEPS = [1, 2, 3, 5, 10, 20, 30, 50, 100]

export function formatIntradayPrice(value: unknown) {
  const price = Number(value)
  return Number.isFinite(price) ? price.toFixed(2) : '-'
}

export function formatIntradayPercentage(value: unknown) {
  const percentage = Number(value)
  if (!Number.isFinite(percentage)) return '-'
  return `${percentage > 0 ? '+' : ''}${percentage.toFixed(2)}%`
}

export function buildIntradayPercentageData(
  referencePrice: number,
  prices: Array<number | null>,
) {
  if (!Number.isFinite(referencePrice) || referencePrice <= 0) {
    return prices.map(() => null)
  }
  return prices.map((price) => (
    price === null ? null : ((price - referencePrice) / referencePrice) * 100
  ))
}

function toMinutes(value: string) {
  const parts = value.slice(0, 5).split(':').map(Number)
  const hours = parts[0] ?? 0
  const minutes = parts[1] ?? 0
  return hours * 60 + minutes
}

function minuteLabel(totalMinutes: number) {
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}`
}

function pointMinute(time: string) {
  return time.match(/T(\d{2}:\d{2})/)?.[1] ?? time.slice(11, 16)
}

export function buildIntradayChartData(
  sessions: StockTradingSessionVO[],
  points: StockIntradayPointVO[],
): IntradayChartData {
  const times = sessions.flatMap((session) => {
    const start = toMinutes(session.start)
    const end = toMinutes(session.end)
    return Array.from({ length: end - start + 1 }, (_, index) => minuteLabel(start + index))
  })
  const pointsByMinute = new Map(points.map((point) => [pointMinute(point.time), point]))

  return {
    times,
    prices: times.map((time) => pointsByMinute.get(time)?.price ?? null),
    averagePrices: times.map((time) => pointsByMinute.get(time)?.averagePrice ?? null),
    volumes: times.map((time) => pointsByMinute.get(time)?.volume ?? null),
  }
}

export function tradingSessionBoundaryLabels(sessions: StockTradingSessionVO[]) {
  const labels = new Map<string, string>()
  sessions.forEach((session, index) => {
    const start = session.start.slice(0, 5)
    const end = session.end.slice(0, 5)
    if (index === 0) labels.set(start, start)
    const nextStart = sessions[index + 1]?.start.slice(0, 5)
    labels.set(end, nextStart ? `${end}/${nextStart}` : end)
  })
  return labels
}

export function buildIntradayPercentScale(
  referencePrice: number,
  points: StockIntradayPointVO[],
): IntradayPercentScale {
  const prices = points.flatMap((point) => [point.price, point.averagePrice])
  const maxDeviation = prices.reduce((maximum, price) => {
    const percentage = Math.abs(((price - referencePrice) / referencePrice) * 100)
    return Math.max(maximum, percentage)
  }, 0)
  const requiredLimit = Math.max(1, maxDeviation * 1.08)
  const percentLimit = PERCENT_SCALE_STEPS.find((step) => step >= requiredLimit)
    ?? Math.ceil(requiredLimit / 10) * 10

  return {
    percentLimit,
    priceMin: referencePrice * (1 - percentLimit / 100),
    priceMax: referencePrice * (1 + percentLimit / 100),
  }
}
