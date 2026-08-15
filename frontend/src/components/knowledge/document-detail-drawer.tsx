import { useState } from 'react'
import {
  Sheet, SheetContent, SheetHeader, SheetTitle, SheetBody, SheetFooter,
} from '@/components/ui/sheet'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip'
import { StatusBadge } from '@/components/common/status-badge'
import { ConfirmDialog } from '@/components/common/confirm-dialog'
import { UploadButton } from './upload-button'
import { FileTypeIcon } from './file-icon'
import { DocumentPreviewDialog } from './document-preview-dialog'
import {
  useDocumentDetail, useDocumentChunks, useDocumentHistory, useDeleteDocument,
  downloadDocument,
  docKeys,
} from '@/api/documents'
import { queryClient } from '@/lib/query-client'
import { ETL_STATUS_META } from '@/lib/status-meta'
import { formatFileSize, time } from '@/lib/format'
import { cn } from '@/lib/utils'
import { Download, Eye, Trash2, AlertCircle, FileText } from 'lucide-react'

export function DocumentDetailDrawer({
  docId,
  open,
  onOpenChange,
}: {
  docId: number | null
  open: boolean
  onOpenChange: (o: boolean) => void
}) {
  const { data: doc } = useDocumentDetail(open ? docId : null)
  const { data: chunksPage } = useDocumentChunks(open && doc?.status === 'COMPLETED' ? docId : null)
  const { data: history } = useDocumentHistory(open ? docId : null)
  const del = useDeleteDocument()
  const [confirmDel, setConfirmDel] = useState(false)

  const [previewOpen, setPreviewOpen] = useState(false)

  if (!doc) {
    return (
      <Sheet open={open} onOpenChange={onOpenChange}>
        <SheetContent><SheetHeader><SheetTitle>文档详情</SheetTitle></SheetHeader></SheetContent>
      </Sheet>
    )
  }
  const meta = ETL_STATUS_META[doc.status]

  // previewable=false 的置灰原因（语义同后端 DocumentPreviewPolicy：OOXML 不可预览；文本类超预览上限）
  const previewDisabledReason = !doc.previewable
    ? doc.mimeType.startsWith('application/vnd.openxmlformats-officedocument')
      ? '该文件类型不支持在线预览，可下载原文件查看'
      : '文件超出在线预览大小限制，可下载后查看'
    : null

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent width="md">
        <SheetHeader>
          <SheetTitle className="flex items-center gap-2 pr-8">
            <FileTypeIcon fileName={doc.fileName} className="size-5" />
            <span className="truncate">{doc.fileName}</span>
          </SheetTitle>
        </SheetHeader>

        <SheetBody>
          <Tabs defaultValue="info">
            <TabsList>
              <TabsTrigger value="info">基本信息</TabsTrigger>
              <TabsTrigger value="chunks">分块内容</TabsTrigger>
              <TabsTrigger value="history">版本历史</TabsTrigger>
            </TabsList>

            <TabsContent value="info" className="space-y-3 text-sm">
              <Row label="文件名" value={doc.fileName} />
              <Row label="类型" value={doc.mimeType} />
              <Row label="大小" value={formatFileSize(doc.fileSize)} />
              <Row label="状态" value={<StatusBadge meta={meta} />} />
              <Row label="分块数" value={doc.chunkCount != null ? String(doc.chunkCount) : '-'} />
              <Row label="版本" value={doc.version ? `v${doc.version}` : '-'} />
              {doc.documentGroupId && <Row label="文档组" value={doc.documentGroupId} mono />}
              <Row label="创建时间" value={time.full(doc.createTime)} />

              {/* 原文件预览/下载（KB-2/KB-3）：预览走 sandbox iframe 弹窗，下载走同源 attachment 导航 */}
              <div className="flex gap-2 pt-1">
                <Tooltip>
                  <TooltipTrigger asChild>
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() => setPreviewOpen(true)}
                      disabled={!doc.previewable}
                    >
                      <Eye className="size-3.5" /> 预览
                    </Button>
                  </TooltipTrigger>
                  {previewDisabledReason && <TooltipContent>{previewDisabledReason}</TooltipContent>}
                </Tooltip>
                <Button variant="secondary" size="sm" onClick={() => downloadDocument(doc.id)}>
                  <Download className="size-3.5" /> 下载
                </Button>
              </div>

              {doc.errorMessage && (
                <div className="flex items-start gap-2 rounded-md bg-error-50 p-3 text-error-700">
                  <AlertCircle className="mt-0.5 size-4 shrink-0" />
                  <div>
                    <div className="font-medium">处理失败</div>
                    <p className="text-error-600">{doc.errorMessage}</p>
                  </div>
                </div>
              )}
            </TabsContent>

            <TabsContent value="chunks">
              {doc.status !== 'COMPLETED' ? (
                <p className="text-sm text-subtle">文档处理完成后可查看分块内容。</p>
              ) : chunksPage?.content?.length ? (
                <div className="space-y-2">
                  {chunksPage.content.map((c, i) => (
                    <div key={c.id} className="rounded-md border border-line-subtle p-2.5 text-sm">
                      <div className="mb-1 flex items-center gap-1.5 text-xs text-subtle">
                        <FileText className="size-3" /> 片段 {(chunksPage.page - 1) * chunksPage.size + i + 1}
                      </div>
                      <p className="whitespace-pre-wrap break-words text-muted line-clamp-4">{c.content}</p>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-sm text-subtle">暂无分块</p>
              )}
            </TabsContent>

            <TabsContent value="history">
              {history && history.length > 0 ? (
                <div className="space-y-2">
                  {history.map((h) => {
                    const m = ETL_STATUS_META[h.status]
                    const current = h.id === doc.id
                    return (
                      <div key={h.id} className={cn(
                        'flex items-center gap-3 rounded-md border p-2.5 text-sm',
                        current ? 'border-primary-600 bg-selected' : 'border-line-subtle',
                      )}>
                        <FileTypeIcon fileName={h.fileName} className="size-4" />
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-2">
                            <span className="font-medium text-fg">v{h.version}</span>
                            {current && <Badge variant="brand" className="px-1.5 py-0">当前</Badge>}
                          </div>
                          <div className="text-xs text-subtle">{time.short(h.createTime)} · {formatFileSize(h.fileSize)}</div>
                        </div>
                        <StatusBadge meta={m} />
                      </div>
                    )
                  })}
                </div>
              ) : (
                <p className="text-sm text-subtle">仅一个版本</p>
              )}
            </TabsContent>
          </Tabs>
        </SheetBody>

        <SheetFooter>
          <div className="mr-auto">
            <UploadButton teamId={doc.teamId} replaceDocumentId={doc.id} compact onDone={() => void queryClient.invalidateQueries({ queryKey: docKeys.all })} />
          </div>
          <Button variant="destructive" onClick={() => setConfirmDel(true)}>
            <Trash2 className="size-4" /> 删除
          </Button>
        </SheetFooter>
      </SheetContent>

      <DocumentPreviewDialog doc={doc} open={previewOpen} onOpenChange={setPreviewOpen} />

      <ConfirmDialog
        open={confirmDel}
        onOpenChange={setConfirmDel}
        title="删除文档"
        description={<>文档「{doc.fileName}」及其向量数据将被永久删除，此操作不可撤销。</>}
        confirmText="删除"
        onConfirm={async () => {
          await del.mutateAsync(doc.id)
          onOpenChange(false)
        }}
      />
    </Sheet>
  )
}

function Row({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div className="flex items-start justify-between gap-3">
      <span className="shrink-0 text-subtle">{label}</span>
      <span className={cn('text-right text-fg', mono && 'font-mono text-xs tabular-nums')}>{value}</span>
    </div>
  )
}
