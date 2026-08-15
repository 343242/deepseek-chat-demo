import { useState } from 'react'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Loader2, Download } from 'lucide-react'
import { FileTypeIcon } from './file-icon'
import { documentPreviewUrl, downloadDocument } from '@/api/documents'
import { formatFileSize } from '@/lib/format'
import type { DocumentDTO } from '@/types/document'

/**
 * 原文件预览弹窗（KB-2，design §4.3 安全契约）
 *
 * - preview URL 只能作为 iframe src 以浏览器导航打开：鉴权走 HttpOnly access_token Cookie，
 *   不走 apiFetch（iframe 无法携带 Authorization 头）
 * - iframe sandbox 不带 allow-same-origin（空值 = 全限制：无脚本、无同源、无表单、无弹窗）；
 *   服务端另有 Jsoup 净化 + 响应 CSP 兜底，构成两道隔离边界
 * - 禁止 fetch 后 innerHTML 注入主应用 DOM、禁止 srcdoc / blob URL（会使服务端 CSP 失效）
 * - PDF 走浏览器内置阅读器（inline 透传，支持 Range）；TXT/MD/HTML 为服务端渲染的
 *   UTF-8 文本 / 净化后 HTML；预览失败时 iframe 内呈现服务端响应（如业务错误 JSON）
 */
export function DocumentPreviewDialog({
  doc,
  open,
  onOpenChange,
}: {
  doc: DocumentDTO | null
  open: boolean
  onOpenChange: (o: boolean) => void
}) {
  if (!doc) return null

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex h-[85vh] max-w-5xl flex-col gap-0 overflow-hidden p-0">
        <DialogHeader className="flex-row items-center gap-2 border-b border-line-subtle p-4 pr-12">
          <FileTypeIcon fileName={doc.fileName} className="size-5 shrink-0" />
          <DialogTitle className="min-w-0 flex-1 truncate text-base">{doc.fileName}</DialogTitle>
          <span className="shrink-0 text-xs text-subtle">{formatFileSize(doc.fileSize)}</span>
          <Button variant="secondary" size="sm" onClick={() => downloadDocument(doc.id)}>
            <Download className="size-3.5" /> 下载
          </Button>
        </DialogHeader>
        <PreviewBody key={doc.id} doc={doc} />
      </DialogContent>
    </Dialog>
  )
}

/** 加载态随 Dialog 开关/切换文档自然重置（Radix 关闭时卸载内容 + key 换文档重挂载），
 *  不用 effect 回写 state（react-hooks/set-state-in-effect）。 */
function PreviewBody({ doc }: { doc: DocumentDTO }) {
  const [loaded, setLoaded] = useState(false)

  return (
    <div className="relative min-h-0 flex-1">
      {!loaded && (
        <div className="absolute inset-0 z-10 flex items-center justify-center gap-2 bg-surface text-sm text-subtle">
          <Loader2 className="size-4 animate-spin" /> 正在加载预览…
        </div>
      )}
      <iframe
        src={documentPreviewUrl(doc.id)}
        sandbox=""
        title={`${doc.fileName} 预览`}
        className="size-full border-0 bg-white"
        onLoad={() => setLoaded(true)}
      />
    </div>
  )
}
