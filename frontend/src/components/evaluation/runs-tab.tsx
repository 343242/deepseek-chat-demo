import { FlaskConical, Play } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/common/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge } from '@/components/common/status-badge'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { useRuns } from '@/api/evaluation'
import { parseRunSummary, summarizeConfig } from '@/lib/eval-metrics'
import { formatDuration, time } from '@/lib/format'
import { EVALUATION_RUN_STATUS_META } from '@/lib/status-meta'
import type { EvalRun } from '@/types/evaluation'
import { ResultsView } from './results-view'

/**
 * 评测运行 Tab（线框 09 §3）——运行列表 + 行内结果子视图。
 * 无停止/删除 Run 端点（EVAL-3），行菜单不画；running 行可打开进度面板（由页面持有）。
 */
export function RunsTab({
  datasetId,
  selectedRunId,
  onSelectRun,
  onViewProgress,
  onStartRun,
}: {
  datasetId: number
  /** URL ?run= 持久化的结果子视图 run id */
  selectedRunId: number | null
  onSelectRun: (runId: number | null) => void
  /** 打开 SSE 进度面板（running 行） */
  onViewProgress: (run: EvalRun) => void
  onStartRun: () => void
}) {
  const runs = useRuns(datasetId)

  if (runs.isLoading) {
    return (
      <div className="space-y-2">
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-32 w-full" />
      </div>
    )
  }
  if (runs.isError) {
    return (
      <EmptyState
        icon={<FlaskConical />}
        title="加载失败"
        description={runs.error.message}
        action={<Button size="sm" variant="secondary" onClick={() => void runs.refetch()}>重试</Button>}
      />
    )
  }

  const list = runs.data?.runs ?? []
  const selectedRun = selectedRunId != null ? list.find((r) => r.id === selectedRunId) : undefined

  // 结果子视图：run 从列表找不到（被清理/参数过期）时退回列表
  if (selectedRunId != null && selectedRun) {
    return <ResultsView run={selectedRun} onBack={() => onSelectRun(null)} />
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted">共 {list.length} 次运行（新 → 旧）</p>
        <Button size="sm" onClick={onStartRun}>
          <Play className="size-4" /> 启动评测
        </Button>
      </div>

      {list.length === 0 ? (
        <EmptyState
          icon={<FlaskConical />}
          title="还没有评测运行"
          description="从数据集启动一次评测"
          action={
            <Button size="sm" onClick={onStartRun}>
              <Play className="size-4" /> 启动评测
            </Button>
          }
        />
      ) : (
        <div className="overflow-x-auto rounded-lg border border-line">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line bg-muted/40 text-left text-muted">
                <th className="px-3 py-2 font-medium">运行名</th>
                <th className="px-3 py-2 font-medium">状态</th>
                <th className="px-3 py-2 font-medium">配置</th>
                <th className="px-3 py-2 font-medium">结果</th>
                <th className="px-3 py-2 font-medium">时间</th>
                <th className="px-3 py-2 font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {list.map((run) => (
                <RunRow
                  key={run.id}
                  run={run}
                  onOpenResults={() => run.id != null && onSelectRun(run.id)}
                  onViewProgress={() => onViewProgress(run)}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="text-xs text-muted">
        运行超过 30 分钟未结束会被系统标记失败；最多同时运行 2 个评测。
      </p>
    </div>
  )
}

function RunRow({
  run,
  onOpenResults,
  onViewProgress,
}: {
  run: EvalRun
  onOpenResults: () => void
  onViewProgress: () => void
}) {
  const summary = parseRunSummary(run.summary)
  const status = run.status ?? 'pending'
  const isActive = status === 'running' || status === 'pending'

  return (
    <tr
      className="cursor-pointer border-b border-line/60 last:border-0 hover:bg-muted/30"
      onClick={onOpenResults}
    >
      <td className="px-3 py-2 font-medium">{run.name ?? `运行 ${run.id}`}</td>
      <td className="px-3 py-2">
        <StatusBadge meta={EVALUATION_RUN_STATUS_META[status] ?? EVALUATION_RUN_STATUS_META.pending} />
      </td>
      <td className="max-w-52 truncate px-3 py-2 text-muted">
        <Tooltip>
          <TooltipTrigger asChild>
            <span className="block truncate">{summarizeConfig(run.configSnapshot)}</span>
          </TooltipTrigger>
          <TooltipContent className="max-w-xs font-mono text-xs">{run.configSnapshot}</TooltipContent>
        </Tooltip>
      </td>
      <td className="max-w-52 truncate px-3 py-2 text-muted tabular-nums">
        {summary?.error ? (
          <Tooltip>
            <TooltipTrigger asChild>
              <span className="block truncate text-error-700">{summary.error}</span>
            </TooltipTrigger>
            <TooltipContent className="max-w-xs">{summary.error}</TooltipContent>
          </Tooltip>
        ) : summary ? (
          `${summary.successCount ?? 0} 通过 · ${summary.failCount ?? 0} 失败 · 平均 ${formatDuration(summary.avgLatencyMs ?? 0) || '—'}`
        ) : isActive ? (
          '运行中…'
        ) : (
          '—'
        )}
      </td>
      <td className="whitespace-nowrap px-3 py-2 text-muted">{time.full(run.completedAt ?? run.startedAt ?? run.createdAt)}</td>
      <td className="px-3 py-2" onClick={(e) => e.stopPropagation()}>
        {isActive ? (
          <Button size="sm" variant="secondary" onClick={onViewProgress}>
            查看进度
          </Button>
        ) : (
          <Button size="sm" variant="ghost" onClick={onOpenResults}>
            查看结果
          </Button>
        )}
      </td>
    </tr>
  )
}
