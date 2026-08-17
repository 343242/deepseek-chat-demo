import { useMemo, useState } from 'react'
import dayjs from 'dayjs'
import { ArrowDown, ArrowUp, ChevronLeft, ChevronRight } from 'lucide-react'
import { PageContainer } from '@/components/common/page-placeholder'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { UsageTimelineChart } from '@/components/usage/timeline-chart'
import {
  useUsageRecords,
  useUsageStats,
  useUsageSummary,
  useUsageTimeline,
  type UsageQueryParams,
  type UsageScene,
  type UsageStatsDim,
  type UsageStatsSort,
  type UsageStatsOrder,
  type TimelineGranularity,
} from '@/api/usage'
import { PERMISSION } from '@/lib/constants'
import { formatDuration, time } from '@/lib/format'
import { usePermission } from '@/hooks/use-permission'

const SCENES: { value: UsageScene; label: string }[] = [
  { value: 'CHAT', label: '聊天' },
  { value: 'AGENT', label: 'Agent' },
  { value: 'INTENT', label: '意图分类' },
]

const RANGE_OPTIONS = [7, 30, 90]

const STAT_COLUMNS: { key: UsageStatsSort; label: string }[] = [
  { key: 'TOTAL_TOKENS', label: '总 token' },
  { key: 'REQUEST_COUNT', label: '请求数' },
  { key: 'AVG_DURATION_MS', label: '平均耗时' },
]

