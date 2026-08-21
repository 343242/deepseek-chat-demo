import { useEffect, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Database, Plus } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/common/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { evalKeys, useDatasets, useGenerateDataset, useGenerationJob } from '@/api/evaluation'
import { time } from '@/lib/format'
import { cn } from '@/lib/utils'
import { GenerateDatasetDialog } from './generate-dataset-dialog'

/**
 * 左栏数据集列表（线框 09 §1）——选中态 + 左 3px 指示条；生成任务轮询由本组件持有
 * （"后台生成"关闭对话框后轮询继续，design.md §2）。
 */
export function DatasetList({
  selectedId,
  onSelect,
}: {
  selectedId: number | null
  onSelect: (id: number) => void
}) {
  const qc = useQueryClient()
  const datasets = useDatasets()
  const [genOpen, setGenOpen] = useState(false)
  const [jobId, setJobId] = useState<number | null>(null)

  const generate = useGenerateDataset()
  const job = useGenerationJob(jobId)
  const jobTerminal = job.data?.status === 'completed' || job.data?.status === 'failed'

  // 终态收尾（含后台生成场景）：只做副作用（toast / 失效列表 / 选中），
  // 对话框留在终态展示由用户关闭——状态清理在事件处理器中（react-hooks/set-state-in-effect）
  useEffect(() => {
    if (jobId == null || !jobTerminal || !job.data) return
    if (job.data.status === 'completed' && job.data.datasetId != null) {
      toast.success(`数据集生成完成（${job.data.name}）`)
      onSelect(job.data.datasetId)
    } else if (job.data.status === 'failed') {
      toast.error(`数据集生成失败：${job.data.error ?? '未知错误'}`)
    }
    void qc.invalidateQueries({ queryKey: evalKeys.datasets() })
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 仅在任务终态转换时收尾
  }, [jobTerminal])

  async function handleSubmit(name: string | undefined, userId: number) {
    const res = await generate.mutateAsync({ name, userId })
    setJobId(res.jobId)
  }

  const items = datasets.data?.pages.flatMap((p) => p.content) ?? []
  const hasMore = datasets.hasNextPage

  return (
    <div className="flex w-60 shrink-0 flex-col">
      <div className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-medium text-fg">数据集</h2>
        <Button
          size="sm"
          variant="secondary"
          onClick={() => {
            // 每次打开回到表单态（清掉上一轮任务）
            setJobId(null)
            setGenOpen(true)
          }}
        >
          <Plus className="size-4" /> 生成
        </Button>
      </div>

      <div className="flex-1 space-y-2 overflow-y-auto">
        {datasets.isLoading ? (
          <>
            <Skeleton className="h-16 w-full" />
            <Skeleton className="h-16 w-full" />
            <Skeleton className="h-16 w-full" />
          </>
        ) : datasets.isError ? (
          <EmptyState
            icon={<Database />}
            title="加载失败"
            description={datasets.error.message}
            action={
              <Button size="sm" variant="secondary" onClick={() => void datasets.refetch()}>重试</Button>
            }
            className="py-8"
          />
        ) : items.length === 0 ? (
          <EmptyState
            icon={<Database />}
            title="还没有数据集"
            description="生成数据集即可开始评估"
            className="py-8"
          />
        ) : (
          <>
            {items.map((ds) => (
              <button
                key={ds.id}
                type="button"
                onClick={() => ds.id != null && onSelect(ds.id)}
                className={cn(
                  'relative w-full rounded-lg border border-line bg-surface p-3 text-left transition-colors',
                  'hover:bg-hover focus-visible:shadow-focus',
                  ds.id === selectedId && 'bg-selected',
                )}
              >
                {ds.id === selectedId && (
                  <span className="absolute left-0 top-1/2 h-8 w-[3px] -translate-y-1/2 rounded-full bg-primary-600" />
                )}
                <p className="truncate text-sm font-medium text-fg">{ds.name ?? `数据集 ${ds.id}`}</p>
                <p className="mt-1 text-xs text-muted tabular-nums">
                  {ds.itemCount} 条 · v{ds.version} · {time.short(ds.createdAt)}
                </p>
              </button>
            ))}
            {hasMore && (
              <div className="pt-1 text-center">
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={datasets.isFetchingNextPage}
                  onClick={() => void datasets.fetchNextPage()}
                >
                  {datasets.isFetchingNextPage ? '加载中…' : '加载更多'}
                </Button>
              </div>
            )}
          </>
        )}
      </div>

      <GenerateDatasetDialog
        open={genOpen}
        onClose={() => {
          setGenOpen(false)
          setJobId(null)
        }}
        onBackground={() => setGenOpen(false)}
        onSubmit={handleSubmit}
        submitting={generate.isPending}
        job={job.data ?? null}
        jobLoading={job.isLoading}
      />
    </div>
  )
}
