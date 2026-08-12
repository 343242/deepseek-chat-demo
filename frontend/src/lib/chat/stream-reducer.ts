import type { SseFrame } from '@/types/chat'
import type { RenderMessage } from '@/types/chat'

/**
 * 流式消息 reducer（FE-006：从 chat-store.send() 抽出的纯函数）。
 *
 * 把一帧 SSE 应用到「当前消息数组」，返回新数组（不可变）。
 * 仅处理**消息数组**的变更；streaming/error 等顶层态与 query 失效由编排层（send）负责。
 *
 * 抽出的动机（SRP/KISS/可测）：原 send() 是 ~100 行的上帝函数，7 类帧各自内联 mutation、不可单测。
 * 这里集中后可对每类帧独立写单测。
 */

/** 对目标 assistant 消息做不可变 patch；其余消息原样保留（引用不变） */
function patch(
  messages: RenderMessage[],
  assistantId: number,
  fn: (m: RenderMessage) => RenderMessage,
): RenderMessage[] {
  return messages.map((m) => (m.id === assistantId ? fn(m) : m))
}

/**
 * 将一帧 SSE 应用到消息数组。
 * - content / reasoning：追加文本
 * - references / agentMetadata / fallback：替换字段
 * - canceled：消息转 FINISHED
 * - error：消息转 ERROR（错误文本属顶层态，由 send 写入 store.error，不在此处理）
 */
export function applyFrame(
  messages: RenderMessage[],
  frame: SseFrame,
  assistantId: number,
): RenderMessage[] {
  switch (frame.type) {
    case 'content':
      return patch(messages, assistantId, (m) => ({ ...m, content: m.content + frame.chunk }))
    case 'reasoning':
      return patch(messages, assistantId, (m) => ({
        ...m,
        reasoning: (m.reasoning ?? '') + frame.chunk,
      }))
    case 'references':
      return patch(messages, assistantId, (m) => ({ ...m, references: frame.references }))
    case 'agentMetadata':
      return patch(messages, assistantId, (m) => ({ ...m, agentMetadata: frame.metadata }))
    case 'fallback':
      return patch(messages, assistantId, (m) => ({ ...m, fallback: frame.fallback }))
    case 'canceled':
      return patch(messages, assistantId, (m) => ({ ...m, status: 'FINISHED' }))
    case 'error':
      return patch(messages, assistantId, (m) => ({ ...m, status: 'ERROR' }))
  }
}

/**
 * 收尾：流结束时把仍在 IN_PROGRESS 的 assistant 消息置为 FINISHED。
 * （正常完成 / 软取消后服务端关闭流都会走到此）
 */
export function finalizeInProgress(messages: RenderMessage[], assistantId: number): RenderMessage[] {
  return patch(messages, assistantId, (m) =>
    m.status === 'IN_PROGRESS' ? { ...m, status: 'FINISHED' } : m,
  )
}
