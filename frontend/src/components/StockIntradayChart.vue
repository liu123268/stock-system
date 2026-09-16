<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { init, use, type ECharts } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import type { StockIntradayPointVO, StockTradingSessionVO } from '@/api/stock'
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

const props = defineProps<{
  points: StockIntradayPointVO[]
  tradingSessions: StockTradingSessionVO[]
  referencePrice: number
  name: string
}>()

use([
  LineChart,
  BarChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  CanvasRenderer,
])

const chartHost = ref<HTMLDivElement | null>(null)
let chart: ECharts | null = null
let resizeObserver: ResizeObserver | null = null

function renderChart() {
  if (!chartHost.value) return
  chart ??= init(chartHost.value)
  const chartData = buildIntradayChartData(props.tradingSessions, props.points)
  const percentageData = buildIntradayPercentageData(props.referencePrice, chartData.prices)
  const percentScale = buildIntradayPercentScale(props.referencePrice, props.points)
  const boundaryLabels = tradingSessionBoundaryLabels(props.tradingSessions)
  chart.setOption({
    animation: !window.matchMedia('(prefers-reduced-motion: reduce)').matches,
    legend: { top: 0, data: ['价格', '均价'] },
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
    grid: [
      { left: 68, right: 68, top: 42, height: '57%' },
      { left: 68, right: 68, top: '75%', height: '15%' },
    ],
    xAxis: [
      {
        type: 'category',
        data: chartData.times,
        boundaryGap: false,
        axisLabel: {
          interval: (_index: number, value: string) => boundaryLabels.has(value),
          formatter: (value: string) => boundaryLabels.get(value) ?? '',
          hideOverlap: true,
        },
      },
      {
        type: 'category',
        gridIndex: 1,
        data: chartData.times,
        boundaryGap: true,
        axisLabel: { show: false },
      },
    ],
    yAxis: [
      {
        type: 'value',
        min: percentScale.priceMin,
        max: percentScale.priceMax,
        splitNumber: 4,
        axisLabel: { formatter: (value: number) => value.toFixed(2) },
        splitLine: { lineStyle: { color: '#E7E9EA' } },
      },
      {
        type: 'value',
        position: 'right',
        min: -percentScale.percentLimit,
        max: percentScale.percentLimit,
        splitNumber: 4,
        axisLabel: {
          formatter: formatIntradayPercentage,
        },
        axisPointer: { label: { formatter: ({ value }: { value: number }) => formatIntradayPercentage(value) } },
        splitLine: { show: false },
      },
      { type: 'value', scale: true, gridIndex: 1, axisLabel: { show: false } },
    ],
    series: [
      {
        name: '价格',
        type: 'line',
        data: chartData.prices,
        symbol: 'none',
        connectNulls: INTRADAY_LINE_CONNECT_NULLS,
        lineStyle: { color: '#3F6F8F', width: 1.5 },
        tooltip: { valueFormatter: formatIntradayPrice },
      },
      {
        name: '均价',
        type: 'line',
        data: chartData.averagePrices,
        symbol: 'none',
        connectNulls: INTRADAY_LINE_CONNECT_NULLS,
        lineStyle: { color: '#C8892A', width: 1.2 },
        tooltip: { valueFormatter: formatIntradayPrice },
      },
      {
        name: INTRADAY_PERCENTAGE_LABEL,
        type: 'line',
        yAxisIndex: 1,
        data: percentageData,
        symbol: 'none',
        lineStyle: { opacity: 0 },
        emphasis: { disabled: true },
        tooltip: { valueFormatter: formatIntradayPercentage },
      },
      {
        name: '成交量',
        type: 'bar',
        xAxisIndex: 1,
        yAxisIndex: 2,
        data: chartData.volumes,
        itemStyle: { color: 'rgba(98, 103, 107, 0.38)' },
      },
    ],
  }, true)
}

onMounted(async () => {
  await nextTick()
  renderChart()
  if (chartHost.value) {
    resizeObserver = new ResizeObserver(() => chart?.resize())
    resizeObserver.observe(chartHost.value)
  }
})

watch(() => [props.points, props.tradingSessions, props.referencePrice], async () => {
  await nextTick()
  renderChart()
}, { deep: true })

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <div
    ref="chartHost"
    class="intraday-chart"
    role="img"
    :aria-label="`${name}分时图，以昨收 ${referencePrice} 为涨跌幅基准，共 ${points.length} 个数据点`"
  ></div>
</template>

<style scoped>
.intraday-chart { width: 100%; height: 420px; }
@media (max-width: 640px) { .intraday-chart { height: 340px; } }
</style>
