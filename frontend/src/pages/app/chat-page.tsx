import { useEffect } from 'react'
import { useParams } from 'react-router'
import { X, Loader2 } from 'lucide-react'
import { MessageList } from '@/components/chat/message-list'
import { ChatInput } from '@/components/chat/chat-input'
import { ReferenceCard } from '@/components/chat/reference-card'
import { AgentSummary } from '@/components/chat/agent-summary'
import { Skeleton } from '@/components/ui/skeleton'
import { useChatStore } from '@/stores/chat-store'
import { useConversationDetail } from '@/api/conversations'
import { flattenMessages } from '@/lib/chat/flatten-messages'

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

  // 加载会话详情（首批消息）—— 后端返回一层子消息树，此处摊平为线性序列
  const { data, isLoading } = useConversationDetail(conversationId)
  useEffect(() => {
    if (data?.messages) setMessages(flattenMessages(data.messages))
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
                    <ReferenceCard key={r.refNumber} reference={r} />
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
