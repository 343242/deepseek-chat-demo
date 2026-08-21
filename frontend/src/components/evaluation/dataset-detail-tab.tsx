import { useMemo, useState } from 'react'
import { ChevronLeft, ChevronRight, Download, FlaskConical, Play } from 'lucide-react'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/common/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge } from '@/components/common/status-badge'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { exportDataset, useDataset } from '@/api/evaluation'
import { EVALUATION } from '@/lib/constants'
import { EVALUATION_ITEM_STATUS_META } from '@/lib/status-meta'
import { time } from '@/lib/format'
import type { EvalDatasetItem, EvalItemStatus } from '@/types/evaluation'
import { ItemEditDialog } from './item-edit-dialog'

/**
 * 数据集详情 Tab（线框 09 §2.2）——状态分布 + 条目表（GET /{id} 全量返回，前端分页）。
 * 条目行点击进入审核编辑；无手工新建/删除入口（EVAL-4）。
 */
export function DatasetDetailTab({
  datasetId,
  onStartRun,
}: {
  datasetId: number
  /** 打开启动评测 Modal（由页面持有，启动后切进度视图） */
  onStartRun: () => void
}) {
  const dataset = useDataset(datasetId)
  const [page, setPage] = useState(1)
  const [editing, setEditing] = useState<EvalDatasetItem | null>(null)

  const items = useMemo(() => dataset.data?.items ?? [], [dataset.data])
  const totalPages = Math.max(1, Math.ceil(items.length / EVALUATION.itemsPageSize))
  const pageItems = useMemo(
    () => items.slice((page - 1) * EVALUATION.itemsPageSize, page * EVALUATION.itemsPageSize),
    [items, page],
  )
  const distribution = useMemo(() => {
    const counts: Record<EvalItemStatus, number> = { draft: 0, approved: 0, rejected: 0 }
    for (const it of items) counts[it.status ?? 'draft'] += 1
    return counts
  }, [items])

  async function handleExport() {
    try {
      await exportDataset(datasetId, dataset.data?.name ?? '')
      toast.success('已导出 JSON')
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  if (dataset.isLoading) {
    return (
      <div className="space-y-3">
        <Skeleton className="h-9 w-2/3" />
        <Skeleton className="h-64 w-full" />
      </div>
    )
  }
  if (dataset.isError) {
    return (
      <EmptyState
        icon={<FlaskConical />}
        title="加载失败"
        description={dataset.error.message}
        action={<Button size="sm" variant="secondary" onClick={() => void dataset.refetch()}>重试</Button>}
      />
    )
  }

  const ds = dataset.data
  if (!ds) return null

  return (
    <div className="space-y-4">
      {/* 头部：元信息 + 动作 */}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="text-base font-semibold text-fg">{ds.name ?? `数据集 ${ds.id}`}</h2>
          <p className="mt-0.5 text-sm text-muted tabular-nums">
            {ds.itemCount} 条 · v{ds.version} · LLM 生成 · {time.full(ds.createdAt)}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="secondary" onClick={() => void handleExport()}>
            <Download className="size-4" /> 导出 JSON
          </Button>
          <Button size="sm" onClick={onStartRun}>
            <Play className="size-4" /> 启动评测
          </Button>
        </div>
      </div>

      {/* 状态分布 */}
      <div className="flex items-center gap-2 text-sm text-muted">
        <span>状态分布:</span>
        {(Object.keys(distribution) as EvalItemStatus[]).map((s) => (
          <span key={s} className="inline-flex items-center gap-1">
            <StatusBadge meta={EVALUATION_ITEM_STATUS_META[s]} />
            <span className="tabular-nums">{distribution[s]}</span>
          </span>
        ))}
      </div>

      {/* 条目表（前端分页） */}
      <div className="overflow-x-auto rounded-lg border border-line">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-line bg-muted/40 text-left text-muted">
              <th className="px-3 py-2 font-medium">#</th>
              <th className="px-3 py-2 font-medium">问题</th>
              <th className="px-3 py-2 font-medium">标准答案</th>
              <th className="px-3 py-2 font-medium">相关分块</th>
              <th className="px-3 py-2 font-medium">标签</th>
              <th className="px-3 py-2 font-medium">状态</th>
            </tr>
          </thead>
          <tbody>
            {pageItems.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-3 py-6 text-center text-muted">暂无条目</td>
              </tr>
            ) : (
              pageItems.map((it) => (
                <tr
                  key={it.id ?? it.seq}
                  className="cursor-pointer border-b border-line/60 last:border-0 hover:bg-muted/30"
                  onClick={() => setEditing(it)}
                >
                  <td className="px-3 py-2 text-muted tabular-nums">{it.seq}</td>
                  <td className="max-w-56 truncate px-3 py-2">{it.question ?? '—'}</td>
                  <td className="max-w-56 truncate px-3 py-2 text-muted">
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <span className="block truncate">{it.groundTruthAnswer ?? '—'}</span>
                      </TooltipTrigger>
                      <TooltipContent className="max-w-sm whitespace-pre-wrap">{it.groundTruthAnswer}</TooltipContent>
                    </Tooltip>
                  </td>
                  <td className="px-3 py-2 text-muted tabular-nums">
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <span>{it.relevantChunkIds?.length ?? 0} 个</span>
                      </TooltipTrigger>
                      <TooltipContent className="max-w-xs">
                        <p className="mb-1 text-xs opacity-70">相关分块 ID</p>
                        <p className="font-mono text-xs">{(it.relevantChunkIds ?? []).join('\n')}</p>
                      </TooltipContent>
                    </Tooltip>
                  </td>
                  <td className="px-3 py-2">
                    <div className="flex flex-wrap gap-1">
                      {(it.tags ?? []).slice(0, 3).map((t) => (
                        <Badge key={t} variant="neutral" square>{t}</Badge>
                      ))}
                      {(it.tags?.length ?? 0) > 3 && <Badge variant="outline" square>+{it.tags!.length - 3}</Badge>}
                    </div>
                  </td>
                  <td className="px-3 py-2">
                    <StatusBadge meta={EVALUATION_ITEM_STATUS_META[it.status ?? 'draft']} />
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {totalPages > 1 && (
        <div className="flex items-center justify-between text-sm text-muted">
          <span>第 {page} / {totalPages} 页 · 共 {items.length} 条</span>
          <div className="flex items-center gap-2">
            <Button variant="secondary" size="sm" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>
              <ChevronLeft className="size-4" /> 上一页
            </Button>
            <Button variant="secondary" size="sm" disabled={page >= totalPages} onClick={() => setPage((p) => p + 1)}>
              下一页 <ChevronRight className="size-4" />
            </Button>
          </div>
        </div>
      )}

      {editing && (
        <ItemEditDialog
          key={editing.id ?? `seq-${editing.seq}`}
          datasetId={datasetId}
          item={editing}
          onOpenChange={(next) => { if (!next) setEditing(null) }}
        />
      )}
    </div>
  )
}
