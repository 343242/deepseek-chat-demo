import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowDown, Loader2 } from 'lucide-react'
import { ChatMessage } from './chat-message'
import { EmptyState } from '@/components/common/empty-state'
import { useInfiniteScroll } from '@/hooks/use-infinite-scroll'
import { useChatStore } from '@/stores/chat-store'
import { fetchMessages } from '@/api/conversations'
import { flattenMessages } from '@/lib/chat/flatten-messages'
import { time } from '@/lib/format'
import { MessageSquare } from 'lucide-react'

export function MessageList() {
  const conversationId = useChatStore((s) => s.conversationId)
  const messages = useChatStore((s) => s.messages)
  const streaming = useChatStore((s) => s.streaming)
  const prepend = useChatStore((s) => s.prepend)
  const openRefs = useChatStore((s) => s.openRefs)
  const openAgent = useChatStore((s) => s.openAgent)

  const scrollRef = useRef<HTMLDivElement>(null)
  const [autoScroll, setAutoScroll] = useState(true)
  const [showJump, setShowJump] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [hasMore, setHasMore] = useState(true)

  // 流式/新消息自动滚底（除非用户手动上滚）
  useEffect(() => {
    if (autoScroll) {
      const el = scrollRef.current
      if (el) el.scrollTop = el.scrollHeight
    }
  }, [messages, autoScroll])

  function onScroll() {
    const el = scrollRef.current
    if (!el) return
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
    setAutoScroll(atBottom)
    setShowJump(!atBottom && streaming)
  }

  // 滚到顶部加载更早历史（游标分页）—— onLoadMore 依赖 messages，随新页到达重新订阅
  const loadMore = useCallback(async () => {
    if (!conversationId) return
    setLoadingMore(true)
    try {
      const earliest = messages.find((m) => m.parentId == null)?.id
      const page = await fetchMessages(conversationId, 20, earliest)
      if (page.items.length) prepend(flattenMessages(page.items))
      setHasMore(page.hasMore)
    } finally {
      setLoadingMore(false)
    }
  }, [conversationId, messages, prepend])
  const topRef = useInfiniteScroll({ onLoadMore: loadMore, hasMore, loading: loadingMore })

  if (messages.length === 0) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <EmptyState
          icon={<MessageSquare />}
          title="开始新的对话"
          description="输入消息即可与 AI 交流"
        />
      </div>
    )
  }

  // 时间分组：相邻消息间隔 ≥10min 或跨日插入分隔
  const grouped: React.ReactNode[] = []
  let lastAt: string | null = null
  messages.forEach((m, i) => {
    if (lastAt && (time.gapMinutes(lastAt, m.createdAt) || !time.isToday(lastAt) !== !time.isToday(m.createdAt))) {
      grouped.push(<TimeSeparator key={`sep-${i}`} label={time.short(m.createdAt)} />)
    } else if (!lastAt) {
      grouped.push(<TimeSeparator key={`sep-first`} label={time.short(m.createdAt)} />)
    }
    grouped.push(
      <ChatMessage key={m.id} message={m} onOpenRefs={openRefs} onOpenAgent={openAgent} />,
    )
    lastAt = m.createdAt
  })

  return (
    <div className="relative flex min-h-0 flex-1 flex-col">
      <div ref={scrollRef} onScroll={onScroll} className="flex-1 overflow-y-auto py-4">
        <div className="mx-auto w-full max-w-[var(--layout-chat-input-w)]">
          {conversationId && hasMore && (
            <div ref={topRef} className="flex justify-center py-2 text-xs text-subtle">
              {loadingMore ? <Loader2 className="size-4 animate-spin" /> : '↑ 加载更早消息'}
            </div>
          )}
          {grouped}
          {streaming && (
            <div className="mx-auto mt-2 w-full max-w-[var(--layout-chat-input-w)] px-4 text-xs text-subtle">
              <span className="flex items-center gap-1.5">
                <Loader2 className="size-3 animate-spin" /> 生成中…
              </span>
            </div>
          )}
        </div>
      </div>

      {showJump && (
        <button
          onClick={() => {
            setAutoScroll(true)
            const el = scrollRef.current
            if (el) el.scrollTop = el.scrollHeight
          }}
          className="absolute bottom-4 left-1/2 -translate-x-1/2 rounded-full border border-line bg-surface px-3 py-1.5 text-xs text-fg shadow-md transition-colors hover:bg-hover"
        >
          <ArrowDown className="mr-1 inline size-3" /> 回到最新
        </button>
      )}
    </div>
  )
}

function TimeSeparator({ label }: { label: string }) {
  return (
    <div className="my-3 flex items-center justify-center">
      <span className="rounded-full bg-base px-2.5 py-0.5 text-xs text-subtle">{label}</span>
    </div>
  )
}
