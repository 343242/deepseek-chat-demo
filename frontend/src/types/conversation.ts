import type { CursorPage } from './api'
import type { MessageVO } from './chat'

/** 会话状态（ConversationStatus，DS §4.4.2） */
export type ConversationStatus = 'ACTIVE' | 'ARCHIVED' | 'DELETED'

/** 标题来源（TitleSource，DS §4.4.8） */
export type TitleSource = 'SYSTEM' | 'USER'

/** ConversationSummary（conversation/dto/ConversationSummary.java） */
export interface ConversationSummary {
  id: number
  conversationId: string
  title: string
  titleSource: TitleSource
  modelId?: string | null
  pinned: boolean
  status: ConversationStatus
  messageCount: number
  lastMessageAt: string
  createdAt: string
}

/** 会话详情（含首批消息） */
export interface ConversationDetail {
  conversationId: string
  title: string
  titleSource: TitleSource
  modelId?: string | null
  status: ConversationStatus
  messages: MessageVO[]
}

export type { CursorPage }