/** 用量统计页：摘要 + 时间桶图 + 分组聚合 + 明细分页（管理员可查指定用户） */
export default function UsagePage() {
  const { has } = usePermission()
  const canViewAll = has(PERMISSION.USAGE_VIEW_ALL)

  const [granularity, setGranularity] = useState<TimelineGranularity>('DAY')
  const [rangeDays, setRangeDays] = useState(30)
  const [scene, setScene] = useState<UsageScene | 'ALL'>('ALL')
  const [userIdInput, setUserIdInput] = useState('')
  const [dim, setDim] = useState<UsageStatsDim>('MODEL')
  const [sort, setSort] = useState<UsageStatsSort>('TOTAL_TOKENS')
  const [order, setOrder] = useState<UsageStatsOrder>('DESC')
  const [modelFilter, setModelFilter] = useState('')
  const [conversationFilter, setConversationFilter] = useState('')
  const [page, setPage] = useState(1)

  const range = useMemo(() => {
    const end = dayjs()
    return { start: end.subtract(rangeDays, 'day').toISOString(), end: end.toISOString() }
  }, [rangeDays])

  const userId = canViewAll && userIdInput.trim() ? Number(userIdInput.trim()) : undefined
  const sceneParam = scene === 'ALL' ? undefined : scene
  const baseParams: UsageQueryParams = useMemo(
    () => ({
      userId,
      scene: sceneParam,
      model: modelFilter.trim() || undefined,
      start: range.start,
      end: range.end,
    }),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- sceneParam/modelFilter 依赖已由下游值覆盖
    [userId, scene, modelFilter, range],
  )

  const summary = useUsageSummary(baseParams)
  const timeline = useUsageTimeline(granularity, baseParams)
  const stats = useUsageStats(dim, sort, order, baseParams)
  const records = useUsageRecords(
    { ...baseParams, conversation: conversationFilter.trim() || undefined },
    page,
  )

  const toggleSort = (key: UsageStatsSort) => {
    if (sort === key) {
      setOrder((o) => (o === 'DESC' ? 'ASC' : 'DESC'))
    } else {
      setSort(key)
      setOrder('DESC')
    }
  }

  return (
    <PageContainer
      title="用量统计"
      subtitle="Token 用量、耗时与成功率——本人维度；管理员可查看指定用户"
      actions={
        <div className="flex flex-wrap items-center gap-2">
          {canViewAll && (
            <Input
              value={userIdInput}
              onChange={(e) => {
                setUserIdInput(e.target.value)
                setPage(1)
              }}
              placeholder="用户 ID（管理员）"
              className="h-9 w-36"
              inputMode="numeric"
            />
          )}
          <Select value={scene} onValueChange={(v) => { setScene(v as UsageScene | 'ALL'); setPage(1) }}>
            <SelectTrigger className="h-9 w-28">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">全部场景</SelectItem>
              {SCENES.map((s) => (
                <SelectItem key={s.value} value={s.value}>{s.label}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Select value={String(rangeDays)} onValueChange={(v) => { setRangeDays(Number(v)); setPage(1) }}>
            <SelectTrigger className="h-9 w-28">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {RANGE_OPTIONS.map((d) => (
                <SelectItem key={d} value={String(d)}>近 {d} 天</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      }
    >
      {/* 摘要卡 */}
      <div className="mb-6 grid grid-cols-2 gap-3 md:grid-cols-5">
        <SummaryCard label="总请求" value={summary.data ? String(summary.data.requestCount) : undefined} />
        <SummaryCard label="成功率" value={summary.data ? `${(summary.data.successRate * 100).toFixed(1)}%` : undefined} />
        <SummaryCard
          label="总 token"
          value={summary.data ? summary.data.totalTokens.toLocaleString() : undefined}
          hint={summary.data ? `输入 ${summary.data.totalPromptTokens.toLocaleString()} / 输出 ${summary.data.totalCompletionTokens.toLocaleString()}` : undefined}
        />
        <SummaryCard label="平均耗时" value={summary.data ? formatDuration(summary.data.avgDurationMs) : undefined} />
        <SummaryCard label="最大耗时" value={summary.data ? formatDuration(summary.data.maxDurationMs) : undefined} />
      </div>

      {/* 时间桶图 */}
      <Card className="mb-6">
        <CardContent className="pt-6">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-medium text-fg">用量趋势</h2>
            <Tabs value={granularity} onValueChange={(v) => setGranularity(v as TimelineGranularity)}>
              <TabsList>
                <TabsTrigger value="DAY">按天</TabsTrigger>
                <TabsTrigger value="MONTH">按月</TabsTrigger>
              </TabsList>
            </Tabs>
          </div>
          <UsageTimelineChart points={timeline.data ?? []} loading={timeline.isLoading} />
        </CardContent>
      </Card>

      {/* 分组聚合 */}
      <Card className="mb-6">
        <CardContent className="pt-6">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-medium text-fg">分组聚合</h2>
            <Tabs value={dim} onValueChange={(v) => setDim(v as UsageStatsDim)}>
              <TabsList>
                <TabsTrigger value="MODEL">模型</TabsTrigger>
                <TabsTrigger value="SCENE">场景</TabsTrigger>
                {canViewAll && <TabsTrigger value="USER">用户</TabsTrigger>}
              </TabsList>
            </Tabs>
          </div>
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border bg-muted/40 text-left text-muted">
                  <th className="px-3 py-2 font-medium">{dim === 'MODEL' ? '模型' : dim === 'SCENE' ? '场景' : '用户 ID'}</th>
                  <th className="px-3 py-2 font-medium">输入 token</th>
                  <th className="px-3 py-2 font-medium">输出 token</th>
                  {STAT_COLUMNS.map((col) => (
                    <th key={col.key} className="px-3 py-2 font-medium">
                      <button
                        type="button"
                        onClick={() => toggleSort(col.key)}
                        className="inline-flex items-center gap-1 hover:text-fg"
                      >
                        {col.label}
                        {sort === col.key && (order === 'DESC' ? <ArrowDown className="size-3" /> : <ArrowUp className="size-3" />)}
                      </button>
                    </th>
                  ))}
                  <th className="px-3 py-2 font-medium">成功率</th>
                </tr>
              </thead>
              <tbody>
                {stats.isLoading ? (
                  <tr><td colSpan={7} className="px-3 py-6"><Skeleton className="h-5 w-full" /></td></tr>
                ) : !stats.data?.length ? (
                  <tr><td colSpan={7} className="px-3 py-6 text-center text-muted">暂无数据</td></tr>
                ) : stats.data.map((row) => (
                  <tr key={row.groupKey} className="border-b border-border/60 last:border-0 hover:bg-muted/30">
                    <td className="px-3 py-2 font-mono text-xs">{SCENES.find((s) => s.value === row.groupKey)?.label ?? row.groupKey}</td>
                    <td className="px-3 py-2 tabular-nums">{row.totalPromptTokens.toLocaleString()}</td>
                    <td className="px-3 py-2 tabular-nums">{row.totalCompletionTokens.toLocaleString()}</td>
                    <td className="px-3 py-2 tabular-nums">{row.totalTokens.toLocaleString()}</td>
                    <td className="px-3 py-2 tabular-nums">{row.requestCount.toLocaleString()}</td>
                    <td className="px-3 py-2 tabular-nums">{formatDuration(row.avgDurationMs)}</td>
                    <td className="px-3 py-2 tabular-nums">{(row.successRate * 100).toFixed(1)}%</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>

      {/* 明细分页 */}
      <Card>
        <CardContent className="pt-6">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <h2 className="text-sm font-medium text-fg">调用明细</h2>
            <div className="flex items-center gap-2">
              <Input
                value={modelFilter}
                onChange={(e) => { setModelFilter(e.target.value); setPage(1) }}
                placeholder="模型过滤"
                className="h-9 w-32"
              />
              <Input
                value={conversationFilter}
                onChange={(e) => { setConversationFilter(e.target.value); setPage(1) }}
                placeholder="会话过滤"
                className="h-9 w-40"
              />
            </div>
          </div>
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border bg-muted/40 text-left text-muted">
                  <th className="px-3 py-2 font-medium">时间</th>
                  <th className="px-3 py-2 font-medium">场景</th>
                  <th className="px-3 py-2 font-medium">模型</th>
                  <th className="px-3 py-2 font-medium">输入</th>
                  <th className="px-3 py-2 font-medium">输出</th>
                  <th className="px-3 py-2 font-medium">总 token</th>
                  <th className="px-3 py-2 font-medium">耗时</th>
                  <th className="px-3 py-2 font-medium">状态</th>
                </tr>
              </thead>
              <tbody>
                {records.isLoading ? (
                  <tr><td colSpan={8} className="px-3 py-6"><Skeleton className="h-5 w-full" /></td></tr>
                ) : !records.data?.content.length ? (
                  <tr><td colSpan={8} className="px-3 py-6 text-center text-muted">暂无数据</td></tr>
                ) : records.data.content.map((e) => (
                  <tr key={e.eventId} className="border-b border-border/60 last:border-0 hover:bg-muted/30">
                    <td className="whitespace-nowrap px-3 py-2 text-muted">{time.full(e.createdAt ?? undefined)}</td>
                    <td className="px-3 py-2">
                      <Badge variant="neutral" square>{SCENES.find((s) => s.value === e.scene)?.label ?? e.scene}</Badge>
                    </td>
                    <td className="px-3 py-2 font-mono text-xs">{e.modelId}</td>
                    <td className="px-3 py-2 tabular-nums">{e.promptTokens?.toLocaleString() ?? '—'}</td>
                    <td className="px-3 py-2 tabular-nums">{e.completionTokens?.toLocaleString() ?? '—'}</td>
                    <td className="px-3 py-2 tabular-nums">
                      {e.totalTokens?.toLocaleString() ?? '—'}
                      {e.estimated && <Badge variant="warning" square className="ml-1">估算</Badge>}
                    </td>
                    <td className="px-3 py-2 tabular-nums">{formatDuration(e.durationMs)}</td>
                    <td className="px-3 py-2">
                      <Badge variant={e.success ? 'success' : 'error'} square>{e.success ? '成功' : '失败'}</Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="mt-3 flex items-center justify-between text-sm text-muted">
            <span>
              {records.data ? `第 ${records.data.page} / ${Math.max(records.data.totalPages, 1)} 页 · 共 ${records.data.total} 条` : ''}
            </span>
            <div className="flex items-center gap-2">
              <Button variant="secondary" size="sm" disabled={page <= 1 || records.isLoading} onClick={() => setPage((p) => p - 1)}>
                <ChevronLeft className="size-4" /> 上一页
              </Button>
              <Button
                variant="secondary"
                size="sm"
                disabled={!records.data || page >= records.data.totalPages || records.isLoading}
                onClick={() => setPage((p) => p + 1)}
              >
                下一页 <ChevronRight className="size-4" />
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>
    </PageContainer>
  )
}

function SummaryCard({ label, value, hint }: { label: string; value?: string; hint?: string }) {
  return (
    <Card>
      <CardContent className="px-4 py-3">
        <p className="text-xs text-muted">{label}</p>
        {value === undefined ? (
          <Skeleton className="mt-1.5 h-7 w-20" />
        ) : (
          <p className="mt-1 text-xl font-semibold tabular-nums text-fg">{value}</p>
        )}
        {hint && <p className="mt-0.5 text-xs text-muted">{hint}</p>}
      </CardContent>
    </Card>
  )
}
