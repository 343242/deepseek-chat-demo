import type { Reference } from './document'

/** 聊天模式（ChatMode 3 值，DS §4.4.7） */
export type ChatMode = 'SIMPLE' | 'MULTI_TURN' | 'AGENT'

/** 模型能力（ModelVO.capability） */
export type ModelCapability = 'CHAT' | 'EMBEDDING' | 'RERANKING'

export interface ModelVO {
  /** 候选唯一标识（请求 /api/chat 时传此值） */
  id: string
  /** 供应商 ID（deepseek/zhipu/MiniMax…），用于分组 */
  provider: string
  /** 原始模型名 */
  model: string
  capability: ModelCapability
  available: boolean
}

/** 消息角色 */
export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM'

/** 消息状态（MessageStatus，DS §4.4.3） */
export type MessageStatus = 'IN_PROGRESS' | 'FINISHED' | 'ERROR'

/** MessageVO（conversation/dto/MessageVO.java） */
export interface MessageVO {
  id: number
  parentId: number | null
  role: MessageRole
  content: string
  status: MessageStatus
  modelId?: string | null
  thinkingEnabled?: boolean | null
  /** 单 Integer 总 token 数（无 prompt/completion 拆分） */
  tokenUsage?: number | null
  durationMs?: number | null
  createdAt: string
  /** 子消息（分支，仅一层） */
  children?: MessageVO[]
}

/**
 * 渲染用消息（MessageVO + 流式附加字段）。
 * 定义在 types 层（而非 store）以供 stream-reducer 等纯函数复用，避免与 store 的循环依赖。
 */
export interface RenderMessage extends MessageVO {
  references?: Reference[]
  agentMetadata?: AgentMetadata
  fallback?: FallbackMeta
  reasoning?: string
  /** 本地临时消息（未持久化） */
  pending?: boolean
}

/** ChatRequest（mode/ChatRequest.java） */
export interface ChatRequest {
  model: string
  message: string
  conversationId?: string | null
  ragEnabled?: boolean
  mode?: ChatMode
  enableThinking?: boolean
  teamId?: number | null
}

/** Agent 元数据（随 ChatResponse.agentMetadata 或 SSE event:agentMetadata 终端帧） */
export interface AgentMetadata {
  intent?: string
  confidence?: number
  retrievalRounds?: number
  agentDegraded?: boolean
  degradedTo?: string
}

/** 跨模型降级信号（FallbackMeta） */
export interface FallbackMeta {
  requestedModel: string
  fallback: string
}

/** SSE 流式取消原因 */
export type CancelReason = 'USER_ABORT' | 'NAVIGATE_AWAY' | 'SESSION_SWITCH'

/* ============ SSE 七类帧（DS §11.3 SSE 帧结构） ============ */

/** 内容帧（无 event 名）—— 模型输出文本片段 */
export interface SseContentFrame {
  type: 'content'
  chunk: string
}
/** 推理帧 event:reasoning —— 思考模型过程 */
export interface SseReasoningFrame {
  type: 'reasoning'
  chunk: string
}
/** 引用终端帧 event:references */
export interface SseReferencesFrame {
  type: 'references'
  references: Reference[]
}
/** Agent 元数据终端帧 event:agentMetadata */
export interface SseAgentMetadataFrame {
  type: 'agentMetadata'
  metadata: AgentMetadata
}
/** 降级终端帧 event:fallback */
export interface SseFallbackFrame {
  type: 'fallback'
  fallback: FallbackMeta
}
/** 软取消终端帧 event:canceled */
export interface SseCanceledFrame {
  type: 'canceled'
  reason: CancelReason
}
/** 流式失败 event:error */
export interface SseErrorFrame {
  type: 'error'
  error: string
  message: string
  attempted?: string
}

export type SseFrame =
  | SseContentFrame
  | SseReasoningFrame
  | SseReferencesFrame
  | SseAgentMetadataFrame
  | SseFallbackFrame
  | SseCanceledFrame
  | SseErrorFrame

/** SSE 事件处理器 */
export interface SseHandlers {
  onContent?: (chunk: string) => void
  onReasoning?: (chunk: string) => void
  onReferences?: (refs: Reference[]) => void
  onAgentMetadata?: (meta: AgentMetadata) => void
  onFallback?: (fb: FallbackMeta) => void
  onCanceled?: (reason: CancelReason) => void
  onError?: (frame: SseErrorFrame) => void
  /** 流读取结束（正常完成 / 软取消后服务端关闭）。用于复位 streaming 态 */
  onComplete?: () => void
}
