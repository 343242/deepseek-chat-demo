import { Fragment, useState } from 'react'
import { GitCompareArrows } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { EmptyState } from '@/components/common/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { useCompareRuns, useRuns } from '@/api/evaluation'
import { buildCompareRows, formatDiff, formatMetric, type CompareColumn } from '@/lib/eval-metrics'
import { EVALUATION } from '@/lib/constants'
import { cn } from '@/lib/utils'
import type { EvalRun } from '@/types/evaluation'

/**
 * 多 Run 对比 Tab（线框 09 §4.2）——≥2、≤10 个 Run；首个选中为基线列，
 * 其余列 ▲▼ 差值（箭头+数字，不以色彩为唯一载体，DS 12.6）。
 * compare 响应按 run name 键控（重名覆盖），此处按 runId 反查（design.md §5）。
 */
export function CompareTab({ datasetId }: { datasetId: number }) {
  const runs = useRuns(datasetId)
  const compare = useCompareRuns()
  const [selected, setSelected] = useState<number[]>([])
  const [columns, setColumns] = useState<CompareColumn[] | null>(null)

  const list = runs.data?.runs ?? []

  function toggle(runId: number, checked: boolean) {
    setSelected((prev) => {
      if (checked) {
        if (prev.length >= EVALUATION.maxCompareRuns) {
          toast.error(`单次最多对比 ${EVALUATION.maxCompareRuns} 个运行`)
          return prev
        }
        return [...prev, runId]
      }
      return prev.filter((id) => id !== runId)
    })
    setColumns(null)
  }

  async function handleCompare() {
    try {
      const res = await compare.mutateAsync(selected)
      // 以选中顺序构造列（首个为基线）；从 comparison 按 runId 反查，重名覆盖丢失的列整列 '—'
      const cols: CompareColumn[] = selected.map((runId) => {
        const run: EvalRun | undefined = list.find((r) => r.id === runId)
        const name = run?.name ?? `运行 ${runId}`
        const entry = Object.values(res.comparison).find((e) => e.runId === runId) ?? null
        return { runId, name, entry }
      })
      setColumns(cols)
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  if (runs.isLoading) {
    return (
      <div className="space-y-2">
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-48 w-full" />
      </div>
    )
  }
  if (runs.isError) {
    return (
      <EmptyState
        icon={<GitCompareArrows />}
        title="加载失败"
        description={runs.error.message}
        action={<Button size="sm" variant="secondary" onClick={() => void runs.refetch()}>重试</Button>}
      />
    )
  }

  const comparable = list.filter((r) => r.id != null)

  return (
    <div className="space-y-4">
      {/* Run 选择 */}
      <div className="rounded-lg border border-line bg-surface p-3">
        <div className="mb-2 flex items-center justify-between gap-2">
          <p className="text-sm font-medium text-fg">选择 Run（首个为基线）</p>
          <Button
            size="sm"
            onClick={() => void handleCompare()}
            loading={compare.isPending}
            disabled={selected.length < EVALUATION.minCompareRuns}
          >
            <GitCompareArrows className="size-4" /> 开始对比
          </Button>
        </div>
        {comparable.length === 0 ? (
          <p className="py-4 text-center text-sm text-muted">还没有可对比的运行</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {comparable.map((run) => {
              const index = selected.indexOf(run.id!)
              const checked = index >= 0
              return (
                <label
                  key={run.id}
                  className={cn(
                    'flex cursor-pointer items-center gap-2 rounded-md border border-line px-2.5 py-1.5 text-sm transition-colors hover:bg-hover',
                    checked && 'border-primary-600/50 bg-selected',
                  )}
                >
                  <Checkbox checked={checked} onCheckedChange={(v) => toggle(run.id!, v === true)} />
                  <span className="max-w-44 truncate">{run.name ?? `运行 ${run.id}`}</span>
                  {index === 0 && <span className="text-xs text-primary-700">基线</span>}
                </label>
              )
            })}
          </div>
        )}
        <p className="mt-2 text-xs text-muted">
          至少选择 {EVALUATION.minCompareRuns} 个运行（勾选顺序决定基线，最多 {EVALUATION.maxCompareRuns} 个）。
        </p>
      </div>

      {/* 对比表 */}
      {columns && columns.length >= EVALUATION.minCompareRuns && (
        <CompareTable columns={columns} />
      )}
    </div>
  )
}

function CompareTable({ columns }: { columns: CompareColumn[] }) {
  const rows = buildCompareRows(columns)
  const groups: { key: 'retrieval' | 'generation'; label: string }[] = [
    { key: 'retrieval', label: '检索侧' },
    { key: 'generation', label: '生成侧' },
  ]

  return (
    <div className="overflow-x-auto rounded-lg border border-line">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-line bg-muted/40 text-left text-muted">
            <th className="px-3 py-2 font-medium">指标</th>
            {columns.map((col, i) => (
              <th key={col.runId} className="px-3 py-2 font-medium">
                <span className="block max-w-36 truncate">{col.name}</span>
                {i === 0 && <span className="text-xs font-normal">基线</span>}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {groups.map((group) => {
            const groupRows = rows.filter((r) => r.def.group === group.key)
            if (groupRows.length === 0) return null
            return (
              <Fragment key={group.key}>
                <tr className="border-b border-line/60 bg-base/60">
                  <td colSpan={columns.length + 1} className="px-3 py-1.5 text-xs font-medium text-muted">
                    {group.label}
                  </td>
                </tr>
                {groupRows.map((row) => (
                  <tr key={row.def.key} className="border-b border-line/60 last:border-0 hover:bg-muted/30">
                    <td className="px-3 py-2">{row.def.label}</td>
                    {row.cells.map((cell, i) => (
                      <td key={i} className="px-3 py-2 tabular-nums">
                        {formatMetric(cell.value)}
                        {cell.diff != null && cell.diff !== 0 && (
                          <span
                            className={cn(
                              'ml-1.5 text-xs',
                              cell.diff > 0 ? 'text-success-700' : 'text-error-700',
                            )}
                          >
                            {formatDiff(cell.diff)}
                          </span>
                        )}
                      </td>
                    ))}
                  </tr>
                ))}
              </Fragment>
            )
          })}
          {/* 条目/失败数行 */}
          <tr className="border-t border-line hover:bg-muted/30">
            <td className="px-3 py-2">条目 / 失败数</td>
            {columns.map((col) => (
              <td key={col.runId} className="px-3 py-2 tabular-nums">
                {col.entry ? `${col.entry.metrics.total_items} / ${col.entry.metrics.error_items}` : '—'}
              </td>
            ))}
          </tr>
        </tbody>
      </table>
    </div>
  )
}
