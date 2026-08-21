import { useState } from 'react'
import { ArrowLeft, ChevronLeft, ChevronRight, FlaskConical } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/common/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { useResults } from '@/api/evaluation'
import { EVALUATION } from '@/lib/constants'
import { formatMetric, parseRunSummary, summarizeConfig } from '@/lib/eval-metrics'
import { formatDuration, time } from '@/lib/format'
import type { EvalRun } from '@/types/evaluation'

/**
 * 单 Run 结果表（线框 09 §4.1）——分页 0 基（API 层已换算，UI 保持 1 基）；
 * 生成指标 -1 哨兵显示 '—'；结果行不含答案全文，不臆造入口（EVAL-5）。
 */
export function ResultsView({ run, onBack }: { run: EvalRun; onBack: () => void }) {
  const [page, setPage] = useState(1)
  const results = useResults(run.id ?? null, page)

  const summary = parseRunSummary(run.summary)
  const totalPages = results.data ? Math.max(1, Math.ceil(results.data.total / EVALUATION.resultsPageSize)) : 1

  return (
    <div className="space-y-4">
      {/* Run 级 summary 头 */}
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <div className="flex items-center gap-2">
            <Button size="icon-sm" variant="ghost" onClick={onBack} title="返回运行列表">
              <ArrowLeft className="size-4" />
            </Button>
            <h2 className="text-base font-semibold text-fg">{run.name ?? `运行 ${run.id}`}</h2>
          </div>
          <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted tabular-nums">
            <span>{summarizeConfig(run.configSnapshot)}</span>
            {summary && !summary.error && (
              <span>
                {summary.successCount ?? 0} 通过 · {summary.failCount ?? 0} 失败 · 平均{' '}
                {formatDuration(summary.avgLatencyMs ?? 0) || '—'}
              </span>
            )}
            <span>{time.full(run.completedAt ?? run.startedAt ?? run.createdAt)}</span>
          </p>
          {summary?.error && (
            <p className="mt-1 rounded-md border border-error-600/40 bg-error-600/10 px-2 py-1 text-xs text-error-700">
              运行失败：{summary.error}
            </p>
          )}
          <p className="mt-1 text-xs text-muted">
            生成模型 <span className="font-mono">{run.generationModel ?? '—'}</span> · 评判模型{' '}
            <span className="font-mono">{run.judgeModel ?? '—'}</span>
          </p>
        </div>
      </div>

      {/* 结果表 */}
      <div className="overflow-x-auto rounded-lg border border-line">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-line bg-muted/40 text-left text-muted">
              <th className="px-3 py-2 font-medium">#</th>
              <th className="px-3 py-2 font-medium">问题</th>
              <th className="px-3 py-2 font-medium">Recall</th>
              <th className="px-3 py-2 font-medium">Prec</th>
              <th className="px-3 py-2 font-medium">MRR</th>
              <th className="px-3 py-2 font-medium">NDCG</th>
              <th className="px-3 py-2 font-medium">CtxP</th>
              <th className="px-3 py-2 font-medium">Faith</th>
              <th className="px-3 py-2 font-medium">CtxRec</th>
              <th className="px-3 py-2 font-medium">AnsRel</th>
              <th className="px-3 py-2 font-medium">CtxRel</th>
              <th className="px-3 py-2 font-medium">耗时</th>
            </tr>
          </thead>
          <tbody>
            {results.isLoading ? (
              <tr><td colSpan={12} className="px-3 py-6"><Skeleton className="h-5 w-full" /></td></tr>
            ) : (results.data?.items.length ?? 0) === 0 ? (
              <tr>
                <td colSpan={12} className="px-3 py-8 text-center text-muted">
                  <EmptyState
                    icon={<FlaskConical />}
                    title="暂无结果"
                    description="该运行没有产出结果条目"
                    className="py-4"
                  />
                </td>
              </tr>
            ) : (
              results.data?.items.map((row) => (
                <tr key={row.id} className="border-b border-line/60 last:border-0 hover:bg-muted/30">
                  <td className="px-3 py-2 text-muted tabular-nums">{row.itemId}</td>
                  <td className="max-w-64 truncate px-3 py-2">
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <span className="block truncate">{row.question ?? '—'}</span>
                      </TooltipTrigger>
                      <TooltipContent className="max-w-sm">{row.question}</TooltipContent>
                    </Tooltip>
                    {row.error && (
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <span className="block truncate text-xs text-error-700">{row.error}</span>
                        </TooltipTrigger>
                        <TooltipContent className="max-w-sm whitespace-pre-wrap">{row.error}</TooltipContent>
                      </Tooltip>
                    )}
                  </td>
                  <td className="px-3 py-2 tabular-nums">{formatMetric(row.retrieval?.recall)}</td>
                  <td className="px-3 py-2 tabular-nums">{formatMetric(row.retrieval?.precision)}</td>
                  <td className="px-3 py-2 tabular-nums">{formatMetric(row.retrieval?.mrr)}</td>
                  <td className="px-3 py-2 tabular-nums">{formatMetric(row.retrieval?.ndcg)}</td>
                  <td className="px-3 py-2 tabular-nums">{formatMetric(row.retrieval?.contextPrecision)}</td>
                  <td className="px-3 py-2 tabular-nums">{formatMetric(row.generation?.faithfulness)}</td>
                  <td className="px-3 py-2 tabular-nums">{formatMetric(row.generation?.contextRecall)}</td>
                  <td className="px-3 py-2 tabular-nums">{formatMetric(row.generation?.answerRelevance)}</td>
                  <td className="px-3 py-2 tabular-nums">{formatMetric(row.generation?.contextRelevance)}</td>
                  <td className="whitespace-nowrap px-3 py-2 text-muted tabular-nums">
                    {row.latencyMs != null ? formatDuration(row.latencyMs) : '—'}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {totalPages > 1 && results.data && (
        <div className="flex items-center justify-between text-sm text-muted">
          <span>第 {page} / {totalPages} 页 · 共 {results.data.total} 条</span>
          <div className="flex items-center gap-2">
            <Button variant="secondary" size="sm" disabled={page <= 1 || results.isLoading} onClick={() => setPage((p) => p - 1)}>
              <ChevronLeft className="size-4" /> 上一页
            </Button>
            <Button
              variant="secondary"
              size="sm"
              disabled={page >= totalPages || results.isLoading}
              onClick={() => setPage((p) => p + 1)}
            >
              下一页 <ChevronRight className="size-4" />
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
