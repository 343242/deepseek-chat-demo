import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowDown, Loader2, MessageSquare, FileText, Search, ListChecks } from 'lucide-react'
import { ChatMessage } from './chat-message'
import { useInfiniteScroll } from '@/hooks/use-infinite-scroll'
import { useChatStore } from '@/stores/chat-store'
import { fetchMessages } from '@/api/conversations'
import { flattenMessages } from '@/lib/chat/flatten-messages'
import { time } from '@/lib/format'

/** 空状态开场白（DS §2.4 可发现性：空状态引导下一步动作；点击填入输入框并聚焦） */
const STARTERS = [
  { icon: FileText, text: '帮我总结一份 PDF 文档的核心要点' },
  { icon: Search, text: 'Agentic RAG 与普通 RAG 有什么区别？' },
  { icon: ListChecks, text: '列一份构建知识库的最佳实践清单' },
]

export function MessageList() {
  const conversationId = useChatStore((s) => s.conversationId)
  const messages = useChatStore((s) => s.messages)
  const streaming = useChatStore((s) => s.streaming)
  const prepend = useChatStore((s) => s.prepend)
  const openRefs = useChatStore((s) => s.openRefs)
  const openAgent = useChatStore((s) => s.openAgent)
  const setDraft = useChatStore((s) => s.setDraft)

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
    // 上滚浏览历史（含非流式时段）都提供"回到最新"——寻路动机，不限于生成中
    setShowJump(!atBottom)
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
  const topRef = useInfiniteScroll({ onLoadMore: () => void loadMore(), hasMore, loading: loadingMore })

  if (messages.length === 0) {
    return (
      <div className="flex min-h-0 flex-1 items-center justify-center">
        <div className="flex flex-col items-center px-6 py-12 text-center">
          <div className="mb-4 flex size-12 items-center justify-center rounded-full bg-base text-faint [&_svg]:size-6">
            <MessageSquare />
          </div>
          <h3 className="text-lg font-semibold text-fg">开始新的对话</h3>
          <p className="mt-1 max-w-xs text-sm text-muted">输入消息即可与 AI 交流，或从下面的开场白开始</p>
          <div className="mt-5 flex max-w-lg flex-wrap justify-center gap-2">
            {STARTERS.map((s) => (
              <button
                key={s.text}
                type="button"
                onClick={() => setDraft(s.text, true)}
                className="flex items-center gap-1.5 rounded-md border border-line bg-surface px-3 py-1.5 text-sm text-muted transition-colors hover:bg-hover hover:text-fg"
              >
                <s.icon className="size-3.5 text-subtle" />
                {s.text}
              </button>
            ))}
          </div>
        </div>
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
        <div className="mx-auto w-full max-w-[var(--layout-chat-input-w)] space-y-4">
          {conversationId && hasMore && (
            <div ref={topRef} className="flex justify-center py-2 text-xs text-subtle">
              {loadingMore ? <Loader2 className="size-4 animate-spin" /> : '↑ 加载更早消息'}
            </div>
          )}
          {grouped}
          {streaming && (
            <div className="animate-in fade-in duration-200 px-4 text-xs text-subtle">
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
          className="animate-in fade-in zoom-in-95 absolute bottom-4 left-1/2 flex -translate-x-1/2 items-center rounded-full border border-line bg-surface px-3 py-1.5 text-xs text-fg shadow-md transition-colors hover:bg-hover"
        >
          <ArrowDown className="mr-1 size-3" /> 回到最新
        </button>
      )}
    </div>
  )
}

function TimeSeparator({ label }: { label: string }) {
  return (
    <div className="flex items-center justify-center py-1">
      <span className="rounded-full bg-base px-2.5 py-0.5 text-xs text-subtle">{label}</span>
    </div>
  )
}
