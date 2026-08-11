import { useMemo, useRef, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { Search, Plus, Pin, MoreHorizontal, Pencil, Archive, Trash2, MessageSquare } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { EmptyState } from '@/components/common/empty-state'
import { ConfirmDialog } from '@/components/common/confirm-dialog'
import { useConversations, useUpdateConversation, useDeleteConversation, convKeys } from '@/api/conversations'
import { time } from '@/lib/format'
import { cn } from '@/lib/utils'
import { useChatStore } from '@/stores/chat-store'
import type { ConversationSummary } from '@/types/conversation'

/** 分组键 */
type GroupKey = 'pinned' | 'today' | 'yesterday' | 'week' | 'earlier'
const GROUP_LABEL: Record<GroupKey, string> = {
  pinned: '置顶',
  today: '今天',
  yesterday: '昨天',
  week: '7 天内',
  earlier: '更早',
}

function groupOf(c: ConversationSummary): GroupKey {
  if (c.pinned) return 'pinned'
  if (time.isToday(c.lastMessageAt)) return 'today'
  if (time.isYesterday(c.lastMessageAt)) return 'yesterday'
  if (time.isThisWeek(c.lastMessageAt)) return 'week'
  return 'earlier'
}

export function ConversationList() {
  const navigate = useNavigate()
  const { conversationId } = useParams()
  const reset = useChatStore((s) => s.reset)
  const [keyword, setKeyword] = useState('')
  const [renaming, setRenaming] = useState<string | null>(null)
  const [renameVal, setRenameVal] = useState('')
  const [toDelete, setToDelete] = useState<ConversationSummary | null>(null)
  const update = useUpdateConversation()
  const del = useDeleteConversation()
  const { data, fetchNextPage, hasNextPage, isFetchingNextPage } = useConversations()

  const allConvs = useMemo(() => data?.pages.flatMap((p) => p.content) ?? [], [data])

  // 🔶 客户端过滤已加载列表（CHAT-1：后端无 keyword 参数）
  const filtered = useMemo(() => {
    if (!keyword.trim()) return allConvs
    const kw = keyword.toLowerCase()
    return allConvs.filter((c) => c.title?.toLowerCase().includes(kw))
  }, [allConvs, keyword])

  const grouped = useMemo(() => {
    const map = new Map<GroupKey, ConversationSummary[]>()
    for (const c of filtered) {
      const k = groupOf(c)
      if (!map.has(k)) map.set(k, [])
      map.get(k)!.push(c)
    }
    const order: GroupKey[] = ['pinned', 'today', 'yesterday', 'week', 'earlier']
    return order.map((k) => ({ key: k, items: (map.get(k) ?? []).sort((a, b) => b.lastMessageAt.localeCompare(a.lastMessageAt)) })).filter((g) => g.items.length)
  }, [filtered])

  // 无限滚动
  const sentinelRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = sentinelRef.current
    if (!el || !hasNextPage) return
    const ob = new IntersectionObserver((entries) => {
      if (entries[0].isIntersecting && !isFetchingNextPage) void fetchNextPage()
    })
    ob.observe(el)
    return () => ob.disconnect()
  }, [hasNextPage, isFetchingNextPage, fetchNextPage])

  function openConv(c: ConversationSummary) {
    reset()
    navigate(`/app/chat/${c.conversationId}`)
  }

  function newChat() {
    reset()
    navigate('/app/chat')
  }

  function doRename(c: ConversationSummary) {
    if (!renameVal.trim()) return
    update.mutate({ conversationId: c.conversationId, title: renameVal.trim() })
    setRenaming(null)
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* 顶部：新建 + 搜索 */}
      <div className="flex items-center gap-2 p-3">
        <Button size="sm" className="flex-1" onClick={newChat}>
          <Plus className="size-4" /> 新建会话
        </Button>
      </div>
      <div className="px-3 pb-2">
        <div className="relative">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-faint" />
          <Input
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="搜索会话"
            className="h-8 pl-8 text-sm"
          />
        </div>
        {keyword && (
          <p className="mt-1 px-1 text-xs text-faint">仅已加载 {allConvs.length} 条</p>
        )}
      </div>

      {/* 列表（独立滚动） */}
      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-2">
        {grouped.length === 0 ? (
          <EmptyState icon={<MessageSquare />} title="暂无会话" description="点击上方「新建会话」开始对话" className="py-10" />
        ) : (
          grouped.map((g) => (
            <div key={g.key} className="mb-2">
              <div className="px-2 py-1 text-xs font-medium text-subtle">{GROUP_LABEL[g.key]}</div>
              {g.items.map((c) => {
                const active = c.conversationId === conversationId
                return (
                  <div
                    key={c.conversationId}
                    className={cn(
                      'group relative flex items-center rounded-md px-2.5 py-2 transition-colors cursor-pointer',
                      active ? 'bg-selected' : 'hover:bg-hover',
                    )}
                    onClick={() => (renaming === c.conversationId ? undefined : openConv(c))}
                  >
                    {active && <span className="absolute left-0 top-1/2 h-5 w-[3px] -translate-y-1/2 rounded-full bg-primary-600" />}
                    {renaming === c.conversationId ? (
                      <Input
                        autoFocus
                        value={renameVal}
                        onChange={(e) => setRenameVal(e.target.value)}
                        onBlur={() => doRename(c)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') doRename(c)
                          if (e.key === 'Escape') setRenaming(null)
                        }}
                        className="h-6 py-0 text-sm"
                        onClick={(e) => e.stopPropagation()}
                      />
                    ) : (
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-1">
                          {c.pinned && <Pin className="size-3 shrink-0 text-primary-600" />}
                          <span className={cn('truncate text-sm', active ? 'font-medium text-primary-700' : 'text-fg')}>{c.title || '新会话'}</span>
                        </div>
                        <div className="truncate text-xs text-subtle">
                          {c.messageCount} 条 · {time.short(c.lastMessageAt)}
                        </div>
                      </div>
                    )}

                    {renaming !== c.conversationId && (
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <button
                            onClick={(e) => e.stopPropagation()}
                            className="ml-1 rounded p-1 text-subtle opacity-0 transition-opacity hover:bg-base hover:text-fg group-hover:opacity-100 data-[state=open]:opacity-100"
                            aria-label="会话操作"
                          >
                            <MoreHorizontal className="size-4" />
                          </button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" onClick={(e) => e.stopPropagation()}>
                          <DropdownMenuItem onClick={() => update.mutate({ conversationId: c.conversationId, pinned: !c.pinned })}>
                            <Pin /> {c.pinned ? '取消置顶' : '置顶'}
                          </DropdownMenuItem>
                          <DropdownMenuItem onClick={() => { setRenaming(c.conversationId); setRenameVal(c.title) }}>
                            <Pencil /> 重命名
                          </DropdownMenuItem>
                          <DropdownMenuItem onClick={() => update.mutate({ conversationId: c.conversationId, status: 'ARCHIVED' })}>
                            <Archive /> 归档
                          </DropdownMenuItem>
                          <DropdownMenuSeparator />
                          <DropdownMenuItem variant="destructive" onClick={() => setToDelete(c)}>
                            <Trash2 /> 删除
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    )}
                  </div>
                )
              })}
            </div>
          ))
        )}
        {hasNextPage && <div ref={sentinelRef} className="py-2 text-center text-xs text-subtle">{isFetchingNextPage ? '加载中…' : '↑ 加载更多'}</div>}
      </div>

      <ConfirmDialog
        open={!!toDelete}
        onOpenChange={(o) => !o && setToDelete(null)}
        title="删除会话"
        description={<>会话「{toDelete?.title}」及其全部消息将被永久删除，此操作不可撤销。</>}
        confirmText="删除"
        onConfirm={async () => {
          if (toDelete) {
            await del.mutateAsync(toDelete.conversationId)
            if (toDelete.conversationId === conversationId) navigate('/app/chat')
          }
        }}
      />
    </div>
  )
}

// 保持 convKeys 引用避免 tree-shake 误删（导出供他处复用）
export { convKeys }
