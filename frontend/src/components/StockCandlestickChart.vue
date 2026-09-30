<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { BarChart, CandlestickChart } from 'echarts/charts'
import {
  DataZoomComponent,
  GridComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { init, use, type ECharts } from 'echarts/core'
import type { StockCandlestickVO } from '@/api/stock'
import { marketTooltip, readMarketChartTheme } from '@/utils/market-chart-theme'

const props = defineProps<{
  candlesticks: StockCandlestickVO[]
  name: string
  period: string
  periodLabel: string
}>()
const emit = defineEmits<{ 'reach-start': [] }>()

use([CandlestickChart, BarChart, GridComponent, TooltipComponent, DataZoomComponent, CanvasRenderer])

const chartHost = ref<HTMLDivElement | null>(null)
let chart: ECharts | null = null
let resizeObserver: ResizeObserver | null = null
let visibleRange: { start: number; end: number } | null = null
let previousTimes: string[] = []

function dateLabel(value: string) {
  const date = new Date(value)
  const intradayPeriod = ['1m', '5m', '15m', '30m', '1h'].includes(props.period)
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    ...(intradayPeriod ? { hour: '2-digit', minute: '2-digit' } : { year: 'numeric' }),
  }).format(date)
}

function renderChart() {
  if (!chartHost.value) return
  chart ??= init(chartHost.value)
  const theme = readMarketChartTheme(chartHost.value)
  const times = props.candlesticks.map((item) => item.time)
  const values = props.candlesticks.map((item) => [
    item.open,
    item.close,
    item.low,
    item.high,
  ])
  const volumes = props.candlesticks.map((item) => item.volume)

  const prepended = previousTimes.length > 0
    && times.length > previousTimes.length
    && times[times.length - 1] === previousTimes[previousTimes.length - 1]
  const addedCount = prepended ? times.length - previousTimes.length : 0
  const defaultStart = Math.max(0, values.length - 60)
  const startValue = visibleRange && prepended ? visibleRange.start + addedCount : defaultStart
  const endValue = visibleRange && prepended ? visibleRange.end + addedCount : values.length - 1

  chart.setOption(
    {
      animation: !window.matchMedia('(prefers-reduced-motion: reduce)').matches,
      textStyle: { color: theme.muted, fontFamily: theme.mono, fontSize: 11 },
      grid: [
        { left: 70, right: 20, top: 18, height: '57%' },
        { left: 70, right: 20, top: '69%', height: '13%' },
      ],
      tooltip: marketTooltip(theme),
      xAxis: [
        {
          type: 'category',
          data: times,
          boundaryGap: true,
          axisLine: { lineStyle: { color: theme.line } },
          axisLabel: { show: false },
        },
        {
          type: 'category',
          gridIndex: 1,
          data: times,
          boundaryGap: true,
          axisLine: { lineStyle: { color: theme.line } },
          axisLabel: { color: theme.muted, hideOverlap: true, formatter: dateLabel },
        },
      ],
      yAxis: [
        {
          scale: true,
          splitLine: { lineStyle: { color: theme.line } },
          axisLabel: { color: theme.muted },
        },
        {
          type: 'value',
          gridIndex: 1,
          min: 0,
          name: '成交量',
          nameTextStyle: { color: theme.muted, fontSize: 11 },
          splitLine: { show: false },
          axisLabel: { show: false },
        },
      ],
      dataZoom: [
        { type: 'inside', xAxisIndex: [0, 1], startValue, endValue },
        {
          type: 'slider',
          xAxisIndex: [0, 1],
          height: 24,
          bottom: 10,
          borderColor: theme.line,
          backgroundColor: theme.surface,
          fillerColor: theme.secondary,
          dataBackground: { lineStyle: { color: theme.muted }, areaStyle: { color: theme.line } },
          selectedDataBackground: { lineStyle: { color: theme.focus }, areaStyle: { color: theme.accent } },
          handleStyle: { color: theme.focus, borderColor: theme.focus },
          textStyle: { color: theme.muted, fontFamily: theme.mono },
          startValue,
          endValue,
        },
      ],
      series: [
        {
          name: props.name,
          type: 'candlestick',
          data: values,
          itemStyle: {
            color: theme.rise,
            color0: theme.fall,
            borderColor: theme.rise,
            borderColor0: theme.fall,
          },
        },
        {
          name: '成交量',
          type: 'bar',
          xAxisIndex: 1,
          yAxisIndex: 1,
          data: volumes,
          itemStyle: { color: theme.volume },
        },
      ],
      media: [
        {
          query: { maxWidth: 480 },
          option: { grid: [{ left: 54, right: 14 }, { left: 54, right: 14 }] },
        },
        { option: { grid: [{ left: 70, right: 20 }, { left: 70, right: 20 }] } },
      ],
    },
    true,
  )
  previousTimes = times
}

function handleDataZoom() {
  if (!chart || props.candlesticks.length === 0) return
  const option = chart.getOption()
  const zoom = Array.isArray(option.dataZoom) ? option.dataZoom[0] : undefined
  if (!zoom) return
  const start = Number(zoom.startValue ?? 0)
  const end = Number(zoom.endValue ?? props.candlesticks.length - 1)
  visibleRange = { start, end }
  if (start <= 5) emit('reach-start')
}

onMounted(async () => {
  await nextTick()
  renderChart()
  chart?.on('datazoom', handleDataZoom)
  if (chartHost.value) {
    resizeObserver = new ResizeObserver(() => chart?.resize())
    resizeObserver.observe(chartHost.value)
  }
})

watch(
  () => [props.candlesticks, props.period],
  async () => {
    await nextTick()
    renderChart()
  },
  { deep: true },
)

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div
    ref="chartHost"
    class="candlestick-chart"
    role="img"
    :aria-label="`${name}的${periodLabel} K 线及成交量图，共 ${candlesticks.length} 根 K 线`"
  ></div>
</template>

<style scoped>
.candlestick-chart {
  width: 100%;
  height: 440px;
}

@media (max-width: 640px) {
  .candlestick-chart {
    height: 370px;
  }
}
</style>
