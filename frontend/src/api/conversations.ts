import { useQuery, useMutation, useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-fetch'
import type { PagedResult } from '@/types/api'
import type { ConversationSummary, ConversationDetail, ConversationStatus } from '@/types/conversation'
import type { CursorPage } from '@/types/api'
import type { MessageVO } from '@/types/chat'

export const convKeys = {
  list: ['conversations'] as const,
  detail: (id: string) => ['conversations', id] as const,
  messages: (id: string) => ['conversations', id, 'messages'] as const,
}

interface ListParams {
  page?: number
  size?: number
  status?: ConversationStatus
}

/** GET /api/conversations —— 会话列表（status=ACTIVE 默认） */
export function useConversations(params: ListParams = { status: 'ACTIVE', size: 50 }) {
  return useInfiniteQuery({
    queryKey: [...convKeys.list, params],
    queryFn: ({ pageParam }) =>
      api.get<PagedResult<ConversationSummary>>('/conversations', {
        params: { page: pageParam ?? 1, size: params.size ?? 50, status: params.status ?? 'ACTIVE' },
      }),
    initialPageParam: 1,
    getNextPageParam: (last) => (last.page < last.totalPages ? last.page + 1 : undefined),
  })
}

/** GET /api/conversations/{id} —— 会话详情（含首批消息） */
export function useConversationDetail(conversationId: string | null) {
  return useQuery({
    queryKey: convKeys.detail(conversationId ?? ''),
    queryFn: () => api.get<ConversationDetail>(`/conversations/${conversationId}`),
    enabled: !!conversationId,
  })
}

/**
 * GET /api/conversations/{id}/messages —— 消息历史（游标分页）
 * before=本页最早根消息 id；返回 items（时间升序）、nextCursor、hasMore
 */
export function fetchMessages(conversationId: string, limit = 20, before?: number) {
  return api.get<CursorPage<MessageVO>>(`/conversations/${conversationId}/messages`, {
    params: { limit, before },
  })
}

export function useUpdateConversation() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (args: { conversationId: string; title?: string; pinned?: boolean; status?: ConversationStatus }) => {
      const { conversationId: id, ...patch } = args
      return api.post<void>(`/conversations/${id}/update`, patch)
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: convKeys.list }),
  })
}

export function useDeleteConversation() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (conversationId: string) =>
      api.post<void>(`/conversations/${conversationId}/delete`),
    onSuccess: () => qc.invalidateQueries({ queryKey: convKeys.list }),
  })
}
