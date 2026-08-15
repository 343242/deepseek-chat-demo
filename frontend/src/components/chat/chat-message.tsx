import { memo } from 'react'
import { Bot, Copy, Check, RotateCcw, AlertCircle, AlertTriangle, Brain, BrainCircuit, BookMarked, ChevronRight } from 'lucide-react'
import { MarkdownViewer } from './markdown-viewer'
import { ReferenceCard } from './reference-card'
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

/** memo：流式期间 store 每帧更新 messages 数组，但 stream-reducer.patch 保持未变更消息
 *  的对象引用——memo 后历史消息不再随每个 token 重渲染（仅 MarkdownViewer 内部受益不够，
 *  外层 JSX + hooks 也省掉）。回调 props 均来自 zustand store，引用稳定。 */
export const ChatMessage = memo(function ChatMessage({ message, onOpenRefs, onOpenAgent, onRegenerate }: ChatMessageProps) {
  const { initials } = useAuth()
  const { copied, copy } = useCopy()
  const isUser = message.role === 'USER'
  const streaming = message.status === 'IN_PROGRESS'
  // const 局部收窄可穿透闭包，替代回调里的非空断言（FE-019）
  const refs = message.references
  const agentMeta = message.agentMetadata
  const isAgent = !!agentMeta

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

  // 助手消息：左对齐，全宽排版（无名字头部——模型信息由底部元信息行承载，避免重复）
  return (
    <div className="group flex flex-col gap-1.5 px-4">
      <div className="flex items-start gap-2.5">
        <Avatar className="size-7 shrink-0 bg-primary-600">
          <AvatarFallback className="bg-primary-600 text-inv">
            <Bot className="size-4" />
          </AvatarFallback>
        </Avatar>
        <div className="min-w-0 flex-1 pt-0.5">
          {/* 错误态 */}
          {message.status === 'ERROR' ? (
            <div className="rounded-lg bg-error-50 px-3 py-2.5 text-sm text-error-700">
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
            <div className="flex items-center gap-1.5 py-1 text-subtle" aria-label="正在思考">
              <span className="waiting-dot size-1.5 rounded-full bg-primary-600 [animation-delay:-0.24s]" />
              <span className="waiting-dot size-1.5 rounded-full bg-primary-600 [animation-delay:-0.12s]" />
              <span className="waiting-dot size-1.5 rounded-full bg-primary-600" />
            </div>
          )}

          {/* 推理过程（思考模型）：折叠面板，展开内容淡入 */}
          {message.reasoning && (
            <details className="group mt-2 rounded-lg bg-base/60">
              <summary className="flex cursor-pointer select-none list-none items-center gap-1.5 px-3 py-2 text-sm font-medium text-muted transition-colors hover:text-fg [&::-webkit-details-marker]:hidden">
                <ChevronRight className="size-3.5 shrink-0 text-subtle transition-transform duration-200 group-open:rotate-90" />
                <Brain className="size-3.5 shrink-0 text-subtle" />
                思考过程
              </summary>
              <div className="animate-in fade-in duration-200 whitespace-pre-wrap px-3 pb-3 text-sm leading-relaxed text-muted">
                {message.reasoning}
              </div>
            </details>
          )}

          {/* 降级提示（DS §11.3.6）：消息底部小字 */}
          {message.fallback && (
            <div className="mt-1.5 flex items-center gap-1.5 text-xs text-warning-700">
              <AlertTriangle className="size-3.5 shrink-0" />
              <span>
                已从 {message.fallback.requestedModel} 降级到 {message.fallback.fallback}（备用链）
              </span>
            </div>
          )}

          {/* 引用来源（RAG，DS §11.3.4）：头部行 + 前 3 张卡，其余进侧栏 */}
          {refs && refs.length > 0 && (
            <div className="mt-3">
              <div className="mb-1.5 flex items-center gap-1.5 text-xs text-subtle">
                <BookMarked className="size-3.5" />
                <span className="font-medium">引用来源</span>
                <span className="tabular-nums">{refs.length}</span>
                <button
                  onClick={() => onOpenRefs?.(refs)}
                  className="ml-auto flex items-center gap-0.5 text-link transition-colors hover:underline"
                  type="button"
                >
                  查看全部 <ChevronRight className="size-3" />
                </button>
              </div>
              <div className="space-y-1.5">
                {refs.slice(0, 3).map((r) => (
                  <ReferenceCard key={r.refNumber} reference={r} />
                ))}
              </div>
            </div>
          )}

          {/* Agent 元数据条（DS §11.3.5）：意图胶囊 + 统计 + 查看推理入口 */}
          {isAgent && (
            <button
              onClick={() => onOpenAgent?.(agentMeta)}
              className="mt-2 flex max-w-full items-center gap-1.5 rounded-lg bg-primary-50 px-2.5 py-1.5 text-xs text-primary-700 transition-colors hover:bg-primary-100"
              type="button"
            >
              <BrainCircuit className="size-3.5 shrink-0" />
              <span className="shrink-0 font-medium">Agent</span>
              <span className="shrink-0 rounded-full bg-primary-100 px-1.5 py-px font-medium">{agentIntentLabel(agentMeta.intent)}</span>
              <span className="min-w-0 truncate">
                {agentMeta.confidence != null && <span>置信度 {agentMeta.confidence.toFixed(2)}</span>}
                {agentMeta.confidence != null && agentMeta.retrievalRounds != null && <span> · </span>}
                {agentMeta.retrievalRounds != null && <span>检索 {agentMeta.retrievalRounds} 轮</span>}
              </span>
              <span className="ml-auto flex shrink-0 items-center gap-0.5 text-primary-600">
                查看推理 <ChevronRight className="size-3" />
              </span>
            </button>
          )}

          {/* hover 操作栏 + 元信息行（仅完成态） */}
          {!streaming && message.status !== 'ERROR' && (
            <div className="mt-1.5 flex items-center gap-2">
              <div className="flex items-center gap-0.5 opacity-0 transition-opacity duration-150 group-hover:opacity-100 focus-within:opacity-100">
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
})
