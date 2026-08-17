import { lazy, Suspense } from 'react'
import dayjs from 'dayjs'
import type { UsageTimelinePointDTO } from '@/api/usage'
import { Skeleton } from '@/components/ui/skeleton'

// echarts 单独拆包（vite manualChunks），仅用量页路由内按需加载
const ReactECharts = lazy(() => import('echarts-for-react'))

function buildOption(points: UsageTimelinePointDTO[]) {
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['输入 token', '输出 token', '请求数'], top: 0 },
    grid: { left: 48, right: 48, top: 36, bottom: 28 },
    xAxis: {
      type: 'category',
      data: points.map((p) => dayjs(p.bucket).format('MM-DD')),
      axisLabel: { hideOverlap: true },
    },
    yAxis: [
      { type: 'value', name: 'token' },
      { type: 'value', name: '次数', splitLine: { show: false } },
    ],
    series: [
      {
        name: '输入 token',
        type: 'bar',
        stack: 'tokens',
        data: points.map((p) => p.totalPromptTokens),
        barMaxWidth: 28,
      },
      {
        name: '输出 token',
        type: 'bar',
        stack: 'tokens',
        data: points.map((p) => p.totalCompletionTokens),
        barMaxWidth: 28,
      },
      {
        name: '请求数',
        type: 'line',
        yAxisIndex: 1,
        data: points.map((p) => p.requestCount),
        smooth: true,
        symbolSize: 5,
      },
    ],
  }
}

/** 用量时间桶图：堆叠柱（输入/输出 token）+ 折线（请求数，右轴） */
export function UsageTimelineChart({ points, loading }: {
  points: UsageTimelinePointDTO[]
  loading?: boolean
}) {
  if (loading) {
    return <Skeleton className="h-72 w-full" />
  }
  return (
    <Suspense fallback={<Skeleton className="h-72 w-full" />}>
      <ReactECharts
        option={buildOption(points)}
        style={{ height: 288 }}
        notMerge
        lazyUpdate
        opts={{ renderer: 'svg' }}
      />
    </Suspense>
  )
}
