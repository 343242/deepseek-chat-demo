import { useEffect } from 'react'
import { useParams } from 'react-router'
import { X, BrainCircuit, Loader2 } from 'lucide-react'
import { MessageList } from '@/components/chat/message-list'
import { ChatInput } from '@/components/chat/chat-input'
import { ReferenceCard } from '@/components/chat/reference-card'
import { Skeleton } from '@/components/ui/skeleton'
import { useChatStore, type RenderMessage } from '@/stores/chat-store'
import { useConversationDetail } from '@/api/conversations'
import { agentIntentLabel } from '@/lib/status-meta'

export default function ChatPage() {
  const { conversationId = null } = useParams<{ conversationId?: string }>()
  const setConversationId = useChatStore((s) => s.setConversationId)
  const setMessages = useChatStore((s) => s.setMessages)
  const stop = useChatStore((s) => s.stop)
  const detail = useChatStore((s) => s.detail)
  const closeDetail = useChatStore((s) => s.closeDetail)

  // 会话切换：停流 + 清消息 + 同步 id
  useEffect(() => {
    stop()
    setMessages([])
    setConversationId(conversationId)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conversationId])

  // 加载会话详情（首批消息）
  const { data, isLoading } = useConversationDetail(conversationId)
  useEffect(() => {
    if (data?.messages) setMessages(data.messages as RenderMessage[])
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data])

  return (
    <div className="flex min-h-0 flex-1">
      {/* 中栏：消息流 + 输入 */}
      <div className="flex min-w-0 flex-1 flex-col">
        {isLoading && conversationId ? (
          <div className="flex flex-1 flex-col gap-4 p-6">
            {[0, 1, 2].map((i) => (
              <div key={i} className="space-y-2">
                <Skeleton className="h-3 w-24" />
                <Skeleton className="h-3 w-full" />
                <Skeleton className="h-3 w-4/5" />
              </div>
            ))}
          </div>
        ) : (
          <MessageList />
        )}
        <ChatInput />
      </div>

      {/* 右栏：详情（引用 / Agent 推理），默认收起 */}
      {detail && (
        <aside className="flex w-[var(--layout-detail-w)] shrink-0 flex-col border-l border-line bg-surface animate-in slide-in-from-right duration-300">
          <div className="flex items-center justify-between border-b border-line-subtle px-4 py-3">
            <span className="flex items-center gap-2 text-sm font-medium text-fg">
              {detail.type === 'refs' ? '引用来源' : 'Agent 推理过程'}
            </span>
            <button onClick={closeDetail} className="rounded p-1 text-subtle hover:bg-hover hover:text-fg" aria-label="关闭">
              <X className="size-4" />
            </button>
          </div>
          <div className="flex-1 overflow-y-auto p-3">
            {detail.type === 'refs' ? (
              detail.refs && detail.refs.length > 0 ? (
                <div className="space-y-2">
                  {detail.refs.map((r) => (
                    <ReferenceCard key={r.refNumber} ref={r} />
                  ))}
                </div>
              ) : (
                <p className="text-sm text-subtle">暂无引用</p>
              )
            ) : detail.meta ? (
              <AgentSummary meta={detail.meta} />
            ) : (
              <div className="flex items-center gap-2 text-sm text-subtle">
                <Loader2 className="size-4 animate-spin" /> Agent 推理中…
              </div>
            )}
          </div>
        </aside>
      )}
    </div>
  )
}

/** Agent 推理汇总视图（DS §11.4 当前可实现版：完整 6 事件回放待用户态端点 T3） */
function AgentSummary({ meta }: { meta: NonNullable<ReturnType<typeof useChatStore.getState>['detail']>['meta'] }) {
  return (
    <div className="space-y-3 text-sm">
      <div className="rounded-md border border-line-subtle p-3">
        <div className="flex items-center gap-1.5 font-medium text-fg">
          <BrainCircuit className="size-4 text-primary-600" /> 意图识别
        </div>
        <p className="mt-1 text-muted">
          {agentIntentLabel(meta?.intent)}
          {meta?.confidence != null && <span> · 置信度 {meta.confidence.toFixed(2)}</span>}
        </p>
      </div>
      {meta?.retrievalRounds != null && (
        <div className="rounded-md border border-line-subtle p-3">
          <div className="font-medium text-fg">检索轮数</div>
          <p className="mt-1 text-muted">{meta.retrievalRounds} 轮</p>
        </div>
      )}
      {meta?.agentDegraded && (
        <div className="rounded-md bg-warning-50 p-3 text-warning-700">
          Agent 已降级为普通多轮对话{meta.degradedTo ? `（→ ${meta.degradedTo}）` : ''}
        </div>
      )}
      <p className="text-xs text-subtle">
        完整 6 事件推理时间线需后端用户态端点（T3，预留）。
      </p>
    </div>
  )
}
