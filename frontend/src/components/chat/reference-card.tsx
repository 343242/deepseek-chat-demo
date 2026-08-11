import { useState } from 'react'
import { FileText, ChevronDown, ChevronRight } from 'lucide-react'
import type { Reference } from '@/types/document'
import { fetchChunk } from '@/api/documents'
import { sourceLabel } from '@/lib/status-meta'
import { Badge } from '@/components/ui/badge'

/** ReferenceCard 引用来源卡（DS §11.8）—— 序号 + 文件名 + 页码 + source/score + 片段预览 */
export function ReferenceCard({ ref, onOpenDoc }: { ref: Reference; onOpenDoc?: (docId: string) => void }) {
  const [expanded, setExpanded] = useState(false)
  const [fullContent, setFullContent] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const src = sourceLabel(ref.source)

  async function loadFull() {
    if (fullContent !== null) {
      setExpanded((v) => !v)
      return
    }
    setLoading(true)
    try {
      const chunk = await fetchChunk(ref.chunkId)
      setFullContent(chunk.content)
      setExpanded(true)
    } catch {
      setFullContent(ref.content ?? '（无法加载片段）')
      setExpanded(true)
    } finally {
      setLoading(false)
    }
  }

  const preview = fullContent ?? ref.content

  return (
    <div className="rounded-md border border-line-subtle bg-base/50 p-2.5 text-sm">
      <div className="flex items-start gap-2">
        <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-primary-600 text-xs font-medium text-inv">
          {ref.refNumber}
        </span>
        <div className="min-w-0 flex-1">
          <button
            className="flex items-center gap-1.5 text-left text-fg hover:text-link"
            onClick={() => onOpenDoc?.(ref.documentId)}
            type="button"
          >
            <FileText className="size-3.5 shrink-0 text-subtle" />
            <span className="truncate font-medium">{ref.fileName}</span>
            {ref.page != null && <span className="shrink-0 text-subtle">· 第{ref.page}页</span>}
          </button>
          {(src || ref.score != null) && (
            <div className="mt-0.5 flex items-center gap-2 text-xs text-subtle">
              {src && <span>来自: {src}</span>}
              {ref.score != null && <span>· 相关性 {ref.score.toFixed(2)}</span>}
            </div>
          )}
        </div>
      </div>

      {preview && (
        <div className="mt-2 pl-7">
          {!expanded ? (
            <p className="line-clamp-2 text-muted">{ref.content}</p>
          ) : (
            <p className="whitespace-pre-wrap break-words text-muted">{preview}</p>
          )}
        </div>
      )}

      <div className="mt-1.5 pl-7">
        <button
          onClick={loadFull}
          disabled={loading}
          className="inline-flex items-center gap-1 text-xs text-link hover:underline disabled:opacity-50"
          type="button"
        >
          {expanded ? <ChevronDown className="size-3" /> : <ChevronRight className="size-3" />}
          {loading ? '加载中…' : expanded ? '收起' : preview ? '展开片段' : '查看完整片段'}
        </button>
      </div>
    </div>
  )
}

export { Badge }
