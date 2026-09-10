<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { CandlestickChart } from 'echarts/charts'
import {
  DataZoomComponent,
  GridComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { init, use, type ECharts } from 'echarts/core'
import type { StockCandlestickVO } from '@/api/stock'

const props = defineProps<{
  candlesticks: StockCandlestickVO[]
  name: string
  period: string
  periodLabel: string
}>()
const emit = defineEmits<{ 'reach-start': [] }>()

use([CandlestickChart, GridComponent, TooltipComponent, DataZoomComponent, CanvasRenderer])

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
  const times = props.candlesticks.map((item) => item.time)
  const values = props.candlesticks.map((item) => [
    item.open,
    item.close,
    item.low,
    item.high,
  ])

  const prepended = previousTimes.length > 0
    && times.length > previousTimes.length
    && times.at(-1) === previousTimes.at(-1)
  const addedCount = prepended ? times.length - previousTimes.length : 0
  const defaultStart = Math.max(0, values.length - 60)
  const startValue = visibleRange && prepended ? visibleRange.start + addedCount : defaultStart
  const endValue = visibleRange && prepended ? visibleRange.end + addedCount : values.length - 1

  chart.setOption(
    {
      animation: !window.matchMedia('(prefers-reduced-motion: reduce)').matches,
      grid: { left: 70, right: 20, top: 18, bottom: 76 },
      tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
      xAxis: {
        type: 'category',
        data: times,
        boundaryGap: true,
        axisLine: { lineStyle: { color: '#AEB3B6' } },
        axisLabel: { color: '#62676B', hideOverlap: true, formatter: dateLabel },
      },
      yAxis: {
        scale: true,
        splitLine: { lineStyle: { color: '#E7E9EA' } },
        axisLabel: { color: '#62676B' },
      },
      dataZoom: [
        { type: 'inside', startValue, endValue },
        {
          type: 'slider',
          height: 24,
          bottom: 18,
          borderColor: '#D5D8DA',
          backgroundColor: '#FCFCFC',
          fillerColor: 'rgba(22, 109, 103, 0.12)',
          handleStyle: { color: '#166D67', borderColor: '#166D67' },
          textStyle: { color: '#62676B' },
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
            color: '#166D67',
            color0: '#FCFCFC',
            borderColor: '#166D67',
            borderColor0: '#B42318',
          },
        },
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
    :aria-label="`${name}的${periodLabel} K 线图，共 ${candlesticks.length} 根 K 线`"
  ></div>
</template>

<style scoped>
.candlestick-chart {
  width: 100%;
  height: 380px;
}

@media (max-width: 640px) {
  .candlestick-chart {
    height: 320px;
  }
}
</style>
