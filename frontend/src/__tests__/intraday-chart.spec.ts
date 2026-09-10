import { describe, expect, it } from 'vitest'
import {
  buildIntradayChartData,
  buildIntradayPercentageData,
  buildIntradayPercentScale,
  formatIntradayPercentage,
  formatIntradayPrice,
  INTRADAY_LINE_CONNECT_NULLS,
  INTRADAY_PERCENTAGE_LABEL,
  tradingSessionBoundaryLabels,
} from '@/utils/intraday-chart'

describe('intraday chart time axis', () => {
  const sessions = [
    { start: '09:30:00', end: '11:30:00' },
    { start: '13:00:00', end: '15:00:00' },
  ]

  it('keeps the complete trading day while future minutes remain empty', () => {
    const chartData = buildIntradayChartData(sessions, [
      {
        time: '2026-09-10T09:30:00',
        price: 10,
        averagePrice: 10,
        volume: 100,
        turnover: 1000,
      },
      {
        time: '2026-09-10T10:00:00',
        price: 10.2,
        averagePrice: 10.1,
        volume: 120,
        turnover: 1224,
      },
    ])

    expect(chartData.times).toHaveLength(242)
    expect(chartData.times[0]).toBe('09:30')
    expect(chartData.times[chartData.times.length - 1]).toBe('15:00')
    expect(chartData.prices[0]).toBe(10)
    expect(chartData.prices[30]).toBe(10.2)
    expect(chartData.prices[chartData.prices.length - 1]).toBeNull()
  })

  it('compresses the lunch break into one boundary label and keeps lines continuous', () => {
    expect([...tradingSessionBoundaryLabels(sessions)]).toEqual([
      ['09:30', '09:30'],
      ['11:30', '11:30/13:00'],
      ['15:00', '15:00'],
    ])
    expect(INTRADAY_LINE_CONNECT_NULLS).toBe(true)
  })

  it('builds a symmetric stepped percentage scale around the previous close', () => {
    const scale = buildIntradayPercentScale(100, [
      {
        time: '2026-09-10T09:30:00',
        price: 102.6,
        averagePrice: 101.2,
        volume: 100,
        turnover: 10260,
      },
      {
        time: '2026-09-10T10:00:00',
        price: 98.5,
        averagePrice: 100.4,
        volume: 120,
        turnover: 11820,
      },
    ])

    expect(scale).toEqual({ percentLimit: 3, priceMin: 97, priceMax: 103 })
  })

  it('formats tooltip prices and percentage labels for stock reading', () => {
    expect(formatIntradayPrice(19.025097)).toBe('19.03')
    expect(INTRADAY_PERCENTAGE_LABEL).toBe('涨跌幅')
    expect(formatIntradayPercentage(1.03)).toBe('+1.03%')
    expect(formatIntradayPercentage(0)).toBe('0.00%')
    expect(formatIntradayPercentage(-1.2)).toBe('-1.20%')

    const percentages = buildIntradayPercentageData(100, [101.03, 98.8, null])
    expect(percentages[0]).toBeCloseTo(1.03)
    expect(percentages[1]).toBeCloseTo(-1.2)
    expect(percentages[2]).toBeNull()
  })
})
