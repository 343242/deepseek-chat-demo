import { create } from 'zustand'
import type { RenderMessage, ChatMode, AgentMetadata } from '@/types/chat'
import type { Reference } from '@/types/document'
import { streamChat, cancelChat } from '@/lib/sse'
import { toRawConversationId, newRawId } from '@/lib/conversation-id'
import { applyFrame, finalizeInProgress } from '@/lib/chat/stream-reducer'
import { nextTempId } from '@/lib/chat/temp-id'
import { useAuthStore } from './auth-store'
import { queryClient } from '@/lib/query-client'
import { convKeys } from '@/api/conversations'

// RenderMessage 定义已迁至 types/chat（供 stream-reducer 等纯函数复用，避免循环依赖）
export type { RenderMessage } from '@/types/chat'

export interface SendOptions {
  model: string
  ragEnabled?: boolean
  mode?: ChatMode
  enableThinking?: boolean
  teamId?: number | null
}

interface ChatState {
  /** 当前会话 isolated id（URL 同步） */
  conversationId: string | null
  messages: RenderMessage[]
  loading: boolean
  streaming: boolean
  error: string | null

  /** 右侧详情面板 */
  detail: { type: 'refs' | 'agent'; refs?: Reference[]; meta?: AgentMetadata } | null

  // actions
  setConversationId: (id: string | null) => void
  setMessages: (msgs: RenderMessage[]) => void
  prepend: (msgs: RenderMessage[]) => void
  reset: () => void
  send: (text: string, opts: SendOptions) => void
  stop: () => void
  openRefs: (refs: Reference[]) => void
  openAgent: (meta: AgentMetadata) => void
  closeDetail: () => void
}

export const useChatStore = create<ChatState>((set, get) => {
  let abortController: AbortController | null = null

  return {
    conversationId: null,
    messages: [],
    loading: false,
    streaming: false,
    error: null,
    detail: null,

    setConversationId: (id) => set({ conversationId: id, error: null }),
    setMessages: (msgs) => set({ messages: msgs }),
    prepend: (msgs) => set((s) => ({ messages: [...msgs, ...s.messages] })),
    reset: () => {
      abortController?.abort()
      abortController = null
      set({ conversationId: null, messages: [], streaming: false, error: null, detail: null })
    },

    // send 仅做编排（FE-006）：构造乐观消息 → streamChat → 每帧交给纯函数 applyFrame
    send: (text, opts) => {
      const trimmed = text.trim()
      if (!trimmed || !opts.model || get().streaming) return

      const user = useAuthStore.getState().user
      const isolatedId = get().conversationId
      // 新会话：客户端生成 raw，后端按 u_{userId}_{raw} 拼 isolated
      const raw = isolatedId ? toRawConversationId(isolatedId, user?.id) : newRawId()

      const now = new Date().toISOString()
      const userMsg: RenderMessage = {
        id: nextTempId(),
        parentId: null,
        role: 'USER',
        content: trimmed,
        status: 'FINISHED',
        createdAt: now,
        pending: true,
      }
      const assistantMsg: RenderMessage = {
        id: nextTempId(),
        parentId: userMsg.id,
        role: 'ASSISTANT',
        content: '',
        status: 'IN_PROGRESS',
        modelId: opts.model,
        createdAt: now,
        pending: true,
      }
      const assistantId = assistantMsg.id

      set((s) => ({ messages: [...s.messages, userMsg, assistantMsg], streaming: true, error: null }))

      abortController = streamChat(
        {
          model: opts.model,
          message: trimmed,
          conversationId: raw,
          ragEnabled: opts.ragEnabled,
          mode: opts.mode,
          enableThinking: opts.enableThinking,
          teamId: opts.teamId,
        },
        {
          onContent: (chunk) =>
            set((s) => ({
              messages: applyFrame(s.messages, { type: 'content', chunk }, assistantId),
            })),
          onReasoning: (chunk) =>
            set((s) => ({
              messages: applyFrame(s.messages, { type: 'reasoning', chunk }, assistantId),
            })),
          onReferences: (refs) =>
            set((s) => ({
              messages: applyFrame(s.messages, { type: 'references', references: refs }, assistantId),
            })),
          onAgentMetadata: (meta) =>
            set((s) => ({
              messages: applyFrame(s.messages, { type: 'agentMetadata', metadata: meta }, assistantId),
            })),
          onFallback: (fb) =>
            set((s) => ({
              messages: applyFrame(s.messages, { type: 'fallback', fallback: fb }, assistantId),
            })),
          onCanceled: (reason) =>
            set((s) => ({
              messages: applyFrame(s.messages, { type: 'canceled', reason }, assistantId),
            })),
          onError: (frame) =>
            set((s) => ({
              error: frame.message,
              messages: applyFrame(s.messages, frame, assistantId),
            })),
          onComplete: () => {
            // 正常结束 / 软取消后服务端关闭流：复位 streaming，收尾消息态
            if (get().streaming) {
              set((s) => ({
                streaming: false,
                messages: finalizeInProgress(s.messages, assistantId),
              }))
            }
            // 新会话首条消息完成 → 刷新会话列表（wireframe §2.6）
            queryClient.invalidateQueries({ queryKey: convKeys.list })
          },
        },
      )
    },

    stop: () => {
      const isolatedId = get().conversationId
      // 软取消：POST /chat/stream/cancel；叠加 AbortController 兜底断流
      if (isolatedId) {
        const user = useAuthStore.getState().user
        const raw = toRawConversationId(isolatedId, user?.id)
        void cancelChat(raw ?? isolatedId)
      }
      abortController?.abort()
      abortController = null
    },

    openRefs: (refs) => set({ detail: { type: 'refs', refs } }),
    openAgent: (meta) => set({ detail: { type: 'agent', meta } }),
    closeDetail: () => set({ detail: null }),
  }
})
