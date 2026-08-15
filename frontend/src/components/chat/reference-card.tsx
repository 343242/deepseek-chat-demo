import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { FileText, ChevronDown, ChevronRight } from 'lucide-react'
import type { Reference } from '@/types/document'
import { fetchChunk } from '@/api/documents'
import { sourceLabel } from '@/lib/status-meta'

/** ReferenceCard 引用来源卡（DS §11.8）—— 序号 + 文件名 + 页码 + source/score + 片段预览。
 *  FE-014：按需展开走 React Query 缓存（同一 chunk 二次展开命中缓存，不再重复请求）。
 *  prop 命名 reference 而非 ref：ref 是 React 保留 prop 名，数据属性占用会触发
 *  react-hooks/refs（React Compiler lint）且语义混淆。 */
export function ReferenceCard({ reference, onOpenDoc }: { reference: Reference; onOpenDoc?: (docId: string) => void }) {
  const [expanded, setExpanded] = useState(false)

  // 展开时才拉取全文；staleTime 内二次展开直接命中缓存
  const { data: chunk, isFetching } = useQuery({
    queryKey: ['chunks', reference.chunkId],
    queryFn: () => fetchChunk(reference.chunkId),
    enabled: expanded,
    retry: false,
    staleTime: 5 * 60_000,
  })

  const src = sourceLabel(reference.source)
  // 展开后：命中缓存用全文，否则回退预览/失败提示
  const expandedContent = chunk?.content ?? reference.content ?? '（无法加载片段）'
  const preview = expanded ? expandedContent : reference.content

  return (
    <div className="rounded-md border border-line-subtle bg-base/50 p-2.5 text-sm">
      <div className="flex items-start gap-2">
        <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-primary-600 text-xs font-medium text-inv">
          {reference.refNumber}
        </span>
        <div className="min-w-0 flex-1">
          <button
            className="flex items-center gap-1.5 text-left text-fg hover:text-link"
            onClick={() => onOpenDoc?.(reference.documentId)}
            type="button"
          >
            <FileText className="size-3.5 shrink-0 text-subtle" />
            <span className="truncate font-medium">{reference.fileName}</span>
            {reference.page != null && <span className="shrink-0 text-subtle">· 第{reference.page}页</span>}
          </button>
          {(src || reference.score != null) && (
            <div className="mt-0.5 flex items-center gap-2 text-xs text-subtle">
              {src && <span>来自: {src}</span>}
              {reference.score != null && <span>· 相关性 {reference.score.toFixed(2)}</span>}
            </div>
          )}
        </div>
      </div>

      {preview && (
        <div className="mt-2 pl-7">
          {!expanded ? (
            <p className="line-clamp-2 text-muted">{reference.content}</p>
          ) : (
            <p className="animate-in fade-in duration-200 whitespace-pre-wrap break-words text-muted">{preview}</p>
          )}
        </div>
      )}

      <div className="mt-1.5 pl-7">
        <button
          onClick={() => setExpanded((v) => !v)}
          disabled={isFetching}
          className="inline-flex items-center gap-1 text-xs text-link hover:underline disabled:opacity-50"
          type="button"
        >
          {expanded ? <ChevronDown className="size-3" /> : <ChevronRight className="size-3" />}
          {isFetching ? '加载中…' : expanded ? '收起' : preview ? '展开片段' : '查看完整片段'}
        </button>
      </div>
    </div>
  )
}
