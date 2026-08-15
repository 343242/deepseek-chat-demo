import { create } from 'zustand'
import type { RenderMessage, ChatMode, AgentMetadata, ChatDetail } from '@/types/chat'
import type { Reference } from '@/types/document'
import { streamChat, cancelChat } from '@/lib/sse'
import { toRawConversationId, newRawId } from '@/lib/conversation-id'
import { applyFrame, finalizeInProgress } from '@/lib/chat/stream-reducer'
import { flattenMessages } from '@/lib/chat/flatten-messages'
import { nextTempId } from '@/lib/chat/temp-id'
import { useAuthStore } from './auth-store'
import { queryClient } from '@/lib/query-client'
import { convKeys, fetchConversationDetail } from '@/api/conversations'

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
  /** 历史装载中（chat-page 骨架态） */
  loading: boolean
  streaming: boolean
  error: string | null

  /** 右侧详情面板（FE-017：判别联合，type 即载荷） */
  detail: ChatDetail | null

  /** 输入框草稿：空状态开场白一键填充；跨会话切换保留未发送内容（可恢复性） */
  draft: string
  /** 草稿聚焦信号：外部填充草稿后递增，ChatInput 订阅触发聚焦 */
  draftSeq: number

  // actions
  setConversationId: (id: string | null) => void
  setMessages: (msgs: RenderMessage[]) => void
  prepend: (msgs: RenderMessage[]) => void
  /** 装载会话历史（含首批消息，摊平为线性序列）；store 是消息唯一归属 */
  loadConversation: (id: string) => void
  reset: () => void
  send: (text: string, opts: SendOptions) => void
  stop: () => void
  openRefs: (refs: Reference[]) => void
  openAgent: (meta: AgentMetadata) => void
  closeDetail: () => void
  /** 更新草稿；focus=true 时同时请求输入框聚焦（开场白 chip 场景） */
  setDraft: (text: string, focus?: boolean) => void
}

/** 历史装载代序号：会话快速切换时丢弃过期响应 */
let loadSeq = 0

export const useChatStore = create<ChatState>((set, get) => {
  let abortController: AbortController | null = null

  return {
    conversationId: null,
    messages: [],
    loading: false,
    streaming: false,
    error: null,
    detail: null,
    draft: '',
    draftSeq: 0,

    setConversationId: (id) => set({ conversationId: id, error: null }),
    setMessages: (msgs) => set({ messages: msgs }),
    prepend: (msgs) => set((s) => ({ messages: [...msgs, ...s.messages] })),
    loadConversation: (id) => {
      const seq = ++loadSeq
      set({ loading: true })
      void fetchConversationDetail(id)
        .then((detail) => {
          if (seq !== loadSeq) return
          set({ messages: flattenMessages(detail.messages), loading: false })
        })
        .catch(() => {
          // 装载失败放行骨架：渲染空会话态（与旧行为一致），不打断停留
          if (seq !== loadSeq) return
          set({ loading: false })
        })
    },
    reset: () => {
      abortController?.abort()
      abortController = null
      set({ conversationId: null, messages: [], loading: false, streaming: false, error: null, detail: null })
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
            void queryClient.invalidateQueries({ queryKey: convKeys.list })
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
    setDraft: (text, focus = false) =>
      set((s) => ({ draft: text, ...(focus ? { draftSeq: s.draftSeq + 1 } : {}) })),
  }
})
