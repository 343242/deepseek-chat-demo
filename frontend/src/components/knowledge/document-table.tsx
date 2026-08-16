import { useMemo, useState } from 'react'
import { Search, MoreHorizontal, Eye, History, Upload, RefreshCw, Trash2, FileStack } from 'lucide-react'
import { toast } from 'sonner'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Skeleton } from '@/components/ui/skeleton'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { StatusBadge } from '@/components/common/status-badge'
import { ConfirmDialog } from '@/components/common/confirm-dialog'
import { EmptyState } from '@/components/common/empty-state'
import { FileTypeIcon } from './file-icon'
import { useDocuments, useDeleteDocument, useDeleteDocumentsBatch, useRetryDocument } from '@/api/documents'
import { ETL_STATUS_META } from '@/lib/status-meta'
import { time, formatFileSize } from '@/lib/format'
import type { DocumentDTO, EtlStatus } from '@/types/document'

/** 文档表格（wireframe §3，扁平结构） */
export function DocumentTable({
  teamId,
  onOpenDoc,
  onNewVersion,
}: {
  teamId: number | null
  onOpenDoc: (doc: DocumentDTO) => void
  onNewVersion: (doc: DocumentDTO) => void
}) {
  const [keyword, setKeyword] = useState('')
  const [toDelete, setToDelete] = useState<DocumentDTO | null>(null)
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [batchConfirmOpen, setBatchConfirmOpen] = useState(false)
  const { data, isLoading, fetchNextPage, hasNextPage, isFetchingNextPage } = useDocuments({ teamId, size: 20 })
  const del = useDeleteDocument()
  const delBatch = useDeleteDocumentsBatch()
  const retry = useRetryDocument()

  const all = useMemo(() => data?.pages.flatMap((p) => p.content) ?? [], [data])
  // 🔶 客户端过滤已加载列表（KB-1：后端无 keyword/status/mimeType 参数）
  const filtered = useMemo(() => {
    if (!keyword.trim()) return all
    const kw = keyword.toLowerCase()
    return all.filter((d) => d.fileName?.toLowerCase().includes(kw))
  }, [all, keyword])

  // 多选：按 id 持久（不随 keyword 过滤裁剪）；全选三态只作用于当前 filtered（无限查询仅含已加载页）
  const filteredIds = useMemo(() => filtered.map((d) => d.id), [filtered])
  const allSelected = filteredIds.length > 0 && filteredIds.every((id) => selected.has(id))
  const someSelected = selected.size > 0 && !allSelected

  function toggleSelected(id: number, on: boolean) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (on) next.add(id)
      else next.delete(id)
      return next
    })
  }

  function isFailed(s: EtlStatus) {
    return s === 'FAILED' || s === 'VECTOR_FAILED'
  }

  return (
    <div className="flex flex-1 flex-col">
      {/* 工具栏 */}
      <div className="mb-3 flex items-center gap-3">
        <div className="relative w-64">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-faint" />
          <Input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="搜索文档" className="h-9 pl-8" />
        </div>
        {keyword && <span className="text-xs text-faint">仅已加载 {all.length} 条</span>}
        {selected.size > 0 && (
          <div className="flex items-center gap-2">
            <span className="text-xs text-subtle">已选 {selected.size} 项</span>
            <Button variant="destructive" size="sm" onClick={() => setBatchConfirmOpen(true)}>
              <Trash2 className="size-3.5" /> 删除
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setSelected(new Set())}>取消选择</Button>
          </div>
        )}
        <span className="ml-auto text-sm text-subtle">共 {data?.pages[0]?.total ?? 0} 个文档</span>
      </div>

      {/* 表格：容器圆角（DS §7.4），窄屏横向滚动兜底（adapt），单元格内部保持直角 */}
      <div className="overflow-x-auto rounded-lg border border-line bg-surface">
        <table className="w-full min-w-[820px] border-collapse text-sm">
          <thead>
            <tr className="border-b border-line-subtle bg-base text-left text-xs font-medium text-muted">
              <th className="w-10 py-2.5 pl-4">
                <Checkbox
                  aria-label="全选"
                  checked={allSelected ? true : someSelected ? 'indeterminate' : false}
                  onCheckedChange={(checked) =>
                    setSelected((prev) => {
                      const next = new Set(prev)
                      if (checked) filteredIds.forEach((id) => next.add(id))
                      else filteredIds.forEach((id) => next.delete(id))
                      return next
                    })
                  }
                />
              </th>
              <th className="py-2.5">文件名</th>
              <th className="py-2.5 text-right">大小</th>
              <th className="py-2.5">状态</th>
              <th className="py-2.5 text-right">分块</th>
              <th className="py-2.5 text-center">版本</th>
              <th className="py-2.5 text-right">时间</th>
              <th className="w-12 py-2.5 pr-3"></th>
            </tr>
          </thead>
          <tbody>
            {isLoading ? (
              Array.from({ length: 5 }).map((_, i) => (
                <tr key={i} className="border-b border-line-subtle">
                  <td className="py-3 pl-4"><Skeleton className="size-4 rounded-sm" /></td>
                  <td className="py-3 pr-4"><Skeleton className="h-4 w-2/5" /></td>
                  <td className="py-3"><Skeleton className="ml-auto h-4 w-14" /></td>
                  <td className="py-3"><Skeleton className="h-5 w-16 rounded-full" /></td>
                  <td className="py-3"><Skeleton className="ml-auto h-4 w-8" /></td>
                  <td className="py-3"><Skeleton className="mx-auto h-4 w-8" /></td>
                  <td className="py-3"><Skeleton className="ml-auto h-4 w-16" /></td>
                  <td className="py-3 pr-3"><Skeleton className="ml-auto size-7 rounded-md" /></td>
                </tr>
              ))
            ) : filtered.length === 0 ? (
              <tr>
                <td colSpan={8}>
                  <EmptyState icon={<FileStack />} title="暂无文档" description="上传文档即可用 RAG 检索" />
                </td>
              </tr>
            ) : (
              filtered.map((doc) => {
                const meta = ETL_STATUS_META[doc.status]
                const weak = doc.status === 'SUPERSEDED'
                return (
                  <tr
                    key={doc.id}
                    onClick={() => onOpenDoc(doc)}
                    className={`group cursor-pointer border-b border-line-subtle transition-colors hover:bg-hover ${weak ? 'opacity-60' : ''}`}
                  >
                    <td className="py-3 pl-4" onClick={(e) => e.stopPropagation()}>
                      <Checkbox
                        aria-label={`选择 ${doc.fileName}`}
                        checked={selected.has(doc.id)}
                        onCheckedChange={(checked) => toggleSelected(doc.id, checked === true)}
                      />
                    </td>
                    <td className="py-3">
                      <div className="flex items-center gap-2">
                        <FileTypeIcon fileName={doc.fileName} className="size-4 shrink-0" />
                        <span className="truncate font-medium text-fg">{doc.fileName}</span>
                      </div>
                    </td>
                    <td className="py-3 text-right tabular-nums text-muted">{formatFileSize(doc.fileSize)}</td>
                    <td className="py-3"><StatusBadge meta={meta} /></td>
                    <td className="py-3 text-right tabular-nums text-muted">{doc.chunkCount ?? '-'}</td>
                    <td className="py-3 text-center tabular-nums text-muted">{doc.version ? `v${doc.version}` : '-'}</td>
                    <td className="py-3 text-right tabular-nums text-subtle">{time.short(doc.createTime)}</td>
                    <td className="py-3 pr-3 text-right" onClick={(e) => e.stopPropagation()}>
                      <div className="flex items-center justify-end gap-1">
                        {isFailed(doc.status) && (
                          <Button variant="ghost" size="icon-sm" onClick={() => retry.mutate(doc.id)} aria-label="重试">
                            <RefreshCw className="size-3.5 text-warning-600" />
                          </Button>
                        )}
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <Button variant="ghost" size="icon-sm" aria-label="操作">
                              <MoreHorizontal className="size-4" />
                            </Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end">
                            <DropdownMenuItem onClick={() => onOpenDoc(doc)}><Eye /> 查看详情</DropdownMenuItem>
                            <DropdownMenuItem onClick={() => onOpenDoc(doc)}><History /> 版本历史</DropdownMenuItem>
                            <DropdownMenuItem onClick={() => onNewVersion(doc)}><Upload /> 上传新版本</DropdownMenuItem>
                            <DropdownMenuSeparator />
                            <DropdownMenuItem variant="destructive" onClick={() => setToDelete(doc)}>
                              <Trash2 /> 删除
                            </DropdownMenuItem>
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </div>
                    </td>
                  </tr>
                )
              })
            )}
          </tbody>
        </table>
      </div>

      {/* 分页（加载更多） */}
      {hasNextPage && (
        <div className="mt-3 flex justify-center">
          <Button variant="secondary" size="sm" onClick={() => void fetchNextPage()} loading={isFetchingNextPage}>
            加载更多
          </Button>
        </div>
      )}

      <ConfirmDialog
        open={!!toDelete}
        onOpenChange={(o) => !o && setToDelete(null)}
        title="删除文档"
        description={<>文档「{toDelete?.fileName}」及其向量数据将被永久删除，此操作不可撤销。</>}
        confirmText="删除"
        onConfirm={async () => {
          if (toDelete) {
            await del.mutateAsync(toDelete.id)
            toggleSelected(toDelete.id, false)
          }
        }}
      />

      <ConfirmDialog
        open={batchConfirmOpen}
        onOpenChange={(o) => !o && setBatchConfirmOpen(false)}
        title="批量删除文档"
        description={<>已选 {selected.size} 个文档及其向量数据将被永久删除，此操作不可撤销。</>}
        confirmText="删除"
        onConfirm={async () => {
          // 不 rethrow：ConfirmDialog 无 catch（既有实现），整批 HTTP 失败在此兜底，选中保留便于重试
          try {
            const results = await delBatch.mutateAsync([...selected])
            const deleted = results.filter((r) => r.success).map((r) => r.id)
            const failed = results.filter((r) => !r.success)
            if (deleted.length > 0) {
              setSelected((prev) => {
                const next = new Set(prev)
                deleted.forEach((id) => next.delete(id))
                return next
              })
              toast.success(`已删除 ${deleted.length} 个文档`)
            }
            if (failed.length > 0) {
              const nameOf = (id: number) => all.find((d) => d.id === id)?.fileName ?? `#${id}`
              failed.forEach((r) => toast.error(`${nameOf(r.id)} 删除失败：${r.message ?? '未知原因'}`))
            }
          } catch (e) {
            toast.error(`批量删除失败：${(e as Error).message}`)
          }
        }}
      />
    </div>
  )
}
