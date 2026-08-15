import { Bot, Copy, Check, RotateCcw, AlertCircle, BrainCircuit, ChevronRight } from 'lucide-react'
import { MarkdownViewer } from './markdown-viewer'
import { ReferenceCard } from './reference-card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { useAuth } from '@/hooks/use-auth'
import { useCopy } from '@/hooks/use-copy'
import { agentIntentLabel } from '@/lib/status-meta'
import { time, formatDuration } from '@/lib/format'
import { cn } from '@/lib/utils'
import type { RenderMessage } from '@/stores/chat-store'
import type { AgentMetadata } from '@/types/chat'
import type { Reference } from '@/types/document'

interface ChatMessageProps {
  message: RenderMessage
  onOpenRefs?: (refs: Reference[]) => void
  onOpenAgent?: (meta: AgentMetadata) => void
  onRegenerate?: () => void
}

export function ChatMessage({ message, onOpenRefs, onOpenAgent, onRegenerate }: ChatMessageProps) {
  const { initials } = useAuth()
  const { copied, copy } = useCopy()
  const isUser = message.role === 'USER'
  const streaming = message.status === 'IN_PROGRESS'
  const isAgent = !!message.agentMetadata

  // 用户消息：右对齐，轻气泡
  if (isUser) {
    return (
      <div className="group flex flex-col items-end gap-1.5 px-4">
        <div className="flex items-end justify-end gap-2.5">
          <div className="max-w-[70%] whitespace-pre-wrap break-words rounded-lg bg-base px-3 py-2 text-md text-fg">
            {message.content}
          </div>
          <Avatar className="size-7">
            <AvatarFallback className="text-xs">{initials}</AvatarFallback>
          </Avatar>
        </div>
        <div className="meta-row pr-9">{time.short(message.createdAt)}</div>
      </div>
    )
  }

  // 助手消息：左对齐，全宽排版
  return (
    <div className="group flex flex-col gap-1.5 px-4">
      <div className="flex items-start gap-2.5">
        <Avatar className="size-7 shrink-0 bg-primary-600">
          <AvatarFallback className="bg-primary-600 text-inv">
            <Bot className="size-4" />
          </AvatarFallback>
        </Avatar>
        <div className="min-w-0 flex-1 pt-0.5">
          <div className="mb-1 flex items-center gap-2">
            <span className="text-sm font-medium text-fg">{message.modelId || '助手'}</span>
            {message.fallback && (
              <Badge variant="warning" className="gap-1">
                <AlertCircle className="size-3" />
                已从 {message.fallback.requestedModel} 降级到 {message.fallback.fallback}
              </Badge>
            )}
          </div>

          {/* 错误态 */}
          {message.status === 'ERROR' ? (
            <div className="rounded-md bg-error-50 px-3 py-2 text-sm text-error-700">
              <div className="mb-1 flex items-center gap-1.5 font-medium">
                <AlertCircle className="size-4" /> 生成失败
              </div>
              <p className="text-error-600">{message.content || '请重试'}</p>
            </div>
          ) : message.content || streaming ? (
            <div className={cn(streaming && 'typing-cursor')}>
              <MarkdownViewer content={message.content} />
            </div>
          ) : (
            <div className="flex items-center gap-1.5 text-sm text-subtle">
              <span className="size-1.5 animate-bounce rounded-full bg-primary-600 [animation-delay:-0.2s]" />
              <span className="size-1.5 animate-bounce rounded-full bg-primary-600 [animation-delay:-0.1s]" />
              <span className="size-1.5 animate-bounce rounded-full bg-primary-600" />
            </div>
          )}

          {/* 推理过程（思考模型） */}
          {message.reasoning && (
            <details className="mt-2 rounded-md bg-base/60 px-3 py-2 text-sm text-muted">
              <summary className="cursor-pointer font-medium text-fg">思考过程</summary>
              <div className="mt-1 whitespace-pre-wrap">{message.reasoning}</div>
            </details>
          )}

          {/* 引用来源（RAG） */}
          {message.references && message.references.length > 0 && (
            <div className="mt-3">
              <button
                onClick={() => onOpenRefs?.(message.references!)}
                className="mb-1.5 text-xs font-medium text-subtle hover:text-fg"
                type="button"
              >
                ── 引用来源（{message.references.length}）──
              </button>
              <div className="space-y-1.5">
                {message.references.slice(0, 3).map((r) => (
                  <ReferenceCard key={r.refNumber} reference={r} />
                ))}
              </div>
            </div>
          )}

          {/* Agent 元数据条 */}
          {isAgent && (
            <button
              onClick={() => onOpenAgent?.(message.agentMetadata!)}
              className="mt-2 flex items-center gap-1.5 rounded-md bg-primary-50 px-2 py-1 text-xs text-primary-700 transition-colors hover:bg-primary-100"
              type="button"
            >
              <BrainCircuit className="size-3.5" />
              <span>Agent · 意图: {agentIntentLabel(message.agentMetadata?.intent)}</span>
              {message.agentMetadata?.confidence != null && (
                <span>· 置信度 {message.agentMetadata.confidence.toFixed(2)}</span>
              )}
              {message.agentMetadata?.retrievalRounds != null && (
                <span>· 检索 {message.agentMetadata.retrievalRounds} 轮</span>
              )}
              <ChevronRight className="ml-1 size-3" />
              <span className="text-primary-600">查看推理</span>
            </button>
          )}

          {/* hover 操作栏 + 元信息行（仅完成态） */}
          {!streaming && message.status !== 'ERROR' && (
            <div className="mt-1.5 flex items-center gap-2">
              <div className="flex items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100">
                <Button variant="ghost" size="icon-sm" onClick={() => void copy(message.content)} aria-label="复制">
                  {copied ? <Check className="size-3.5 text-success-600" /> : <Copy className="size-3.5" />}
                </Button>
                {onRegenerate && (
                  <Button variant="ghost" size="icon-sm" onClick={onRegenerate} aria-label="重新生成">
                    <RotateCcw className="size-3.5" />
                  </Button>
                )}
              </div>
              <span className="meta-row">
                {message.modelId && <span>{message.modelId}</span>}
                {!message.pending && message.tokenUsage != null && <span> · {message.tokenUsage} token</span>}
                {!message.pending && message.durationMs != null && <span> · {formatDuration(message.durationMs)}</span>}
                <span> · {time.short(message.createdAt)}</span>
              </span>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
