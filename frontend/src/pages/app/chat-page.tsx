import { useEffect } from 'react'
import { useParams } from 'react-router'
import { X } from 'lucide-react'
import { MessageList } from '@/components/chat/message-list'
import { ChatInput } from '@/components/chat/chat-input'
import { ReferenceCard } from '@/components/chat/reference-card'
import { AgentSummary } from '@/components/chat/agent-summary'
import { Skeleton } from '@/components/ui/skeleton'
import { useChatStore } from '@/stores/chat-store'

export default function ChatPage() {
  const { conversationId = null } = useParams<{ conversationId?: string }>()
  const loading = useChatStore((s) => s.loading)
  const detail = useChatStore((s) => s.detail)
  const closeDetail = useChatStore((s) => s.closeDetail)

  // 会话切换：停流 + 清消息 + 同步 id + 装载历史（store 是消息唯一归属，不经 RQ 中转）
  useEffect(() => {
    const store = useChatStore.getState()
    store.stop()
    store.setMessages([])
    store.setConversationId(conversationId)
    if (conversationId) store.loadConversation(conversationId)
  }, [conversationId])

  return (
    <div className="flex min-h-0 flex-1">
      {/* 中栏：消息流 + 输入 */}
      <div className="flex min-w-0 flex-1 flex-col">
        {loading && conversationId ? (
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
              detail.refs.length > 0 ? (
                <div className="space-y-2">
                  {detail.refs.map((r) => (
                    <ReferenceCard key={r.refNumber} reference={r} />
                  ))}
                </div>
              ) : (
                <p className="text-sm text-subtle">暂无引用</p>
              )
            ) : (
              <AgentSummary meta={detail.meta} />
            )}
          </div>
        </aside>
      )}
    </div>
  )
}
