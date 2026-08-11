import { useMemo, useState } from 'react'
import { Search, MoreHorizontal, Eye, History, Upload, RefreshCw, Trash2, FileStack } from 'lucide-react'
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
import { useDocuments, useDeleteDocument, useRetryDocument } from '@/api/documents'
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
  const { data, isLoading, fetchNextPage, hasNextPage, isFetchingNextPage } = useDocuments({ teamId, size: 20 })
  const del = useDeleteDocument()
  const retry = useRetryDocument()

  const all = useMemo(() => data?.pages.flatMap((p) => p.content) ?? [], [data])
  // 🔶 客户端过滤已加载列表（KB-1：后端无 keyword/status/mimeType 参数）
  const filtered = useMemo(() => {
    if (!keyword.trim()) return all
    const kw = keyword.toLowerCase()
    return all.filter((d) => d.fileName?.toLowerCase().includes(kw))
  }, [all, keyword])

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
        <span className="ml-auto text-sm text-subtle">共 {data?.pages[0]?.total ?? 0} 个文档</span>
      </div>

      {/* 表格 */}
      <div className="overflow-hidden rounded-lg border border-line bg-surface">
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr className="border-b border-line-subtle bg-base text-left text-xs font-medium text-muted">
              <th className="w-10 py-2.5 pl-4"><Checkbox aria-label="全选" /></th>
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
                  <td colSpan={8} className="py-3 pl-4"><Skeleton className="h-5 w-full" /></td>
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
                      <Checkbox aria-label={`选择 ${doc.fileName}`} />
                    </td>
                    <td className="py-3">
                      <div className="flex items-center gap-2">
                        <FileTypeIcon fileName={doc.fileName} className="size-4 shrink-0" />
                        <span className="truncate font-medium text-fg">{doc.fileName}</span>
                      </div>
                    </td>
                    <td className="py-3 text-right text-muted">{formatFileSize(doc.fileSize)}</td>
                    <td className="py-3"><StatusBadge meta={meta} /></td>
                    <td className="py-3 text-right text-muted">{doc.chunkCount ?? '-'}</td>
                    <td className="py-3 text-center text-muted">{doc.version ? `v${doc.version}` : '-'}</td>
                    <td className="py-3 text-right text-subtle">{time.short(doc.createTime)}</td>
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
          <Button variant="secondary" size="sm" onClick={() => fetchNextPage()} loading={isFetchingNextPage}>
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
          if (toDelete) await del.mutateAsync(toDelete.id)
        }}
      />
    </div>
  )
}
